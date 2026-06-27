package com.paicli.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskFailureClassifierTest {

    private final TaskFailureClassifier classifier = new TaskFailureClassifier();

    @Test
    void classifiesTransientToolFailuresAsRetryable() {
        Task task = new Task("task_1", "fetch remote data", Task.TaskType.COMMAND);

        TaskFailureClassifier.Decision decision = classifier.classify(
                task,
                new IOException("HTTP 503 service unavailable, retry later"));

        assertEquals(TaskFailureClassifier.FailureKind.TOOL_TRANSIENT_FAILURE, decision.kind());
        assertEquals(TaskFailureClassifier.RecoveryAction.RETRY, decision.action());
    }

    @Test
    void classifiesParameterErrorsAsFixParameters() {
        Task task = new Task("task_1", "read file", Task.TaskType.FILE_READ);

        TaskFailureClassifier.Decision decision = classifier.classify(
                task,
                new IllegalArgumentException("missing required parameter: path"));

        assertEquals(TaskFailureClassifier.FailureKind.PARAMETER_ERROR, decision.kind());
        assertEquals(TaskFailureClassifier.RecoveryAction.FIX_PARAMETERS, decision.action());
    }

    @Test
    void classifiesDependencyErrorsAsReplan() {
        Task task = new Task("task_2", "build after setup", Task.TaskType.COMMAND);
        task.addDependency("task_1");

        TaskFailureClassifier.Decision decision = classifier.classify(
                task,
                new IOException("dependency task_1 output not found"));

        assertEquals(TaskFailureClassifier.FailureKind.DEPENDENCY_ERROR, decision.kind());
        assertEquals(TaskFailureClassifier.RecoveryAction.REPLAN, decision.action());
    }

    @Test
    void classifiesVerificationFailuresAsRollback() {
        Task task = new Task("task_3", "run tests", Task.TaskType.VERIFICATION);

        TaskFailureClassifier.Decision decision = classifier.classify(
                task,
                new AssertionError("test failed: expected 200 but was 500"));

        assertEquals(TaskFailureClassifier.FailureKind.VALIDATION_FAILURE, decision.kind());
        assertEquals(TaskFailureClassifier.RecoveryAction.ROLLBACK, decision.action());
    }

    @Test
    void prioritizesExplicitValidationFailureOverEofKeyword() {
        Task task = new Task("task_4", "write Java source", Task.TaskType.FILE_WRITE);

        TaskFailureClassifier.Decision decision = classifier.classify(
                task,
                new IOException("validation failed: Parse error. Found <EOF>"));

        assertEquals(TaskFailureClassifier.FailureKind.VALIDATION_FAILURE, decision.kind());
        assertEquals(TaskFailureClassifier.RecoveryAction.ROLLBACK, decision.action());
    }
}
