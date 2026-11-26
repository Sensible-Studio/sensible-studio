/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationException;

/**
 * Delivers to an agent's transport URI over HTTP: one POST per request, the content pack as the
 * body, the action and path in headers, basic authentication with the transport user.
 */
final class HttpTransport {

    static final String HEADER_ACTION = "X-Studio-Replication-Action";
    static final String HEADER_PATH = "X-Studio-Replication-Path";
    static final String CONTENT_TYPE = "application/vnd.studio.content-pack+zip";
    /** Agents of this serialization type flush a cache (a dispatcher) instead of delivering content. */
    static final String FLUSH = "flush";
    /** Agents of this serialization type notify a URL with a small JSON body (a CDN purge, a site rebuild, a search index). */
    static final String WEBHOOK = "webhook";
    static final String HEADER_CQ_ACTION = "CQ-Action";
    static final String HEADER_CQ_HANDLE = "CQ-Handle";
    static final String HEADER_CQ_PATH = "CQ-Path";

    private HttpTransport() {
    }

    /** @return the response body on success (2xx) */
    static String deliver(AgentConfig agent, String action, String path, byte[] pack) throws ReplicationException {
        String uri = agent.getTransportURI();
        if (uri == null || uri.isEmpty()) {
            throw new ReplicationException("Agent " + agent.getId() + " has no transport URI");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(uri).openConnection();
            TrustStore.apply(c);
            c.setConnectTimeout(10000);
            c.setReadTimeout(120000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            String user = agent.getTransportUser();
            if (user != null && !user.isEmpty()) {
                String pw = agent.getTransportPassword() == null ? "" : agent.getTransportPassword();
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8)));
            }
            boolean flush = FLUSH.equals(agent.getSerializationType());
            if (flush) {
                // a dispatcher-style invalidation: the headers say what changed, the body is empty
                c.setRequestProperty(HEADER_CQ_ACTION, action);
                c.setRequestProperty(HEADER_CQ_HANDLE, path);
                c.setRequestProperty(HEADER_CQ_PATH, path);
                c.setRequestProperty("Content-Length", "0");
                pack = null;
            }
            boolean webhook = WEBHOOK.equals(agent.getSerializationType());
            if (webhook) {
                // a notification: what happened to which path, as JSON; the receiver fetches what it needs
                pack = webhookBody(agent.getId(), action, path).getBytes(StandardCharsets.UTF_8);
            }
            c.setRequestProperty(HEADER_ACTION, action);
            c.setRequestProperty(HEADER_PATH, path);
            c.setRequestProperty("Content-Type", webhook ? "application/json; charset=utf-8" : pack == null ? "text/plain" : CONTENT_TYPE);
            byte[] body = pack == null ? new byte[0] : pack;
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(ContentPack.readAll(in), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new ReplicationException("HTTP " + status + " from " + uri + (text.isEmpty() ? "" : ": " + summary(text)));
            }
            return text;
        } catch (IOException e) {
            throw new ReplicationException("Cannot reach " + uri + ": " + e.getMessage(), e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** What a webhook agent sends: {@code {"event","action","path","agent","time"}}. */
    static String webhookBody(String agentId, String action, String path) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("event", "studio.replication");
        m.put("action", action);
        m.put("path", path);
        m.put("agent", agentId);
        m.put("time", java.time.Instant.now().toString());
        return Json.write(m);
    }

    /** Agents that send no content pack: a cache flush, a webhook. */
    static boolean sendsNoContent(String serializationType) {
        return FLUSH.equals(serializationType) || WEBHOOK.equals(serializationType);
    }

    private static String summary(String text) {
        String t = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
