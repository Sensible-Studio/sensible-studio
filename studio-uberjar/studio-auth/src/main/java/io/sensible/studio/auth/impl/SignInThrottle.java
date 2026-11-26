package io.sensible.studio.auth.impl;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Against guessing passwords: after {@code perUser} wrong passwords for one user name from one client
 * within the window, that pair is refused until the window has passed; after {@code perClient} wrong
 * passwords from one client, whatever the names, that client is. A refused attempt never reaches the
 * repository and looks like a wrong password. A right password clears the pair's count.
 *
 * <p>Kept in memory, per instance: a farm of n publish instances lets n times as many guesses through
 * the cache, which sends a visitor's attempts to them in turn. Counted per user and client, not per user
 * alone, so that nobody can lock a member out by guessing for her.</p>
 */
final class SignInThrottle {

    /** No more than this many pairs and clients are remembered; the oldest go first. */
    static final int MAX_KEYS = 20_000;

    private final int perUser;
    private final int perClient;
    private final long windowMs;
    private final Map<String, Deque<Long>> failures = new LinkedHashMap<String, Deque<Long>>(256, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Long>> eldest) {
            return size() > MAX_KEYS;
        }
    };

    SignInThrottle(int perUser, int perClient, long windowMs) {
        this.perUser = Math.max(1, perUser);
        this.perClient = Math.max(1, perClient);
        this.windowMs = Math.max(1000, windowMs);
    }

    /** May this client try a password for this user now? */
    synchronized boolean allowed(String user, String client, long now) {
        return count(pair(user, client), now) < perUser && count(client(client), now) < perClient;
    }

    /** A wrong password. */
    synchronized void failed(String user, String client, long now) {
        add(pair(user, client), now);
        add(client(client), now);
    }

    /** A right password: the pair starts again (the client's count stays, it may be guessing other names). */
    synchronized void succeeded(String user, String client) {
        failures.remove(pair(user, client));
    }

    private int count(String key, long now) {
        Deque<Long> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        while (!times.isEmpty() && times.peekFirst() <= now - windowMs) {
            times.pollFirst();
        }
        if (times.isEmpty()) {
            failures.remove(key);
            return 0;
        }
        return times.size();
    }

    private void add(String key, long now) {
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        times.addLast(now);
        int keep = Math.max(perUser, perClient);
        while (times.size() > keep) {
            times.pollFirst();
        }
    }

    private static String pair(String user, String client) {
        return "u\u0000" + (user == null ? "" : user.trim().toLowerCase(Locale.ROOT)) + "\u0000" + client;
    }

    private static String client(String client) {
        return "c\u0000" + client;
    }

    /**
     * Who is asking: the address the request came from or, when that is a proxy this instance trusts
     * (the cache, the web server in front), the nearest address before it in {@code X-Forwarded-For}
     * that is not one of them.
     */
    static String client(String remoteAddr, String forwardedFor, Collection<String> trustedProxies) {
        Set<String> trusted = new HashSet<>();
        for (String proxy : trustedProxies) {
            trusted.add(address(proxy));
        }
        String client = address(remoteAddr);
        if (!trusted.contains(client) || forwardedFor == null) {
            return client;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = address(hops[i]);
            if (hop.isEmpty()) {
                continue;
            }
            client = hop;
            if (!trusted.contains(hop)) {
                break;
            }
        }
        return client;
    }

    /** One spelling of an address: without brackets, lower case, the IPv6 loopback as {@code ::1}. */
    static String address(String text) {
        String a = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        return "0:0:0:0:0:0:0:1".equals(a) ? "::1" : a;
    }

    /** For the tests: how many keys are remembered. */
    synchronized int size() {
        for (Iterator<Map.Entry<String, Deque<Long>>> it = failures.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
        return failures.size();
    }
}
