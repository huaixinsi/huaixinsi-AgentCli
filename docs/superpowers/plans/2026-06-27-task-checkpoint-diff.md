# Plan 任务级 Checkpoint 与 Diff 实施计划

> **供执行 Agent 使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项执行本计划。所有步骤使用复选框（`- [ ]`）跟踪状态。

**目标：** 为串行执行的每个 Plan task 增加隔离的 Side-Git checkpoint、统一 diff、Java 语法校验、依赖范围 diff 上下文和精准回滚。

**架构：** 在现有 Side-Git 仓库上增加 task 快照阶段，以及 commit 到工作区的 diff/恢复操作。`PlanExecuteAgent` 每次只执行一个就绪 task，在重试期间复用同一 checkpoint，校验成功 task 的 diff，将有效 diff 保留为依赖上下文，并在终止恢复前只恢复当前 task 的 checkpoint。

**技术栈：** Java 17、Maven、JUnit 5、JGit、JavaParser

---

## 文件规划

**新增文件**

- `src/main/java/com/paicli/snapshot/TaskCheckpoint.java`：不可变的 task 基线描述对象，支持明确表示 checkpoint 不可用。
- `src/main/java/com/paicli/snapshot/TaskDiff.java`：保存变更文件、行数统计、统一补丁和受限长度的 LLM 格式化结果。
- `src/main/java/com/paicli/plan/TaskSyntaxValidator.java`：使用 JavaParser 校验变更的 Java 文件。
- `src/test/java/com/paicli/plan/TaskSyntaxValidatorTest.java`：语法校验单元测试。
- `docs/phase-25-task-checkpoint-diff.md`：面向使用者的第三期行为和验证记录。

**修改文件**

- `src/main/java/com/paicli/snapshot/SnapshotPhase.java`：增加 `PRE_TASK` 和 `POST_TASK`。
- `src/main/java/com/paicli/snapshot/SideGitManager.java`：增加 task 快照、commit 到工作区的 diff，以及指定 commit 精准恢复。
- `src/main/java/com/paicli/snapshot/SnapshotService.java`：提供同步 task checkpoint 门面和非阻断降级。
- `src/test/java/com/paicli/snapshot/SideGitManagerTest.java`：覆盖 diff、快照阶段和精准恢复。
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`：接入串行 task 事务生命周期和依赖 diff 上下文。
- `src/test/java/com/paicli/agent/PlanExecuteAgentTest.java`：覆盖 Plan checkpoint 集成和回滚。
- `README.md`：增加第三期状态。
- `AGENTS.md`：更新已交付阶段、Plan 执行规则和验证路径。
- `docs/phase-24-plan-failure-recovery.md`：将 turn 级回滚更新为 task 级回滚。
- `docs/phase-18-side-history-snapshot.md`：记录第三期已覆盖早期“不做 task 快照”的边界。

### 任务 1：Task 快照模型与受限上下文

**文件：**
- 新增：`src/main/java/com/paicli/snapshot/TaskCheckpoint.java`
- 新增：`src/main/java/com/paicli/snapshot/TaskDiff.java`
- 测试：`src/test/java/com/paicli/snapshot/SideGitManagerTest.java`

- [ ] **步骤 1：编写失败的模型测试**

增加测试，证明不可用 checkpoint 会被明确表示，同时 diff 格式化在截断补丁时仍保留元数据：

```java
@Test
void taskDiffFormatsMetadataAndTruncatesPatch() {
    TaskDiff diff = new TaskDiff(
            "task_1",
            List.of("src/A.java", "src/B.java"),
            4,
            2,
            "+".repeat(200),
            false
    );

    String context = diff.formatForContext(120);

    assertTrue(context.contains("task_id=task_1"));
    assertTrue(context.contains("src/A.java"));
    assertTrue(context.contains("additions=4"));
    assertTrue(context.contains("diff truncated"));
    assertTrue(context.length() <= 120);
}

@Test
void unavailableTaskCheckpointIsInactive() {
    TaskCheckpoint checkpoint = TaskCheckpoint.unavailable("task_2");

    assertEquals("task_2", checkpoint.taskId());
    assertFalse(checkpoint.active());
    assertEquals("", checkpoint.snapshotCommitId());
}
```

- [ ] **步骤 2：运行针对性测试并确认 RED**

运行：

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

预期：由于 `TaskCheckpoint` 和 `TaskDiff` 尚不存在，编译失败。

- [ ] **步骤 3：增加最小不可变模型**

创建 `TaskCheckpoint`：

```java
package com.paicli.snapshot;

import java.time.Instant;

