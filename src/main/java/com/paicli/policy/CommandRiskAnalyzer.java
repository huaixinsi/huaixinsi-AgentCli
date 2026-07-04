package com.paicli.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Shell command lexer and risk analyzer.
 *
 * <p>The parser intentionally covers the bounded syntax needed for policy decisions rather than
 * implementing complete Bash, PowerShell, or cmd grammars.</p>
 */
public final class CommandRiskAnalyzer {

    private static final int MAX_SEGMENTS = 128;
    private static final int MAX_TOKENS = 1_024;

    public Analysis analyze(String command, ShellDialect dialect) {
        ShellDialect actualDialect = dialect == null ? ShellDialect.current() : dialect;
        String source = command == null ? "" : command;
        ParseResult parsed = new Parser(source, actualDialect).parse();
        return new Analysis(actualDialect, parsed.segments(), List.of(), parsed.risk());
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

    private record ParseResult(List<CommandSegment> segments, CommandRisk risk) {
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
                    if (currentChar == '"') {
                        quote = QuoteState.NONE;
                    } else {
                        append(currentChar);
                    }
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
            return new ParseResult(List.copyOf(segments), risk);
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
}
