package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.snapshot.SideGitManager;
import com.paicli.snapshot.SnapshotConfig;
import com.paicli.snapshot.SnapshotService;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWritePlanExecutionArtifactsBackToShortTermMemoryOnly() throws Exception {
        Path sampleFile = Files.createFile(tempDir.resolve("sample.txt"));
        Files.writeString(sampleFile, "plan-memory-content");

        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_1",
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + sampleFile.toString().replace("\\", "\\\\") + "\"}"
                                )
                        )),
                        120,
                        30
                ),
                new LlmClient.ChatResponse("assistant", "已读取并确认文件内容", null, 140, 40)
        ));

        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                new LongTermMemory(tempDir.resolve("memory-store").toFile())
        );
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(tempDir.toString());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolRegistry,
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("请读取测试文件并确认内容");

        List<String> shortTermContents = memoryManager.getShortTermMemory().getAll().stream()
                .map(entry -> entry.getContent())
                .toList();

        assertTrue(result.contains("计划执行完成"));
        assertTrue(shortTermContents.stream().anyMatch(content -> content.contains("请读取测试文件并确认内容")));
        assertTrue(shortTermContents.stream().anyMatch(content -> content.contains("plan-memory-content")));
        assertTrue(shortTermContents.stream().anyMatch(content -> content.contains("已读取并确认文件内容")));
        assertEquals(0, memoryManager.getLongTermMemory().size());
    }

    @Test
    void shouldNotExtractFactsWhenPlanIsCanceled() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of());
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory-store-cancel").toFile());
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                longTermMemory
        );
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("⏹️ 已取消本次计划执行。", result);
        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldNotRepeatStreamedTaskOutputInFinalPlanSummary() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "当前目录包含 8 个目录和 8 个文件。",
                        null,
                        60,
                        20
                ))
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("✅ 计划执行完成！", result);
    }

    @Test
    void shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(
                        listener -> {
                            listener.onReasoningDelta("  \n");
                            listener.onContentDelta("我来读取 pom.xml 文件。");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来读取 pom.xml 文件。",
                                "  \n",
                                null,
                                60,
                                20
                        )
                )
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.run("读取 pom.xml");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("任务思考 [task_1]"),
                "空白 reasoning 不应打印空的任务思考标题: " + rendered);
        assertTrue(rendered.contains("任务输出 [task_1]"));
        assertFalse(rendered.contains("任务结果 [task_1]"),
                "tool-call 前后的流式 content 不应被误标成任务结果: " + rendered);
    }

    @Test
    void shouldInjectRecoveryHintWhenToolParametersAreInvalid() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_bad_args",
                                new LlmClient.ToolCall.Function("read_file", "{}")
                        )),
                        60,
                        10
                ),
                new LlmClient.ChatResponse("assistant", "fixed parameters result", null, 80, 20)
        ));

        ToolRegistry toolRegistry = new ToolRegistry() {
            @Override
            public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
                ToolInvocation invocation = invocations.get(0);
                return List.of(new ToolExecutionResult(
                        invocation.id(),
                        invocation.name(),
                        invocation.argumentsJson(),
                        "Tool execution failed: missing required parameter: path",
                        0,
                        false,
                        List.of()
                ));
            }
        };

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolRegistry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("read file with invalid parameters");

        assertTrue(result.contains("fixed parameters result"));
        assertTrue(llmClient.seenMessages().stream()
                        .flatMap(List::stream)
                        .filter(message -> "user".equals(message.role()))
                        .anyMatch(message -> message.content() != null
                                && message.content().contains("FIX_PARAMETERS")),
                "Plan execution should tell the model to fix bad tool parameters before retrying");
    }

    @Test
    void shouldInjectSuccessfulTaskDiffIntoDependentTask() throws Exception {
        Path project = createProject("diff-context");
        ToolRegistry tools = taskToolRegistry(project);
        StubGLMClient llmClient = new StubGLMClient(List.of(
                writeToolResponse("write_1", project.resolve("src/Feature.java"), "class Feature {}"),
                new LlmClient.ChatResponse("assistant", "task one done", null, 30, 10),
                new LlmClient.ChatResponse("assistant", "task two done", null, 30, 10)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                tools,
                new TwoTaskPlanner(llmClient, Task.TaskType.ANALYSIS, true),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("implement then review");

        assertTrue(result.contains("task two done"));
        assertTrue(llmClient.seenMessages().stream()
                        .flatMap(List::stream)
                        .filter(message -> "user".equals(message.role()))
                        .anyMatch(message -> message.content() != null
                                && message.content().contains("[TASK_DIFF task_1]")
                                && message.content().contains("src/Feature.java")),
                "Dependent tasks should receive the successful task diff");
    }

    @Test
    void shouldExecuteReadyPlanTasksSerially() throws Exception {
        Path project = createProject("serial-plan");
        ToolRegistry tools = taskToolRegistry(project);
        ConcurrentProbeGLMClient llmClient = new ConcurrentProbeGLMClient();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                tools,
                new TwoTaskPlanner(llmClient, Task.TaskType.ANALYSIS, false),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        agent.run("analyze two independent areas");

        assertEquals(1, llmClient.maxConcurrentCalls());
    }

    @Test
    void shouldRollbackOnlyFailedTaskAndKeepPreviousTaskChanges() throws Exception {
        Path project = createProject("precise-rollback");
        ToolRegistry tools = taskToolRegistry(project);
        StubGLMClient llmClient = new StubGLMClient(List.of(
                writeToolResponse("write_1", project.resolve("src/Good.java"), "class Good {}"),
                new LlmClient.ChatResponse("assistant", "task one done", null, 30, 10),
                writeToolResponse("write_2", project.resolve("src/Broken.java"), "class Broken {"),
                new LlmClient.ChatResponse("assistant", "task two done", null, 30, 10)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                tools,
                new TwoTaskPlanner(llmClient, Task.TaskType.FILE_WRITE, true),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("write valid code then invalid code");

        assertTrue(result.toLowerCase().contains("rollback"));
        assertTrue(Files.exists(project.resolve("src/Good.java")));
        assertFalse(Files.exists(project.resolve("src/Broken.java")));
    }

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

    private LlmClient.ChatResponse writeToolResponse(String callId, Path file, String content) throws Exception {
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

    private record StubResponse(LlmClient.ChatResponse response, boolean streamContent,
                                java.util.function.Consumer<LlmClient.StreamListener> streamScript) {
        private static StubResponse plain(LlmClient.ChatResponse response) {
            return new StubResponse(response, false, null);
        }

        private static StubResponse streamed(LlmClient.ChatResponse response) {
            return new StubResponse(response, true, null);
        }

        private static StubResponse scripted(java.util.function.Consumer<LlmClient.StreamListener> streamScript,
                                             LlmClient.ChatResponse response) {
            return new StubResponse(response, false, streamScript);
        }
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "读取测试文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class TwoTaskPlanner extends Planner {
        private final Task.TaskType secondType;
        private final boolean dependent;

        private TwoTaskPlanner(LlmClient llmClient, Task.TaskType secondType, boolean dependent) {
            super(llmClient);
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

    private static final class StubGLMClient extends GLMClient {
        private final Queue<StubResponse> responses;
        private final List<List<Message>> seenMessages = new ArrayList<>();

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses.stream().map(StubResponse::plain).toList());
        }

        private StubGLMClient(Queue<StubResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        private static StubGLMClient streaming(List<StubResponse> responses) {
            return new StubGLMClient(new ArrayDeque<>(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            seenMessages.add(List.copyOf(messages));
            StubResponse stubResponse = responses.poll();
            if (stubResponse == null) {
                throw new IOException("缺少预设响应");
            }
            if (stubResponse.streamScript() != null) {
                stubResponse.streamScript().accept(listener);
            } else if (stubResponse.streamContent() && stubResponse.response().content() != null) {
                listener.onContentDelta(stubResponse.response().content());
            }
            return stubResponse.response();
        }

        private List<List<Message>> seenMessages() {
            return seenMessages;
        }
    }

    private static final class ConcurrentProbeGLMClient extends GLMClient {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();

        private ConcurrentProbeGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
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
    }
}
