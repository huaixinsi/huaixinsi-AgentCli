# Phase 27: 工具拦截增强

本期把工具安全从“高危命令拒绝 + 常规 HITL”推进到三层拦截：

1. Prompt 约束：在基础提示词中明确禁止主动构造 `sudo`、全盘 `rm -rf`、`mkfs`、`dd of=/dev`、`curl | sh`、系统关机等高危操作。
2. 硬策略拒绝：命令仍先经过 `CommandRiskAnalyzer` 的结构化风险分析；同时新增命令文件访问分析，显式读写文件时必须证明路径位于当前项目工作区内，否则在进程启动前拒绝。
3. 二次确认：对命令里的文件写入/删除，以及对敏感文件的读写，不再允许被“本会话全部放行”缓存绕过，而是每次都触发 HITL 确认。

## 新增能力

- 新增 `SensitivePathPolicy`，集中识别 `.env`、私钥、token/password/credential 命名文件，以及 `.ssh`、`.aws`、`.kube` 等敏感目录。
- 新增 `CommandFileAccessAnalyzer`，基于结构化命令段识别重定向、管道后的文件写入、常见读写命令参数和嵌套 shell 命令。
- 新增 `ToolCallRisk`，作为 HITL 前的轻量预检结果，区分“直接拒绝”和“需要每次确认”。
- `HitlToolRegistry` 在审批缓存前执行预检：敏感文件读写、命令写文件等操作会强制进入单次确认。
- `ToolRegistry.execute_command` 复用同一套分析结果作为最终防线，保证即使 HITL 关闭，也不能执行已识别的越界文件操作或高危命令。

## 当前边界

- 该能力是业务层安全策略，不是完整 shell 沙箱；它识别的是显式路径、常见读写命令和已支持的 shell 结构。
- 对通配符、变量展开、`~`、`$HOME`、`%USERPROFILE%` 等无法静态证明位于工作区的路径，按 fail-closed 处理。
- 对未知工具或复杂自定义脚本，仍需要用户在 HITL 确认时进行判断。

## 验证

已通过聚焦测试：

```bash
mvn -q -DskipTests=false '-Dtest=CommandFileAccessAnalyzerTest,CommandGuardTest,CommandRiskAnalyzerTest,HitlToolRegistryTest,ToolRegistryTest#shouldAllowQuotedRiskWordsButRejectStructuredRisk+shouldRejectBroadFilesystemScan+shouldRunCommandInProjectDirectory' test
```
