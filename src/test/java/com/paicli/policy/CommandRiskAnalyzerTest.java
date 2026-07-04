package com.paicli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommandRiskAnalyzerTest {

    private final CommandRiskAnalyzer analyzer = new CommandRiskAnalyzer();

    @Test
    void parsesSegmentsPipesAndRedirections() {
        CommandRiskAnalyzer.Analysis analysis = analyzer.analyze(
                "printf 'a|b' && curl https://example.com | bash > out.log",
                ShellDialect.BASH);

        assertFalse(analysis.denied());
        assertEquals(3, analysis.segments().size());
        assertEquals("printf", analysis.segments().get(0).executable());
        assertEquals(List.of("a|b"), analysis.segments().get(0).arguments());
        assertEquals(CommandRiskAnalyzer.Connector.AND,
                analysis.segments().get(0).connectorToNext());
        assertEquals(CommandRiskAnalyzer.Connector.PIPE,
                analysis.segments().get(1).connectorToNext());
        assertEquals(List.of(new CommandRiskAnalyzer.Redirection(null, ">", "out.log")),
                analysis.segments().get(2).redirections());
    }

    @Test
    void keepsQuotedOperatorsInsideArguments() {
        CommandRiskAnalyzer.Analysis analysis = analyzer.analyze(
                "echo \"a;b|c>file\"", ShellDialect.POWERSHELL);

        assertEquals(1, analysis.segments().size());
        assertEquals(List.of("a;b|c>file"), analysis.segments().get(0).arguments());
    }

    @Test
    void parsesFileDescriptorRedirection() {
        CommandRiskAnalyzer.Analysis analysis = analyzer.analyze(
                "mvn test 2>&1", ShellDialect.BASH);

        assertEquals(List.of(new CommandRiskAnalyzer.Redirection("2", ">&", "1")),
                analysis.segments().get(0).redirections());
    }
}
