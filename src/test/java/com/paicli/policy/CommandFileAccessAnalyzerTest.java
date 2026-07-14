package com.paicli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFileAccessAnalyzerTest {

    private final CommandFileAccessAnalyzer analyzer = new CommandFileAccessAnalyzer();

    @Test
    void blocksKnownDangerousCommandsBeforeFileAccess(@TempDir Path root) {
        ToolCallRisk risk = analyzer.analyze("sudo whoami", ShellDialect.BASH, root);

        assertTrue(risk.blocked());
    }

    @Test
    void blocksExplicitWritesOutsideWorkspace(@TempDir Path root) {
        Path outside = root.getParent().resolve("outside.txt");

        ToolCallRisk risk = analyzer.analyze("echo hi > \"" + outside + "\"", ShellDialect.POWERSHELL, root);

        assertTrue(risk.blocked());
        assertTrue(risk.reason().contains("越界"));
    }

    @Test
    void requiresApprovalForWorkspaceWrites(@TempDir Path root) {
        ToolCallRisk risk = analyzer.analyze("echo hi > build.log", ShellDialect.BASH, root);

        assertFalse(risk.blocked());
        assertTrue(risk.requiresPerCallApproval());
        assertTrue(risk.reason().contains("写入"));
    }

    @Test
    void requiresApprovalForSensitiveReads(@TempDir Path root) {
        ToolCallRisk risk = analyzer.analyze("cat .env", ShellDialect.BASH, root);

        assertFalse(risk.blocked());
        assertTrue(risk.requiresPerCallApproval());
        assertTrue(risk.reason().contains("敏感文件"));
    }

    @Test
    void followsNestedShellCommands(@TempDir Path root) {
        ToolCallRisk risk = analyzer.analyze("bash -c \"cat .env\"", ShellDialect.BASH, root);

        assertFalse(risk.blocked());
        assertTrue(risk.requiresPerCallApproval());
    }

    @Test
    void allowsCommandsWithoutExplicitFileAccess(@TempDir Path root) {
        ToolCallRisk risk = analyzer.analyze("git status", ShellDialect.BASH, root);

        assertFalse(risk.blocked());
        assertFalse(risk.requiresPerCallApproval());
    }
}
