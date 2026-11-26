/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationException;
import com.sun.net.httpserver.HttpServer;

/** What the three kinds of agent that deliver put on the wire. */
class HttpTransportTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    private volatile int answer = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            Map<String, String> h = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            headers.set(h);
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "done".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AgentConfig agent(String type) {
        Map<String, Object> props = new HashMap<>();
        props.put(AgentConfig.TRANSPORT_URI, "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        props.put(AgentConfig.TRANSPORT_USER, "u");
        props.put(AgentConfig.TRANSPORT_PASSWORD, "p");
        props.put(AgentConfig.SERIALIZATION_TYPE, type);
        props.put(AgentConfig.AGENT_ENABLED, true);
        return new AgentManagerImpl.ConfigImpl("hook", "/etc/replication/agents.author/hook", new ValueMapDecorator(props));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWebhookAgentSendsWhatHappenedAsJson() throws Exception {
        assertEquals("done", HttpTransport.deliver(agent(HttpTransport.WEBHOOK), "Activate", "/content/wknd/en", new byte[] {1, 2, 3}));
        assertTrue(headers.get().get("content-type").startsWith("application/json"));
        assertTrue(headers.get().get("authorization").startsWith("Basic "));
        Map<String, Object> sent = (Map<String, Object>) Json.parse(body.get());
        assertEquals("studio.replication", sent.get("event"));
        assertEquals("Activate", sent.get("action"));
        assertEquals("/content/wknd/en", sent.get("path"));
        assertEquals("hook", sent.get("agent"));
        assertTrue(String.valueOf(sent.get("time")).endsWith("Z"));
    }

    @Test
    void aFlushAgentSendsHeadersAndNoBody() throws Exception {
        HttpTransport.deliver(agent(HttpTransport.FLUSH), "Deactivate", "/content/wknd/en", null);
        assertEquals("", body.get());
        assertEquals("Deactivate", headers.get().get("cq-action"));
        assertEquals("/content/wknd/en", headers.get().get("cq-handle"));
    }

    @Test
    void aContentAgentSendsThePackAndFailsOnAnError() throws Exception {
        HttpTransport.deliver(agent("studio"), "Activate", "/content/wknd/en", "pack".getBytes(StandardCharsets.UTF_8));
        assertEquals("pack", body.get());
        assertEquals(HttpTransport.CONTENT_TYPE, headers.get().get("content-type"));
        answer = 500;
        ReplicationException e = assertThrows(ReplicationException.class,
                () -> HttpTransport.deliver(agent("studio"), "Activate", "/content/wknd/en", new byte[0]));
        assertTrue(e.getMessage().contains("HTTP 500"));
        assertTrue(HttpTransport.sendsNoContent(HttpTransport.WEBHOOK));
        assertFalse(HttpTransport.sendsNoContent("studio"));
    }
}
