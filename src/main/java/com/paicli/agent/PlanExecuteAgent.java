package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.*;
import com.paicli.prompt.PromptAssembler;
import com.paicli.prompt.PromptContext;
import com.paicli.prompt.PromptMode;
import com.paicli.runtime.CancellationContext;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SnapshotService;
import com.paicli.snapshot.TaskCheckpoint;
import com.paicli.snapshot.TaskDiff;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillIndexFormatter;
import com.paicli.skill.SkillRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import com.paicli.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    private static final class ClassifiedTaskFailureException extends IOException {
        private final TaskFailureClassifier.Decision decision;
        private final String detail;

        private ClassifiedTaskFailureException(TaskFailureClassifier.Decision decision, String detail) {
            super(detail);
            this.decision = decision;
            this.detail = detail;
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final PrintStream out;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private final TaskFailureClassifier failureClassifier = new TaskFailureClassifier();

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(this.memoryManager::storeFact);
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    private void maybeCompactHistory(List<LlmClient.Message> messages, PrintStream out) {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(messages, trigger);
            if (compacted && out != null) {
                out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);
        StreamState streamState = new StreamState();
        try {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果] " + outcome.result());
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            memoryManager.addAssistantMessage(errorMessage);
            return errorMessage;
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();
        Map<String, Integer> recoveryAttempts = new HashMap<>();
        Map<String, String> recoveryHints = new HashMap<>();
        Map<String, TaskCheckpoint> taskCheckpoints = new HashMap<>();
        Map<String, TaskDiff> completedTaskDiffs = new LinkedHashMap<>();
        SnapshotService snapshots = toolRegistry.getSnapshotService();
        TaskSyntaxValidator syntaxValidator = new TaskSyntaxValidator();
        Path projectRoot = Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            Task nextTask = executableTasks.get(0);
            TaskCheckpoint checkpoint = taskCheckpoints.computeIfAbsent(
                    nextTask.getId(),
                    ignored -> snapshots.beginTask(nextTask.getId(), nextTask.getDescription())
            );
            List<TaskExecutionResult> batchResults = List.of(executeSingleTask(
                    plan,
                    nextTask,
                    streamState,
                    recoveryHints,
                    completedTaskDiffs
            ));
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    TaskDiff taskDiff = captureTaskDiff(snapshots, checkpoint, task);
                    TaskSyntaxValidator.ValidationResult validation = syntaxValidator.validate(projectRoot, taskDiff);
                    if (!validation.valid()) {
                        batchResult = TaskExecutionResult.failure(
                                task,
                                new IOException("validation failed:\n" + validation.summary())
                        );
                    } else {
                        snapshots.completeTask(checkpoint, task.getDescription());
                        if (checkpoint.active()) {
                            completedTaskDiffs.put(task.getId(), taskDiff);
                        }
                        taskCheckpoints.remove(task.getId());
                    }
                }

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    recoveryHints.remove(task.getId());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                TaskFailureClassifier.Decision decision = classifyFailure(task, error);
                String detail = failureDetail(error);
                log.warn("Task failed: {} kind={} action={} error={}",
                        task.getId(), decision.kind(), decision.action(), detail);
                out.println(AnsiStyle.subtle("  recovery: " + decision.kind()
                        + " -> " + decision.action() + " (" + decision.reason() + ")"));

                if (decision.action() == TaskFailureClassifier.RecoveryAction.RETRY
                        || decision.action() == TaskFailureClassifier.RecoveryAction.FIX_PARAMETERS) {
                    int attempts = recoveryAttempts.getOrDefault(task.getId(), 0);
                    if (attempts < MAX_TASK_RECOVERY_ATTEMPTS) {
                        recoveryAttempts.put(task.getId(), attempts + 1);
                        recoveryHints.put(task.getId(), buildRecoveryInstruction(task, decision, detail));
                        task.setStatus(Task.TaskStatus.PENDING);
                        task.setError(null);
                        out.println(AnsiStyle.subtle("  retrying task " + task.getId()
                                + " with recovery hint (" + (attempts + 1) + "/"
                                + MAX_TASK_RECOVERY_ATTEMPTS + ")") + "\n");
                        continue;
                    }
                }

                if (decision.action() == TaskFailureClassifier.RecoveryAction.REPLAN) {
                    RestoreResult restoreResult = restoreFailedTask(task, taskCheckpoints);
                    logTaskRestore(task, restoreResult);
                    out.println("馃攧 灏濊瘯閲嶆柊瑙勫垝...\n");
                    ExecutionPlan replanned = planner.replan(plan, detail);
                    return reviewAndExecutePlan(replanned, streamState).result();
                }

                if (decision.action() == TaskFailureClassifier.RecoveryAction.ROLLBACK) {
                    task.markFailed(detail);
                    RestoreResult restoreResult = restoreFailedTask(task, taskCheckpoints);
                    return rollbackAfterFailure(task, decision, detail, restoreResult);
                }
                RestoreResult restoreResult = restoreFailedTask(task, taskCheckpoints);
                logTaskRestore(task, restoreResult);
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5) {
                    out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replanned, streamState).result();
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private TaskExecutionResult executeSingleTask(ExecutionPlan plan,
                                                  Task task,
                                                  StreamState streamState,
                                                  Map<String, String> recoveryHints,
                                                  Map<String, TaskDiff> completedTaskDiffs) {
        log.info("Executing task serially: {} type={}", task.getId(), task.getType());
        out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
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

    private static final int MAX_TASK_ITERATIONS = 5;
    private static final int MAX_TASK_RECOVERY_ATTEMPTS = 1;
    private static final int MAX_TASK_DIFF_CONTEXT_CHARS = 12_000;

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out,
                                      String recoveryHint,
                                      Map<String, TaskDiff> completedTaskDiffs) throws IOException {
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = buildTaskContext(goal, plan, task, completedTaskDiffs);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }
        if (recoveryHint != null && !recoveryHint.isBlank()) {
            taskInput = taskInput + "\n\n" + recoveryHint;
        }
        taskInput = prependSkillBodies(taskInput);

        List<LlmClient.Message> messages = new ArrayList<>(Arrays.asList(
                LlmClient.Message.system(prompt),
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(toolRegistry.getProjectPath()))
        ));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCachedInputTokens = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }
            iteration++;

            // 调 LLM 前评估 messages 是否接近 window 上限；超阈值压缩早期消息为摘要。
            injectPendingLspDiagnostics(messages, out);
            maybeCompactHistory(messages, out);

            LlmClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolRegistry.getToolDefinitions(),
                    streamRenderer
            );
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }

            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();
            totalCachedInputTokens += response.cachedInputTokens();

            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            if (!response.hasToolCalls()) {
                memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens, totalCachedInputTokens);
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    if (!toolOnlyResult.isBlank()) {
                        memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + toolOnlyResult);
                    }
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }
                if (response.content() != null && !response.content().isBlank()) {
                    memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + response.content());
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            printToolCalls(out, response.toolCalls());
            messages.add(LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = executeToolCalls(task.getId(), response.toolCalls());
            for (ToolExecutionResult toolResult : toolResults) {
                memoryManager.addToolResult(toolResult.name(), toolResult.result());
                allResults.append(toolResult.result()).append("\n");
                messages.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                if (failureClassifier.isFailureResult(toolResult)) {
                    TaskFailureClassifier.Decision decision = failureClassifier.classify(task, toolResult);
                    if (decision.action() == TaskFailureClassifier.RecoveryAction.REPLAN
                            || decision.action() == TaskFailureClassifier.RecoveryAction.ROLLBACK) {
                        throw new ClassifiedTaskFailureException(decision, toolResult.result());
                    }
                    messages.add(LlmClient.Message.user(buildRecoveryInstruction(task, decision, toolResult.result())));
                }
            }
            appendImageToolMessages(messages, toolResults);
        }

        String fallbackResult = allResults.toString().trim();
        if (!fallbackResult.isBlank()) {
            memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + fallbackResult);
        }
        streamRenderer.finish();
        return TaskRunResult.of(fallbackResult, streamRenderer.hasStreamedOutput());
    }

    private TaskDiff captureTaskDiff(SnapshotService snapshots,
                                     TaskCheckpoint checkpoint,
                                     Task task) {
        try {
            return snapshots.diffTask(checkpoint);
        } catch (Exception e) {
            log.warn("Task diff unavailable: taskId={}", task.getId(), e);
            out.println(AnsiStyle.subtle("  task diff unavailable [" + task.getId()
                    + "]: " + e.getMessage()));
            return TaskDiff.empty(task.getId());
        }
    }

    private TaskFailureClassifier.Decision classifyFailure(Task task, Exception error) {
        if (error instanceof ClassifiedTaskFailureException classified) {
            return classified.decision;
        }
        return failureClassifier.classify(task, error);
    }

    private String failureDetail(Exception error) {
        if (error instanceof ClassifiedTaskFailureException classified) {
            return classified.detail;
        }
        return error == null || error.getMessage() == null ? "unknown failure" : error.getMessage();
    }

    private String buildRecoveryInstruction(Task task, TaskFailureClassifier.Decision decision, String detail) {
        return """
                [PLAN_RECOVERY]
                task_id=%s
                failure_kind=%s
                recovery_action=%s
                reason=%s
                failure_detail=%s

                Apply the requested recovery action before continuing this task.
                - RETRY: retry the failed operation only when it is safe and idempotent.
                - FIX_PARAMETERS: correct the tool name or arguments, then call the tool again.
                - REPLAN: stop this task and ask the planner for a new path.
                - ROLLBACK: stop this task because verification failed after changes.
                """.formatted(
                task == null ? "" : task.getId(),
                decision.kind(),
                decision.action(),
                decision.reason(),
                detail == null ? "" : detail.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private RestoreResult restoreFailedTask(Task task,
                                            Map<String, TaskCheckpoint> taskCheckpoints) {
        TaskCheckpoint checkpoint = taskCheckpoints.remove(task.getId());
        if (checkpoint == null || !checkpoint.active()) {
            return RestoreResult.failure("task checkpoint unavailable");
        }
        try {
            return toolRegistry.getSnapshotService().restoreTask(checkpoint);
        } catch (Exception e) {
            return RestoreResult.failure("task rollback failed: " + e.getMessage());
        }
    }

    private void logTaskRestore(Task task, RestoreResult restoreResult) {
        String summary = restoreResult == null
                ? "task rollback returned no result"
                : restoreResult.formatForCli();
        log.info("Task rollback: taskId={} success={} summary={}",
                task.getId(), restoreResult != null && restoreResult.success(), summary);
        out.println(AnsiStyle.subtle("  task rollback [" + task.getId() + "]: " + summary));
    }

    private String rollbackAfterFailure(Task task,
                                        TaskFailureClassifier.Decision decision,
                                        String detail,
                                        RestoreResult restoreResult) {
        out.println(AnsiStyle.subtle("  rollback requested for " + task.getId()
                + " after " + decision.kind()));
        String restoreSummary = restoreResult == null
                ? "task rollback returned no result"
                : restoreResult.formatForCli();
        return "Plan failed during verification; task rollback executed.\n"
                + "Task: " + task.getId() + "\n"
                + "Failure: " + detail + "\n"
                + restoreSummary;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
            return "";
        }
    }

    private void injectPendingLspDiagnostics(List<LlmClient.Message> messages, PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        messages.add(LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<ToolExecutionResult> executeToolCalls(String taskId, List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private void appendImageToolMessages(List<LlmClient.Message> messages, List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            messages.add(LlmClient.Message.user(parts));
        }
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导。
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

    private String buildTaskContext(String goal,
                                    ExecutionPlan plan,
                                    Task task,
                                    Map<String, TaskDiff> completedTaskDiffs) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        appendDependencyDiffs(context, plan, task, completedTaskDiffs);
        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private void appendDependencyDiffs(StringBuilder context,
                                       ExecutionPlan plan,
                                       Task task,
                                       Map<String, TaskDiff> taskDiffs) {
        if (taskDiffs == null || taskDiffs.isEmpty()) {
            return;
        }
        LinkedHashSet<String> dependencyIds = new LinkedHashSet<>();
        collectDependencyIds(plan, task, dependencyIds);
        int remaining = MAX_TASK_DIFF_CONTEXT_CHARS;
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
        if (!dependencyIds.isEmpty()) {
            context.append("\n\n");
        }
    }

    private void collectDependencyIds(ExecutionPlan plan,
                                      Task task,
                                      LinkedHashSet<String> dependencyIds) {
        for (String dependencyId : task.getDependencies()) {
            Task dependency = plan.getTask(dependencyId);
            if (dependency == null || dependencyIds.contains(dependencyId)) {
                continue;
            }
            collectDependencyIds(plan, dependency, dependencyIds);
            dependencyIds.add(dependencyId);
        }
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
