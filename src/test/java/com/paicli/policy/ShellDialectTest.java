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
