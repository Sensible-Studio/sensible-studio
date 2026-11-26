/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.PageManager;

/** Pages deleted outside the console: the Sling POST delete, the JCR API, and moved pages that must not count. */
@ExtendWith(SlingContextExtension.class)
class DeletedOutsideTest {

    private static final String SITE = "/content/site";
    private static final String EN = SITE + "/en";
    private static final String NEWS = EN + "/news";
    private static final String DRAFT = EN + "/draft";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource(SITE, "jcr:primaryType", "sling:Folder");
        for (String[] p : new String[][] {{EN, "English"}, {NEWS, "News"}, {DRAFT, "Draft"}}) {
            context.create().resource(p[0], "jcr:primaryType", "cq:Page");
            context.create().resource(p[0] + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", p[1]);
        }
        context.create().resource("/content/dam/site", "jcr:primaryType", "sling:Folder");
        resolver = context.resourceResolver();
        resolver.commit();
        context.registerAdapter(ResourceResolver.class, PageManager.class,
                (java.util.function.Function<ResourceResolver, PageManager>) OakPages::pageManager);
        session = resolver.adaptTo(Session.class);
        OakPages.pageManager(resolver).createRevision(OakPages.page(NEWS), "v1", "before");
    }

    private List<Object> deletedInside(VersionRestore rail) throws Exception {
        return rail.versionsInside(EN).stream().filter(p -> Boolean.TRUE.equals(p.get("deleted"))).map(p -> p.get("path"))
                .collect(Collectors.toList());
    }

    @Test
    void aPageDeletedThroughTheJcrApiIsFoundByItsVersions() throws Exception {
        session.getNode(NEWS).remove();
        session.getNode(DRAFT).remove(); // never versioned: nothing to find
        session.save();
        VersionRestore rail = new VersionRestore(resolver);
        assertTrue(deletedInside(rail).isEmpty(), "no record yet");

        assertEquals(1, rail.recordDeleted("/content"));
        assertEquals(0, rail.recordDeleted("/content"), "recorded once");
        assertEquals(List.of(NEWS), deletedInside(rail));
        Map<String, Object> news = rail.versionsInside(EN).get(0);
        assertEquals("News", news.get("title"));
        assertNull(news.get("deletedBy"), "who deleted it is not known");

        String id = String.valueOf(((Map<?, ?>) ((List<?>) news.get("versions")).get(0)).get("id"));
        assertEquals("recreated", rail.restoreOne(NEWS, id));
        assertEquals("News", resolver.getResource(NEWS + "/jcr:content").getValueMap().get("jcr:title", String.class));
    }

    @Test
    void theIndexQueryFindsWhatTheWalkFinds() throws Exception {
        session.getNode(NEWS).remove();
        session.save();
        String workspace = session.getWorkspace().getName();
        VersionHistories.recheckIndex();
        assertTrue(!VersionHistories.indexed(session, workspace), "no Lucene here: the walk is used");
        assertEquals(List.of(NEWS), new java.util.ArrayList<>(VersionHistories.deletedBelow(session, "/content").keySet()));

        // the query the index answers on a real instance gives the same history (here by traversal)
        java.util.Set<String> found = new java.util.HashSet<>();
        for (javax.jcr.query.RowIterator it = VersionHistories.query(session, workspace, EN + "/").getRows(); it.hasNext();) {
            found.add(it.nextRow().getNode().getProperty(workspace).getString());
        }
        assertTrue(found.contains(NEWS + "/jcr:content"), String.valueOf(found));
        assertTrue(VersionHistories.query(session, workspace, "/content/other/").getRows().getSize() <= 0
                || !VersionHistories.query(session, workspace, "/content/other/").getRows().hasNext());
        assertEquals("SELECT [jcr:path] FROM [nt:versionHistory] AS h WHERE ISDESCENDANTNODE(h, '/jcr:system/jcr:versionStorage')"
                + " AND h.[default] LIKE '/content/a\\_b\\%/o''k/%'", VersionHistories.sql("default", "/content/a_b%/o'k/"));
    }

