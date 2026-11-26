/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

/** Restore Version and Restore Tree on a real Oak repository, with Sensible Studio's page versions. */
@ExtendWith(SlingContextExtension.class)
class VersionRestoreTest {

    private static final String SITE = "/content/site";
    private static final String EN = SITE + "/en";
    private static final String NEWS = EN + "/news";
    private static final String STORY = NEWS + "/story";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource(SITE, "jcr:primaryType", "sling:Folder");
        page(EN, "English");
        page(NEWS, "News");
        page(STORY, "Story");
        context.create().resource(STORY + "/jcr:content/root", "jcr:primaryType", "nt:unstructured", "text", "first");
        resolver = context.resourceResolver();
        resolver.commit();
        // the PageManager of studio-cq-impl, as far as versions go
        context.registerAdapter(ResourceResolver.class, PageManager.class, (java.util.function.Function<ResourceResolver, PageManager>) this::pageManager);
    }

    private void page(String path, String title) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", title);
    }

    private PageManager pageManager(ResourceResolver r) {
        return OakPages.pageManager(r);
    }

    private static Page page(String path) {
        return OakPages.page(path);
    }

    private VersionRestore as(String user) {
        ResourceResolver r = new ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return user;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T adaptTo(Class<T> type) {
                return type == PageManager.class ? (T) pageManager(this) : super.adaptTo(type);
            }
        };
        return new VersionRestore(r);
    }

    private void set(String path, String name, Object value) throws Exception {
        resolver.getResource(path).adaptTo(ModifiableValueMap.class).put(name, value);
        resolver.commit();
    }

    private String title(String page) {
        return resolver.getResource(page + "/jcr:content").getValueMap().get("jcr:title", String.class);
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(60);
    }

    @Test
    void deletedPagesComeBackFromTheirVersions() throws Exception {
        VersionRestore admin = as("admin");
        assertEquals(2, admin.delete(List.of(NEWS)), "News and Story got a version");
        assertNull(resolver.getResource(NEWS));
        assertNotNull(resolver.getResource(VersionRestore.DELETED + STORY));

        List<Map<String, Object>> inside = admin.versionsInside(EN);
        assertEquals(List.of(NEWS, STORY), inside.stream().map(p -> p.get("path")).collect(Collectors.toList()));
        assertEquals(true, inside.get(1).get("deleted"));
        assertEquals("admin", inside.get(1).get("deletedBy"));
        @SuppressWarnings("unchecked")
        Map<String, Object> version = ((List<Map<String, Object>>) inside.get(1).get("versions")).get(0);
        assertEquals("Deleted by admin", version.get("comment"));

        // the child first: it waits for its parent, which comes first anyway
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put(STORY, String.valueOf(version.get("id")));
        @SuppressWarnings("unchecked")
        String newsVersion = String.valueOf(((List<Map<String, Object>>) inside.get(0).get("versions")).get(0).get("id"));
        choices.put(NEWS, newsVersion);
        List<Map<String, Object>> results = admin.restoreVersions(choices);
        assertEquals(List.of("recreated", "recreated"), results.stream().map(r -> r.get("status")).collect(Collectors.toList()));
        assertEquals("Story", title(STORY));
        assertEquals("first", resolver.getResource(STORY + "/jcr:content/root").getValueMap().get("text", String.class));
        assertEquals("cq:Page", resolver.getResource(STORY).getValueMap().get("jcr:primaryType", String.class));
        assertNull(resolver.getResource(VersionRestore.DELETED + STORY), "the record is gone");
        assertEquals(1, pageManager(resolver).getRevisions(STORY, null).size(), "the page keeps its versions");
        assertTrue(admin.versionsInside(EN).stream().noneMatch(p -> Boolean.TRUE.equals(p.get("deleted"))));
    }

    @Test
    void aChildCannotComeBackBeforeItsParent() throws Exception {
        VersionRestore admin = as("admin");
        admin.delete(List.of(NEWS));
        String id = String.valueOf(((List<?>) admin.versionsInside(EN).get(1).get("versions")).isEmpty() ? null
                : ((Map<?, ?>) ((List<?>) admin.versionsInside(EN).get(1).get("versions")).get(0)).get("id"));
        Map<String, Object> result = admin.restoreVersions(Map.of(STORY, id)).get(0);
        assertEquals("failed", result.get("status"));
        assertTrue(String.valueOf(result.get("error")).contains("Restore " + NEWS + " first"));
        assertNull(resolver.getResource(STORY));
    }

    @Test
    void lockedPagesAreNotDeleted() throws Exception {
        set(STORY + "/jcr:content", "cq:lockOwner", "editor");
        assertThrows(SecurityException.class, () -> as("someone").delete(List.of(NEWS)));
        assertNotNull(resolver.getResource(STORY));
        as("editor").delete(List.of(NEWS));
        assertNull(resolver.getResource(NEWS));
    }

    @Test
    void restoreTreeGoesBackToADate() throws Exception {
        PageManager pm = pageManager(resolver);
        pm.createRevision(page(EN), null, "one");
        pm.createRevision(page(STORY), null, "one");
        pause();
        long then = System.currentTimeMillis();
        pause();
        set(STORY + "/jcr:content", "jcr:title", "Story, edited");
        pm.createRevision(page(STORY), null, "two");
        page(EN + "/later", "Later");
        resolver.commit();

        VersionRestore admin = as("admin");
        Map<String, Object> tree = admin.treeAt(EN, then);
        assertEquals(EN, tree.get("path"));
        assertNotNull(tree.get("version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) tree.get("children");
        Map<String, Map<String, Object>> byName = children.stream().collect(Collectors.toMap(c -> String.valueOf(c.get("name")), c -> c));
        assertNull(byName.get("later").get("version"), "Later had no version then");
        assertNull(byName.get("news").get("version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> story = ((List<Map<String, Object>>) byName.get("news").get("children")).get(0);
        assertEquals("one", ((Map<?, ?>) story.get("version")).get("comment"));

        Map<String, Object> kept = admin.restoreTree(EN, then, true);
        assertEquals(2, kept.get("restored"));
        assertEquals(0, kept.get("removed"));
        assertEquals("Story", title(STORY));
        assertNotNull(resolver.getResource(EN + "/later"), "preserved");

        Map<String, Object> exact = admin.restoreTree(EN, then, false);
        assertEquals(1, exact.get("removed"));
        assertNull(resolver.getResource(EN + "/later"));
        assertNotNull(resolver.getResource(NEWS), "News has a version below it: it stays");
        assertTrue(admin.versionsInside(EN).stream().anyMatch(p -> (EN + "/later").equals(p.get("path")) && Boolean.TRUE.equals(p.get("deleted"))),
                "the removed page can be restored");
    }

    @Test
    void restoreTreeBringsBackPagesDeletedSince() throws Exception {
        pageManager(resolver).createRevision(page(STORY), null, "one");
        pause();
        long then = System.currentTimeMillis();
        pause();
        VersionRestore admin = as("admin");
        admin.delete(List.of(NEWS));

        Map<String, Object> tree = admin.treeAt(EN, then);
        @SuppressWarnings("unchecked")
        Map<String, Object> news = ((List<Map<String, Object>>) tree.get("children")).get(0);
        assertEquals(false, news.get("exists"));
        assertNull(news.get("version"), "News had no version then");
        assertEquals("Deleted by admin", ((Map<?, ?>) news.get("fallback")).get("comment"));

        Map<String, Object> result = admin.restoreTree(EN, then, true);
        // Story had a version then; News comes back with it, as it was deleted
        assertEquals(2, result.get("recreated"));
        assertTrue(((List<?>) result.get("failed")).isEmpty());
        assertEquals("News", title(NEWS));
        assertEquals("first", resolver.getResource(STORY + "/jcr:content/root").getValueMap().get("text", String.class));
    }

    @Test
    void revertOnePage() throws Exception {
        PageManager pm = pageManager(resolver);
        String first = String.valueOf(pm.createRevision(page(STORY), "v-one", null).getId());
        set(STORY + "/jcr:content", "jcr:title", "Changed");
        assertEquals("restored", as("admin").restoreOne(STORY, first));
        assertEquals("Story", title(STORY));
        assertThrows(IllegalArgumentException.class, () -> as("admin").restoreOne(STORY, ""));
        assertThrows(IllegalArgumentException.class, () -> as("admin").restoreOne(EN + "/missing", first));
        assertFalse(as("admin").versionsInside(NEWS).isEmpty());
    }

    @Test
    void parsesTimes() {
        assertEquals(0L, SiteRailServlet.time("1970-01-01T00:00:00Z"));
        assertEquals(3_600_000L, SiteRailServlet.time("1970-01-01T03:00+02:00"));
        assertEquals(60_000L, SiteRailServlet.time("1970-01-01T00:01"));
        assertEquals(1234L, SiteRailServlet.time("1234"));
        assertThrows(IllegalArgumentException.class, () -> SiteRailServlet.time("yesterday"));
        assertTrue(Math.abs(SiteRailServlet.time(null) - System.currentTimeMillis()) < 1000);
    }
}
