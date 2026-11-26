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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.day.cq.replication.Agent;
import com.day.cq.replication.ReplicationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A stand-in cache and a stand-in publish instance on local ports; the agents point at them. */
class LiveCacheTest {

    private HttpServer cache;
    private HttpServer publish;
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());

    private static void answer(HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    @BeforeEach
    void start() throws Exception {
        cache = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cache.createContext("/", x -> {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            asked.add("cache " + x.getRequestMethod() + " " + x.getRequestURI() + (auth == null ? "" : " auth"));
            if ("/_live/status".equals(x.getRequestURI().getPath())) {
                answer(x, 200, "{\"hits\":7,\"stored\":3,\"files\":{\"plain\":2,\"held\":1,\"stale\":0},\"id\":\"not the agent's\"}");
            } else if ("/_live/explain".equals(x.getRequestURI().getPath())) {
                answer(x, 200, "{\"summary\":\"Allowed by filter rule 1 of 13.\",\"allowed\":true,\"asked\":\"" + x.getRequestURI().getRawQuery() + "\"}");
            } else if ("/_live/clear".equals(x.getRequestURI().getPath()) && "POST".equals(x.getRequestMethod())) {
                answer(x, 200, "OK, 3 removed\n");
            } else {
                answer(x, 404, "no");
            }
        });
        cache.start();
        publish = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        publish.createContext("/bin/studio/replication.json", x -> {
            String body = new String(ContentPack.readAll(x.getRequestBody()), StandardCharsets.UTF_8);
            asked.add("publish " + x.getRequestMethod() + " " + x.getRequestURI() + (body.isEmpty() ? "" : " " + body));
            String q = x.getRequestURI().getRawQuery();
            if ("GET".equals(x.getRequestMethod()) && q != null && q.startsWith("op=explainUrl")) {
                answer(x, 200, q.contains("cache=live_cache&")
                        ? "{\"ok\":true,\"explain\":{\"summary\":\"Refused with 404 by the built-in list.\",\"allowed\":false}}"
                        : "{\"ok\":false,\"message\":\"No such cache: other\"}");
            } else if ("GET".equals(x.getRequestMethod())) {
                answer(x, 200, "{\"caches\":[{\"id\":\"live_cache\",\"title\":\"Live cache\",\"ok\":true,\"hits\":1}],\"canClear\":true}");
            } else if (body.contains("cache=live_cache")) {
                answer(x, 200, "{\"ok\":true,\"message\":\"OK, 5 removed\"}");
            } else {
                answer(x, 200, "{\"ok\":false,\"message\":\"No such cache: other\"}");
            }
        });
        publish.start();
    }

    @AfterEach
    void stop() {
        cache.stop(0);
        publish.stop(0);
    }

    private static Agent agent(String id, String type, boolean enabled, String uri, String user) {
        Map<String, Object> props = new HashMap<>();
        props.put("enabled", enabled);
        props.put("serializationType", type);
        props.put("jcr:title", "The " + id);
        props.put("transportUri", uri);
        if (user != null) {
            props.put("transportUser", user);
            props.put("transportPassword", "secret");
        }
        return new AgentManagerImpl.AgentImpl(new AgentManagerImpl.ConfigImpl(id, "/etc/replication/agents/" + id + "/jcr:content",
                new org.apache.sling.api.wrappers.ValueMapDecorator(props)), null);
    }

    private String cacheUri() {
        return "http://127.0.0.1:" + cache.getAddress().getPort() + "/dispatcher/invalidate.cache";
    }

    private String publishUri() {
        return "http://127.0.0.1:" + publish.getAddress().getPort() + "/bin/receive";
    }

    @Test
    void aPublishReportsTheCachesBehindItsFlushTargets() {
        List<Agent> agents = Arrays.asList(
                agent("live_cache", "flush", true, cacheUri(), "flush"),
                agent("not_a_cache", "flush", true, "http://127.0.0.1:" + publish.getAddress().getPort() + "/purge", null),
                agent("hook", "webhook", true, cacheUri(), null));
        List<Map<String, Object>> caches = LiveCache.list(agents, false);
        assertEquals(2, caches.size());
        Map<String, Object> c = caches.get(0);
        assertEquals("live_cache", c.get("id"), "the cache's own answer does not replace who it is here");
        assertEquals("The live_cache", c.get("title"));
        assertEquals("http://127.0.0.1:" + cache.getAddress().getPort(), c.get("target"));
        assertEquals(true, c.get("ok"));
        assertEquals(7, ((Number) c.get("hits")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) c.get("files")).get("plain")).intValue());
        assertTrue(asked.contains("cache GET /_live/status auth"), asked.toString());
        // a flush target that is something else says so instead of numbers
        assertEquals(false, caches.get(1).get("ok"));
        assertTrue(String.valueOf(caches.get(1).get("message")).contains("404"));
    }

    @Test
    void anAuthorAsksThePublishInstancesItDeliversTo() {
        List<Agent> agents = Arrays.asList(
                agent("publish", "studio", true, publishUri(), "studio-transport"),
                agent("publish_off", "studio", false, publishUri(), "studio-transport"),
                agent("publish_reverse", "reverse", true, publishUri().replace("/bin/receive", "/bin/outbox"), "studio-transport"));
        List<Map<String, Object>> caches = LiveCache.list(agents, true);
        assertEquals(1, caches.size());
        assertEquals("live_cache", caches.get(0).get("id"));
        assertEquals("publish", caches.get(0).get("via"));
        assertEquals("The publish", caches.get(0).get("viaTitle"));
        assertEquals(Collections.singletonList("publish GET /bin/studio/replication.json?op=cache&local=true"), asked);
        // a publish answers for itself only: it does not ask on
        asked.clear();
        assertTrue(LiveCache.list(agents, false).isEmpty());
        assertTrue(asked.isEmpty());
    }

    @Test
    void emptyingGoesTheSameWay() throws Exception {
        List<Agent> onPublish = Collections.singletonList(agent("live_cache", "flush", true, cacheUri(), "flush"));
        assertEquals("OK, 3 removed", LiveCache.clear(onPublish, "live_cache", null));
        assertTrue(asked.contains("cache POST /_live/clear auth"), asked.toString());

        List<Agent> onAuthor = Collections.singletonList(agent("publish", "studio", true, publishUri(), "studio-transport"));
        assertEquals("OK, 5 removed", LiveCache.clear(onAuthor, "live_cache", "publish"));
        assertTrue(asked.contains("publish POST /bin/studio/replication.json op=clearCache&cache=live_cache"), asked.toString());

        // what the publish refuses is an error here, and nothing is emptied by a name that is not a cache
        assertThrows(ReplicationException.class, () -> LiveCache.clear(onAuthor, "other", "publish"));
        assertThrows(ReplicationException.class, () -> LiveCache.clear(onPublish, "nobody", null));
        assertThrows(ReplicationException.class, () -> LiveCache.clear(onAuthor, "publish", null), "a publish agent is not a cache");
        assertFalse(asked.contains("cache POST /_live/clear"));
    }

    @Test
    void theUrlTesterGoesTheSameWay() throws Exception {
        List<Agent> onPublish = Collections.singletonList(agent("live_cache", "flush", true, cacheUri(), "flush"));
        Map<String, Object> e = LiveCache.explain(onPublish, "live_cache", null, "/us/en.html?a=1&b=2", "HEAD", "wknd.localhost");
        assertEquals("Allowed by filter rule 1 of 13.", e.get("summary"));
        // the visitor's URL arrives whole, its own query included, with the method and the host
        assertEquals("url=%2Fus%2Fen.html%3Fa%3D1%26b%3D2&method=HEAD&host=wknd.localhost", e.get("asked"));
        assertTrue(asked.contains("cache GET /_live/explain?url=%2Fus%2Fen.html%3Fa%3D1%26b%3D2&method=HEAD&host=wknd.localhost auth"), asked.toString());

        List<Agent> onAuthor = Collections.singletonList(agent("publish", "studio", true, publishUri(), "studio-transport"));
        Map<String, Object> relayed = LiveCache.explain(onAuthor, "live_cache", "publish", "/bin/receive", null, null);
        assertEquals(false, relayed.get("allowed"));
        assertTrue(asked.contains("publish GET /bin/studio/replication.json?op=explainUrl&cache=live_cache&url=%2Fbin%2Freceive"), asked.toString());

        assertThrows(ReplicationException.class, () -> LiveCache.explain(onAuthor, "other", "publish", "/x.html", null, null));
        assertThrows(ReplicationException.class, () -> LiveCache.explain(onPublish, "nobody", null, "/x.html", null, null));
    }

    @Test
    void theOriginOfATransportUri() {
        assertEquals("http://127.0.0.1:4520", LiveCache.origin("http://127.0.0.1:4520/dispatcher/invalidate.cache"));
        assertEquals("https://cache.example", LiveCache.origin("https://cache.example/dispatcher/invalidate.cache?x=1"));
    }
}
