# Plan Task Checkpoint and Diff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add isolated Side-Git checkpoints, unified diffs, Java syntax validation, dependency-scoped diff context, and precise rollback to every serially executed Plan task.

**Architecture:** Extend the existing Side-Git repository with task snapshot phases and commit-to-worktree diff/restore operations. `PlanExecuteAgent` will execute one ready task at a time, reuse one checkpoint across retries, validate successful task diffs, retain valid diffs for dependency context, and restore only the active task checkpoint before terminal recovery.

**Tech Stack:** Java 17, Maven, JUnit 5, JGit, JavaParser

---

## File Map

**Create**

- `src/main/java/com/paicli/snapshot/TaskCheckpoint.java`: immutable task baseline descriptor, including unavailable-state support.
- `src/main/java/com/paicli/snapshot/TaskDiff.java`: changed-file list, line statistics, unified patch, and bounded LLM formatting.
- `src/main/java/com/paicli/plan/TaskSyntaxValidator.java`: JavaParser validation for changed Java files.
- `src/test/java/com/paicli/plan/TaskSyntaxValidatorTest.java`: syntax validation unit tests.
- `docs/phase-25-task-checkpoint-diff.md`: user-facing third-phase behavior and verification record.

**Modify**

- `src/main/java/com/paicli/snapshot/SnapshotPhase.java`: add `PRE_TASK` and `POST_TASK`.
- `src/main/java/com/paicli/snapshot/SideGitManager.java`: task snapshots, commit-to-worktree diff, and exact commit restore.
- `src/main/java/com/paicli/snapshot/SnapshotService.java`: synchronous task checkpoint facade with non-blocking degradation.
- `src/test/java/com/paicli/snapshot/SideGitManagerTest.java`: diff, phase, and precise restore coverage.
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`: serial task transaction lifecycle and dependency diff context.
- `src/test/java/com/paicli/agent/PlanExecuteAgentTest.java`: Plan checkpoint integration and rollback coverage.
- `README.md`: add third-phase status.
- `AGENTS.md`: update delivered phase, Plan execution rules, and verification path.
- `docs/phase-24-plan-failure-recovery.md`: replace turn rollback with task rollback.
- `docs/phase-18-side-history-snapshot.md`: record that phase 25 supersedes the earlier no-task-snapshot boundary.

### Task 1: Task Snapshot Models and Bounded Context

**Files:**
- Create: `src/main/java/com/paicli/snapshot/TaskCheckpoint.java`
- Create: `src/main/java/com/paicli/snapshot/TaskDiff.java`
- Test: `src/test/java/com/paicli/snapshot/SideGitManagerTest.java`

- [ ] **Step 1: Write failing model tests**

Add tests proving unavailable checkpoints are explicit and diff formatting always preserves metadata while truncating the patch:

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

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

Expected: compilation fails because `TaskCheckpoint` and `TaskDiff` do not exist.

- [ ] **Step 3: Add the minimal immutable models**

Create `TaskCheckpoint`:

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

Create `TaskDiff`:

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

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

Expected: all `SideGitManagerTest` tests pass.

- [ ] **Step 5: Commit the models**

```bash
git add src/main/java/com/paicli/snapshot/TaskCheckpoint.java src/main/java/com/paicli/snapshot/TaskDiff.java src/test/java/com/paicli/snapshot/SideGitManagerTest.java
git commit -m "add task checkpoint models"
```

### Task 2: Side-Git Task Diff and Exact Restore

**Files:**
- Modify: `src/main/java/com/paicli/snapshot/SnapshotPhase.java`
- Modify: `src/main/java/com/paicli/snapshot/SideGitManager.java`
- Modify: `src/main/java/com/paicli/snapshot/SnapshotService.java`
- Test: `src/test/java/com/paicli/snapshot/SideGitManagerTest.java`

- [ ] **Step 1: Write failing Side-Git transaction tests**

Add one test that modifies, creates, and deletes files, then checks diff metadata and exact restore:

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

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

Expected: compilation fails because task snapshot, diff, and exact restore methods are missing.

- [ ] **Step 3: Add task phases and Side-Git operations**

Add enum constants:

```java
PRE_TASK("pre-task"),
POST_TASK("post-task"),
```

Add synchronized task snapshot methods that delegate to `createSnapshot(...)`:

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

Implement `diffFromSnapshot(...)` with `CanonicalTreeParser`, `FileTreeIterator`, and `DiffFormatter`. Sort changed paths, format each `DiffEntry`, and count patch lines whose prefix is `+` or `-` while excluding `+++` and `---` headers:

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

Add the helpers used by `diffFromSnapshot(...)`:

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

Refactor the current restore body into `restoreSnapshot(commitId, taskId)`. It must create a `pre-restore` snapshot, compare its tree with the target tree, delete files absent from the target, and write target blobs. Make `restorePreTurn(...)` locate the pre-turn commit and delegate to this method.

- [ ] **Step 4: Add the non-blocking SnapshotService facade**

Add synchronous methods:

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

- [ ] **Step 5: Run snapshot tests and verify GREEN**

Run:

```bash
mvn test -Dtest=SideGitManagerTest -DskipTests=false
```

Expected: task diff, task phase, existing turn restore, and async service tests all pass.

- [ ] **Step 6: Commit Side-Git task operations**

```bash
git add src/main/java/com/paicli/snapshot src/test/java/com/paicli/snapshot/SideGitManagerTest.java
git commit -m "add side git task transactions"
```

### Task 3: Java Task Syntax Validation

**Files:**
- Create: `src/main/java/com/paicli/plan/TaskSyntaxValidator.java`
- Create: `src/test/java/com/paicli/plan/TaskSyntaxValidatorTest.java`

- [ ] **Step 1: Write failing syntax validator tests**

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

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
mvn test -Dtest=TaskSyntaxValidatorTest -DskipTests=false
```

