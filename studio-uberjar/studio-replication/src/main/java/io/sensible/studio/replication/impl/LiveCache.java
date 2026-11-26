/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationException;

/**
 * The caches in front of the published site, for the Publishing console: what they hold, what they
 * were last told, and emptying them.
 *
 * <p>A cache is known by the flush target that tells it about publications: its state is at
 * {@code /_live/status} on the same host and it is emptied at {@code /_live/clear}, with the
 * target's credentials. Flush targets live on the publish instance, and an author cannot reach a
 * cache (nor should it): it asks each publish instance it delivers to, with that agent's
 * credentials, and the publish asks its caches. So the request goes the way content does.
 */
final class LiveCache {

    static final String STATUS = "/_live/status";
    static final String CLEAR = "/_live/clear";
    static final String EXPLAIN = "/_live/explain";
    static final String ENDPOINT = "/bin/studio/replication.json";

    private LiveCache() {
    }

    /**
     * @param relay also ask the publish instances this instance delivers to for theirs
     * @return one entry per cache: {@code id} (the flush target), {@code title}, {@code target},
     *         {@code enabled}, {@code ok}, {@code message} when not, the cache's own numbers when ok,
     *         and {@code via} / {@code viaTitle} when it was reported by a publish instance
     */
    static List<Map<String, Object>> list(Collection<Agent> agents, boolean relay) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agent a : agents) {
            AgentConfig c = a.getConfiguration();
            if (!a.isValid()) {
                continue;
            }
            if (HttpTransport.FLUSH.equals(c.getSerializationType())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("title", c.getName());
                m.put("target", origin(c.getTransportURI()));
                m.put("enabled", a.isEnabled());
                try {
                    Object status = Json.parse(request("GET", origin(c.getTransportURI()) + STATUS, c, null));
                    if (!(status instanceof Map)) {
                        throw new IOException("not a Live cache");
                    }
                    m.put("ok", true);
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) status).entrySet()) {
                        m.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
                    }
                } catch (IOException | RuntimeException e) {
                    m.put("ok", false);
                    m.put("message", e.getMessage());
                }
                out.add(m);
            } else if (relay && a.isEnabled() && isContent(c)) {
                try {
                    Object answer = Json.parse(request("GET", origin(c.getTransportURI()) + ENDPOINT + "?op=cache&local=true", c, null));
                    Object caches = answer instanceof Map ? ((Map<?, ?>) answer).get("caches") : null;
                    if (caches instanceof List) {
                        for (Object o : (List<?>) caches) {
                            if (o instanceof Map) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                                    m.put(String.valueOf(e.getKey()), e.getValue());
                                }
                                m.put("via", a.getId());
                                m.put("viaTitle", c.getName());
                                out.add(m);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // a publish that is down or older than this has no caches to report
                }
            }
        }
        return out;
    }

    /**
     * Empties one cache: {@code id} is the flush target, {@code via} the agent of the publish
     * instance that reported it (none: a flush target of this instance).
     *
     * @return the cache's answer
     */
    static String clear(Collection<Agent> agents, String id, String via) throws ReplicationException {
        boolean relayed = via != null && !via.isEmpty();
        for (Agent a : agents) {
            AgentConfig c = a.getConfiguration();
            if (!a.isValid() || !a.getId().equals(relayed ? via : id)) {
                continue;
            }
            try {
                if (!relayed && HttpTransport.FLUSH.equals(c.getSerializationType())) {
                    return request("POST", origin(c.getTransportURI()) + CLEAR, c, "").trim();
                }
                if (relayed && isContent(c)) {
                    String body = "op=clearCache&cache=" + URLEncoder.encode(id, "UTF-8");
                    Object answer = Json.parse(request("POST", origin(c.getTransportURI()) + ENDPOINT, c, body));
                    Map<?, ?> m = answer instanceof Map ? (Map<?, ?>) answer : new LinkedHashMap<>();
                    if (!Boolean.TRUE.equals(m.get("ok"))) {
                        throw new ReplicationException(String.valueOf(m.get("message")));
                    }
                    return String.valueOf(m.get("message"));
                }
            } catch (IOException | RuntimeException e) {
                throw new ReplicationException("Could not empty the cache: " + e.getMessage(), e);
            }
        }
        throw new ReplicationException("No such cache: " + id);
    }

    /**
     * The URL tester: what the cache {@code id} would do with a request from a visitor who is not
     * signed in ({@code /_live/explain}), asked the way {@link #clear} goes.
     *
     * @return the cache's explanation: {@code summary} in words, and the parts it went by
     */
    static Map<String, Object> explain(Collection<Agent> agents, String id, String via, String url, String method, String host)
            throws ReplicationException {
        boolean relayed = via != null && !via.isEmpty();
        StringBuilder query = new StringBuilder();
        try {
            query.append("url=").append(URLEncoder.encode(url, "UTF-8"));
            if (method != null && !method.isEmpty()) {
                query.append("&method=").append(URLEncoder.encode(method, "UTF-8"));
            }
            if (host != null && !host.isEmpty()) {
                query.append("&host=").append(URLEncoder.encode(host, "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        for (Agent a : agents) {
            AgentConfig c = a.getConfiguration();
            if (!a.isValid() || !a.getId().equals(relayed ? via : id)) {
                continue;
            }
            try {
                if (!relayed && HttpTransport.FLUSH.equals(c.getSerializationType())) {
                    return asMap(Json.parse(request("GET", origin(c.getTransportURI()) + EXPLAIN + "?" + query, c, null)));
                }
                if (relayed && isContent(c)) {
                    String ask = ENDPOINT + "?op=explainUrl&cache=" + URLEncoder.encode(id, "UTF-8") + "&" + query;
                    Map<String, Object> m = asMap(Json.parse(request("GET", origin(c.getTransportURI()) + ask, c, null)));
                    if (!Boolean.TRUE.equals(m.get("ok"))) {
                        throw new ReplicationException(String.valueOf(m.get("message")));
                    }
                    return asMap(m.get("explain"));
                }
            } catch (IOException | RuntimeException e) {
                throw new ReplicationException("The cache could not be asked: " + e.getMessage(), e);
            }
        }
        throw new ReplicationException("No such cache: " + id);
    }

    private static Map<String, Object> asMap(Object o) throws IOException {
        if (!(o instanceof Map)) {
            throw new IOException("not a Live cache's answer");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            m.put(String.valueOf(e.getKey()), e.getValue());
        }
        return m;
    }

    private static boolean isContent(AgentConfig c) {
        String type = c.getSerializationType();
        return !HttpTransport.sendsNoContent(type) && !ReverseReplication.REVERSE.equals(type);
    }

    /** {@code http://host:port} of a transport URI. */
    static String origin(String uri) {
        try {
            URL u = new URL(uri);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort());
        } catch (java.net.MalformedURLException e) {
            return uri;
        }
    }

    /** @param form the body of a POST ({@code application/x-www-form-urlencoded}), or {@code null} for a GET */
    private static String request(String method, String url, AgentConfig credentials, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        TrustStore.apply(c);
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            String user = credentials.getTransportUser();
            if (user != null && !user.isEmpty()) {
                String pw = credentials.getTransportPassword() == null ? "" : credentials.getTransportPassword();
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8)));
            }
            if (form != null) {
                byte[] body = form.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " from " + url);
            }
            try (InputStream in = c.getInputStream()) {
                return new String(ContentPack.readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
