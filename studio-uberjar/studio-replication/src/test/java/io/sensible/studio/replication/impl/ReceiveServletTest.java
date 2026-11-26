/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.replication.ReplicationActionType;

/** The receiving servlet on a publish instance (here: the same Oak, a pack made under /author installed under /publish). */
@ExtendWith(SlingContextExtension.class)
class ReceiveServletTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        ResourceResolver rr = context.resourceResolver();
        session = rr.adaptTo(Session.class);
        Node en = session.getRootNode().addNode("author", "sling:Folder").addNode("site", "cq:Page").addNode("en", "cq:Page");
        en.addNode("jcr:content", "cq:PageContent").setProperty("jcr:title", "English");
        session.getRootNode().addNode("publish", "sling:Folder");
        session.save();
    }

    private byte[] packFor(String action) throws Exception {
        byte[] pack = ContentPack.pack(session, "/author/site/en", action, false);
        ContentPack.Unpacked u = ContentPack.unpack(new ByteArrayInputStream(pack));
        String json = Json.write(u.header).replace("\"/author", "\"/publish");
        // repack with the rewritten header
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(ContentPack.CONTENT_ENTRY));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void post(String action, String path, byte[] body) throws Exception {
        context.request().setMethod("POST");
        context.request().setHeader(HttpTransport.HEADER_ACTION, action);
        context.request().setHeader(HttpTransport.HEADER_PATH, path);
        context.request().setContent(body == null ? new byte[0] : body);
        new ReceiveServlet().service(context.request(), context.response());
    }

    @Test
    void activatesDeactivatesAndAnswersTests() throws Exception {
        post(ReplicationActionType.TEST.getName(), "/", null);
        assertEquals(200, context.response().getStatus());
        assertTrue(context.response().getOutputAsString().startsWith("OK"));

        post(ReplicationActionType.ACTIVATE.getName(), "/publish/site/en", packFor("Activate"));
        assertEquals(200, context.response().getStatus(), context.response().getOutputAsString());
        assertEquals("English", session.getNode("/publish/site/en/jcr:content").getProperty("jcr:title").getString());

        post(ReplicationActionType.DEACTIVATE.getName(), "/publish/site/en", null);
        assertEquals(200, context.response().getStatus());
        assertFalse(session.nodeExists("/publish/site/en"));

        post(ReplicationActionType.DEACTIVATE.getName(), "/publish", null);
        assertEquals(400, context.response().getStatus(), "a top-level folder is never removed");
    }

    private static com.day.cq.replication.Agent agent(String id, String type, boolean enabled, String uri) {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("enabled", enabled);
        props.put("serializationType", type);
        if (uri != null) {
            props.put("transportUri", uri);
        }
        return new AgentManagerImpl.AgentImpl(new AgentManagerImpl.ConfigImpl(id, "/etc/replication/agents.publish/" + id + "/jcr:content",
                new org.apache.sling.api.wrappers.ValueMapDecorator(props)), null);
    }

    @Test
    void whatArrivedIsPassedOnToTheFlushAndWebhookAgentsOnly() {
        java.util.List<com.day.cq.replication.Agent> agents = java.util.Arrays.asList(
                agent("live_cache", "flush", true, "http://127.0.0.1:4520/dispatcher/invalidate.cache"),
                agent("search", "webhook", true, "https://search.example/hook"),
                agent("flush_off", "flush", false, "http://127.0.0.1:4520/dispatcher/invalidate.cache"),
                agent("flush_nowhere", "flush", true, null),
                agent("next_publish", "studio", true, "http://other:4503/bin/receive"),
                agent("reverse", "reverse", true, "http://other:4503/bin/outbox"));
        java.util.List<java.util.Map<String, Object>> jobs = ReceiveServlet.jobsOnReceive(agents, "Activate", "/content/site/en", "studio-transport");
        assertEquals(2, jobs.size());
        assertEquals("live_cache", jobs.get(0).get(ReplicationJob.PROP_AGENT));
        assertEquals("Activate", jobs.get(0).get(ReplicationJob.PROP_ACTION));
        assertEquals("/content/site/en", jobs.get(0).get(ReplicationJob.PROP_PATH));
        assertEquals("studio-transport", jobs.get(0).get(ReplicationJob.PROP_USER));
        assertEquals("search", jobs.get(1).get(ReplicationJob.PROP_AGENT));
        assertTrue(ReceiveServlet.jobsOnReceive(java.util.Collections.emptyList(), "Delete", "/content/site/en", "x").isEmpty());
    }

    @Test
    void statusIsRecordedWhereAemKeepsIt() throws Exception {
        Status.write(session, "/author/site/en", ReplicationActionType.ACTIVATE, "admin");
        session.save();
        Node jc = session.getNode("/author/site/en/jcr:content");
        assertTrue(jc.isNodeType("cq:ReplicationStatus"));
        assertEquals("Activate", jc.getProperty("cq:lastReplicationAction").getString());
        Status s = Status.read(session, "/author/site/en", false);
        assertTrue(s.isActivated());
        assertTrue(s.isDelivered());
        jc.setProperty("cq:lastModified", java.util.Calendar.getInstance());
        Thread.sleep(5);
        jc.getProperty("cq:lastModified").setValue(java.util.Calendar.getInstance());
        session.save();
        assertFalse(Status.read(session, "/author/site/en", false).isDelivered(), "modified after it was published");
    }
}
