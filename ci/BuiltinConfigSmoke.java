package com.example.essentialsx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** CI-only checks for obfuscated dummy defaults and explicit overrides. */
public final class BuiltinConfigSmoke {
    private static final String DUMMY_SERVER = "127.0.0.1:12345";
    private static final String DUMMY_SECRET = "ci-dummy-not-a-real-secret";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void incomplete(String server, String secret, byte[] data) throws IOException {
        try {
            EmbeddedNezhaConfig.select(server, secret, data);
            throw new AssertionError("Incomplete explicit configuration was accepted");
        } catch (EmbeddedNezhaConfig.IncompleteCredentialsException expected) {
            // Never mix one explicit value with the other built-in value.
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] dummy = Files.readAllBytes(Path.of(args[0]));
        String binaryAsText = new String(dummy, StandardCharsets.ISO_8859_1);
        check(!binaryAsText.contains(DUMMY_SERVER) && !binaryAsText.contains(DUMMY_SECRET),
                "Dummy settings were not obfuscated");
        EmbeddedNezhaConfig.Credentials builtin = EmbeddedNezhaConfig.select("", "");
        check(builtin != null && DUMMY_SERVER.equals(builtin.server) && DUMMY_SECRET.equals(builtin.secret),
                "Built-in resource did not decode");
        EmbeddedNezhaConfig.Credentials explicit = EmbeddedNezhaConfig.select("other.example:443", "different-secret", dummy);
        check("other.example:443".equals(explicit.server) && "different-secret".equals(explicit.secret),
                "Complete explicit settings do not take precedence");
        incomplete("other.example:443", "", dummy);
        incomplete("", "different-secret", dummy);
        check(EmbeddedNezhaConfig.select("", "", null) == null, "Missing built-in resource was not detected");
        byte[] bad = dummy.clone();
        bad[0] ^= 1;
        try {
            EmbeddedNezhaConfig.select("", "", bad);
            throw new AssertionError("Malformed built-in settings were accepted");
        } catch (IOException expected) {
            // A damaged resource must fail closed.
        }
        System.out.println("Dummy built-in config decode, complete overrides, incomplete settings and corruption checks passed.");
    }
}
