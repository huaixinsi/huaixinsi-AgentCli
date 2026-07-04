package com.paicli.policy;

/**
 * Bounded structural fast-fail before {@code execute_command} starts a process.
 *
 * <p>This auxiliary defense understands the command structure needed by the policy rules, but it
 * is not a complete Shell parser or a sandbox. HITL approval and user judgment remain the primary
 * safety boundary.</p>
 */
public final class CommandGuard {

    private static final CommandRiskAnalyzer ANALYZER = new CommandRiskAnalyzer();

    private CommandGuard() {
    }

    /**
     * Checks a command with the dialect selected for the current runtime.
     *
     * @return {@code null} when allowed, otherwise a user-visible denial reason
     */
    public static String check(String command) {
        return check(command, ShellDialect.current());
    }

    /**
     * Checks a command with an explicit Shell dialect.
     *
     * @return {@code null} when allowed, otherwise a user-visible denial reason
     */
    public static String check(String command, ShellDialect dialect) {
        if (command == null || command.isBlank()) {
            return null;
        }
        CommandRiskAnalyzer.Analysis analysis = ANALYZER.analyze(command, dialect);
        return analysis.risk() == null ? null : analysis.risk().reason();
    }
}