    @Test
    void adminForgetsADeletedPageForGood() throws Exception {
        session.getNode(NEWS).remove();
        session.save();
        VersionRestore rail = new VersionRestore(resolver);
        rail.recordDeleted("/content");
        org.apache.sling.api.resource.ResourceResolver someone = new org.apache.sling.api.wrappers.ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return "someone";
            }
        };
        assertThrows(SecurityException.class, () -> new VersionRestore(someone).forgetDeleted(NEWS));
        assertThrows(IllegalArgumentException.class, () -> rail.forgetDeleted(DRAFT), "it exists");
        rail.forgetDeleted(NEWS);
        assertTrue(deletedInside(rail).isEmpty());
        assertNull(resolver.getResource(VersionRestore.DELETED + NEWS));
        assertNull(resolver.getResource(VersionRestore.DELETED + SITE), "nor the folders that held only it");
        assertEquals(0, rail.recordDeleted("/content"), "no versions left to find");
    }

    @Test
    void movedPagesAreNotDeletedPages() throws Exception {
        VersionRestore rail = new VersionRestore(resolver);
        rail.prepareDelete(List.of(NEWS)); // a version and a record, as before a delete …
        session.move(NEWS, EN + "/moved"); // … but the page was moved instead
        session.save();
        assertTrue(deletedInside(rail).isEmpty(), "the record of a moved page is not listed");
        assertEquals(0, rail.recordDeleted("/content"), "nor found in the version storage");

        String id = String.valueOf(OakPages.pageManager(resolver).getRevisions(EN + "/moved", null).iterator().next().getId());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> rail.restoreOne(NEWS, id));
        assertTrue(e.getMessage().contains("moved"), e.getMessage());
        assertNotNull(resolver.getResource(EN + "/moved/jcr:content"), "the moved page is untouched");
    }

    @Test
    void theSlingPostDeleteKeepsAVersionFirst() throws Exception {
        RestorableDeleteFilter filter = context.registerInjectActivateService(new RestorableDeleteFilter());
        MockSlingHttpServletRequest request = context.request();
        request.setMethod("POST");
        context.currentResource(resolver.getResource(DRAFT));
        request.setParameterMap(Map.of(":operation", "delete"));
        assertEquals(List.of(DRAFT), RestorableDeleteFilter.targets(request));

        filter.doFilter(request, context.response(), (req, res) -> {
            try {
                resolver.delete(resolver.getResource(DRAFT));
                resolver.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNull(resolver.getResource(DRAFT));
        assertEquals(List.of(DRAFT), deletedInside(new VersionRestore(resolver)), "the draft never had a version: now it has one");

        request.setParameterMap(Map.of(":operation", "delete", ":applyTo", new String[] {NEWS, "/content/dam/site", "*"}));
        assertEquals(List.of(NEWS), RestorableDeleteFilter.targets(request), "pages only: not the DAM, not wildcards");
        request.setParameterMap(Map.of(":operation", "copy"));
        filter.doFilter(request, context.response(), (req, res) -> { });
        assertNull(resolver.getResource(VersionRestore.DELETED + NEWS), "other operations are left alone");
    }

    @Test
    void lockedPagesStopTheSlingPostDelete() throws Exception {
        resolver.getResource(DRAFT + "/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("cq:lockOwner", "someone");
        resolver.commit();
        RestorableDeleteFilter filter = context.registerInjectActivateService(new RestorableDeleteFilter());
        MockSlingHttpServletRequest request = context.request();
        request.setMethod("POST");
        context.currentResource(resolver.getResource(DRAFT));
        request.setParameterMap(Map.of(":operation", "delete"));
        boolean[] ran = {false};
        filter.doFilter(request, context.response(), (req, res) -> ran[0] = true);
        assertEquals(false, ran[0]);
        assertEquals(403, context.response().getStatus());
        assertNotNull(resolver.getResource(DRAFT));
    }
}
