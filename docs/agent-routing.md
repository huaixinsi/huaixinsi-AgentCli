# Agent 自动路由说明

`AgentRouter` 用来在普通用户输入进入 Agent 前，选择最合适的执行模式：

- `ReAct`：适合简单问答、解释、单步读取和轻量分析。
- `Plan-and-Execute`：适合多步骤实现、需要顺序推进、需要文档或验证的任务。
- `Multi-Agent`：适合多模块、多文件、可并行拆分的任务。

显式命令优先级最高：`/plan` 会强制进入 Plan-and-Execute，`/team` 会强制进入 Multi-Agent，自动路由不会覆盖这些命令。

## 路由规则

路由当前是本地确定性规则，不额外调用 LLM，因此不会增加模型调用成本。

`AgentRouter.route(...)` 会根据输入内容累积分数：

| 信号 | 加分 | 示例 |
|------|------|------|
| 变更意图 | `+2` | 实现、新增、修改、修复、重构、创建、提交、push |
| 多步骤意图 | `+2` | 先、然后、最后、一步一步、step by step |
| 项目范围 | `+2` | 项目、代码库、模块、多个文件、架构、入口、链路 |
| 验证或交付 | `+1` | 测试、验证、文档、提交、推送、发布、部署 |
| 并行候选 | `+2` | 同时、并行、分别、独立、多模块 |
| 结构化输入 | `+1` | 多行输入或较长需求 |

最终选择：

| 条件 | 执行模式 |
|------|----------|
| `score <= 2` | `ReAct` |
| `score >= 3` | `Plan-and-Execute` |
| `score >= 6` 且存在并行候选 | `Multi-Agent` |

## CLI 行为

普通输入进入 Agent 前的流程：

1. 展开 MCP resource mention。
2. 展开本地 `@path`。
3. 调用 `AgentRouter.route(...)`。
4. 根据结果选择执行模式。
5. 如果自动选择 Plan 或 Team，输出路由提示。

示例：

```text
🧭 自动路由: Plan-and-Execute (score=5, reasons=change, multi_step, verify_or_ship)
```

路由结果也会同步影响 Side-Git 快照里的 `snapshotMode`，后续可以区分 `react`、`plan`、`team` turn。

## 当前边界

- 自动路由只负责选择执行模式，不负责失败恢复、checkpoint 或 diff 合并。
- 规则偏保守：没有明确多步骤或修改意图时，继续使用 ReAct。
- Multi-Agent 必须同时满足高分和并行候选，避免普通多步骤任务被过度升级。

## 相关文件

- `src/main/java/com/paicli/agent/AgentRouter.java`
- `src/main/java/com/paicli/cli/Main.java`
- `src/test/java/com/paicli/agent/AgentRouterTest.java`
- `docs/phase-23-agent-routing.md`
