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
