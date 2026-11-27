/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.AgentManager;

/**
 * What the live site counted, for the author's reports. Impressions and conversions are counted where visitors
 * are: on each publish instance, under its own {@code /var/studio/personalization/stats}. Nothing carries them
 * to the author, so the author <b>asks</b>: every enabled publishing target that delivers content to a publish
 * instance (not a cache, not a webhook, not a reverse target, not Preview) is asked for its counters with the
 * target's own transport user ({@code GET /bin/studio/targeting.json?op=allStats}), and the numbers are added
 * to the author's own. Answers are kept for {@link #TTL_MS}; an instance that does not answer is named in
 * {@link Snapshot#sources} and counts as nothing.
 */
@Component(service = LiveStats.class)
public class LiveStats {

    private static final Logger LOG = LoggerFactory.getLogger(LiveStats.class);
    static final long TTL_MS = 30_000L;
    static final int TIMEOUT_MS = 2500;
    static final String ENDPOINT = "/bin/studio/targeting.json";

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile AgentManager agents;

    private volatile Snapshot cached;

    /** The counters of every live instance, and which instances were asked. */
    public static final class Snapshot {
        /** target path → experience → {impressions, conversions, rate, days}, as {@link Stats#read}. */
        final Map<String, Map<String, Object>> byTarget = new LinkedHashMap<>();
        /** One per instance asked: {id, title, ok, error}. */
        final List<Map<String, Object>> sources = new ArrayList<>();
        final long at = System.currentTimeMillis();
    }

    /** What the live instances counted; from the last answers when they are fresh. */
    public Snapshot snapshot() {
        Snapshot s = cached;
        if (s != null && System.currentTimeMillis() - s.at < TTL_MS) {
            return s;
        }
        s = new Snapshot();
        for (AgentConfig c : targets()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", c.getId());
            source.put("title", c.getName() == null || c.getName().isEmpty() ? c.getId() : c.getName());
            try {
                Object parsed = Json.parse(call(c, "GET", "op=allStats"));
                Object list = parsed instanceof Map ? ((Map<?, ?>) parsed).get("stats") : null;
                if (!(list instanceof List)) {
                    throw new IOException("no counters in the answer");
                }
                for (Object o : (List<?>) list) {
                    if (o instanceof Map && ((Map<?, ?>) o).get("path") instanceof String && ((Map<?, ?>) o).get("experiences") instanceof Map) {
                        String path = (String) ((Map<?, ?>) o).get("path");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> exps = (Map<String, Object>) ((Map<?, ?>) o).get("experiences");
                        s.byTarget.put(path, merge(s.byTarget.get(path), exps));
                    }
                }
                source.put("ok", Boolean.TRUE);
            } catch (IOException | RuntimeException e) {
                source.put("ok", Boolean.FALSE);
                source.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                LOG.debug("Live counters of {}: {}", c.getId(), e.toString());
            }
            s.sources.add(source);
        }
        cached = s;
        return s;
    }

    /** The author's own counters of a target plus what the live instances counted. */
    public Map<String, Object> withLive(Map<String, Object> own, String targetPath) {
        return merge(own, snapshot().byTarget.get(targetPath));
    }

    public List<Map<String, Object>> sources() {
        return snapshot().sources;
    }

