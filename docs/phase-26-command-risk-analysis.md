# 第 26 期：结构化命令风险分析

## 1. 背景

此前 `CommandGuard` 会压缩整条命令的空白，再依次执行风险正则。它可以快速拦住明显危险命令，但不理解 Shell 结构：

- `echo "sudo rm -rf /"` 中的普通文本也会命中黑名单。
- `rm -r -f /`、路径形式的可执行文件和后续命令段可能绕过特定正则。
- 管道、重定向、命令替换和嵌套 Shell 只能按原始字符串猜测。
- 项目实际使用 PowerShell、cmd 和 Bash，三者引号与转义规则不同。

本期把安全判断升级为“先提取结构，再评估风险”，同时保留 HITL 与审计链路。

## 2. 实现架构

```text
execute_command
    |
    v
ShellDialect.current()
    |
    +--> CommandGuard.check(command, dialect)
    |        |
    |        v
    |    CommandRiskAnalyzer
    |        |- 词法扫描
    |        |- 嵌套命令递归
    |        `- 结构化风险规则
    |
    `--> dialect.invocation(command)
             |
             v
         ProcessBuilder
```

`ToolRegistry` 只计算一次 `ShellDialect`。同一个值既交给策略层，也负责生成进程启动参数，避免“按 Bash 检查、按 PowerShell 执行”一类判断漂移。

`CommandGuard` 保留原有 `check(String)` 入口，并增加显式方言重载。HITL、`PolicyException` 和 `AuditLog` 的调用关系不变。

## 3. 结构模型

`CommandRiskAnalyzer.Analysis` 包含：

- `dialect`：本次使用的 Shell 方言。
- `segments`：顶层命令段。
- `nestedAnalyses`：命令替换和嵌套 Shell 的递归结果。
- `risk`：首个解析风险或命令风险。

每个 `CommandSegment` 记录：

- 规范化前的可执行文件。
- 参数列表。
- 重定向列表。
- 与下一段的连接符。
- 所属管道编号。

连接符包括顺序执行、`&&`、`||`、管道和后台执行。重定向单独保存文件描述符、操作符与目标，因此规则不需要再从整条文本中猜 `2>&1` 或 `> /dev/sda`。

## 4. 三种 Shell 方言

### Bash

- 支持单引号与双引号。
- 反斜杠用于转义。
- 识别 `$()` 和反引号命令替换。
- 识别 `sh/bash/zsh/fish/ksh -c` 的命令参数。

### PowerShell

- 支持单引号与双引号。
- 反引号用于转义。
- 识别 `$()` 子表达式。
- 识别 `powershell/pwsh -Command` 和 `-c`。
- 将调用运算符 `& "path/to/tool.exe"` 后的工具作为真实可执行文件分析。

### cmd

- 双引号用于分组参数，单引号视为普通字符。
- 脱字符 `^` 用于转义。
- 单个 `&` 可切分命令段。
- 识别 `cmd /c` 的命令参数。

## 5. 有界分析

分析器不是完整 Shell AST，而是面向安全规则的确定性词法状态机：

- 单次词法扫描为 `O(n)`。
- 单条命令最多 65,536 个字符。
- 最多解析 128 个命令段。
- 最多解析 1,024 个 token。
- 每层最多提取 128 个嵌套命令。
- 递归分析深度最多 12 层。
- 未闭合引号、未闭合替换、缺失重定向目标和超过上限均保守拒绝。

分析器不会重写用户命令。检查通过后，`ProcessBuilder` 仍接收原始命令文本，词法结果只用于决策，不改变执行语义。

## 6. 风险规则

本期结构化实现以下策略：

| 风险 | 结构化判断 |
|------|------------|
| sudo 提权 | 规范化可执行文件名后等于 `sudo` |
| 大范围删除 | `rm` 同时包含递归、强制参数，目标为根目录或用户目录 |
| 磁盘格式化 | 可执行文件为 `mkfs` 或 `mkfs.*` |
| 裸设备写入 | `dd` 的 `of` 参数指向 `/dev/*` |
| fork bomb | 识别对应语法 token 序列 |
| 下载后执行 | `curl/wget` 通过管道连接 Shell 解释器 |
| 全盘扫描 | `find` 的目标为根目录或用户目录 |
| 全盘放权 | `chmod` 递归设置 `777` 到根目录或用户目录 |
| 电源操作 | 可执行文件为 `shutdown/reboot/halt/poweroff` |
| 设备重定向 | 输出目标为 Unix 块设备或 Windows `PhysicalDrive` |

