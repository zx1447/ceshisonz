package com.example.essentialsx;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Fake players plus explicitly configured, in-process Nezha monitoring. */
public final class EssentialsX extends JavaPlugin {
    private final Object lifecycleLock = new Object();
    private volatile boolean stopping;
    private volatile Thread startupThread;
    private volatile Thread updateThread;
    private FakePlayerEngine fakePlayers;
    private NezhaRuntime activeNezha;

    @Override
    public void onEnable() {
        this.stopping = false;
        getLogger().info("Ceshisonz: built-in fake players and optional Nezha monitoring; no Node or CF deployment.");
        Map<String, String> env;
        try {
            env = RuntimeConfig.readEnvFile();
        } catch (Exception error) {
            getLogger().warning("Could not read the private .env file; default fake-player settings will be used.");
            env = new HashMap<>();
        }
        final Map<String, String> settings = env;
        try {
            FakePlayerEngine.preload();
            FakePlayerEngine engine = new FakePlayerEngine(this, settings);
            Bukkit.getPluginManager().registerEvents(engine, this);
            engine.start();
            this.fakePlayers = engine;
        } catch (Throwable error) {
            getLogger().warning("Built-in fake players could not start: " + error.getClass().getSimpleName());
        }
        startNezhaIfConfigured(settings);
        if ("true".equalsIgnoreCase(RuntimeConfig.value(settings, "false", "PLUGIN_UPDATE_ENABLED"))) {
            this.updateThread = new Thread(() -> {
                try {
                    TransparentUpdater.stage(this, settings);
                } catch (Throwable error) {
                    getLogger().warning("Plugin update was not staged: " + error.getClass().getSimpleName()
                            + ". Existing files were left in place.");
                }
            }, "ceshisonz-update-check");
            this.updateThread.setDaemon(true);
            this.updateThread.start();
        }
    }

    private void startNezhaIfConfigured(Map<String, String> env) {
        if ("false".equalsIgnoreCase(RuntimeConfig.value(env, "true", "NEZHA_ENABLED"))) {
            getLogger().info("Nezha monitoring is disabled by configuration.");
            return;
        }
        String server = RuntimeConfig.value(env, "", "NEZHA_SERVER", "NZ_SERVER");
        String secret = RuntimeConfig.value(env, "", "NEZHA_SECRET", "NEZHA_KEY", "NZ_CLIENT_SECRET");
        if (server.isBlank() || secret.isBlank()) {
            getLogger().info("Nezha monitoring is not configured; set NEZHA_SERVER and NEZHA_SECRET privately.");
            return;
        }
        if (RuntimeConfig.secretComesFromFile(env) && !RuntimeConfig.envFileIsPrivate()) {
            getLogger().warning("Nezha not started: protect logs/.mcchajian/.env from group/other access (mode 600).");
            return;
        }
        boolean tls = !"false".equalsIgnoreCase(RuntimeConfig.value(env, "true", "NEZHA_TLS", "NZ_TLS"));
        boolean allowCommands = "true".equalsIgnoreCase(RuntimeConfig.value(env, "false", "NEZHA_ALLOW_COMMANDS"));
        if (!tls) getLogger().warning("Nezha TLS is disabled; monitoring traffic may be exposed.");
        if (allowCommands) getLogger().warning("Nezha dashboard command execution is explicitly enabled.");
        this.startupThread = new Thread(() -> {
            NezhaRuntime candidate = new NezhaRuntime(getLogger());
            try {
                String uuid = RuntimeConfig.stableUuid(env);
                boolean started = candidate.start(server, secret, tls, uuid, allowCommands, () -> this.stopping);
                if (!started) return;
                synchronized (this.lifecycleLock) {
                    if (this.stopping) {
                        if (!candidate.stop()) getLogger().warning("Nezha did not stop within the expected time.");
                    } else {
                        this.activeNezha = candidate;
                    }
                }
            } catch (Throwable error) {
                // Exception messages may contain addresses or credentials; never print them here.
                getLogger().warning("Nezha startup failed: " + error.getClass().getSimpleName()
                        + ". Check platform compatibility and private configuration.");
            }
        }, "ceshisonz-nezha-start");
        this.startupThread.setDaemon(true);
        this.startupThread.start();
    }

    @Override
    public void onDisable() {
        NezhaRuntime runtime;
        synchronized (this.lifecycleLock) {
            this.stopping = true;
            runtime = this.activeNezha;
            this.activeNezha = null;
        }
        if (this.startupThread != null) this.startupThread.interrupt();
        if (this.updateThread != null) this.updateThread.interrupt();
        if (this.fakePlayers != null) {
            try {
                this.fakePlayers.stop();
            } catch (Throwable error) {
                getLogger().warning("Fake players did not stop cleanly.");
            }
        }
        if (runtime != null && !runtime.stop()) {
            getLogger().warning("Nezha did not stop within the expected time; restart the server to clear it.");
        }
        getLogger().info("Ceshisonz disabled; the native library remains loaded until JVM exit.");
    }
}