    /** Clears a target's counters on every live instance; the instances that could not be reached. */
    public List<String> reset(String targetPath) {
        List<String> failed = new ArrayList<>();
        for (AgentConfig c : targets()) {
            try {
                call(c, "POST", "op=resetStats&path=" + URLEncoder.encode(targetPath, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                failed.add(c.getName() == null || c.getName().isEmpty() ? c.getId() : c.getName());
                LOG.warn("Counters of {} not cleared on {}: {}", targetPath, c.getId(), e.toString());
            }
        }
        cached = null;
        return failed;
    }

    /** The targets that deliver content to a publish instance, one per instance. */
    List<AgentConfig> targets() {
        List<AgentConfig> out = new ArrayList<>();
        AgentManager m = agents;
        if (m == null) {
            return out;
        }
        List<String> seen = new ArrayList<>();
        for (Agent a : m.getAgents().values()) {
            AgentConfig c = a.getConfiguration();
            String base = c == null ? null : base(c.getTransportURI());
            if (c == null || !c.isEnabled() || base == null || !isContent(c.getSerializationType())
                    || "preview".equals(c.getProperties().get("tier", String.class)) || seen.contains(base)) {
                continue;
            }
            seen.add(base);
            out.add(c);
        }
        return out;
    }

    /** A target that carries content (the default kind), not a cache flush, a webhook or a reverse target. */
    static boolean isContent(String serializationType) {
        return serializationType == null || serializationType.isEmpty() || "studio".equals(serializationType)
                || "durbo".equals(serializationType);
    }

    /** {@code http://host:4503/bin/receive} → {@code http://host:4503}; null for what is not an http address. */
    static String base(String transportUri) {
        if (transportUri == null) {
            return null;
        }
        try {
            URI u = URI.create(transportUri.trim());
            if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                return null;
            }
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Adds counters up: experience → {impressions, conversions, days}. The rate is worked out again from the sums
     * and the days are merged by date. Neither argument is changed; either may be null.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> side : new Map[] {a, b}) {
            if (side == null) {
                continue;
            }
            for (Map.Entry<String, Object> e : side.entrySet()) {
                if (!(e.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> from = (Map<String, Object>) e.getValue();
                Map<String, Object> to = (Map<String, Object>) out.get(e.getKey());
                long i = num(from.get(Stats.IMPRESSIONS)) + (to == null ? 0 : num(to.get(Stats.IMPRESSIONS)));
                long c = num(from.get(Stats.CONVERSIONS)) + (to == null ? 0 : num(to.get(Stats.CONVERSIONS)));
                Map<String, long[]> days = new TreeMap<>();
                for (Object list : new Object[] {to == null ? null : to.get("days"), from.get("days")}) {
                    if (!(list instanceof List)) {
                        continue;
                    }
                    for (Object d : (List<?>) list) {
                        if (d instanceof Map && ((Map<?, ?>) d).get("day") != null) {
                            long[] n = days.computeIfAbsent(String.valueOf(((Map<?, ?>) d).get("day")), k -> new long[2]);
                            n[0] += num(((Map<?, ?>) d).get(Stats.IMPRESSIONS));
                            n[1] += num(((Map<?, ?>) d).get(Stats.CONVERSIONS));
                        }
                    }
                }
                List<Object> dayList = new ArrayList<>();
                for (Map.Entry<String, long[]> d : days.entrySet()) {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("day", d.getKey());
                    dm.put(Stats.IMPRESSIONS, d.getValue()[0]);
                    dm.put(Stats.CONVERSIONS, d.getValue()[1]);
                    dayList.add(dm);
                }
                Map<String, Object> sum = new LinkedHashMap<>();
                sum.put(Stats.IMPRESSIONS, i);
                sum.put(Stats.CONVERSIONS, c);
                sum.put("rate", i == 0 ? 0.0 : Math.round(10000.0 * c / i) / 100.0);
                sum.put("days", dayList);
                out.put(e.getKey(), sum);
            }
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static String call(AgentConfig c, String method, String query) throws IOException {
        String base = base(c.getTransportURI());
        HttpURLConnection con = (HttpURLConnection) URI.create(base + ENDPOINT + ("GET".equals(method) ? "?" + query : "")).toURL().openConnection();
        try {
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS * 2);
            con.setRequestMethod(method);
            con.setInstanceFollowRedirects(false);
            String user = c.getTransportUser();
            String pw = c.getTransportPassword();
            if (user != null && !user.isEmpty()) {
                con.setRequestProperty("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString((user + ":" + (pw == null ? "" : pw)).getBytes(StandardCharsets.UTF_8)));
            }
            if ("POST".equals(method)) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                con.getOutputStream().write(query.getBytes(StandardCharsets.UTF_8));
            }
            int status = con.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }
}
