package com.paicli.policy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds explicit file reads/writes in parsed shell commands for workspace and HITL policy.
 */
public final class CommandFileAccessAnalyzer {

    private static final CommandRiskAnalyzer ANALYZER = new CommandRiskAnalyzer();

    private static final Set<String> READ_COMMANDS = Set.of(
            "cat", "type", "more", "less", "head", "tail", "grep", "rg", "findstr",
            "get-content", "gc"
    );

    private static final Set<String> WRITE_COMMANDS = Set.of(
            "touch", "mkdir", "rmdir", "rm", "del", "erase", "tee",
            "set-content", "add-content", "out-file", "new-item", "remove-item"
    );

    private static final Set<String> COPY_COMMANDS = Set.of(
            "cp", "copy", "xcopy", "robocopy", "copy-item"
    );

    private static final Set<String> MOVE_COMMANDS = Set.of(
            "mv", "move", "move-item", "rename-item", "ren"
    );

    public ToolCallRisk analyze(String command, ShellDialect dialect, Path workspaceRoot) {
        CommandRiskAnalyzer.Analysis analysis = ANALYZER.analyze(command, dialect);
        if (analysis.risk() != null) {
            return ToolCallRisk.block(analysis.risk().reason());
        }

        AccessSummary summary = new AccessSummary();
        collect(analysis, workspaceRoot.toAbsolutePath().normalize(), summary);
        if (summary.blockReason != null) {
            return ToolCallRisk.block(summary.blockReason);
        }
        if (!summary.sensitivePaths.isEmpty()) {
            return ToolCallRisk.requireApproval(
                    "命令将读写敏感文件: " + preview(summary.sensitivePaths));
        }
        if (!summary.writePaths.isEmpty()) {
            return ToolCallRisk.requireApproval(
                    "命令将写入或删除工作区文件: " + preview(summary.writePaths));
        }
        return ToolCallRisk.allow();
    }

    private void collect(CommandRiskAnalyzer.Analysis analysis, Path root, AccessSummary summary) {
        for (CommandRiskAnalyzer.CommandSegment segment : analysis.segments()) {
            inspectRedirections(segment, root, summary);
            inspectCommandArguments(segment, root, summary);
            if (summary.blockReason != null) {
                return;
            }
        }
        for (CommandRiskAnalyzer.Analysis nested : analysis.nestedAnalyses()) {
            collect(nested, root, summary);
            if (summary.blockReason != null) {
                return;
            }
        }
    }

    private void inspectRedirections(
            CommandRiskAnalyzer.CommandSegment segment,
            Path root,
            AccessSummary summary) {
        for (CommandRiskAnalyzer.Redirection redirection : segment.redirections()) {
            AccessMode mode = redirection.operator().contains(">") ? AccessMode.WRITE : AccessMode.READ;
            recordPath(redirection.target(), mode, root, summary);
            if (summary.blockReason != null) {
                return;
            }
        }
    }

    private void inspectCommandArguments(
            CommandRiskAnalyzer.CommandSegment segment,
            Path root,
            AccessSummary summary) {
        String executable = normalizeExecutable(segment.executable());
        List<String> paths = candidatePathArguments(segment.arguments());
        if (paths.isEmpty()) {
            return;
        }

        if (READ_COMMANDS.contains(executable)) {
            paths.forEach(path -> recordPath(path, AccessMode.READ, root, summary));
        } else if (WRITE_COMMANDS.contains(executable)) {
            paths.forEach(path -> recordPath(path, AccessMode.WRITE, root, summary));
        } else if (COPY_COMMANDS.contains(executable)) {
            for (int i = 0; i < paths.size(); i++) {
                recordPath(paths.get(i), i == paths.size() - 1 ? AccessMode.WRITE : AccessMode.READ, root, summary);
            }
        } else if (MOVE_COMMANDS.contains(executable)) {
            for (String path : paths) {
                recordPath(path, AccessMode.WRITE, root, summary);
            }
        } else if (executable.equals("sed") && segment.arguments().stream().anyMatch(this::isInPlaceFlag)) {
            paths.forEach(path -> recordPath(path, AccessMode.WRITE, root, summary));
        }
    }

    private List<String> candidatePathArguments(List<String> arguments) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument == null || argument.isBlank()) {
                continue;
            }
            if (argument.startsWith("-")) {
                if (optionTakesValue(argument) && i + 1 < arguments.size()) {
                    i++;
                }
                continue;
            }
            result.add(argument);
        }
        return result;
    }

    private void recordPath(String raw, AccessMode mode, Path root, AccessSummary summary) {
        if (summary.blockReason != null || raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        if (value.equals("-") || value.startsWith("http://") || value.startsWith("https://")) {
            return;
        }
        if (hasShellExpansion(value)) {
            summary.blockReason = "命令文件路径无法证明位于当前工作区: " + value;
            return;
        }

        Path resolved;
        try {
            Path rawPath = Path.of(value);
            resolved = rawPath.isAbsolute() ? rawPath.normalize() : root.resolve(rawPath).normalize();
        } catch (Exception e) {
            summary.blockReason = "命令文件路径无法可靠解析: " + value;
            return;
        }

        if (!resolved.startsWith(root)) {
            summary.blockReason = "命令文件路径越界: " + value + " 不在当前工作区 " + root + " 内";
            return;
        }
        if (SensitivePathPolicy.isSensitive(resolved)) {
            summary.sensitivePaths.add(root.relativize(resolved).toString());
        }
        if (mode == AccessMode.WRITE) {
            summary.writePaths.add(root.relativize(resolved).toString());
        }
    }

    private boolean hasShellExpansion(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("~")
                || lower.startsWith("$")
                || lower.contains("${")
                || lower.contains("%userprofile%")
                || lower.contains("%home%")
                || lower.contains("*")
                || lower.contains("?")
                || lower.contains("[");
    }

    private boolean optionTakesValue(String option) {
        String lower = option.toLowerCase(Locale.ROOT);
        return Set.of(
                "-o", "--output", "--output-document", "-out", "-outfile",
                "-path", "--path", "-destination", "--destination", "-literalpath",
                "-filepath", "-file"
        ).contains(lower);
    }

    private boolean isInPlaceFlag(String argument) {
        return argument.equals("-i") || argument.startsWith("-i.") || argument.equals("--in-place");
    }

    private static String normalizeExecutable(String executable) {
        String normalized = executable == null ? "" : executable.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        for (String extension : List.of(".exe", ".cmd", ".bat")) {
            if (normalized.endsWith(extension)) {
                return normalized.substring(0, normalized.length() - extension.length());
            }
        }
        return normalized;
    }

    private static String preview(List<String> paths) {
        int limit = Math.min(paths.size(), 3);
        String text = String.join(", ", paths.subList(0, limit));
        if (paths.size() > limit) {
            text += " 等 " + paths.size() + " 个路径";
        }
        return text;
    }

    private enum AccessMode {
        READ,
        WRITE
    }

    private static final class AccessSummary {
        private final List<String> sensitivePaths = new ArrayList<>();
        private final List<String> writePaths = new ArrayList<>();
        private String blockReason;
    }
}
