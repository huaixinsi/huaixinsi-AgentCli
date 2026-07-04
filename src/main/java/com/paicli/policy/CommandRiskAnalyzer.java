package com.paicli.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shell command lexer and risk analyzer.
 *
 * <p>The parser intentionally covers the bounded syntax needed for policy decisions rather than
 * implementing complete Bash, PowerShell, or cmd grammars.</p>
 */
public final class CommandRiskAnalyzer {

    private static final int MAX_SEGMENTS = 128;
    private static final int MAX_TOKENS = 1_024;
    private static final int MAX_RECURSION_DEPTH = 12;

    public Analysis analyze(String command, ShellDialect dialect) {
        ShellDialect actualDialect = dialect == null ? ShellDialect.current() : dialect;
        String source = command == null ? "" : command;
        return analyze(source, actualDialect, 0);
    }

    private Analysis analyze(String source, ShellDialect dialect, int depth) {
        if (depth > MAX_RECURSION_DEPTH) {
            return new Analysis(dialect, List.of(), List.of(), limitRisk());
        }

        ParseResult parsed = new Parser(source, dialect).parse();
        if (parsed.risk() != null) {
            return new Analysis(dialect, parsed.segments(), List.of(), parsed.risk());
        }

        List<Analysis> nestedAnalyses = new ArrayList<>();
        for (NestedCommand nestedCommand : parsed.nestedCommands()) {
            Analysis nested = analyze(nestedCommand.source(), nestedCommand.dialect(), depth + 1);
            nestedAnalyses.add(nested);
            if (nested.risk() != null) {
                return new Analysis(dialect, parsed.segments(), nestedAnalyses, nested.risk());
            }
        }

        for (CommandSegment segment : parsed.segments()) {
            NestedCommand nestedCommand = nestedShellCommand(segment);
            if (nestedCommand == null) {
                continue;
            }
            Analysis nested = analyze(nestedCommand.source(), nestedCommand.dialect(), depth + 1);
            nestedAnalyses.add(nested);
            if (nested.risk() != null) {
                return new Analysis(dialect, parsed.segments(), nestedAnalyses, nested.risk());
            }
        }
        return new Analysis(dialect, parsed.segments(), nestedAnalyses, null);
    }

    public enum Connector {
        SEQUENCE,
        AND,
        OR,
        PIPE,
        BACKGROUND,
        END
    }

    public record Redirection(String fileDescriptor, String operator, String target) {
    }

    public record CommandSegment(
            String executable,
            List<String> arguments,
            List<Redirection> redirections,
            Connector connectorToNext,
            int pipelineIndex) {

        public CommandSegment {
            arguments = List.copyOf(arguments);
            redirections = List.copyOf(redirections);
        }
    }

    public record CommandRisk(String code, String reason) {
    }

    public record Analysis(
            ShellDialect dialect,
            List<CommandSegment> segments,
            List<Analysis> nestedAnalyses,
            CommandRisk risk) {

        public Analysis {
            segments = List.copyOf(segments);
            nestedAnalyses = List.copyOf(nestedAnalyses);
        }

        public boolean denied() {
            return risk != null;
        }
    }

    private static CommandRisk parseRisk(String detail) {
        return new CommandRisk("PARSE_ERROR", "命令结构无法可靠解析：" + detail);
    }

    private static CommandRisk limitRisk() {
        return new CommandRisk("PARSE_LIMIT", "命令结构超过安全分析上限");
    }

    private static NestedCommand nestedShellCommand(CommandSegment segment) {
        String executable = launcherName(segment.executable());
        List<String> arguments = segment.arguments();
        for (int index = 0; index + 1 < arguments.size(); index++) {
            String option = arguments.get(index);
            if (isPosixShell(executable) && option.equals("-c")) {
                return new NestedCommand(arguments.get(index + 1), ShellDialect.BASH);
            }
            if ((executable.equals("powershell") || executable.equals("pwsh"))
                    && (option.equalsIgnoreCase("-command") || option.equalsIgnoreCase("-c"))) {
                return new NestedCommand(arguments.get(index + 1), ShellDialect.POWERSHELL);
            }
            if (executable.equals("cmd") && option.equalsIgnoreCase("/c")) {
                return new NestedCommand(arguments.get(index + 1), ShellDialect.CMD);
            }
        }
        return null;
    }

    private static boolean isPosixShell(String executable) {
        return executable.equals("sh")
                || executable.equals("bash")
                || executable.equals("zsh")
                || executable.equals("fish")
                || executable.equals("ksh");
    }

