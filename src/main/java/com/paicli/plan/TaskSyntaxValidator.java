package com.paicli.plan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Problem;
import com.paicli.snapshot.TaskDiff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TaskSyntaxValidator {
    private final JavaParser parser = new JavaParser();

    public ValidationResult validate(Path projectRoot, TaskDiff diff) {
        if (diff == null) {
            return ValidationResult.success();
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        for (String relative : diff.changedFiles()) {
            if (relative == null || !relative.endsWith(".java")) {
                continue;
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (Problem problem : parser.parse(file).getProblems()) {
                    errors.add(relative + ": " + normalize(problem.getMessage()));
                }
            } catch (Exception e) {
                errors.add(relative + ": " + normalize(e.getMessage()));
            }
        }
        return errors.isEmpty()
                ? ValidationResult.success()
                : new ValidationResult(false, errors);
    }

    private static String normalize(String message) {
        if (message == null || message.isBlank()) {
            return "Java parse error";
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public String summary() {
            return String.join("\n", errors);
        }
    }
}
