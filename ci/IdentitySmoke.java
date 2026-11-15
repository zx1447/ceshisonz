package com.example.essentialsx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** CI-only tests using documentation IPs and an isolated loopback HTTP fixture. */
public final class IdentitySmoke {
    private static final String SAMPLE_IP = "198.51.100.42";
    private static final String SAMPLE_UUID = "38ee6b9d-8ed7-4593-8fe0-1e362327b8ef";
    private static final String OTHER_UUID = "123e4567-e89b-12d3-a456-426614174000";

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void reject(String uuid, String ip, List<String> endpoints) throws IOException {
        try {
            NezhaIdentity.resolve(uuid, ip, endpoints);
            throw new AssertionError("Invalid or unavailable identity was accepted");
        } catch (NezhaIdentity.IdentityException expected) {
            // A bad explicit identity or failed lookup must not create a random UUID.
        }
    }

    private static void respond(HttpExchange exchange, int status, String body,
                                AtomicInteger requests, AtomicInteger leakedHeaders) throws IOException {
        requests.incrementAndGet();
        if (exchange.getRequestHeaders().getFirst("Authorization") != null
                || exchange.getRequestURI().getRawQuery() != null) leakedHeaders.incrementAndGet();
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> noEndpoints = List.of();
        String uuid = NezhaIdentity.resolve("", SAMPLE_IP, noEndpoints);
        check(SAMPLE_UUID.equals(uuid), "legacy index.js UUID vector mismatch");
        check(uuid.equals(NezhaIdentity.resolve("", SAMPLE_IP, noEndpoints)), "UUID is not stable");
        check(uuid.equals(NezhaIdentity.resolve("", "198.051.100.042", noEndpoints)), "IPv4 was not normalized");
        check(!uuid.equals(NezhaIdentity.resolve("", "198.51.100.43", noEndpoints)), "different IPs collide");
        check(OTHER_UUID.equals(NezhaIdentity.resolve(OTHER_UUID.toUpperCase(), "not-an-ip", noEndpoints)),
                "explicit UUID does not take precedence");
        reject("not-a-uuid", SAMPLE_IP, noEndpoints);
        for (String invalid : List.of("0.0.0.0", "999.0.0.1", "192.0.2.1:25565", "2001:db8::1", "a.b.c.d")) {
            reject("", invalid, noEndpoints);
        }
        reject("", "", noEndpoints);

        // Do not read, delete or overwrite the random UUID left by older versions.
        Path oldFile = Path.of("logs", ".mcchajian", ".runtime", "ceshisonz-nezha.uuid");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, OTHER_UUID + "\n", StandardCharsets.UTF_8);
        check(SAMPLE_UUID.equals(RuntimeConfig.stableUuid(Map.of("NEZHA_IDENTITY_IP", SAMPLE_IP))),
                "old random UUID was used instead of the IP-derived UUID");
        check((OTHER_UUID + "\n").equals(Files.readString(oldFile, StandardCharsets.UTF_8)),
                "old UUID file was changed");
        check(OTHER_UUID.equals(RuntimeConfig.stableUuid(Map.of("NZ_UUID", OTHER_UUID,
                        "NEZHA_IDENTITY_IP", SAMPLE_IP))), "configured NZ_UUID was not respected");

        AtomicInteger requests = new AtomicInteger();
        AtomicInteger leakedHeaders = new AtomicInteger();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/status", e -> respond(e, 503, "not available", requests, leakedHeaders));
        fixture.createContext("/invalid", e -> respond(e, 200, "999.1.2.3\n", requests, leakedHeaders));
        fixture.createContext("/private", e -> respond(e, 200, "10.1.2.3\n", requests, leakedHeaders));
        fixture.createContext("/long", e -> respond(e, 200, "8.8.8.8" + "x".repeat(100), requests, leakedHeaders));
        fixture.createContext("/public", e -> respond(e, 200, "8.8.8.8\n", requests, leakedHeaders));
        fixture.start();
        try {
            String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
            List<String> endpoints = List.of(base + "/status", base + "/invalid", base + "/private",
                    base + "/long", base + "/public");
            check("29c715f1-8fd3-498d-8dd6-fb317776754d".equals(NezhaIdentity.resolve("", "", endpoints)),
                    "public-IP lookup or UUID derivation failed");
            check(requests.get() == 5, "lookup did not reject bad sources or use fallback correctly");
            check(leakedHeaders.get() == 0, "lookup unexpectedly sent an authorization header or query");
            check(SAMPLE_UUID.equals(NezhaIdentity.resolve("", SAMPLE_IP, endpoints)), "explicit IP changed");
            check(requests.get() == 5, "explicit IP should not make network requests");
            reject("", "", List.of(base + "/status", base + "/private"));
        } finally {
            fixture.stop(0);
        }
        System.out.println("IP UUID vectors, legacy compatibility, overrides, no-random fallback and loopback discovery passed.");
    }
}
