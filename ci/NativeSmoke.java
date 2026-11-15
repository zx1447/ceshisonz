import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.NativeLibrary;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Runs only in GitHub Actions, against a deliberately closed loopback port. */
public final class NativeSmoke {
    public static void main(String[] args) throws Exception {
        NativeLibrary library = NativeLibrary.getInstance(args[0],
                Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name()));
        Function start = library.getFunction("StartNezha");
        Function stop = library.getFunction("StopNezha");
        int rc = start.invokeInt(new Object[]{"127.0.0.1:1", "ci-dummy-not-a-real-secret", 0,
                UUID.randomUUID().toString(), 0});
        if (rc != 0) throw new IllegalStateException("JNA start failed: " + rc);
        Thread.sleep(1000);
        rc = stop.invokeInt(new Object[]{});
        if (rc != 0) throw new IllegalStateException("JNA stop failed: " + rc);
        System.out.println("JNA start/stop passed; a real dashboard connection was not tested.");
        // Do not close/dlclose a Go c-shared library while the JVM is live.
    }
}
