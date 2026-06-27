package com.paicli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }

    @Test
    void taskDiffFormatsMetadataAndTruncatesPatch() {
        TaskDiff diff = new TaskDiff(
                "task_1",
                List.of("src/A.java", "src/B.java"),
                4,
                2,
                "+".repeat(200)
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
        Path snapshots = tempDir.resolve("phase-snapshots");
        Files.createDirectories(project);
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));

        manager.preTaskSnapshot("task_1", "before");
        manager.postTaskSnapshot("task_1", "after");

        List<TurnSnapshot> all = manager.listSnapshots(2);
        assertEquals(SnapshotPhase.POST_TASK, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TASK, all.get(1).phase());
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
}
