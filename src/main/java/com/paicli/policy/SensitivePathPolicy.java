package com.paicli.policy;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Static policy for files that may contain secrets or local credentials.
 */
public final class SensitivePathPolicy {

    private static final Set<String> EXACT_NAMES = Set.of(
            ".env",
            ".npmrc",
            ".pypirc",
            ".netrc",
            ".pgpass",
            "id_rsa",
            "id_dsa",
            "id_ecdsa",
            "id_ed25519",
            "credentials",
            "credentials.json",
            "settings.xml"
    );

    private static final Set<String> SENSITIVE_SEGMENTS = Set.of(
            ".ssh",
            ".aws",
            ".azure",
            ".gcp",
            ".kube",
            "secrets",
            "secret"
    );

    private SensitivePathPolicy() {
    }

    public static boolean isSensitive(Path path) {
        if (path == null) {
            return false;
        }
        for (Path segment : path) {
            String value = normalize(segment.toString());
            if (SENSITIVE_SEGMENTS.contains(value)) {
                return true;
            }
        }

        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = normalize(fileName.toString());
        return EXACT_NAMES.contains(name)
                || name.startsWith(".env.")
                || name.endsWith(".env")
                || name.endsWith(".pem")
                || name.endsWith(".key")
                || name.endsWith(".p12")
                || name.endsWith(".pfx")
                || name.endsWith(".keystore")
                || name.endsWith(".jks")
                || name.contains("secret")
                || name.contains("token")
                || name.contains("password")
                || name.contains("passwd")
                || name.contains("credential");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
