package com.paicli.snapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SnapshotService implements AutoCloseable {
    private final SideGitManager manager;
    private final ExecutorService executor;
    private volatile Future<?> lastAsyncTask;

    public SnapshotService(SideGitManager manager) {
        this.manager = manager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-snapshot-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static SnapshotService forProject(Path projectRoot) {
        return new SnapshotService(new SideGitManager(projectRoot));
    }

    public <T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier) throws Exception {
        String turnId = turnId(mode);
        String summary = summarize(mode, input);
        snapshotBeforeTurn(turnId, summary);
        try {
            return supplier.get();
        } finally {
            snapshotAfterTurnAsync(turnId, summary);
        }
    }

    public void snapshotBeforeTurn(String turnId, String summary) {
        if (!manager.config().enabled()) {
            return;
        }
        try {
            manager.preTurnSnapshot(turnId, summary);
        } catch (Exception e) {
            System.err.println("⚠️ pre-turn 快照失败: " + e.getMessage());
        }
    }

    public void snapshotAfterTurnAsync(String turnId, String summary) {
        if (!manager.config().enabled()) {
            return;
        }
        lastAsyncTask = executor.submit(() -> {
            try {
                manager.postTurnSnapshot(turnId, summary);
            } catch (Exception e) {
                System.err.println("⚠️ post-turn 快照失败: " + e.getMessage());
            }
        });
    }

    public List<TurnSnapshot> listSnapshots(int limit) throws Exception {
        awaitIdle();
        return manager.listSnapshots(limit);
    }

    public RestoreResult restorePreTurn(int offset) throws Exception {
        awaitIdle();
        return manager.restorePreTurn(offset);
    }

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
        awaitIdle();
        return manager.restoreSnapshot(checkpoint.snapshotCommitId(), checkpoint.taskId());
    }

    public String status() {
        return manager.formatStatus();
    }

    public String clean() {
        return manager.cleanSnapshots();
    }

    public SideGitManager manager() {
        return manager;
    }

    public void awaitIdle() throws Exception {
        Future<?> task = lastAsyncTask;
        if (task != null) {
            task.get(60, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String turnId(String mode) {
        String safeMode = mode == null || mode.isBlank() ? "turn" : mode.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return safeMode + "-" + Instant.now().toEpochMilli();
    }

    private static String summarize(String mode, String input) {
        String normalized = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120) + "...";
        }
        return "mode=" + (mode == null ? "turn" : mode) + "\ninput=" + normalized;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
