/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.replication.Agent;
import com.day.cq.replication.ReplicationActionType;

/** The preview tier: its agents, and a status of its own on the content. */
@ExtendWith(SlingContextExtension.class)
class TiersTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private static Agent agent(String id, boolean enabled, String tier, String transport, String publicUrl) {
        Map<String, Object> p = new HashMap<>();
        p.put("enabled", enabled);
        p.put("transportUri", transport);
        if (tier != null) {
            p.put(Tiers.PN_TIER, tier);
        }
        if (publicUrl != null) {
            p.put(Tiers.PN_PUBLIC_URL, publicUrl);
        }
        return new AgentManagerImpl.AgentImpl(new AgentManagerImpl.ConfigImpl(id, "/etc/replication/agents.author/" + id + "/jcr:content",
                new ValueMapDecorator(p)), null);
    }

    @Test
    void publishAndPreviewGoToTheirOwnAgents() {
        Agent publish = agent("publish", true, null, "http://localhost:4503/bin/receive", null);
        Agent preview = agent("preview", true, "preview", "http://localhost:4505/bin/receive", null);
        Agent off = agent("preview2", false, "preview", "http://localhost:4506/bin/receive", null);
        assertFalse(Tiers.isPreview(publish));
        assertTrue(Tiers.isPreview(preview));
        assertTrue(Tiers.filter(null).isIncluded(publish));
        assertFalse(Tiers.filter(null).isIncluded(preview));
        assertTrue(Tiers.filter("preview").isIncluded(preview));
        assertFalse(Tiers.filter("preview").isIncluded(publish));
        assertFalse(Tiers.filter("preview").isIncluded(off));
    }

    @Test
    void readsTheTierOfARequest() {
        assertNull(Tiers.of(null));
        assertNull(Tiers.of("publish"));
        assertEquals("preview", Tiers.of("preview"));
        assertThrows(IllegalArgumentException.class, () -> Tiers.of("live"));
        assertEquals("_preview", Tiers.suffix("preview"));
        assertEquals("", Tiers.suffix(null));
    }

    @Test
    void theTierIsSeenAtItsPublicAddress() {
        assertEquals("http://localhost:4505", Tiers.publicUrl(agent("preview", true, "preview", "http://localhost:4505/bin/receive", null)));
        assertEquals("https://preview.example.com", Tiers.publicUrl(agent("preview", true, "preview", "http://10.0.0.5:4503/bin/receive",
                "https://preview.example.com/")));
        assertNull(Tiers.publicUrl(agent("x", true, "preview", "not a url", null)));
    }

    @Test
    void previewingKeepsItsOwnStatus() throws Exception {
        context.create().resource("/content/site/en", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/site/en/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "En");
        Session session = context.resourceResolver().adaptTo(Session.class);
        Status.write(session, "/content/site/en", ReplicationActionType.ACTIVATE, "anna", Tiers.suffix("preview"));
        session.save();
        // previewed, never published
        assertNull(Status.read(session, "/content/site/en", false));
        Status p = Status.read(session, "/content/site/en", false, "_preview");
        assertTrue(p.isActivated());
        assertEquals("anna", p.getLastPublishedBy());
        Node n = session.getNode("/content/site/en/jcr:content");
        assertTrue(n.hasProperty("cq:lastReplicated_preview"));
        assertEquals("Activate", n.getProperty("cq:lastReplicationAction_preview").getString());

        // publishing does not touch the preview status
        Status.write(session, "/content/site/en", ReplicationActionType.DEACTIVATE, "bob");
        session.save();
        assertTrue(Status.read(session, "/content/site/en", false).isDeactivated());
        assertTrue(Status.read(session, "/content/site/en", false, "_preview").isActivated());
    }
}
