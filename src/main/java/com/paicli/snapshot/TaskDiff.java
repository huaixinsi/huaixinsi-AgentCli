package com.paicli.snapshot;

import java.util.List;

public record TaskDiff(
        String taskId,
        List<String> changedFiles,
        int additions,
        int deletions,
        String unifiedDiff
) {
    private static final String TRUNCATED_MARKER = "\n[diff truncated]";

    public TaskDiff {
        taskId = taskId == null ? "" : taskId;
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        unifiedDiff = unifiedDiff == null ? "" : unifiedDiff;
    }

    public static TaskDiff empty(String taskId) {
        return new TaskDiff(taskId, List.of(), 0, 0, "");
    }

    public String formatForContext(int maxChars) {
        int limit = Math.max(0, maxChars);
        if (limit == 0) {
            return "";
        }
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
        int bodyBudget = Math.max(0, patchBudget - TRUNCATED_MARKER.length());
        return header + unifiedDiff.substring(0, bodyBudget) + TRUNCATED_MARKER;
    }
}
