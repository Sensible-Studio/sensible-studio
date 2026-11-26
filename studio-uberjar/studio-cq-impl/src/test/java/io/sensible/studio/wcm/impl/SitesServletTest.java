/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class SitesServletTest {

    private final SlingContext context = WcmTestSupport.newContext();
    private final SitesServlet servlet = new SitesServlet();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        context.resourceResolver().getResource("/content/wknd/jcr:content").adaptTo(ModifiableValueMap.class)
                .put("cq:conf", "/conf/wknd");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsChildrenWithTrail() {
        Map<String, Object> root = servlet.children(context.resourceResolver(), "/content");
        List<Map<String, Object>> kids = (List<Map<String, Object>>) root.get("children");
        assertEquals(List.of("/content/wknd"), kids.stream().map(k -> k.get("path")).collect(Collectors.toList()));
        assertEquals(Boolean.TRUE, kids.get(0).get("hasChildren"));
        assertEquals("page", kids.get(0).get("type"));

        Map<String, Object> en = servlet.children(context.resourceResolver(), EN);
        Map<String, Object> item = (Map<String, Object>) en.get("item");
        assertEquals("WKND Adventures and Travel", item.get("title"));
        assertEquals(WcmTestSupport.TEMPLATE, item.get("template"));
        List<Map<String, Object>> trail = (List<Map<String, Object>>) en.get("trail");
        assertEquals(List.of("/content", "/content/wknd", "/content/wknd/us", EN),
                trail.stream().map(t -> t.get("path")).collect(Collectors.toList()));
        assertEquals("Sites", trail.get(0).get("title"));
        assertNull(servlet.children(context.resourceResolver(), "/content/nope"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void templatesFollowConfAndTemplateRules() {
        List<Map<String, Object>> t = servlet.templates(context.resourceResolver(), EN);
        List<Object> paths = t.stream().map(m -> m.get("path")).collect(Collectors.toList());
        assertTrue(paths.contains(WcmTestSupport.TEMPLATE), paths.toString());
        assertFalse(paths.contains("/conf/wknd/settings/wcm/templates/restricted"), "allowedPaths excludes it");

        context.resourceResolver().getResource("/content/wknd/jcr:content").adaptTo(ModifiableValueMap.class)
                .put("cq:allowedTemplates", new String[] {"/conf/wknd/settings/wcm/templates/nothing-.*"});
        assertTrue(servlet.templates(context.resourceResolver(), EN).isEmpty());
    }

    @Test
    void readsInheritedProperties() {
        assertEquals("/conf/wknd", SitesServlet.inherited(context.resourceResolver().getResource(EN), "cq:conf"));
        assertTrue(SitesServlet.matchesAny(new String[] {"/conf/.*"}, "/conf/wknd/settings/wcm/templates/a"));
        assertFalse(SitesServlet.isListed(context.resourceResolver().getResource(EN + "/jcr:content")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchesPagesByTitleNameAndPath() {
        Map<String, Object> r = servlet.search(context.resourceResolver(), "/content", "adventures", null);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) r.get("hits");
        assertEquals(EN + "/adventures", hits.get(0).get("path"), "the exact title first: " + hits);
        assertEquals(EN, hits.get(1).get("path"), "then the title that contains it");
        assertEquals("WKND Adventures and Travel", hits.get(1).get("title"));

        hits = (List<Map<String, Object>>) servlet.search(context.resourceResolver(), "/content", "wknd", "1").get("hits");
        assertEquals(1, hits.size());
        assertEquals("/content/wknd", hits.get(0).get("path"), "the site itself first: the name matches, the path is shortest");

        assertTrue(((List<?>) servlet.search(context.resourceResolver(), "/content", "", null).get("hits")).isEmpty());
        assertTrue(((List<?>) servlet.search(context.resourceResolver(), "/content", "zzz", null).get("hits")).isEmpty());
        assertNull(servlet.search(context.resourceResolver(), "/content/nope", "x", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsThePagesWithChangesTheLiveSiteDoesNotHave() {
        java.util.Calendar earlier = java.util.Calendar.getInstance();
        earlier.add(java.util.Calendar.HOUR, -2);
        java.util.Calendar later = java.util.Calendar.getInstance();
        // adventures: published, then modified
        ModifiableValueMap adventures = context.resourceResolver().getResource(EN + "/adventures/jcr:content").adaptTo(ModifiableValueMap.class);
        adventures.put("cq:lastModified", later);
        adventures.put("cq:lastReplicated", earlier);
        adventures.put("cq:lastReplicationAction", "Activate");
        // en: published after its last change
        ModifiableValueMap en = context.resourceResolver().getResource(EN + "/jcr:content").adaptTo(ModifiableValueMap.class);
        en.put("cq:lastModified", earlier);
        en.put("cq:lastReplicated", later);
        en.put("cq:lastReplicationAction", "Activate");
        // the site root: never published, never modified by anyone — not a change
        context.resourceResolver().getResource("/content/wknd/jcr:content").adaptTo(ModifiableValueMap.class).remove("cq:lastModified");

        Map<String, Object> r = servlet.unpublished(context.resourceResolver(), "/content", null, null);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        List<String> paths = new java.util.ArrayList<>();
        for (Map<String, Object> i : items) {
            paths.add((String) i.get("path"));
        }
        assertTrue(paths.contains(EN + "/adventures"), "modified since published: " + paths);
        assertEquals("modified", items.get(paths.indexOf(EN + "/adventures")).get("publication"));
        assertTrue(!paths.contains(EN), "published after its change: " + paths);
        assertTrue(!paths.contains("/content/wknd"), "never touched: " + paths);
        assertEquals(items.size(), r.get("total"));
        assertEquals(Boolean.TRUE, r.get("complete"));
        assertEquals(1, ((List<?>) servlet.unpublished(context.resourceResolver(), "/content", "1", null).get("items")).size());
        List<Map<String, Object>> onlyModified = (List<Map<String, Object>>) servlet.unpublished(context.resourceResolver(), "/content", null, "modified").get("items");
        assertEquals(1, onlyModified.size());
        assertEquals(1, ((Map<String, Integer>) r.get("counts")).get("modified"));
        assertNull(servlet.unpublished(context.resourceResolver(), "/content/nope", null, null));
    }

    @Test
    void aMovedPageTakesItsPublishedPagesAlong() {
        context.create().resource("/content/zz", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/zz/jcr:content", "cq:lastReplicationAction", "Activate");
        context.create().resource("/content/zz/draft", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/zz/draft/jcr:content", "jcr:title", "Draft");
        context.create().resource("/content/zz/off", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/zz/off/jcr:content", "cq:lastReplicationAction", "Deactivate");
        context.create().resource("/content/zz/draft/live", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/zz/draft/live/jcr:content", "cq:lastReplicationAction", "Activate");
        java.util.List<String> live = SitesServlet.published(context.resourceResolver().getResource("/content/zz"), "/content/zz", "/content/site/yy");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("/content/site/yy", "/content/site/yy/draft/live"), live);
    }
}
