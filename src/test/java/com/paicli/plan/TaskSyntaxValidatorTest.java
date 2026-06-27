package com.paicli.plan;

import com.paicli.snapshot.TaskDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSyntaxValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsValidChangedJavaFile() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Valid.java"), "class Valid {}");
        TaskDiff diff = new TaskDiff("task_1", List.of("src/Valid.java"), 1, 0, "");

        TaskSyntaxValidator.ValidationResult result = new TaskSyntaxValidator().validate(tempDir, diff);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsInvalidChangedJavaFile() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Broken.java"), "class Broken {");
        TaskDiff diff = new TaskDiff("task_2", List.of("src/Broken.java"), 1, 0, "");

        TaskSyntaxValidator.ValidationResult result = new TaskSyntaxValidator().validate(tempDir, diff);

        assertFalse(result.valid());
        assertTrue(result.summary().contains("src/Broken.java"));
    }

    @Test
    void ignoresDeletedAndNonJavaFiles() {
        TaskDiff diff = new TaskDiff(
                "task_3",
                List.of("deleted/Missing.java", "README.md"),
                0,
                2,
                ""
        );

        assertTrue(new TaskSyntaxValidator().validate(tempDir, diff).valid());
    }
}
