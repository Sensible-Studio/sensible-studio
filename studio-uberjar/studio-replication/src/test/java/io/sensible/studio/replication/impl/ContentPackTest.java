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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Packs travel between two repositories: here, from one subtree of Oak to another. */
@ExtendWith(SlingContextExtension.class)
class ContentPackTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        ResourceResolver rr = context.resourceResolver();
        session = rr.adaptTo(Session.class);
        Node content = session.getRootNode().addNode("author", "sling:Folder");
        Node site = content.addNode("site", "cq:Page");
        Node en = site.addNode("en", "cq:Page");
        en.addMixin("mix:versionable");
        Node jc = en.addNode("jcr:content", "cq:PageContent");
        jc.setProperty("jcr:title", "English");
        jc.setProperty("cq:tags", new String[] {"a", "b"});
        jc.setProperty("count", 3L);
        jc.setProperty("ratio", 1.5);
        jc.setProperty("on", true);
        Calendar when = Calendar.getInstance();
        jc.setProperty("when", when);
        jc.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));
        Node root = jc.addNode("root", "nt:unstructured");
        root.addNode("b", "nt:unstructured").setProperty("text", "B");
        root.addNode("a", "nt:unstructured").setProperty("text", "A");
        jc.addNode("cq:annotations", "nt:unstructured").addNode("1", "nt:unstructured");
        en.addNode("child", "cq:Page").addNode("jcr:content", "cq:PageContent").setProperty("jcr:title", "Child");
        session.getRootNode().addNode("publish", "sling:Folder");
        session.save();
    }

    private List<String> names(Node n) throws Exception {
        List<String> out = new ArrayList<>();
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            out.add(it.nextNode().getName());
        }
        return out;
    }

    private ContentPack.Unpacked relocated(byte[] pack) throws Exception {
        // the pack says /author/...; install it under /publish by rewriting the header (the test's two repositories)
        ContentPack.Unpacked u = ContentPack.unpack(new ByteArrayInputStream(pack));
        String json = Json.write(u.header).replace("\"/author", "\"/publish");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> header = (java.util.Map<String, Object>) Json.parse(json);
        return new ContentPack.Unpacked(header, u.binaries);
    }

    @Test
    void aPageTravelsWithoutItsChildPagesAndIsReplacedInPlace() throws Exception {
        byte[] pack = ContentPack.pack(session, "/author/site/en", "Activate", false);
        ContentPack.install(session, relocated(pack));
        session.save();

        Node en = session.getNode("/publish/site/en");
        assertEquals("cq:Page", session.getNode("/publish/site").getPrimaryNodeType().getName(), "missing ancestors get their type");
        assertFalse(en.isNodeType("mix:versionable"), "author-only mixins stay home");
        Node jc = en.getNode("jcr:content");
        assertEquals("English", jc.getProperty("jcr:title").getString());
        assertEquals(Arrays.asList("a", "b"), Arrays.asList(jc.getProperty("cq:tags").getValues()).stream().map(v -> {
            try {
                return v.getString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(java.util.stream.Collectors.toList()));
        assertEquals(3L, jc.getProperty("count").getLong());
        assertEquals(1.5, jc.getProperty("ratio").getDouble());
        assertTrue(jc.getProperty("on").getBoolean());
        assertEquals(session.getNode("/author/site/en/jcr:content").getProperty("when").getDate().getTimeInMillis(), jc.getProperty("when").getDate().getTimeInMillis());
        assertEquals("hello", new String(ContentPack.readAll(jc.getProperty("jcr:data").getBinary().getStream()), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("b", "a"), names(jc.getNode("root")), "document order");
        assertFalse(jc.hasNode("cq:annotations"), "annotations are not published");
        assertFalse(en.hasNode("child"), "child pages are not part of a non-deep activation");

        // a second activation replaces the content and keeps a child page that is already there
        session.getNode("/publish/site/en").addNode("child", "cq:Page");
        session.getNode("/publish/site/en/jcr:content").setProperty("stale", "x");
        session.getNode("/publish/site/en/jcr:content/root").addNode("local", "nt:unstructured");
        session.save();
        Node a = session.getNode("/author/site/en/jcr:content/root");
        a.orderBefore("a", "b");
        session.getNode("/author/site/en/jcr:content").setProperty("jcr:title", "English 2");
        session.save();
        ContentPack.install(session, relocated(ContentPack.pack(session, "/author/site/en", "Activate", false)));
        session.save();
        jc = session.getNode("/publish/site/en/jcr:content");
        assertEquals("English 2", jc.getProperty("jcr:title").getString());
        assertFalse(jc.hasProperty("stale"), "properties not on the author go");
        assertFalse(jc.getNode("root").hasNode("local"), "nodes not on the author go");
        assertEquals(Arrays.asList("a", "b"), names(jc.getNode("root")), "reordered like the author");
        assertTrue(session.nodeExists("/publish/site/en/child"), "the child page on publish stays");
    }

    @Test
    void deepActivationTakesTheChildPagesAndDeactivationRemovesTheTree() throws Exception {
        ContentPack.install(session, relocated(ContentPack.pack(session, "/author/site/en", "Activate", true)));
        session.save();
        assertEquals("Child", session.getNode("/publish/site/en/child/jcr:content").getProperty("jcr:title").getString());

        assertTrue(ContentPack.remove(session, "/publish/site/en"));
        session.save();
        assertFalse(session.nodeExists("/publish/site/en"));
        assertFalse(ContentPack.remove(session, "/publish/site/en"));
        assertNotNull(session.getNode("/publish/site"));
        assertNull(null);
    }

    @Test
    void thePackCarriesItsNamespacesAndTheReceiverRegistersTheMissingOnes() throws Exception {
        // the author knows a namespace an imported package brought (XMP's stRef); the pack names it
        session.getWorkspace().getNamespaceRegistry().registerNamespace("stRef", "http://ns.adobe.com/xap/1.0/sType/ResourceRef#");
        session.getNode("/author/site/en/jcr:content").setProperty("stRef:documentID", "xmp.did:1");
        session.save();
        byte[] pack = ContentPack.pack(session, "/author/site/en", "Activate", false);
        ContentPack.Unpacked unpacked = ContentPack.unpack(new java.io.ByteArrayInputStream(pack));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ns = (java.util.Map<String, Object>) unpacked.header.get("namespaces");
        assertEquals("http://ns.adobe.com/xap/1.0/sType/ResourceRef#", ns.get("stRef"));
        assertTrue(ns.containsKey("cq") && ns.containsKey("jcr"), ns.toString());
        assertFalse(ns.containsKey("{"), "an expanded name is not a prefix");

        // a receiver that never heard of it registers it before writing (here: unregistering is not
        // possible in Oak, so the registration path is exercised with a prefix the pack adds by hand)
        ns.put("studioTest", "http://sensible.io/test/1.0");
        ContentPack.registerNamespaces(session, unpacked);
        assertEquals("http://sensible.io/test/1.0", session.getNamespaceURI("studioTest"));
        ContentPack.install(session, relocated(pack));
        session.save();
        assertEquals("xmp.did:1", session.getProperty("/publish/site/en/jcr:content/stRef:documentID").getString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theClosedUserGroupTravelsAsTheNamesOfItsPrincipals() throws Exception {
        // what Oak's CUG module keeps below a closed page (the test repository has no such module)
        Node policy = session.getNode("/author/site/en").addNode("rep:cugPolicy", "nt:unstructured");
        policy.setProperty("rep:principalNames", new String[] {"members", "editors"});
        session.save();
        byte[] pack = ContentPack.pack(session, "/author/site/en", "Activate", false);
        java.util.Map<String, Object> node = (java.util.Map<String, Object>) ContentPack.unpack(new ByteArrayInputStream(pack)).header.get("node");
        assertEquals(java.util.Arrays.asList("members", "editors"), node.get("cug"));
        assertEquals(java.util.Collections.emptyList(), ((java.util.Map<String, Object>) ((java.util.List<Object>) node.get("children")).get(0)).get("cug"));

        java.util.List<String> applied = new java.util.ArrayList<>();
        CugTransfer.Applier before = CugTransfer.applier;
        CugTransfer.applier = (s, path, names) -> applied.add(path + " " + names);
        try {
            ContentPack.install(session, relocated(pack));
            assertEquals(java.util.Collections.singletonList("/publish/site/en [members, editors]"), applied, "set once, on the page it is on");

            // lifted on the author: the publish, which has one, is told to remove it
            applied.clear();
            session.getNode("/publish/site/en").addNode("rep:cugPolicy", "nt:unstructured");
            policy.remove();
            session.save();
            ContentPack.install(session, relocated(ContentPack.pack(session, "/author/site/en", "Activate", false)));
            assertEquals(java.util.Collections.singletonList("/publish/site/en []"), applied);
        } finally {
            CugTransfer.applier = before;
        }
    }
}
