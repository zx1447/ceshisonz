package com.example.essentialsx;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/** Resolves a Nezha identity without logging or persisting the server IP. */
final class NezhaIdentity {
    private static final List<String> IPV4_SERVICES = List.of(
            "https://api.ipify.org",
            "https://ipv4.icanhazip.com",
            "https://checkip.amazonaws.com");
    private static final int HTTP_TIMEOUT_MS = 3_000;
    private static final int MAX_RESPONSE_BYTES = 64;

    private NezhaIdentity() {}

    static String resolve(String configuredUuid, String configuredIp) throws IOException {
        return resolve(configuredUuid, configuredIp, IPV4_SERVICES);
    }

    // The endpoint list can be replaced by loopback-only fixtures in CI.
    static String resolve(String configuredUuid, String configuredIp, List<String> endpoints) throws IOException {
        if (configuredUuid != null && !configuredUuid.isBlank()) {
            return canonicalUuid(configuredUuid.trim());
        }
        if (configuredIp != null && !configuredIp.isBlank()) {
            String ip = canonicalIpv4(configuredIp.trim());
            if (ip == null || ip.equals("0.0.0.0")) throw new IdentityException();
            return uuidForIpv4(ip);
        }
        for (String endpoint : endpoints) {
            if (Thread.currentThread().isInterrupted()) throw new IdentityException();
            String ip = queryPublicIpv4(endpoint);
            if (ip != null) return uuidForIpv4(ip);
        }
        // Do not silently create a random identity when public-IP lookup fails.
        throw new IdentityException();
    }

    private static String canonicalUuid(String value) throws IdentityException {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new IdentityException();
        }
    }

    private static String uuidForIpv4(String ip) throws IOException {
        byte[] digest;
        try {
            // Matches mcchajianindex's generateIPBasedUUID for the same IPv4.
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(("nezha-agent-uuid:" + ip).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x40);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        return new UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    private static String queryPublicIpv4(String endpoint) {
        HttpURLConnection connection = null;
        try {
            URLConnection opened = URI.create(endpoint).toURL().openConnection();
            if (!(opened instanceof HttpURLConnection)) return null;
            connection = (HttpURLConnection) opened;
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/plain");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = connection.getInputStream()) {
                byte[] response = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.length > MAX_RESPONSE_BYTES) return null;
                String ip = canonicalIpv4(new String(response, StandardCharsets.US_ASCII).trim());
                return ip != null && isPublicIpv4(ip) ? ip : null;
            }
        } catch (IOException | RuntimeException error) {
            // Never include response contents, endpoints, addresses or credentials in logs.
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String canonicalIpv4(String text) {
        if (text == null || text.length() > 15) return null;
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return null;
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') return null;
                value = value * 10 + digit - '0';
            }
            if (value > 255) return null;
            if (canonical.length() > 0) canonical.append('.');
            canonical.append(value);
        }
        return canonical.toString();
    }

    private static boolean isPublicIpv4(String ip) {
        String[] parts = ip.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        int c = Integer.parseInt(parts[2]);
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false; // carrier-grade NAT
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)))) return false;
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
        return a != 203 || b != 0 || c != 113; // documentation network
    }

    static final class IdentityException extends IOException {
        IdentityException() {
            super("No valid Nezha IPv4 identity or configured UUID");
        }
    }
}