    private static String launcherName(String executable) {
        String normalized = executable.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".exe")) {
            return normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    private record ParseResult(
            List<CommandSegment> segments,
            List<NestedCommand> nestedCommands,
            CommandRisk risk) {
    }

    private record NestedCommand(String source, ShellDialect dialect) {
    }

    private enum QuoteState {
        NONE,
        SINGLE,
        DOUBLE
    }

    private static final class Parser {

        private final String source;
        private final ShellDialect dialect;
        private final List<CommandSegment> segments = new ArrayList<>();
        private final List<NestedCommand> nestedCommands = new ArrayList<>();
        private final SegmentBuilder current = new SegmentBuilder();
        private final StringBuilder word = new StringBuilder();

        private QuoteState quote = QuoteState.NONE;
        private PendingRedirection pendingRedirection;
        private boolean wordStarted;
        private boolean sawConnector;
        private int pipelineIndex;
        private int tokenCount;
        private CommandRisk risk;

        private Parser(String source, ShellDialect dialect) {
            this.source = source;
            this.dialect = dialect;
        }

        private ParseResult parse() {
            for (int index = 0; index < source.length() && risk == null; index++) {
                char currentChar = source.charAt(index);

                if (quote == QuoteState.SINGLE) {
                    if (currentChar == '\'' && dialect != ShellDialect.CMD) {
                        quote = QuoteState.NONE;
                    } else {
                        append(currentChar);
                    }
                    continue;
                }

                if (quote == QuoteState.DOUBLE) {
                    if (isEscapeCharacter(currentChar)) {
                        index = appendEscapedCharacter(index);
                    } else if (startsDollarSubstitution(index)) {
                        index = appendDollarSubstitution(index);
                    } else if (dialect == ShellDialect.BASH && currentChar == '`') {
                        index = appendBacktickSubstitution(index);
                    } else if (currentChar == '"') {
                        quote = QuoteState.NONE;
                    } else {
                        append(currentChar);
                    }
                    continue;
                }

                if (isEscapeCharacter(currentChar)) {
                    index = appendEscapedCharacter(index);
                    continue;
                }

                if (startsDollarSubstitution(index)) {
                    index = appendDollarSubstitution(index);
                    continue;
                }

                if (dialect == ShellDialect.BASH && currentChar == '`') {
                    index = appendBacktickSubstitution(index);
                    continue;
                }

                if (currentChar == '"' || (currentChar == '\'' && dialect != ShellDialect.CMD)) {
                    quote = currentChar == '"' ? QuoteState.DOUBLE : QuoteState.SINGLE;
                    wordStarted = true;
                    continue;
                }

                if (Character.isWhitespace(currentChar)) {
                    finishWord();
                    if (currentChar == '\r' || currentChar == '\n') {
                        if (currentChar == '\r'
                                && index + 1 < source.length()
                                && source.charAt(index + 1) == '\n') {
                            index++;
                        }
                        finishSegment(Connector.SEQUENCE);
                    }
                    continue;
                }

                if (currentChar == ';') {
                    finishWord();
                    finishSegment(Connector.SEQUENCE);
                    continue;
                }

                if (currentChar == '&') {
                    if (index + 1 < source.length() && source.charAt(index + 1) == '&') {
                        finishWord();
                        finishSegment(Connector.AND);
                        index++;
                    } else if (dialect == ShellDialect.POWERSHELL) {
                        append(currentChar);
                    } else {
                        finishWord();
                        finishSegment(Connector.BACKGROUND);
                    }
                    continue;
                }

                if (currentChar == '|') {
                    finishWord();
                    if (index + 1 < source.length() && source.charAt(index + 1) == '|') {
                        finishSegment(Connector.OR);
                        index++;
                    } else {
                        finishSegment(Connector.PIPE);
                    }
                    continue;
                }

                if (currentChar == '<' || currentChar == '>') {
                    index = startRedirection(index);
                    continue;
                }

                append(currentChar);
            }

            if (risk != null) {
                return result();
            }
            if (quote != QuoteState.NONE) {
                risk = parseRisk("引号未闭合");
                return result();
            }

            finishWord();
            if (pendingRedirection != null) {
                risk = parseRisk("重定向缺少目标");
                return result();
            }
            if (!current.words.isEmpty()) {
                finishSegment(Connector.END);
            } else if (sawConnector) {
                risk = parseRisk("命令连接符后缺少命令");
            }
            return result();
        }

        private boolean isEscapeCharacter(char value) {
            return switch (dialect) {
                case BASH -> value == '\\';
                case POWERSHELL -> value == '`';
                case CMD -> value == '^';
            };
        }

        private int appendEscapedCharacter(int index) {
            if (index + 1 >= source.length()) {
                risk = parseRisk("转义符后缺少字符");
                return index;
            }
            append(source.charAt(index + 1));
            return index + 1;
        }

        private boolean startsDollarSubstitution(int index) {
            return dialect != ShellDialect.CMD
                    && source.charAt(index) == '$'
                    && index + 1 < source.length()
                    && source.charAt(index + 1) == '(';
        }

        private int appendDollarSubstitution(int index) {
            Extraction extraction = extractDollarSubstitution(index);
            if (extraction == null) {
                risk = parseRisk("命令替换未闭合");
                return source.length() - 1;
            }
            append(source.substring(index, extraction.endIndex() + 1));
            nestedCommands.add(new NestedCommand(extraction.content(), dialect));
            return extraction.endIndex();
        }

        private Extraction extractDollarSubstitution(int startIndex) {
            int depth = 1;
            QuoteState nestedQuote = QuoteState.NONE;
            for (int index = startIndex + 2; index < source.length(); index++) {
                char value = source.charAt(index);
                if (nestedQuote == QuoteState.SINGLE) {
                    if (value == '\'' && dialect != ShellDialect.CMD) {
                        nestedQuote = QuoteState.NONE;
                    }
                    continue;
                }
                if (nestedQuote == QuoteState.DOUBLE) {
                    if (isEscapeCharacter(value)) {
                        index++;
                    } else if (value == '"') {
                        nestedQuote = QuoteState.NONE;
                    }
                    continue;
                }
                if (isEscapeCharacter(value)) {
                    index++;
                    continue;
                }
                if (value == '"' || (value == '\'' && dialect != ShellDialect.CMD)) {
                    nestedQuote = value == '"' ? QuoteState.DOUBLE : QuoteState.SINGLE;
                    continue;
                }
                if (value == '(') {
                    depth++;
                } else if (value == ')' && --depth == 0) {
                    return new Extraction(
                            source.substring(startIndex + 2, index),
                            index);
                }
            }
            return null;
        }

        private int appendBacktickSubstitution(int index) {
            for (int endIndex = index + 1; endIndex < source.length(); endIndex++) {
                char value = source.charAt(endIndex);
                if (value == '\\' && endIndex + 1 < source.length()) {
                    endIndex++;
                    continue;
                }
                if (value == '`') {
                    append(source.substring(index, endIndex + 1));
                    nestedCommands.add(new NestedCommand(
                            source.substring(index + 1, endIndex),
                            ShellDialect.BASH));
                    return endIndex;
                }
            }
            risk = parseRisk("反引号命令替换未闭合");
            return source.length() - 1;
        }

        private int startRedirection(int index) {
            if (pendingRedirection != null) {
                risk = parseRisk("重定向缺少目标");
                return index;
            }

            String fileDescriptor = null;
            if (wordStarted && isDigits(word)) {
                fileDescriptor = word.toString();
                resetWord();
            } else {
                finishWord();
            }

            char marker = source.charAt(index);
            String operator = String.valueOf(marker);
            if (index + 1 < source.length() && source.charAt(index + 1) == marker) {
                operator += marker;
                index++;
            } else if (index + 1 < source.length() && source.charAt(index + 1) == '&') {
                operator += "&";
                index++;
            }
            pendingRedirection = new PendingRedirection(fileDescriptor, operator);
            return index;
        }

        private void append(char value) {
            word.append(value);
            wordStarted = true;
        }

        private void append(String value) {
            word.append(value);
            wordStarted = true;
        }

        private void finishWord() {
            if (!wordStarted || risk != null) {
                return;
            }
            String value = word.toString();
            resetWord();

            tokenCount++;
            if (tokenCount > MAX_TOKENS) {
                risk = limitRisk();
                return;
            }

            if (pendingRedirection != null) {
                current.redirections.add(new Redirection(
                        pendingRedirection.fileDescriptor(),
                        pendingRedirection.operator(),
                        value));
                pendingRedirection = null;
            } else {
                current.words.add(value);
            }
        }

        private void finishSegment(Connector connector) {
            if (risk != null) {
                return;
            }
            if (pendingRedirection != null) {
                risk = parseRisk("重定向缺少目标");
                return;
            }
            if (current.words.isEmpty()) {
                risk = parseRisk("命令连接符前缺少命令");
                return;
            }

            String executable = current.words.get(0);
            List<String> arguments = current.words.subList(1, current.words.size());
            segments.add(new CommandSegment(
                    executable,
                    arguments,
                    current.redirections,
                    connector,
                    pipelineIndex));
            if (segments.size() > MAX_SEGMENTS) {
                risk = limitRisk();
                return;
            }

            current.clear();
            sawConnector = connector != Connector.END;
            if (connector != Connector.PIPE) {
                pipelineIndex++;
            }
        }

        private ParseResult result() {
            return new ParseResult(
                    List.copyOf(segments),
                    List.copyOf(nestedCommands),
                    risk);
        }

        private void resetWord() {
            word.setLength(0);
            wordStarted = false;
        }

        private static boolean isDigits(StringBuilder value) {
            if (value.isEmpty()) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                if (!Character.isDigit(value.charAt(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class SegmentBuilder {

        private final List<String> words = new ArrayList<>();
        private final List<Redirection> redirections = new ArrayList<>();

        private void clear() {
            words.clear();
            redirections.clear();
        }
    }

    private record PendingRedirection(String fileDescriptor, String operator) {
    }

    private record Extraction(String content, int endIndex) {
    }
}