Expected: compilation fails because `TaskSyntaxValidator` does not exist.

- [ ] **Step 3: Implement JavaParser validation**

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

- [ ] **Step 4: Run validator tests and verify GREEN**

Run:

```bash
mvn test -Dtest=TaskSyntaxValidatorTest -DskipTests=false
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit syntax validation**

```bash
git add src/main/java/com/paicli/plan/TaskSyntaxValidator.java src/test/java/com/paicli/plan/TaskSyntaxValidatorTest.java
git commit -m "validate task java changes"
```

### Task 4: Serial Plan Task Transactions and Diff Context

**Files:**
- Modify: `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- Modify: `src/test/java/com/paicli/agent/PlanExecuteAgentTest.java`

- [ ] **Step 1: Write failing Plan integration tests**

Add test fixtures that use a temporary `SnapshotService`, a two-task `StubPlanner`, and scripted LLM responses.

The first test writes a valid Java file in `task_1`, completes it, and verifies `task_2` receives the dependency diff:

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

The second test preserves a successful first-task file when the dependent second task creates invalid Java:

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

Add the retry and serial-execution tests:

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

Add these complete fixtures to `PlanExecuteAgentTest`:

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

Add imports for `ObjectMapper`, snapshot classes, `Map`, and `AtomicInteger`.

- [ ] **Step 2: Run Plan tests and verify RED**

Run:

```bash
mvn test -Dtest=PlanExecuteAgentTest -DskipTests=false
```

Expected: new assertions fail because Plan still executes ready tasks in parallel, creates no task checkpoints, and injects no task diff.

- [ ] **Step 3: Replace batch execution with one-task execution**

In `executePlan(...)`, select the first ready task from `getExecutableTasksInOrder(plan)` each loop and execute it before selecting another:

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

Replace `executeTaskBatch(...)` with:

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

Remove unused executor, future, and byte-buffer imports and code.

- [ ] **Step 4: Add checkpoint lifecycle and validation**

At the start of `executePlan(...)`, create:

```java
Map<String, TaskCheckpoint> taskCheckpoints = new HashMap<>();
Map<String, TaskDiff> completedTaskDiffs = new LinkedHashMap<>();
SnapshotService snapshots = toolRegistry.getSnapshotService();
TaskSyntaxValidator syntaxValidator = new TaskSyntaxValidator();
Path projectRoot = Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();
```

Before a task's first execution:

```java
TaskCheckpoint checkpoint = taskCheckpoints.computeIfAbsent(
        task.getId(),
        ignored -> snapshots.beginTask(task.getId(), task.getDescription())
);
```

Before marking a successful task complete:

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

If `diffTask(...)` itself fails, log the warning, continue with `TaskDiff.empty(taskId)`, and do not claim diff protection in output.

- [ ] **Step 5: Restore active task before terminal recovery**

Add:

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

Call it:

- before `Planner.replan(...)`;
- for `ROLLBACK`;
- after `RETRY` or `FIX_PARAMETERS` exhausts the attempt budget;
- before final failed-task result handling.

Do not remove the checkpoint when scheduling a retry. Replace `rollbackAfterFailure(...)` so its response reports task checkpoint restoration rather than `restorePreTurn(1)`.