public record TaskCheckpoint(
        String taskId,
        String snapshotCommitId,
        Instant createdAt,
        boolean active
) {
    public TaskCheckpoint {
        taskId = taskId == null ? "" : taskId;
        snapshotCommitId = snapshotCommitId == null ? "" : snapshotCommitId;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    public static TaskCheckpoint unavailable(String taskId) {
        return new TaskCheckpoint(taskId, "", Instant.EPOCH, false);
    }
}
```

创建 `TaskDiff`：

```java
package com.paicli.snapshot;

import java.util.List;

public record TaskDiff(
        String taskId,
        List<String> changedFiles,
        int additions,
        int deletions,
        String unifiedDiff,
        boolean truncated
) {
    public TaskDiff {
        taskId = taskId == null ? "" : taskId;
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        unifiedDiff = unifiedDiff == null ? "" : unifiedDiff;
    }

    public static TaskDiff empty(String taskId) {
        return new TaskDiff(taskId, List.of(), 0, 0, "", false);
    }

    public String formatForContext(int maxChars) {
        int limit = Math.max(0, maxChars);
        String header = "task_id=" + taskId + "\n"
                + "changed_files=" + String.join(", ", changedFiles) + "\n"
                + "additions=" + additions + ", deletions=" + deletions + "\n";
        if (header.length() >= limit) {
            return header.substring(0, limit);
        }
        int patchBudget = limit - header.length();
        if (unifiedDiff.length() <= patchBudget) {
            return header + unifiedDiff;
        }
        String marker = "\n[diff truncated]";
        int bodyBudget = Math.max(0, patchBudget - marker.length());
        return header + unifiedDiff.substring(0, bodyBudget) + marker;
    }
}
```

- [ ] **步骤 4：运行针对性测试并确认 GREEN**

运行：

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

预期：`SideGitManagerTest` 全部通过。

- [ ] **步骤 5：提交模型**

```bash
git add src/main/java/com/paicli/snapshot/TaskCheckpoint.java src/main/java/com/paicli/snapshot/TaskDiff.java src/test/java/com/paicli/snapshot/SideGitManagerTest.java
git commit -m "add task checkpoint models"
```

### 任务 2：Side-Git Task Diff 与指定快照恢复

**文件：**
- 修改：`src/main/java/com/paicli/snapshot/SnapshotPhase.java`
- 修改：`src/main/java/com/paicli/snapshot/SideGitManager.java`
- 修改：`src/main/java/com/paicli/snapshot/SnapshotService.java`
- 测试：`src/test/java/com/paicli/snapshot/SideGitManagerTest.java`

- [ ] **步骤 1：编写失败的 Side-Git 事务测试**

增加测试，在修改、新增和删除文件后检查 diff 元数据与指定快照恢复：

```java
@Test
void capturesTaskDiffAndRestoresOnlyToTaskBaseline() throws Exception {
    Path project = tempDir.resolve("task-project");
    Path snapshots = tempDir.resolve("task-snapshots");
    Files.createDirectories(project);
    Files.writeString(project.resolve("kept.txt"), "successful previous task");
    Files.writeString(project.resolve("changed.txt"), "before");
    Files.writeString(project.resolve("deleted.txt"), "remove me");

    SideGitManager manager = new SideGitManager(project,
            new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));
    TurnSnapshot preTask = manager.preTaskSnapshot("task_2", "before task 2");

    Files.writeString(project.resolve("changed.txt"), "after\nnew line");
    Files.writeString(project.resolve("created.txt"), "created");
    Files.delete(project.resolve("deleted.txt"));

    TaskDiff diff = manager.diffFromSnapshot("task_2", preTask.commitId());
    RestoreResult restored = manager.restoreSnapshot(preTask.commitId(), "task_2");

    assertEquals(List.of("changed.txt", "created.txt", "deleted.txt"), diff.changedFiles());
    assertTrue(diff.additions() > 0);
    assertTrue(diff.deletions() > 0);
    assertTrue(diff.unifiedDiff().contains("created.txt"));
    assertTrue(restored.success());
    assertEquals("successful previous task", Files.readString(project.resolve("kept.txt")));
    assertEquals("before", Files.readString(project.resolve("changed.txt")));
    assertTrue(Files.exists(project.resolve("deleted.txt")));
    assertFalse(Files.exists(project.resolve("created.txt")));
}

@Test
void recordsTaskSnapshotPhases() throws Exception {
    Path project = tempDir.resolve("phase-project");
    Path snapshotsRoot = tempDir.resolve("phase-snapshots");
    Files.createDirectories(project);
    SideGitManager manager = new SideGitManager(project,
            new SnapshotConfig(true, snapshotsRoot, 50, List.of(".git", "target")));
    manager.preTaskSnapshot("task_1", "before");
    manager.postTaskSnapshot("task_1", "after");

    List<TurnSnapshot> snapshots = manager.listSnapshots(2);

    assertEquals(SnapshotPhase.POST_TASK, snapshots.get(0).phase());
    assertEquals(SnapshotPhase.PRE_TASK, snapshots.get(1).phase());
}

