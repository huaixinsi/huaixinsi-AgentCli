# 第二期优化：Plan 失败恢复增强

## 目标

Plan-and-Execute 不再只在任务失败后按进度粗略判断是否重新规划，而是先对失败做本地分类，再选择恢复动作。

当前阶段新增 `TaskFailureClassifier`，覆盖四类失败：

| 失败类型 | 典型场景 | 恢复动作 |
|----------|----------|----------|
| `TOOL_TRANSIENT_FAILURE` | 工具超时、临时网络错误、503、限流 | `RETRY` |
| `PARAMETER_ERROR` | 工具参数缺失、参数格式错误、必填参数为空 | `FIX_PARAMETERS` |
| `DEPENDENCY_ERROR` | 依赖产物缺失、前置任务输出不存在 | `REPLAN` |
| `VALIDATION_FAILURE` | 测试失败、断言失败、校验失败 | `ROLLBACK` |

## 执行策略

Plan 任务失败后会先生成 `Decision(kind, action, reason)`：

- `RETRY`：给当前任务追加恢复提示，最多重试一次。
- `FIX_PARAMETERS`：给模型追加 `PLAN_RECOVERY` 提示，要求修正工具名或参数后继续。
- `REPLAN`：调用已有 `Planner.replan(...)`，基于当前失败原因重新规划。
- `ROLLBACK`：调用 Side-Git `restorePreTurn(1)` 尝试恢复最近的 pre-turn 快照；如果快照不可用，会返回清晰的回滚失败说明。

工具调用返回失败文本时，也会进入同一套分类逻辑。参数错误和临时失败不会立刻终止任务，而是把恢复提示回灌给模型，让下一轮有机会修正。

## 边界

- 当前每个任务最多自动恢复一次，避免无限重试。
- 回滚依赖已有 Side-Git 快照能力；没有可用快照时不会手写文件恢复逻辑。
- 分类器使用本地规则，不额外调用 LLM。
- `UNKNOWN` 失败默认走 `REPLAN`，保持保守。

## 相关文件

- `src/main/java/com/paicli/plan/TaskFailureClassifier.java`
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- `src/test/java/com/paicli/plan/TaskFailureClassifierTest.java`
- `src/test/java/com/paicli/agent/PlanExecuteAgentTest.java`

## 验证

```bash
mvn test -Dtest=TaskFailureClassifierTest,PlanExecuteAgentTest -DskipTests=false
```