- [ ] **Step 6: Inject dependency-scoped diff context**

Extend `executeTask(...)` and `buildTaskContext(...)` with `Map<String, TaskDiff> completedTaskDiffs`.

Collect transitive dependencies in stable order:

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

Append only dependency diffs with a shared 12,000-character budget:

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

- [ ] **Step 7: Run Plan and recovery regression tests**

Run:

```bash
mvn test -Dtest=PlanExecuteAgentTest,TaskFailureClassifierTest,TaskSyntaxValidatorTest,SideGitManagerTest -DskipTests=false
```

Expected: all focused tests pass; the invalid Java fixture is removed while the valid previous-task file remains.

- [ ] **Step 8: Commit Plan integration**

```bash
git add src/main/java/com/paicli/agent/PlanExecuteAgent.java src/test/java/com/paicli/agent/PlanExecuteAgentTest.java
git commit -m "add plan task transactions"
```

### Task 5: Third-Phase Documentation

**Files:**
- Create: `docs/phase-25-task-checkpoint-diff.md`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/phase-24-plan-failure-recovery.md`
- Modify: `docs/phase-18-side-history-snapshot.md`

- [ ] **Step 1: Write the third-phase implementation document**

Document:

- task checkpoint lifecycle;
- serial Plan execution rationale;
- retry baseline reuse;
- Java syntax validation boundary;
- dependency-only 12,000-character diff context;
- precise rollback and disabled-checkpoint degradation;
- exact verification commands and observed results.

- [ ] **Step 2: Incrementally update the repository homepage**

Add this status section after phase two:

```markdown
### 第三期优化：任务级 Checkpoint 与 Diff

- Plan task 改为串行事务执行，每个 task 首次执行前创建独立 Side-Git checkpoint
- task 成功后生成文件 diff，并对变更的 Java 文件执行语法校验
- 成功 diff 只注入后续依赖任务，统一限制上下文长度
- task 最终失败或重新规划前只回滚当前 task，保留此前成功 task 的改动
- 详细记录见 [docs/phase-25-task-checkpoint-diff.md](docs/phase-25-task-checkpoint-diff.md)
```

Change the roadmap item “为每个任务节点增加 checkpoint 和 diff 审查” to completed wording and make the next unimplemented item the first future step.

- [ ] **Step 3: Synchronize behavior docs**

In `AGENTS.md`, record phase 25 as delivered, state that Plan ready tasks are serial while task transactions are active, and add this verification row:

```markdown
| Plan task checkpoint | `mvn test -Dtest=SideGitManagerTest,TaskSyntaxValidatorTest,PlanExecuteAgentTest` |
```

In phase 24, replace `restorePreTurn(1)` with task-checkpoint restore. In phase 18, keep the historical MVP text but add a note that phase 25 intentionally supersedes the no-task-snapshot limitation for Plan.

- [ ] **Step 4: Check documentation links and whitespace**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md AGENTS.md docs/phase-18-side-history-snapshot.md docs/phase-24-plan-failure-recovery.md docs/phase-25-task-checkpoint-diff.md
git commit -m "document third phase task checkpoints"
```

### Task 6: Full Verification and Publish

**Files:**
- Verify all changed production, test, and documentation files.

- [ ] **Step 1: Run focused feature tests**

```bash
mvn test -Dtest=SideGitManagerTest,TaskSyntaxValidatorTest,PlanExecuteAgentTest,TaskFailureClassifierTest,AgentRouterTest -DskipTests=false
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Run the quick regression profile**

```bash
mvn test -Pquick
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Build the runnable shaded JAR**

```bash
mvn package -DskipTests
```

Expected: `BUILD SUCCESS` and `target/paicli-1.0-SNAPSHOT.jar`.

- [ ] **Step 4: Run the CLI startup smoke test**

PowerShell:

```powershell
$env:GLM_API_KEY='test-key'
'/exit' | java -jar target\paicli-1.0-SNAPSHOT.jar
```

Expected: process exits with code `0`, prints the startup screen, accepts `/exit`, and performs no model request.

- [ ] **Step 5: Inspect final repository state**

```bash
git status -sb
git log -6 --oneline --decorate
git diff origin/main...HEAD --check
```

Expected: only intentional commits are ahead of `origin/main`; no unstaged files or whitespace errors.

- [ ] **Step 6: Push the current main branch**

```bash
git push origin main
```

Expected: `origin/main` advances to the final third-phase documentation commit.
