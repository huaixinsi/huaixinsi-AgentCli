package com.paicli.plan;

import com.paicli.tool.ToolRegistry.ToolExecutionResult;

import java.util.Locale;

public class TaskFailureClassifier {

    public enum FailureKind {
        TOOL_TRANSIENT_FAILURE,
        PARAMETER_ERROR,
        DEPENDENCY_ERROR,
        VALIDATION_FAILURE,
        UNKNOWN
    }

    public enum RecoveryAction {
        RETRY,
        FIX_PARAMETERS,
        REPLAN,
        ROLLBACK
    }

    public record Decision(FailureKind kind, RecoveryAction action, String reason) {}

    public Decision classify(Task task, Throwable error) {
        String message = error == null || error.getMessage() == null
                ? error == null ? "" : error.toString()
                : error.getMessage();
        return classify(task, message, false);
    }

    public Decision classify(Task task, ToolExecutionResult result) {
        if (result == null) {
            return new Decision(FailureKind.UNKNOWN, RecoveryAction.REPLAN, "missing tool result");
        }
        return classify(task, result.result(), result.timedOut());
    }

    public boolean isFailureResult(ToolExecutionResult result) {
        if (result == null) {
            return false;
        }
        String normalized = normalize(result.result());
        return result.timedOut()
                || normalized.contains("tool execution failed")
                || normalized.contains("tool failed")
                || normalized.contains("execution failed")
                || normalized.contains("test failed")
                || normalized.contains("validation failed")
                || normalized.contains("verification failed")
                || normalized.contains("failed:")
                || normalized.contains("error:")
                || normalized.contains("\u5de5\u5177\u6267\u884c\u5931\u8d25")
                || normalized.contains("\u6267\u884c\u5931\u8d25")
                || normalized.contains("\u6d4b\u8bd5\u5931\u8d25")
                || normalized.contains("\u6821\u9a8c\u5931\u8d25")
                || normalized.contains("\u9a8c\u8bc1\u5931\u8d25");
    }

    private Decision classify(Task task, String rawMessage, boolean timedOut) {
        String message = normalize(rawMessage);

        if (timedOut || containsAny(message,
                "timeout", "timed out", "temporarily", "temporary", "service unavailable",
                "connection reset", "connection refused", "eof", "http 429", "http 503",
                "rate limit", "interrupted", "\u8d85\u65f6", "\u4e34\u65f6", "\u7f51\u7edc")) {
            return new Decision(FailureKind.TOOL_TRANSIENT_FAILURE, RecoveryAction.RETRY,
                    "tool failure looks temporary");
        }

        if (containsAny(message,
                "invalid argument", "invalid parameter", "bad argument", "bad parameter",
                "missing required", "required parameter", "cannot be empty", "empty argument",
                "malformed json", "parse json", "unknown option", "argument", "parameter",
                "\u53c2\u6570", "\u5fc5\u586b", "\u4e0d\u80fd\u4e3a\u7a7a", "\u683c\u5f0f\u9519\u8bef")) {
            return new Decision(FailureKind.PARAMETER_ERROR, RecoveryAction.FIX_PARAMETERS,
                    "tool arguments need correction");
        }

        if (containsAny(message,
                "dependency", "precondition", "not found", "no such file", "does not exist",
                "missing output", "missing artifact", "\u4f9d\u8d56", "\u4e0d\u5b58\u5728",
                "\u627e\u4e0d\u5230", "\u7f3a\u5c11")) {
            return new Decision(FailureKind.DEPENDENCY_ERROR, RecoveryAction.REPLAN,
                    "task dependency or prerequisite is missing");
        }

        if (isVerificationTask(task) || containsAny(message,
                "test failed", "tests failed", "assertion", "verification failed",
                "validation failed", "check failed", "\u6d4b\u8bd5\u5931\u8d25",
                "\u6821\u9a8c\u5931\u8d25", "\u9a8c\u8bc1\u5931\u8d25", "\u65ad\u8a00")) {
            return new Decision(FailureKind.VALIDATION_FAILURE, RecoveryAction.ROLLBACK,
                    "verification failed after plan changes");
        }

        return new Decision(FailureKind.UNKNOWN, RecoveryAction.REPLAN,
                "failure does not match a safe local recovery pattern");
    }

    private boolean isVerificationTask(Task task) {
        return task != null && task.getType() == Task.TaskType.VERIFICATION;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