@Test
void disabledSnapshotServiceReturnsInactiveTaskCheckpoint() throws Exception {
    Path project = tempDir.resolve("disabled-project");
    Files.createDirectories(project);
    SnapshotConfig config = new SnapshotConfig(
            false,
            tempDir.resolve("disabled-snapshots"),
            50,
            List.of(".git", "target")
    );
    try (SnapshotService service = new SnapshotService(new SideGitManager(project, config))) {
        TaskCheckpoint checkpoint = service.beginTask("task_1", "disabled");

        assertFalse(checkpoint.active());
        assertTrue(service.diffTask(checkpoint).changedFiles().isEmpty());
    }
}
```

- [ ] **步骤 2：运行针对性测试并确认 RED**

运行：

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

预期：由于 task 快照、diff 和指定快照恢复方法尚不存在，编译失败。

- [ ] **步骤 3：增加 task 快照阶段和 Side-Git 操作**

增加枚举值：

```java
PRE_TASK("pre-task"),
POST_TASK("post-task"),
```

增加同步 task 快照方法，并委托给 `createSnapshot(...)`：

```java
public synchronized TurnSnapshot preTaskSnapshot(String taskId, String summary)
        throws IOException, GitAPIException {
    return createSnapshot(SnapshotPhase.PRE_TASK, taskId, summary);
}

public synchronized TurnSnapshot postTaskSnapshot(String taskId, String summary)
        throws IOException, GitAPIException {
    return createSnapshot(SnapshotPhase.POST_TASK, taskId, summary);
}
```

使用 `CanonicalTreeParser`、`FileTreeIterator` 和 `DiffFormatter` 实现 `diffFromSnapshot(...)`。对变更路径排序，格式化每个 `DiffEntry`，并统计以 `+` 或 `-` 开头的补丁行，同时排除 `+++` 和 `---` 文件头：

```java
public synchronized TaskDiff diffFromSnapshot(String taskId, String commitId)
        throws IOException, GitAPIException {
    try (Git git = openGit();
         Repository repository = git.getRepository();
         RevWalk walk = new RevWalk(repository);
         ObjectReader reader = repository.newObjectReader();
         ByteArrayOutputStream output = new ByteArrayOutputStream();
         DiffFormatter formatter = new DiffFormatter(output)) {
        RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
        CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, commit.getTree().getId());
        FileTreeIterator workTree = new FileTreeIterator(repository);
        formatter.setRepository(repository);
        formatter.setDiffComparator(RawTextComparator.DEFAULT);
        formatter.setDetectRenames(true);
        List<DiffEntry> entries = formatter.scan(oldTree, workTree);
        entries.sort(Comparator.comparing(entry -> changedPath(entry)));
        for (DiffEntry entry : entries) {
            formatter.format(entry);
        }
        String patch = output.toString(StandardCharsets.UTF_8);
        int additions = countPatchLines(patch, "+", "+++");
        int deletions = countPatchLines(patch, "-", "---");
        List<String> files = entries.stream()
                .map(SideGitManager::changedPath)
                .distinct()
                .toList();
        return new TaskDiff(taskId, files, additions, deletions, patch, false);
    }
}
```

增加 `diffFromSnapshot(...)` 使用的辅助方法：

```java
private static String changedPath(DiffEntry entry) {
    return entry.getChangeType() == DiffEntry.ChangeType.DELETE
            ? entry.getOldPath()
            : entry.getNewPath();
}

private static int countPatchLines(String patch, String prefix, String headerPrefix) {
    int count = 0;
    for (String line : patch.split("\\R", -1)) {
        if (line.startsWith(prefix) && !line.startsWith(headerPrefix)) {
            count++;
        }
    }
    return count;
}
```

将现有恢复逻辑提取到 `restoreSnapshot(commitId, taskId)`。该方法必须先创建 `pre-restore` 快照，对比当前树和目标树，删除目标树中不存在的文件并写回目标 blob。`restorePreTurn(...)` 只负责定位 pre-turn commit，再委托给此方法。

- [ ] **步骤 4：增加非阻断的 SnapshotService 门面**

增加同步方法：

```java
public TaskCheckpoint beginTask(String taskId, String summary) {
    if (!manager.config().enabled()) {
        return TaskCheckpoint.unavailable(taskId);
    }
    try {
        TurnSnapshot snapshot = manager.preTaskSnapshot(taskId, summary);
        return new TaskCheckpoint(taskId, snapshot.commitId(), snapshot.createdAt(), true);
    } catch (Exception e) {
        System.err.println("task checkpoint unavailable [" + taskId + "]: " + e.getMessage());
        return TaskCheckpoint.unavailable(taskId);
    }
}

public TaskDiff diffTask(TaskCheckpoint checkpoint) throws Exception {
    if (checkpoint == null || !checkpoint.active()) {
        return TaskDiff.empty(checkpoint == null ? "" : checkpoint.taskId());
    }
    return manager.diffFromSnapshot(checkpoint.taskId(), checkpoint.snapshotCommitId());
}

