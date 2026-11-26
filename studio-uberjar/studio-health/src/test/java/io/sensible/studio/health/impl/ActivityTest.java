/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class ActivityTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private ResourceResolver rr;

    @BeforeEach
    void content() {
        rr = context.resourceResolver();
        Map<String, Object> page = new HashMap<>();
        page.put("jcr:primaryType", "cq:Page");
        context.create().resource("/content/wknd/en", page);
        Map<String, Object> content = new HashMap<>();
        content.put("jcr:title", "English");
        context.create().resource("/content/wknd/en/jcr:content", content);
        context.create().resource("/content/wknd/en/jcr:content/root/text", new HashMap<>());
    }

    @Test
    void aChangeInsideAPageIsThePageModifiedAndAFreshReplicationStampIsItsPublication() {
        long now = System.currentTimeMillis();
        Activity.Event e = Activity.classify(rr, "/content/wknd/en/jcr:content/root/text", ChangeType.CHANGED, "ana", now);
        assertEquals("/content/wknd/en", e.path);
        assertEquals(Activity.MODIFIED, e.type);

        ModifiableValueMap m = rr.getResource("/content/wknd/en/jcr:content").adaptTo(ModifiableValueMap.class);
        m.put("cq:lastReplicated", Calendar.getInstance());
        m.put("cq:lastReplicationAction", "Activate");
        m.put("cq:lastReplicatedBy", "ana");
        assertEquals(Activity.PUBLISHED, Activity.classify(rr, "/content/wknd/en/jcr:content", ChangeType.CHANGED, "ana", now).type);
        m.put("cq:lastReplicationAction", "Deactivate");
        assertEquals(Activity.UNPUBLISHED, Activity.classify(rr, "/content/wknd/en/jcr:content", ChangeType.CHANGED, "ana", now).type);
        // an old stamp: just a modification
        assertEquals(Activity.MODIFIED, Activity.classify(rr, "/content/wknd/en/jcr:content", ChangeType.CHANGED, "ana", now + 60000).type);
    }

    @Test
    void addedAndRemovedUnitsAreCreatedAndDeleted() {
        long now = System.currentTimeMillis();
        assertEquals(Activity.CREATED, Activity.classify(rr, "/content/wknd/en", ChangeType.ADDED, "ana", now).type);
        assertEquals(Activity.DELETED, Activity.classify(rr, "/content/wknd/gone", ChangeType.REMOVED, "ana", now).type);
        // a removal inside a page that is gone with it: the page's own removal is the event
        assertNull(Activity.classify(rr, "/content/wknd/gone/jcr:content/root", ChangeType.REMOVED, "ana", now));
        // a plain node added: nothing
        context.create().resource("/content/wknd/misc", new HashMap<>());
        assertNull(Activity.classify(rr, "/content/wknd/misc", ChangeType.ADDED, "ana", now));
    }

    @Test
    void peopleNotServicesAndNoBookkeeping() {
        assertTrue(Activity.isPerson("ana"));
        assertFalse(Activity.isPerson(null));
        assertFalse(Activity.isPerson("studio-replication-service"));
        assertFalse(Activity.isPerson("anonymous"));
        assertTrue(Activity.isNoise("/content/wknd/en/rep:policy/allow"));
        assertTrue(Activity.isNoise("/content/dam/a.png/jcr:content/renditions/original"));
        assertFalse(Activity.isNoise("/content/wknd/en/jcr:content/root"));
        assertEquals("media", Activity.kindOf("/content/dam/a.png"));
        assertEquals("block", Activity.kindOf("/content/experience-fragments/x"));
        assertEquals("page", Activity.kindOf("/content/wknd/en"));
    }

    @Test
    void writesQueriesAndPurges() throws Exception {
        Calendar old = Calendar.getInstance();
        old.add(Calendar.DAY_OF_MONTH, -100);
        Calendar earlier = Calendar.getInstance();
        earlier.add(Calendar.MINUTE, -5);
        Activity.write(rr, new Activity.Event("/content/wknd/en", Activity.CREATED, "ana"), old);
        Activity.write(rr, new Activity.Event("/content/wknd/en", Activity.MODIFIED, "ana"), earlier);
        Activity.write(rr, new Activity.Event("/content/dam/a.png", Activity.PUBLISHED, "bob"), Calendar.getInstance());
        rr.commit();

        List<Map<String, Object>> all = Activity.query(rr, null, null, null, 0, 10);
        assertEquals(3, all.size());
        assertEquals("published", all.get(0).get("type"));
        assertEquals("media", all.get(0).get("kind"));
        assertEquals(Boolean.FALSE, all.get(0).get("exists"));
        assertEquals("English", all.get(1).get("title"));
        assertEquals(2, Activity.query(rr, "ANA", null, null, 0, 10).size());
        assertEquals(1, Activity.query(rr, null, "modified", null, 0, 10).size());
        assertEquals(2, Activity.query(rr, null, null, "/content/wknd", 0, 10).size());
        assertEquals(1, Activity.query(rr, null, null, null, 0, 1).size());
        // paging: older than the newest
        assertEquals(2, Activity.query(rr, null, null, null, (Long) all.get(0).get("at"), 10).size());

        assertEquals(1, Activity.purge(rr, 90, Calendar.getInstance()));
        assertEquals(2, Activity.query(rr, null, null, null, 0, 10).size());
    }
}
