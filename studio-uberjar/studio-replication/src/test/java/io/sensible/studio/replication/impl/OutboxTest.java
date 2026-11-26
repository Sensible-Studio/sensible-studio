/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The publish side of reverse replication: what the outbox keeps, and how the author takes it. */
@ExtendWith(SlingContextExtension.class)
class OutboxTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver rr;

    @BeforeEach
    void content() throws Exception {
        rr = context.resourceResolver();
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "sling:Folder");
        Resource month = context.create().resource("/var/studio/forms/submissions/wknd/contact/2026/11", props);
        Map<String, Object> sub = new HashMap<>();
        sub.put("jcr:primaryType", "nt:unstructured");
        sub.put("sling:resourceType", "studio/forms/submission");
        sub.put("form", "/content/forms/wknd/contact");
        sub.put("name", "Ana");
        Resource s = context.create().resource(month, "abc", sub);
        Map<String, Object> data = new HashMap<>();
        data.put("jcr:primaryType", "nt:unstructured");
        data.put("message", "Hello");
        context.create().resource(s, "data", data);
        rr.commit();
    }

    @Test
    void theUnitOfAChangeIsTheSubmissionNotTheFieldNorTheMonth() {
        String sub = "/var/studio/forms/submissions/wknd/contact/2026/11/abc";
        assertEquals(sub, Outbox.unitOf(rr, sub + "/data"));
        assertEquals(sub, Outbox.unitOf(rr, sub));
        // a folder without a resource type below the root: itself
        assertEquals("/var/studio/forms/submissions/wknd/contact/2026/11", Outbox.unitOf(rr, "/var/studio/forms/submissions/wknd/contact/2026/11"));
        // outside the watched roots: nothing
        assertNull(Outbox.unitOf(rr, "/content/wknd"));
        assertNull(Outbox.rootOf("/var/studio/other"));
    }

    @Test
    void anEntryHoldsThePackAndALaterChangeReplacesIt() throws Exception {
        Outbox outbox = new Outbox();
        String sub = "/var/studio/forms/submissions/wknd/contact/2026/11/abc";
        outbox.put(rr, sub, Outbox.ACTION_ACTIVATE);
        rr.commit();
        List<Map<String, Object>> entries = Outbox.list(rr);
        assertEquals(1, entries.size());
        assertEquals(sub, entries.get(0).get("path"));
        assertEquals("Activate", entries.get(0).get("action"));
        String id = String.valueOf(entries.get(0).get("id"));
        Resource pack = rr.getResource(Outbox.ROOT + "/" + id + "/pack/jcr:content");
        assertNotNull(pack);

        // the pack installs the submission somewhere else (the author), data and all
        try (InputStream in = pack.adaptTo(InputStream.class)) {
            ContentPack.Unpacked u = ContentPack.unpack(in);
            Session session = rr.adaptTo(Session.class);
            session.getNode(sub).remove();
            session.save();
            ContentPack.install(session, u);
            session.save();
            assertEquals("Hello", session.getProperty(sub + "/data/message").getString());
        }

        // the same unit again: still one entry
        outbox.put(rr, sub, Outbox.ACTION_ACTIVATE);
        rr.commit();
        assertEquals(1, Outbox.list(rr).size());

        // acknowledged: gone
        assertTrue(Outbox.ack(rr, id));
        assertFalse(Outbox.ack(rr, id));
        assertTrue(Outbox.list(rr).isEmpty());
    }

    @Test
    void theFoldersMadeOnTheWayToASubmissionGetNoEntryOfTheirOwn() {
        String sub = "/var/studio/forms/submissions/wknd/contact/2026/11/abc";
        java.util.Set<String> units = new java.util.LinkedHashSet<>(List.of(
                "/var/studio/forms/submissions/wknd", "/var/studio/forms/submissions/wknd/contact/2026/11", sub));
        assertTrue(Outbox.isAncestorOfAnother(units, "/var/studio/forms/submissions/wknd"));
        assertTrue(Outbox.isAncestorOfAnother(units, "/var/studio/forms/submissions/wknd/contact/2026/11"));
        assertFalse(Outbox.isAncestorOfAnother(units, sub));
        // a sibling folder is not an ancestor
        assertFalse(Outbox.isAncestorOfAnother(units, "/var/studio/forms/submissions/wknd/contact/2026/10"));
    }

    @Test
    void aRemovalMakesADeleteEntryWithoutAPack() throws Exception {
        Outbox outbox = new Outbox();
        String sub = "/var/studio/forms/submissions/wknd/contact/2026/11/abc";
        outbox.put(rr, sub, Outbox.ACTION_DELETE);
        rr.commit();
        List<Map<String, Object>> entries = Outbox.list(rr);
        assertEquals("Delete", entries.get(0).get("action"));
        assertNull(rr.getResource(Outbox.ROOT + "/" + entries.get(0).get("id") + "/pack"));
        assertEquals(Outbox.entryName(sub), entries.get(0).get("id"));
        new ByteArrayInputStream(new byte[0]).close();
    }
}