public void completeTask(TaskCheckpoint checkpoint, String summary) {
    if (checkpoint == null || !checkpoint.active()) {
        return;
    }
    try {
        manager.postTaskSnapshot(checkpoint.taskId(), summary);
    } catch (Exception e) {
        System.err.println("post-task snapshot unavailable [" + checkpoint.taskId() + "]: " + e.getMessage());
    }
}

public RestoreResult restoreTask(TaskCheckpoint checkpoint) throws Exception {
    if (checkpoint == null || !checkpoint.active()) {
        return RestoreResult.failure("task checkpoint unavailable");
    }
    return manager.restoreSnapshot(checkpoint.snapshotCommitId(), checkpoint.taskId());
}
```

- [ ] **步骤 5：运行快照测试并确认 GREEN**

运行：

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

预期：task diff、task phase、原有 turn 恢复和异步服务测试全部通过。

- [ ] **步骤 6：提交 Side-Git task 操作**

```bash
git add src/main/java/com/paicli/snapshot src/test/java/com/paicli/snapshot/SideGitManagerTest.java
git commit -m "add side git task transactions"
```

### 任务 3：Java Task 语法校验

**文件：**
- 新增：`src/main/java/com/paicli/plan/TaskSyntaxValidator.java`
- 新增：`src/test/java/com/paicli/plan/TaskSyntaxValidatorTest.java`

- [ ] **步骤 1：编写失败的语法校验测试**

```java
@Test
void acceptsValidChangedJavaFile(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/Valid.java"), "class Valid {}");
    TaskDiff diff = new TaskDiff("task_1", List.of("src/Valid.java"), 1, 0, "", false);

    TaskSyntaxValidator.ValidationResult result = new TaskSyntaxValidator().validate(root, diff);

    assertTrue(result.valid());
    assertTrue(result.errors().isEmpty());
}

