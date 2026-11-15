package com.example.essentialsx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class RuntimeConfig {
    static final Path ENV_FILE = Path.of("logs", ".mcchajian", ".env");
    private RuntimeConfig() {}

    static Map<String, String> readEnvFile() throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(ENV_FILE)) return values;
        for (String line : Files.readAllLines(ENV_FILE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("export ")) trimmed = trimmed.substring(7).trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) continue;
            String key = trimmed.substring(0, equals).trim();
            String value = trimmed.substring(equals + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    /** Process environment takes precedence over the private .env file. */
    static String value(Map<String, String> file, String fallback, String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        for (String name : names) {
            String value = file.get(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return fallback;
    }

    static boolean secretComesFromFile(Map<String, String> file) {
        for (String name : new String[]{"NEZHA_SECRET", "NEZHA_KEY", "NZ_CLIENT_SECRET"}) {
            String env = System.getenv(name);
            if (env != null && !env.isBlank()) return false;
        }
        for (String name : new String[]{"NEZHA_SECRET", "NEZHA_KEY", "NZ_CLIENT_SECRET"}) {
            String value = file.get(name);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    static boolean envFileIsPrivate() {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(ENV_FILE);
            return !permissions.contains(PosixFilePermission.GROUP_READ)
                    && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && !permissions.contains(PosixFilePermission.OTHERS_READ)
                    && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            return false;
        }
    }

    static String stableUuid(Map<String, String> file) throws IOException {
        String configuredUuid = value(file, "", "NEZHA_UUID", "NZ_UUID");
        String identityIp = value(file, "", "NEZHA_IDENTITY_IP", "NZ_IDENTITY_IP");
        // An existing random UUID file is deliberately neither read nor deleted.
        return NezhaIdentity.resolve(configuredUuid, identityIp);
    }
}
