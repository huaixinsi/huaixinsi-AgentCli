package com.paicli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void honorsDialectSpecificEscapes() {
        assertEquals(List.of("a;b"), analyzer.analyze(
                "echo a\\;b", ShellDialect.BASH).segments().get(0).arguments());
        assertEquals(List.of("a;b"), analyzer.analyze(
                "echo a`;b", ShellDialect.POWERSHELL).segments().get(0).arguments());
        assertEquals(List.of("a&b"), analyzer.analyze(
                "echo a^&b", ShellDialect.CMD).segments().get(0).arguments());
    }

    @Test
    void recursivelyParsesSubstitutions() {
        CommandRiskAnalyzer.Analysis bash = analyzer.analyze(
                "echo $(pwd) `whoami`", ShellDialect.BASH);
        CommandRiskAnalyzer.Analysis powershell = analyzer.analyze(
                "Write-Output $(Get-Location)", ShellDialect.POWERSHELL);

        assertEquals(2, bash.nestedAnalyses().size());
        assertEquals("pwd", bash.nestedAnalyses().get(0).segments().get(0).executable());
        assertEquals("whoami", bash.nestedAnalyses().get(1).segments().get(0).executable());
        assertEquals(1, powershell.nestedAnalyses().size());
        assertEquals("Get-Location",
                powershell.nestedAnalyses().get(0).segments().get(0).executable());
    }

    @Test
    void recursivelyParsesExplicitShellLaunchers() {
        assertEquals("rm", analyzer.analyze(
                "bash -c \"rm -rf /\"", ShellDialect.BASH)
                .nestedAnalyses().get(0).segments().get(0).executable());
        assertEquals("shutdown", analyzer.analyze(
                "powershell -Command \"shutdown /s\"", ShellDialect.POWERSHELL)
                .nestedAnalyses().get(0).segments().get(0).executable());
        assertEquals("shutdown", analyzer.analyze(
                "cmd /c \"shutdown /s\"", ShellDialect.CMD)
                .nestedAnalyses().get(0).segments().get(0).executable());
    }

    @Test
    void rejectsUnclosedSubstitutionAndRecursionOverflow() {
        CommandRiskAnalyzer.Analysis unclosed = analyzer.analyze(
                "echo $(pwd", ShellDialect.BASH);
        assertNotNull(unclosed.risk());
        assertEquals("PARSE_ERROR", unclosed.risk().code());

        String nested = "pwd";
        for (int i = 0; i < 14; i++) {
            nested = "$(" + nested + ")";
        }
        CommandRiskAnalyzer.Analysis overflow = analyzer.analyze(
                "echo " + nested, ShellDialect.BASH);
        assertNotNull(overflow.risk());
        assertEquals("PARSE_LIMIT", overflow.risk().code());
    }
}
