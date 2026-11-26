/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.ArrayBasedBlob;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.PageManager;

/**
 * Pages deleted outside the console: found between two repository states (Oak node states), kept as they were, and
 * brought back by Restore Version — also when they never had a version.
 */
@ExtendWith(SlingContextExtension.class)
class KeptPagesTest {

    private static final String SITE = "/content/site";
    private static final String EN = SITE + "/en";
    private static final String NEWS = EN + "/news";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource(SITE, "jcr:primaryType", "sling:Folder");
        context.create().resource(EN, "jcr:primaryType", "cq:Page");
        context.create().resource(EN + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "English");
        resolver = context.resourceResolver();
        resolver.commit();
        session = resolver.adaptTo(Session.class);
        context.registerAdapter(ResourceResolver.class, PageManager.class,
                (java.util.function.Function<ResourceResolver, PageManager>) OakPages::pageManager);
    }

    // ------------------------------------------------------------------------------------------ the states

    /** /content/site/en with a news page (two components, ordered), a folder with a page, and an asset. */
    private static NodeBuilder repository() {
        NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        NodeBuilder site = folder(root.child("content").child("site"));
        NodeBuilder en = page(site.child("en"), "English");
        NodeBuilder news = page(en.child("news"), "News");
        NodeBuilder content = news.child("jcr:content");
        content.setProperty("jcr:mixinTypes", List.of("mix:versionable"), Type.NAMES);
        content.setProperty("jcr:uuid", "0b0e1d4a-0000-4000-8000-000000000001");
        content.setProperty("cq:tags", List.of("a:b", "a:c"), Type.STRINGS);
        content.setProperty("rank", 3L);
        content.setProperty("cq:lastModified", "2026-10-18T10:00:00.000Z", Type.DATE);
        NodeBuilder root2 = content.child("root");
        root2.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        for (String c : new String[] {"title", "text", "image"}) {
            root2.child(c).setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
            root2.child(c).setProperty("sling:resourceType", "site/components/" + c);
        }
        root2.child("text").setProperty("text", "<p>Hello</p>");
        root2.child("image").child("file").setProperty("jcr:primaryType", "nt:file", Type.NAME);
        NodeBuilder data = root2.child("image").child("file").child("jcr:content");
        data.setProperty("jcr:primaryType", "nt:resource", Type.NAME);
        data.setProperty("jcr:mimeType", "image/png");
        data.setProperty("jcr:data", new ArrayBasedBlob("old image".getBytes()));
        // orderable content: not alphabetical
        root2.setProperty(":childOrder", List.of("title", "text", "image"), Type.NAMES);
        page(news.child("story"), "Story");
        page(folder(en.child("archive")).child("y2020"), "2020");
        folder(root.child("content").child("dam")).child("a.jpg").setProperty("jcr:primaryType", "dam:Asset", Type.NAME);
        return root;
    }

    private static NodeBuilder folder(NodeBuilder b) {
        b.setProperty("jcr:primaryType", "sling:Folder", Type.NAME);
        return b;
    }

    private static NodeBuilder page(NodeBuilder b, String title) {
        b.setProperty("jcr:primaryType", "cq:Page", Type.NAME);
        NodeBuilder c = b.child("jcr:content");
        c.setProperty("jcr:primaryType", "cq:PageContent", Type.NAME);
        c.setProperty("jcr:title", title);
        return b;
    }

    private static NodeBuilder at(NodeBuilder root, String path) {
        NodeBuilder b = root;
        for (String s : path.substring(1).split("/")) {
            b = b.child(s);
        }
        return b;
    }

    private static List<String> found(NodeState before, NodeState after) {
        return DeletedPages.between(before, after).stream().map(d -> d.path).collect(Collectors.toList());
    }

    @Test
    void deletionsArePagesAndFoldersWithPagesNotEditsMovesOrRecordedOnes() {
        NodeState before = repository().getNodeState();

        NodeBuilder b = before.builder();
        at(b, NEWS).remove();
        assertEquals(List.of(NEWS), found(before, b.getNodeState()), "a page (with the page inside it)");

        b = before.builder();
        at(b, NEWS + "/jcr:content/root/text").remove();
        assertEquals(List.of(), found(before, b.getNodeState()), "a component taken out is an edit");

        b = before.builder();
        at(b, EN + "/archive").remove();
        assertEquals(List.of(EN + "/archive"), found(before, b.getNodeState()), "a folder holding pages");

        b = before.builder();
        at(b, "/content/dam/a.jpg").remove();
        assertEquals(List.of(), found(before, b.getNodeState()), "the DAM is not pages");

        b = before.builder();
        NodeState news = at(b, NEWS).getNodeState();
        at(b, NEWS).remove();
        at(b, EN).setChildNode("moved", news);
        assertEquals(List.of(), found(before, b.getNodeState()), "a move is not a deletion");

        // as Oak moves: the moved node carries where it came from
        b = before.builder();
        NodeBuilder moved = at(b, EN).setChildNode("renamed", news);
        moved.setProperty(":source-path", NEWS);
        at(b, NEWS).remove();
        assertEquals(List.of(), found(before, b.getNodeState()), "Oak's :source-path marks a move");

        b = before.builder();
        at(b, NEWS).remove();
        at(b, VersionRestore.DELETED + NEWS).setProperty("pagePath", NEWS);
        assertEquals(List.of(NEWS), found(before, b.getNodeState()), "the story below has no record yet");
        at(b, VersionRestore.DELETED + NEWS + "/story").setProperty("pagePath", NEWS + "/story");
        assertEquals(List.of(), found(before, b.getNodeState()), "recorded by the console: it has versions");
    }

    @Test
    void aKeptPageComesBackAsItWas() throws Exception {
        NodeState before = repository().getNodeState();
        Calendar when = Calendar.getInstance();
        String entry = KeptPages.keep(session, NEWS, DeletedPages.at(before, NEWS), "someone", when);
        assertTrue(entry.startsWith(KeptPages.ROOT + "/"), entry);

        Node copy = session.getNode(entry + "/node/jcr:content");
        assertEquals("News", copy.getProperty("jcr:title").getString());
        assertFalse(copy.isNodeType("mix:versionable"), "no history link where it is kept");
        assertEquals(2, copy.getProperty("cq:tags").getValues().length);
        assertEquals(3L, copy.getProperty("rank").getLong());
        assertEquals(javax.jcr.PropertyType.DATE, copy.getProperty("cq:lastModified").getType());
        assertEquals(List.of("title", "text", "image"), names(copy.getNode("root")), "the order of the components");

        VersionRestore rail = new VersionRestore(resolver);
        Map<String, Object> news = rail.versionsInside(EN).stream().filter(p -> NEWS.equals(p.get("path"))).findFirst().orElse(null);
        assertNotNull(news, String.valueOf(rail.versionsInside(EN)));
        assertEquals(true, news.get("deleted"));
        assertEquals("someone", news.get("deletedBy"));
        Map<?, ?> version = (Map<?, ?>) ((List<?>) news.get("versions")).get(0);
        assertEquals("As deleted", version.get("name"));
        assertTrue(rail.versionsInside(EN).stream().anyMatch(p -> (NEWS + "/story").equals(p.get("path"))), "the page below too");

        assertEquals("recreated", rail.restoreOne(NEWS, String.valueOf(version.get("id"))));
        Node back = session.getNode(NEWS + "/jcr:content");
        assertEquals("News", back.getProperty("jcr:title").getString());
        assertEquals("<p>Hello</p>", back.getProperty("root/text/text").getString());
        assertEquals(List.of("title", "text", "image"), names(back.getNode("root")));
        try (java.io.InputStream in = back.getNode("root/image/file/jcr:content").getProperty("jcr:data").getBinary().getStream()) {
            assertEquals("old image", new String(in.readAllBytes()));
        }
        assertFalse(session.nodeExists(NEWS + "/story"), "the page below comes back on its own");
        Map<String, Object> restored = rail.versionsInside(EN).stream().filter(p -> NEWS.equals(p.get("path"))).findFirst().get();
        assertEquals(false, restored.get("deleted"));
        assertEquals(VersionRestore.RESTORED_COMMENT, ((Map<?, ?>) ((List<?>) restored.get("versions")).get(0)).get("comment"),
                "the state it came back in is a version");
        assertTrue(rail.versionsInside(EN).stream().noneMatch(p -> NEWS.equals(p.get("path")) && Boolean.TRUE.equals(p.get("deleted"))));

        Map<?, ?> story = (Map<?, ?>) ((List<?>) rail.versionsInside(EN).stream().filter(p -> (NEWS + "/story").equals(p.get("path")))
                .findFirst().get().get("versions")).get(0);
        rail.restoreOne(NEWS + "/story", String.valueOf(story.get("id")));
        assertEquals("Story", session.getNode(NEWS + "/story/jcr:content").getProperty("jcr:title").getString());
        assertFalse(session.nodeExists(entry), "everything is back: the kept copy goes");
        assertFalse(session.getNode(KeptPages.ROOT).getNodes().hasNext(), "with its day folder");
    }

    @Test
    void restoreTreeTakesTheKeptCopyAsAVersionOfItsLastChange() throws Exception {
        // the news page was last changed at 10:00 (cq:lastModified) and deleted later
        KeptPages.keep(session, NEWS, DeletedPages.at(repository().getNodeState(), NEWS), null, Calendar.getInstance());
        VersionRestore rail = new VersionRestore(resolver);
        long changed = java.time.Instant.parse("2026-10-18T10:00:00Z").toEpochMilli();

        Map<String, Object> before = child(rail.treeAt(EN, changed - 60_000), NEWS);
        assertNull(before, "before its last change the kept state is not how it was");
        Map<String, Object> after = child(rail.treeAt(EN, changed + 60_000), NEWS);
        assertNotNull(after);
        assertEquals("As deleted", ((Map<?, ?>) after.get("version")).get("name"));
        assertEquals("News", after.get("title"));
        assertNull(((Map<?, ?>) after.get("version")).get(" time"), "no internal keys in the answer");

        Map<String, Object> result = rail.restoreTree(EN, changed + 60_000, true);
        assertEquals(1, result.get("recreated"), String.valueOf(result));
        assertEquals("News", session.getNode(NEWS + "/jcr:content").getProperty("jcr:title").getString());
    }

    @Test
    void aKeptPageWithVersionsKeepsItsHistory() throws Exception {
        // a real page with a version, then edited and deleted outside the console
        String hist = EN + "/hist";
        context.create().resource(hist, "jcr:primaryType", "cq:Page");
        context.create().resource(hist + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Versioned");
        resolver.commit();
        OakPages.pageManager(resolver).createRevision(OakPages.page(hist), "v1", "first");
        session.getNode(hist).remove();
        session.save();
        // how it was when it was deleted: edited since the version
        NodeBuilder b = EmptyNodeState.EMPTY_NODE.builder();
        NodeBuilder pageState = page(b.child("hist"), "Edited after the version");
        pageState.child("jcr:content").child("note").setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        KeptPages.keep(session, hist, b.getNodeState().getChildNode("hist"), "someone", Calendar.getInstance());

        VersionRestore rail = new VersionRestore(resolver);
        rail.recordDeleted("/content"); // as DeletedPagesListener does when a page goes
        Map<String, Object> entry = rail.versionsInside(EN).stream().filter(p -> hist.equals(p.get("path"))).findFirst().get();
        List<?> versions = (List<?>) entry.get("versions");
        assertEquals("As deleted", ((Map<?, ?>) versions.get(0)).get("name"));
        assertEquals(2, versions.size(), "and the version it had");

        rail.restoreOne(hist, String.valueOf(((Map<?, ?>) versions.get(0)).get("id")));
        Node content = session.getNode(hist + "/jcr:content");
        assertEquals("Edited after the version", content.getProperty("jcr:title").getString());
        assertTrue(content.hasNode("note"));
        assertTrue(content.isNodeType("mix:versionable"), "the same content node as before");
        javax.jcr.version.VersionIterator it = session.getWorkspace().getVersionManager().getVersionHistory(content.getPath()).getAllVersions();
        int count = 0;
        while (it.hasNext()) {
            if (!"jcr:rootVersion".equals(it.nextVersion().getName())) {
                count++;
            }
        }
        assertEquals(2, count, "its earlier version is still its own, and the state it came back in is one more");
    }

    @Test
    void oldKeepingsGoAndLargeOnesAreNotKept() throws Exception {
        NodeState news = DeletedPages.at(repository().getNodeState(), NEWS);
        Calendar old = Calendar.getInstance();
        old.add(Calendar.DAY_OF_MONTH, -100);
        String oldEntry = KeptPages.keep(session, NEWS, news, null, old);
        String newEntry = KeptPages.keep(session, EN + "/archive", DeletedPages.at(repository().getNodeState(), EN + "/archive"), null,
                Calendar.getInstance());
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.DAY_OF_MONTH, -90);
        assertEquals(1, KeptPages.purge(session, cutoff));
        assertFalse(session.nodeExists(oldEntry));
        assertTrue(session.nodeExists(newEntry));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> KeptPages.keep(session, NEWS, news, null, Calendar.getInstance(), 3));
        assertEquals(List.of(newEntry), KeptPages.entryPaths(session), "nothing left of the large one");
    }

    @Test
    void theStateBeforeTellsTheVersionsWhoAndWhen() throws Exception {
        String hist = EN + "/hist2";
        context.create().resource(hist, "jcr:primaryType", "cq:Page");
        context.create().resource(hist + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Versioned");
        resolver.commit();
        OakPages.pageManager(resolver).createRevision(OakPages.page(hist), "v1", "first");
        String historyId = session.getNode(hist + "/jcr:content").getProperty("jcr:versionHistory").getString();
        // the state before the deletion, as the observer reads it
        NodeBuilder b = EmptyNodeState.EMPTY_NODE.builder();
        NodeBuilder folder = folder(b.child("old"));
        page(folder.child("hist2"), "Versioned").child("jcr:content").setProperty("jcr:versionHistory", historyId, Type.REFERENCE);
        page(folder.child("plain"), "Never versioned");
        List<DeletedPages.Versioned> versioned = DeletedPages.versioned(new DeletedPages.Deleted(EN + "/old", b.getNodeState().getChildNode("old")));
        assertEquals(1, versioned.size());
        assertEquals(EN + "/old/hist2", versioned.get(0).path);
        assertEquals(historyId, versioned.get(0).history);

        session.getNode(hist).remove();
        session.save();
        VersionRestore rail = new VersionRestore(resolver);
        rail.recordDeleted("/content"); // the listener was first: "found", nobody known
        assertTrue(rail.recordFromState(hist, historyId, "Versioned", "someone"));
        assertFalse(rail.recordFromState(hist, historyId, "Versioned", "someone else"), "a record that knows who stays");
        Map<String, Object> entry = rail.versionsInside(EN).stream().filter(p -> hist.equals(p.get("path"))).findFirst().get();
        assertEquals("someone", entry.get("deletedBy"));
        assertNull(entry.get("foundAt"));
        assertEquals("1.0", ((Map<?, ?>) ((List<?>) entry.get("versions")).get(0)).get("name"));
    }

    @Test
    void aLargeDeletionIsKeptPageByPage() throws Exception {
        NodeState news = DeletedPages.at(repository().getNodeState(), NEWS);
        assertEquals(10, KeptPages.count(news, 100, false), "news (8 nodes) with the story below it (2)");
        List<String> skipped = new ArrayList<>();
        List<String> entries = KeptPages.keepAll(session, NEWS, news, "someone", Calendar.getInstance(), 8, 1000, skipped);
        assertEquals(2, entries.size(), "too large as a whole: one entry per page");
        assertTrue(skipped.isEmpty());
        assertFalse(session.getNode(entries.get(0) + "/node").hasNode("story"), "each page without the pages below it");
        VersionRestore rail = new VersionRestore(resolver);
        List<Object> listed = rail.versionsInside(EN).stream().map(p -> p.get("path")).collect(Collectors.toList());
        assertTrue(listed.containsAll(List.of(NEWS, NEWS + "/story")), String.valueOf(listed));

        for (String page : new String[] {NEWS, NEWS + "/story"}) {
            Map<?, ?> v = (Map<?, ?>) ((List<?>) rail.versionsInside(EN).stream().filter(p -> page.equals(p.get("path"))).findFirst().get()
                    .get("versions")).get(0);
            assertEquals("recreated", rail.restoreOne(page, String.valueOf(v.get("id"))));
        }
        assertEquals("Story", session.getNode(NEWS + "/story/jcr:content").getProperty("jcr:title").getString());
        assertTrue(KeptPages.entryPaths(session).isEmpty(), "all back: the entries went");

        // with a total budget: what does not fit is named
        session.getNode(NEWS).remove();
        session.save();
        skipped.clear();
        entries = KeptPages.keepAll(session, NEWS, news, null, Calendar.getInstance(), 8, 9, skipped);
        assertEquals(1, entries.size());
        assertEquals(List.of(NEWS + "/story"), skipped);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> tree, String path) {
        for (Map<String, Object> c : (List<Map<String, Object>>) tree.get("children")) {
            if (path.equals(c.get("path"))) {
                return c;
            }
            Map<String, Object> deeper = child(c, path);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    @Test
    void theKeptVersionIsPreviewedAndForgotten() throws Exception {
        NodeState before = repository().getNodeState();
        KeptPages.keep(session, NEWS, DeletedPages.at(before, NEWS), null, Calendar.getInstance());
        VersionRestore rail = new VersionRestore(resolver);
        String id = String.valueOf(((Map<?, ?>) ((List<?>) rail.versionsInside(EN).stream().filter(p -> NEWS.equals(p.get("path")))
                .findFirst().get().get("versions")).get(0)).get("id"));

        Node content = VersionPreview.frozenContent(resolver, resolver, NEWS, id);
        ResourceResolver overlay = VersionPreview.resolver(resolver, NEWS, content, null);
        assertEquals("cq:Page", overlay.getResource(NEWS).getValueMap().get("jcr:primaryType", String.class));
        assertEquals("<p>Hello</p>", overlay.getResource(NEWS + "/jcr:content/root/text").getValueMap().get("text", String.class));
        assertEquals("cq:PageContent", overlay.getResource(NEWS + "/jcr:content").getValueMap().get("jcr:primaryType", String.class));
        List<String> kids = new ArrayList<>();
        overlay.getResource(NEWS + "/jcr:content/root").listChildren().forEachRemaining(r -> kids.add(r.getName()));
        assertEquals(List.of("title", "text", "image"), kids);
        assertNull(KeptPages.node(session, EN + "/other", id), "the id is only good for its own page");

        rail.forgetDeleted(NEWS);
        assertTrue(rail.versionsInside(EN).stream().noneMatch(p -> String.valueOf(p.get("path")).startsWith(NEWS)), "with the pages below it");
        assertTrue(KeptPages.entryPaths(session).isEmpty());
    }

    private static List<String> names(Node n) throws Exception {
        List<String> out = new ArrayList<>();
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            out.add(it.nextNode().getName());
        }
        return out;
    }
}