@Test
void rejectsInvalidChangedJavaFile(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/Broken.java"), "class Broken {");
    TaskDiff diff = new TaskDiff("task_2", List.of("src/Broken.java"), 1, 0, "", false);

    TaskSyntaxValidator.ValidationResult result = new TaskSyntaxValidator().validate(root, diff);

    assertFalse(result.valid());
    assertTrue(result.summary().contains("src/Broken.java"));
}

@Test
void ignoresDeletedAndNonJavaFiles(@TempDir Path root) {
    TaskDiff diff = new TaskDiff(
            "task_3",
            List.of("deleted/Missing.java", "README.md"),
            0,
            2,
            "",
            false
    );

    assertTrue(new TaskSyntaxValidator().validate(root, diff).valid());
}
```

- [ ] **步骤 2：运行新测试并确认 RED**

运行：

```bash
mvn test -Dtest=TaskSyntaxValidatorTest -DskipTests=false
```

预期：由于 `TaskSyntaxValidator` 尚不存在，编译失败。

- [ ] **步骤 3：实现 JavaParser 校验**

```java
package com.paicli.plan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Problem;
import com.paicli.snapshot.TaskDiff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TaskSyntaxValidator {
    private final JavaParser parser = new JavaParser();

    public ValidationResult validate(Path projectRoot, TaskDiff diff) {
        List<String> errors = new ArrayList<>();
        if (diff == null) {
            return ValidationResult.success();
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        for (String relative : diff.changedFiles()) {
            if (relative == null || !relative.endsWith(".java")) {
                continue;
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (Problem problem : parser.parse(file).getProblems()) {
                    errors.add(relative + ": " + problem.getMessage().replaceAll("\\s+", " ").trim());
                }
            } catch (Exception e) {
                errors.add(relative + ": " + e.getMessage());
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : new ValidationResult(false, errors);
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public String summary() {
            return String.join("\n", errors);
        }
    }
}
```

- [ ] **步骤 4：运行校验器测试并确认 GREEN**

运行：

```bash
mvn test -Dtest=TaskSyntaxValidatorTest -DskipTests=false
```

预期：3 个测试全部通过。

- [ ] **步骤 5：提交语法校验**

```bash
git add src/main/java/com/paicli/plan/TaskSyntaxValidator.java src/test/java/com/paicli/plan/TaskSyntaxValidatorTest.java
git commit -m "validate task java changes"
```

### 任务 4：串行 Plan Task 事务与 Diff 上下文

**文件：**
- 修改：`src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- 修改：`src/test/java/com/paicli/agent/PlanExecuteAgentTest.java`

- [ ] **步骤 1：编写失败的 Plan 集成测试**

增加测试夹具，使用临时 `SnapshotService`、包含两个 task 的 `StubPlanner` 和脚本化 LLM 响应。

第一个测试在 `task_1` 写入合法 Java 文件并完成任务，然后验证 `task_2` 收到依赖 diff：

```java
@Test
void injectsSuccessfulTaskDiffIntoDependentTask() throws Exception {
    Path project = createProject("diff-context");
    ToolRegistry tools = taskToolRegistry(project);
    StubGLMClient llm = scriptedWriteThenComplete(
            project.resolve("src/Feature.java"),
            "class Feature {}",
            "task one done",
            "task two done"
    );
    PlanExecuteAgent agent = agentWithPlan(llm, tools, dependentWriteThenAnalysisPlan(llm));

    String result = agent.run("implement then review");

    assertTrue(result.contains("task two done"));
    assertTrue(llm.seenMessages().get(2).stream()
            .filter(message -> "user".equals(message.role()))
            .anyMatch(message -> message.content().contains("[TASK_DIFF task_1]")
                    && message.content().contains("src/Feature.java")));
}
```

第二个测试让依赖 task 写入非法 Java，并验证第一个成功 task 的文件仍被保留：

```java
@Test
void rollsBackOnlyFailedTaskAndKeepsPreviousTaskChanges() throws Exception {
    Path project = createProject("precise-rollback");
    ToolRegistry tools = taskToolRegistry(project);
    StubGLMClient llm = scriptedTwoWrites(
            project.resolve("src/Good.java"), "class Good {}",
            project.resolve("src/Broken.java"), "class Broken {"
    );
    PlanExecuteAgent agent = agentWithPlan(llm, tools, dependentWritePlan(llm));

    String result = agent.run("write good then broken");

    assertTrue(result.toLowerCase().contains("rollback"));
    assertTrue(Files.exists(project.resolve("src/Good.java")));
    assertFalse(Files.exists(project.resolve("src/Broken.java")));
}
```

增加重试和串行执行测试：

```java
@Test
void reusesInitialCheckpointAcrossTaskRetry() throws Exception {
    Path project = createProject("checkpoint-retry");
    ToolRegistry tools = taskToolRegistry(project);
    RetryingGLMClient llm = new RetryingGLMClient(project.resolve("partial.txt"));
    PlanExecuteAgent agent = agentWithPlan(
            llm,
            tools,
            dependentWriteThenAnalysisPlan(llm)
    );

    String result = agent.run("retry task then inspect its diff");

    long preTaskCount = tools.getSnapshotService().listSnapshots(20).stream()
            .filter(snapshot -> snapshot.phase() == SnapshotPhase.PRE_TASK)
            .filter(snapshot -> snapshot.turnId().equals("task_1"))
            .count();
    assertEquals(1, preTaskCount);
    assertTrue(result.contains("task two done"));
    assertTrue(llm.seenMessages().stream()
            .flatMap(List::stream)
            .filter(message -> "user".equals(message.role()))
            .anyMatch(message -> message.content() != null
                    && message.content().contains("partial.txt")));
}

@Test
void executesReadyPlanTasksSerially() throws Exception {
    Path project = createProject("serial-plan");
    ToolRegistry tools = taskToolRegistry(project);
    ConcurrentProbeGLMClient llm = new ConcurrentProbeGLMClient();
    PlanExecuteAgent agent = agentWithPlan(llm, tools, independentAnalysisPlan(llm));

    agent.run("analyze two independent areas");

    assertEquals(1, llm.maxConcurrentCalls());
    assertTrue(llm.seenMessages().stream()
            .flatMap(List::stream)
            .filter(message -> "user".equals(message.role()))
            .noneMatch(message -> message.content() != null
                    && message.content().contains("[TASK_DIFF")));
}
```

将以下完整夹具加入 `PlanExecuteAgentTest`：

```java
private Path createProject(String name) throws IOException {
    Path project = tempDir.resolve(name);
    Files.createDirectories(project.resolve("src"));
    return project;
}

private ToolRegistry taskToolRegistry(Path project) {
    ToolRegistry tools = new ToolRegistry();
    tools.setProjectPath(project.toString());
    SnapshotConfig config = new SnapshotConfig(
            true,
            tempDir.resolve(project.getFileName() + "-snapshots"),
            50,
            List.of(".git", "target", "*.class")
    );
    tools.setSnapshotService(new SnapshotService(new SideGitManager(project, config)));
    return tools;
}

private PlanExecuteAgent agentWithPlan(
        LlmClient llm,
        ToolRegistry tools,
        Planner planner
) {
    return new PlanExecuteAgent(
            llm,
            tools,
            planner,
            null,
            (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
    );
}

private StubGLMClient scriptedWriteThenComplete(
        Path file,
        String content,
        String firstResult,
        String secondResult
) throws Exception {
    return new StubGLMClient(List.of(
            writeToolResponse("write_1", file, content),
            new LlmClient.ChatResponse("assistant", firstResult, null, 30, 10),
            new LlmClient.ChatResponse("assistant", secondResult, null, 30, 10)
    ));
}

private StubGLMClient scriptedTwoWrites(
        Path firstFile,
        String firstContent,
        Path secondFile,
        String secondContent
) throws Exception {
    return new StubGLMClient(List.of(
            writeToolResponse("write_1", firstFile, firstContent),
            new LlmClient.ChatResponse("assistant", "task one done", null, 30, 10),
            writeToolResponse("write_2", secondFile, secondContent),
            new LlmClient.ChatResponse("assistant", "task two done", null, 30, 10)
    ));
}

private LlmClient.ChatResponse writeToolResponse(
        String callId,
        Path file,
        String content
) throws Exception {
    String arguments = new ObjectMapper().writeValueAsString(Map.of(
            "path", file.toString(),
            "content", content
    ));
    return new LlmClient.ChatResponse(
            "assistant",
            "",
            List.of(new LlmClient.ToolCall(
                    callId,
                    new LlmClient.ToolCall.Function("write_file", arguments)
            )),
            30,
            10
    );
}

private Planner dependentWriteThenAnalysisPlan(LlmClient llm) {
    return new TwoTaskPlanner(llm, Task.TaskType.ANALYSIS, true);
}

private Planner dependentWritePlan(LlmClient llm) {
    return new TwoTaskPlanner(llm, Task.TaskType.FILE_WRITE, true);
}

private Planner independentAnalysisPlan(LlmClient llm) {
    return new TwoTaskPlanner(llm, Task.TaskType.ANALYSIS, false);
}

private static final class TwoTaskPlanner extends Planner {
    private final Task.TaskType secondType;
    private final boolean dependent;

    private TwoTaskPlanner(
            LlmClient llm,
            Task.TaskType secondType,
            boolean dependent
    ) {
        super(llm);
        this.secondType = secondType;
        this.dependent = dependent;
    }

    @Override
    public ExecutionPlan createPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan("two-task-plan", goal);
        Task first = new Task("task_1", "implement first change", Task.TaskType.FILE_WRITE);
        Task second = new Task("task_2", "inspect or implement second change", secondType);
        plan.addTask(first);
        if (dependent) {
            second.addDependency(first.getId());
        }
        plan.addTask(second);
        plan.computeExecutionOrder();
        return plan;
    }
}

private static final class RetryingGLMClient extends GLMClient {
    private final Path partialFile;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<List<Message>> seenMessages = new ArrayList<>();

    private RetryingGLMClient(Path partialFile) {
        super("test-key");
        this.partialFile = partialFile;
    }

    @Override
    public ChatResponse chat(
            List<Message> messages,
            List<Tool> tools,
            StreamListener listener
    ) throws IOException {
        seenMessages.add(List.copyOf(messages));
        int call = calls.getAndIncrement();
        if (call == 0) {
            Files.writeString(partialFile, "partial");
            throw new IOException("timeout");
        }
        String content = call == 1 ? "task one done" : "task two done";
        return new ChatResponse("assistant", content, null, 30, 10);
    }

    private List<List<Message>> seenMessages() {
        return seenMessages;
    }
}

private static final class ConcurrentProbeGLMClient extends GLMClient {
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximum = new AtomicInteger();
    private final List<List<Message>> seenMessages =
            java.util.Collections.synchronizedList(new ArrayList<>());

    private ConcurrentProbeGLMClient() {
        super("test-key");
    }

    @Override
    public ChatResponse chat(
            List<Message> messages,
            List<Tool> tools,
            StreamListener listener
    ) {
        seenMessages.add(List.copyOf(messages));
        int current = active.incrementAndGet();
        maximum.accumulateAndGet(current, Math::max);
        try {
            Thread.sleep(100);
            return new ChatResponse("assistant", "done", null, 30, 10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChatResponse("assistant", "interrupted", null, 30, 10);
        } finally {
            active.decrementAndGet();
        }
    }

    private int maxConcurrentCalls() {
        return maximum.get();
    }

    private List<List<Message>> seenMessages() {
        return List.copyOf(seenMessages);
    }
}
```

增加 `ObjectMapper`、snapshot 类、`Map` 和 `AtomicInteger` 的 import。

- [ ] **步骤 2：运行 Plan 测试并确认 RED**

运行：

```bash
mvn test -Dtest=PlanExecuteAgentTest -DskipTests=false
```

预期：新断言失败，因为 Plan 仍并行执行就绪 task，尚未创建 task checkpoint，也未注入 task diff。

- [ ] **步骤 3：将批量执行改为单 task 执行**

在 `executePlan(...)` 的每轮循环中，从 `getExecutableTasksInOrder(plan)` 选择第一个就绪 task，执行完后再选择下一个：

```java
Task task = executableTasks.get(0);
TaskExecutionResult taskResult = executeSingleTask(
        plan,
        task,
        streamState,
        recoveryHints,
        completedTaskDiffs
);
```

将 `executeTaskBatch(...)` 替换为：

```java
private TaskExecutionResult executeSingleTask(
        ExecutionPlan plan,
        Task task,
        StreamState streamState,
        Map<String, String> recoveryHints,
        Map<String, TaskDiff> completedTaskDiffs
) {
    log.info("Executing task serially: {} type={}", task.getId(), task.getType());
    out.println("▶ 执行任务 [" + task.getId() + "]: " + task.getDescription());
    task.markStarted();
    try {
        return TaskExecutionResult.success(task, executeTask(
                plan.getGoal(),
                plan,
                task,
                streamState,
                out,
                recoveryHints.get(task.getId()),
                completedTaskDiffs
        ));
    } catch (Exception e) {
        return TaskExecutionResult.failure(task, e);
    }
}
```

删除不再使用的 executor、future 和字节缓冲区 import 与代码。

- [ ] **步骤 4：接入 checkpoint 生命周期与校验**

在 `executePlan(...)` 开头创建：

```java
Map<String, TaskCheckpoint> taskCheckpoints = new HashMap<>();
Map<String, TaskDiff> completedTaskDiffs = new LinkedHashMap<>();
SnapshotService snapshots = toolRegistry.getSnapshotService();
TaskSyntaxValidator syntaxValidator = new TaskSyntaxValidator();
Path projectRoot = Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();
```

在 task 首次执行前：

```java
TaskCheckpoint checkpoint = taskCheckpoints.computeIfAbsent(
        task.getId(),
        ignored -> snapshots.beginTask(task.getId(), task.getDescription())
);
```

在将成功 task 标记为完成前：

```java
TaskDiff taskDiff = snapshots.diffTask(checkpoint);
TaskSyntaxValidator.ValidationResult validation = syntaxValidator.validate(projectRoot, taskDiff);
if (!validation.valid()) {
    taskResult = TaskExecutionResult.failure(
            task,
            new IOException("validation failed:\n" + validation.summary())
    );
} else {
    snapshots.completeTask(checkpoint, task.getDescription());
    completedTaskDiffs.put(task.getId(), taskDiff);
    taskCheckpoints.remove(task.getId());
    task.markCompleted(taskResult.result());
}
```

如果 `diffTask(...)` 自身失败，记录警告并使用 `TaskDiff.empty(taskId)` 继续执行；输出中不得声称 diff 保护已生效。

- [ ] **步骤 5：在终止恢复前还原当前 task**

增加：

```java
private RestoreResult restoreFailedTask(
        Task task,
        Map<String, TaskCheckpoint> checkpoints
) {
    TaskCheckpoint checkpoint = checkpoints.remove(task.getId());
    if (checkpoint == null || !checkpoint.active()) {
        return RestoreResult.failure("task checkpoint unavailable");
    }
    try {
        return toolRegistry.getSnapshotService().restoreTask(checkpoint);
    } catch (Exception e) {
        return RestoreResult.failure("task rollback failed: " + e.getMessage());
    }
}
```

在以下位置调用：

- 调用 `Planner.replan(...)` 之前；
- 执行 `ROLLBACK` 时；
- `RETRY` 或 `FIX_PARAMETERS` 用尽恢复次数之后；
- 最终失败 task 收尾之前。

安排重试时不要删除 checkpoint。修改 `rollbackAfterFailure(...)`，让响应报告 task checkpoint 恢复结果，不再调用 `restorePreTurn(1)`。

- [ ] **步骤 6：注入依赖范围内的 diff 上下文**

为 `executeTask(...)` 和 `buildTaskContext(...)` 增加 `Map<String, TaskDiff> completedTaskDiffs` 参数。

按稳定顺序收集传递依赖：

```java
private void collectDependencyIds(
        ExecutionPlan plan,
        Task task,
        LinkedHashSet<String> dependencyIds
) {
    for (String dependencyId : task.getDependencies()) {
        Task dependency = plan.getTask(dependencyId);
        if (dependency == null) {
            continue;
        }
        collectDependencyIds(plan, dependency, dependencyIds);
        dependencyIds.add(dependencyId);
    }
}
```

只追加依赖 task 的 diff，并共享 12,000 字符预算：

```java
private void appendDependencyDiffs(
        StringBuilder context,
        ExecutionPlan plan,
        Task task,
        Map<String, TaskDiff> taskDiffs
) {
    LinkedHashSet<String> dependencyIds = new LinkedHashSet<>();
    collectDependencyIds(plan, task, dependencyIds);
    int remaining = 12_000;
    for (String dependencyId : dependencyIds) {
        TaskDiff diff = taskDiffs.get(dependencyId);
        if (diff == null || remaining <= 0) {
            continue;
        }
        String prefix = "\n\n[TASK_DIFF " + dependencyId + "]\n";
        if (prefix.length() >= remaining) {
            break;
        }
        String formatted = diff.formatForContext(remaining - prefix.length());
        context.append(prefix).append(formatted);
        remaining -= prefix.length() + formatted.length();
    }
}
```

- [ ] **步骤 7：运行 Plan 与恢复回归测试**

运行：

```bash
mvn test -Dtest=PlanExecuteAgentTest,TaskFailureClassifierTest,TaskSyntaxValidatorTest,SideGitManagerTest -DskipTests=false
```

预期：所有针对性测试通过；非法 Java 测试文件被删除，前序合法 task 文件仍然存在。

- [ ] **步骤 8：提交 Plan 集成**

```bash
git add src/main/java/com/paicli/agent/PlanExecuteAgent.java src/test/java/com/paicli/agent/PlanExecuteAgentTest.java
git commit -m "add plan task transactions"
```

### 任务 5：第三期文档

**文件：**
- 新增：`docs/phase-25-task-checkpoint-diff.md`
- 修改：`README.md`
- 修改：`AGENTS.md`
- 修改：`docs/phase-24-plan-failure-recovery.md`
- 修改：`docs/phase-18-side-history-snapshot.md`

- [ ] **步骤 1：编写第三期实现文档**

文档包含：

- task checkpoint 生命周期；
- Plan 串行执行原因；
- 重试时复用基线；
- Java 语法校验边界；
- 只面向依赖 task、上限 12,000 字符的 diff 上下文；
- 精准回滚和 checkpoint 关闭时的降级；
- 实际执行的验证命令与结果。

- [ ] **步骤 2：增量更新仓库首页**

在第二期之后增加以下状态：

```markdown
### 第三期优化：任务级 Checkpoint 与 Diff

- Plan task 改为串行事务执行，每个 task 首次执行前创建独立 Side-Git checkpoint
- task 成功后生成文件 diff，并对变更的 Java 文件执行语法校验
- 成功 diff 只注入后续依赖任务，统一限制上下文长度
- task 最终失败或重新规划前只回滚当前 task，保留此前成功 task 的改动
- 详细记录见 [docs/phase-25-task-checkpoint-diff.md](docs/phase-25-task-checkpoint-diff.md)
```

将后续演进中的“为每个任务节点增加 checkpoint 和 diff 审查”改为已完成表述，并让下一项尚未实现的能力成为首个未来步骤。

- [ ] **步骤 3：同步行为文档**

在 `AGENTS.md` 中记录第三期已经交付，说明 task 事务启用时 Plan 就绪 task 串行执行，并增加以下验证行：

```markdown
| Plan task checkpoint | `mvn test -Dtest=SideGitManagerTest,TaskSyntaxValidatorTest,PlanExecuteAgentTest` |
```

在第二期文档中，将 `restorePreTurn(1)` 替换为 task checkpoint 恢复。在第 18 期文档中保留历史 MVP 描述，但注明第三期有意覆盖 Plan 不做 task 快照的旧限制。

- [ ] **步骤 4：检查文档链接与空白**

运行：

```bash
git diff --check
```

预期：无输出。

- [ ] **步骤 5：提交文档**

```bash
git add README.md AGENTS.md docs/phase-18-side-history-snapshot.md docs/phase-24-plan-failure-recovery.md docs/phase-25-task-checkpoint-diff.md
git commit -m "document third phase task checkpoints"
```

### 任务 6：完整验证与发布

**文件：**
- 验证所有修改过的生产代码、测试和文档文件。

- [ ] **步骤 1：运行针对性功能测试**

```bash
mvn test -Dtest=SideGitManagerTest,TaskSyntaxValidatorTest,PlanExecuteAgentTest,TaskFailureClassifierTest,AgentRouterTest -DskipTests=false
```

预期：0 failures、0 errors。

- [ ] **步骤 2：运行 quick 回归 profile**

```bash
mvn test -Pquick
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 3：构建可运行的 shaded JAR**

```bash
mvn package -DskipTests
```

预期：`BUILD SUCCESS`，并生成 `target/paicli-1.0-SNAPSHOT.jar`。

- [ ] **步骤 4：运行 CLI 启动冒烟测试**

PowerShell：

```powershell
$env:GLM_API_KEY='test-key'
'/exit' | java -jar target\paicli-1.0-SNAPSHOT.jar
```

预期：进程退出码为 `0`，打印启动界面，接受 `/exit`，且不会发起模型请求。

- [ ] **步骤 5：检查最终仓库状态**

```bash
git status -sb
git log -6 --oneline --decorate
git diff origin/main...HEAD --check
```

预期：只有本次有意提交领先 `origin/main`；不存在未暂存文件或空白错误。

- [ ] **步骤 6：推送当前 main 分支**

```bash
git push origin main
```

预期：`origin/main` 前进到第三期最终文档提交。