可执行文件会去除路径和 `.exe/.cmd/.bat` 扩展名，因此 `/usr/bin/sudo` 与 `shutdown.exe` 不能通过路径形式绕过。

短参数会按字符组合分析，`rm -rf /`、`rm -fr /` 与 `rm -r -f /` 使用同一语义判断；长参数 `--recursive --force` 也被支持。

## 7. 递归分析

以下内容会创建子 `Analysis`：

- Bash/PowerShell 的 `$()`。
- Bash 反引号。
- POSIX Shell 的 `-c`。
- PowerShell 的 `-Command`/`-c`。
- cmd 的 `/c`。

子分析发现的解析错误或风险会传播给父分析。因此外层即使只是 `echo`，`echo $(rm -rf /)` 仍会被拒绝；普通引用文本 `echo "sudo rm -rf /"` 则不会被当成可执行结构。

## 8. 安全边界

本期明确不做：

- 展开环境变量和通配符。
- 解析别名、函数、profile 或注册表配置。
- 读取并分析外部脚本文件正文。
- 模拟 Shell 的完整语法与运行时类型系统。
- 提供进程隔离、权限降级或系统调用沙箱。

因此 `CommandRiskAnalyzer` 仍是 HITL 前的辅助防线。策略未命中不等于命令绝对安全，生产环境仍应结合最小权限、容器/沙箱和人工审批。

## 9. 测试矩阵

| 类别 | 覆盖内容 |
|------|----------|
| 方言选择 | Linux/Bash、Windows/PowerShell、Windows/cmd |
| 基础结构 | 参数、引号、命令段、管道、文件描述符重定向 |
| 方言转义 | Bash `\`、PowerShell反引号、cmd `^` |
| 递归 | `$()`、Bash 反引号、三种嵌套 Shell |
| 解析失败 | 未闭合替换、递归深度上限 |
| 风险规则 | 原有规则、参数变体、路径可执行文件、后续命令段 |
| 误报回归 | 引号中的风险文本、普通文件重定向、下载到文件 |
| Windows 绕过 | PowerShell `&` 调用运算符、`PhysicalDrive` |
| 工具集成 | 策略拒绝仍经 `PolicyException` 返回 |

项目默认跳过测试，验证时必须显式设置 `-DskipTests=false`：

```powershell
mvn -q -DskipTests=false '-Dtest=ShellDialectTest,CommandRiskAnalyzerTest,CommandGuardTest' test
mvn -q -DskipTests=false '-Dtest=ToolRegistryTest#shouldAllowQuotedRiskWordsButRejectStructuredRisk,ToolRegistryTest#shouldRejectBroadFilesystemScan' test
mvn -q -DskipTests=false test
mvn -q -DskipTests package
```

本期新增的命令安全测试和工具集成测试应保持全绿。仓库完整测试在 Windows 上仍有图片 URI、Memory/RAG、CRLF、文件检索和临时目录清理等既有失败；全量结果需要与改动前基线比较，不能把这些既有问题误记为本期回归。

## 10. 面试讲解要点

### 为什么没有直接引入第三方 Parser

成熟的 Shell parser 多数只覆盖 POSIX/Bash，而本项目在 Windows 默认执行 PowerShell，并允许切换 cmd。引入一个 POSIX AST 仍要维护另外两套逻辑，依赖成本大于本期所需的安全语法子集。

### 为什么结构化分析优于正则叠加

正则看到的是字符，无法稳定区分“数据”和“执行结构”。状态机先确定引号、token、segment、pipe 与 redirect，风险规则只关心规范化后的命令和参数，误报与绕过都更容易通过单测解释。

### 如何控制解析器自身风险

扫描复杂度线性，segment、token 和递归深度都有硬上限；无法可靠解析时 fail closed。分析结果只参与拒绝决策，不参与命令重写，降低语义偏差。

### 如何继续演进

后续可以在不改 `CommandGuard` 契约的前提下增加：

- Windows 原生命令的参数级规则。
- 风险等级与解释链，而不只返回首个原因。
- 容器或受限账户执行后端。
- 将结构化风险结果纳入 Agent 路由和 HITL 展示。
