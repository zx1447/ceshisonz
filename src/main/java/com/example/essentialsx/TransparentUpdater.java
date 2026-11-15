package com.example.essentialsx;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicitly enabled updates are verified, backed up, and staged for restart. */
final class TransparentUpdater {
    private static final long MAX_JAR_BYTES = 64L * 1024L * 1024L;
    private TransparentUpdater() {}

    static void stage(JavaPlugin plugin, Map<String, String> env) throws IOException {
        if (!"true".equalsIgnoreCase(RuntimeConfig.value(env, "false", "PLUGIN_UPDATE_ENABLED"))) return;
        String url = RuntimeConfig.value(env, "", "PLUGIN_UPDATE_URL");
        String expected = RuntimeConfig.value(env, "", "PLUGIN_UPDATE_SHA256").toLowerCase();
        if (url.isBlank() || !VerifiedDownload.validSha256(expected)) {
            throw new IOException("PLUGIN_UPDATE_URL and PLUGIN_UPDATE_SHA256 are both required");
        }
        Logger log = plugin.getLogger();
        Path current = currentPluginJar(plugin);
        if (expected.equals(VerifiedDownload.sha256(current))) {
            log.info("Plugin update skipped: the installed JAR already has the requested SHA-256.");
            return;
        }
        Path updateDir = plugin.getServer().getUpdateFolderFile().toPath().toAbsolutePath().normalize();
        Files.createDirectories(updateDir);
        Path staged = updateDir.resolve(current.getFileName().toString());
        if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("A staged update already exists; it was left untouched");
        }
        Path temp = VerifiedDownload.download(url, expected, updateDir, MAX_JAR_BYTES);
        try {
            verifyPluginJar(temp);
            Path backupDirectory = Path.of("logs", ".mcchajian", ".runtime", "backup");
            Files.createDirectories(backupDirectory);
            String oldHash = VerifiedDownload.sha256(current);
            Path backup = backupDirectory.resolve(current.getFileName() + "." + oldHash.substring(0, 16) + ".bak");
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                        || !oldHash.equals(VerifiedDownload.sha256(backup))) {
                    backup = backupDirectory.resolve(current.getFileName() + "." + UUID.randomUUID() + ".bak");
                }
            }
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) Files.copy(current, backup);
            if (!oldHash.equals(VerifiedDownload.sha256(backup))) throw new IOException("Backup verification failed");
            try {
                // Same-directory hard link publishes the fully verified file without overwriting another update.
                Files.createLink(staged, temp);
            } catch (FileAlreadyExistsException error) {
                throw new IOException("Another update was staged concurrently; it was left untouched");
            }
            log.info("Verified plugin update staged in Bukkit's update folder; it applies on the next normal restart.");
            log.info("The running plugin JAR was not replaced; a verified backup was saved under .runtime/backup.");
        } finally {
            Files.deleteIfExists(temp); // only our newly created download
        }
    }

    private static Path currentPluginJar(JavaPlugin plugin) throws IOException {
        try {
            Path current = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            Path plugins = plugin.getDataFolder().getParentFile().toPath().toRealPath();
            if (Files.isSymbolicLink(current) || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)
                    || current.getParent() == null || !current.getParent().toRealPath().equals(plugins)
                    || !current.getFileName().toString().toLowerCase().endsWith(".jar")) {
                throw new IOException("The current plugin JAR could not be identified safely");
            }
            return current;
        } catch (URISyntaxException | IllegalArgumentException | SecurityException error) {
            throw new IOException("The current plugin JAR could not be identified safely");
        }
    }

    private static void verifyPluginJar(Path candidate) throws IOException {
        try (JarFile jar = new JarFile(candidate.toFile())) {
            JarEntry descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null || descriptor.getSize() > 64 * 1024
                    || jar.getJarEntry("com/example/essentialsx/EssentialsX.class") == null
                    || jar.getJarEntry("com/example/essentialsx/FakePlayerEngine.class") == null
                    || jar.getJarEntry("nezha-libraries.properties") == null) {
                throw new IOException("Update is not a compatible ceshisonz plugin JAR");
            }
            String text;
            try (InputStream in = jar.getInputStream(descriptor)) {
                text = new String(in.readNBytes(64 * 1024 + 1), StandardCharsets.UTF_8);
            }
            if (text.length() > 64 * 1024
                    || !text.matches("(?s).*\\bname:\\s*['\"]?EssentialsX['\"]?\\s*(?:\\r?\\n|$).*")
                    || !text.matches("(?s).*\\bmain:\\s*com\\.example\\.essentialsx\\.EssentialsX\\s*(?:\\r?\\n|$).*")) {
                throw new IOException("Update plugin descriptor does not match this plugin");
            }
        }
    }
}
