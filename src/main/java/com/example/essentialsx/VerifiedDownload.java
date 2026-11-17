package com.example.essentialsx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Download over HTTPS and accept only the operator/build-provided SHA-256. */
final class VerifiedDownload {
    private VerifiedDownload() {}

    static boolean validSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Returns a newly created temporary file; the caller owns its cleanup. */
    static Path download(String rawUrl, String expected, Path directory, long maxBytes)
            throws IOException {
        if (!validSha256(expected)) {
            throw new IOException("A valid SHA-256 is required");
        }
        URI current;
        try {
            current = URI.create(rawUrl);
        } catch (RuntimeException error) {
            throw new IOException("Invalid download URL");
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".ceshisonz-download-", ".part");
        boolean success = false;
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (!"https".equalsIgnoreCase(current.getScheme()) || current.getHost() == null
                        || current.getRawUserInfo() != null) {
                    throw new IOException("Only HTTPS URLs without embedded credentials are allowed");
                }
                HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "ceshisonz-plugin");
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Redirect has no location");
                        current = current.resolve(location);
                        continue;
                    }
                    if (status != 200) throw new IOException("Download HTTP status " + status);
                    if (connection.getContentLengthLong() > maxBytes) {
                        throw new IOException("Download exceeds size limit");
                    }
                    MessageDigest digest = sha256Digest();
                    long total = 0;
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("Download interrupted");
                            total += count;
                            if (total > maxBytes) throw new IOException("Download exceeds size limit");
                            digest.update(buffer, 0, count);
                            out.write(buffer, 0, count);
                        }
                    }
                    String actual = HexFormat.of().formatHex(digest.digest());
                    if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(actual))) {
                        throw new IOException("Downloaded SHA-256 does not match");
                    }
                    success = true;
                    return temporary;
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Too many download redirects");
        } finally {
            if (!success) Files.deleteIfExists(temporary);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
