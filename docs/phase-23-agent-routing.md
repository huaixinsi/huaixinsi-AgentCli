# Phase 23: Agent 自动路由

## 目标

普通输入不再固定走 ReAct，而是先由 `AgentRouter` 做一次确定性打分，然后选择更合适的执行路径：

| 分数 | 路径 | 适用场景 |
|------|------|----------|
| `0-2` | ReAct | 简单问答、解释、单步读取、轻量分析 |
| `3+` | Plan-and-Execute | 多步骤实现、文档 + 测试、需要顺序推进的任务 |
| `6+` 且存在并行候选 | Multi-Agent | 多模块、多文件、可并行检查或分别处理的任务 |

显式命令仍然最高优先级：`/plan` 和 `/team` 不会被自动路由覆盖。

## 路由信号

`AgentRouter` 当前只使用本地规则，不额外调用 LLM，避免一次输入就增加模型成本和延迟。

主要信号：

- 变更意图：实现、新增、修改、修复、重构、创建、提交、push 等。
- 多步骤意图：先、然后、最后、一步一步、step by step 等。
- 范围信号：项目、代码库、模块、多个文件、架构、入口、链路等。
- 验证或交付：测试、验证、文档、提交、推送、发布、部署等。
- 并行候选：同时、并行、分别、独立、多模块、两个模块，或同时提到多个区域。
- 结构化输入：多行或较长输入会轻微加分。

## CLI 行为

普通输入进入 Agent 前仍保持原来的顺序：

1. 展开 MCP resource mention。
2. 展开本地 `@path`。
3. 调用 `AgentRouter.route(...)`。
4. 根据结果选择 ReAct、Plan-and-Execute 或 Multi-Agent。
5. 如果自动选择 Plan 或 Team，输出一行低调提示：

```text
🧭 自动路由: Plan-and-Execute (score=5, reasons=change, multi_step, verify_or_ship)
```

路由结果同时用于 Side-Git 快照的 `snapshotMode`，因此后续 `/snapshot` 里可以继续区分 `react`、`plan`、`team` turn。

## 边界

- 自动路由不做失败恢复、checkpoint、diff 合并或资源锁；这些属于后续阶段。
- 自动路由不改变 `/plan`、`/team` 的强制语义。
- 分数规则保守优先：没有明确多步骤或修改意图时，继续走 ReAct。
- Multi-Agent 需要同时满足高分和并行候选，避免普通多步骤任务过度升级。

## 验证

核心单测：

```bash
mvn test -Dtest=AgentRouterTest -DskipTests=false
```

入口相关回归建议：

```bash
mvn test -Dtest=AgentRouterTest,CliCommandParserTest,MainInputNormalizationTest -DskipTests=false
```
