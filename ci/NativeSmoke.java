package com.example.essentialsx;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.NativeLibrary;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** CI-only: use the packaged config and JNA against a loopback mock dashboard. */
public final class NativeSmoke {
    public static void main(String[] args) throws Exception {
        NativeLibrary library = NativeLibrary.getInstance(args[0],
                Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name()));
        Function start = library.getFunction("StartNezha");
        Function stop = library.getFunction("StopNezha");
        String uuid = RuntimeConfig.stableUuid(Map.of("NEZHA_IDENTITY_IP", "198.51.100.42"));
        if (!uuid.equals("38ee6b9d-8ed7-4593-8fe0-1e362327b8ef")) {
            throw new IllegalStateException("IP-derived UUID vector mismatch");
        }
        int rc = start.invokeInt(new Object[]{args[1], "ci-dummy-not-a-real-secret", 0, uuid, 0});
        if (rc != 0) throw new IllegalStateException("JNA start failed: " + rc);
        System.out.println("JNA_STARTED");
        System.out.flush();
        try {
            // The test coordinator signals only after the mock has received reports.
            System.in.read();
        } finally {
            rc = stop.invokeInt(new Object[]{});
            if (rc != 0) throw new IllegalStateException("JNA stop failed: " + rc);
        }
        System.out.println("JNA_STOPPED");
        // Do not close/dlclose a Go c-shared library while the JVM is live.
    }
}
