package com.paicli.policy;

/**
 * Preview result for a tool call before HITL caching and actual execution.
 */
public record ToolCallRisk(boolean blocked, boolean requiresPerCallApproval, String reason) {

    public static ToolCallRisk allow() {
        return new ToolCallRisk(false, false, null);
    }

    public static ToolCallRisk block(String reason) {
        return new ToolCallRisk(true, false, reason);
    }

    public static ToolCallRisk requireApproval(String reason) {
        return new ToolCallRisk(false, true, reason);
    }
}
