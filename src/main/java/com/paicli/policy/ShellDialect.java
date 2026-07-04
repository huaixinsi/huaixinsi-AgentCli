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
