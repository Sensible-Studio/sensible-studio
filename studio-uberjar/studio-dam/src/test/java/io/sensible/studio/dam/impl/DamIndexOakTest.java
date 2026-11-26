/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.index.AsyncIndexUpdate;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProvider;
import org.apache.jackrabbit.oak.spi.commit.Observer;
import org.apache.jackrabbit.oak.spi.query.QueryIndexProvider;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DamIndex in a real (in-memory) Oak with oak-lucene, indexed the way Sensible Studio indexes it: async. */
class DamIndexOakTest {

    private static final String CND = "<dam='http://www.day.com/dam/1.0'>\n<sling='http://sling.apache.org/jcr/sling/1.0'>\n"
            + "<cq='http://www.day.com/jcr/cq/1.0'>\n<dc='http://purl.org/dc/elements/1.1/'>\n<studio='http://sensible.io/jcr/studio/1.0'>\n"
            + "[sling:Folder] > nt:folder\n - * (undefined) multiple\n - * (undefined)\n + * (nt:base) = sling:Folder version\n"
            + "[sling:OrderedFolder] > sling:Folder orderable\n"
            + "[dam:Asset] > nt:hierarchyNode\n + jcr:content (nt:unstructured) = nt:unstructured\n";

    private NodeStore store;
    private LuceneIndexEditorProvider editors;
    private Repository repository;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryNodeStore();
        LuceneIndexProvider provider = new LuceneIndexProvider();
        editors = new LuceneIndexEditorProvider();
        repository = new Jcr(new Oak(store)).with((QueryIndexProvider) provider).with((Observer) provider).with(editors)
                .createRepository();
        session = repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
        CndImporter.registerNodeTypes(new StringReader(CND), session);
        Node content = session.getRootNode().addNode("content", "sling:Folder");
        Node dam = content.addNode("dam", "sling:OrderedFolder");
        folder(dam, "wknd", "WKND Adventures");
        folder(dam.getNode("wknd"), "surf", "Surf Camps");
        folder(dam, "private", "Private");
        asset("/content/dam/wknd/surf/bali.jpg", "Surfing in Bali", "Waves at Balangan beach", new String[] {"wknd-shared:activity/surfing"}, "a1", 900, 5);
        asset("/content/dam/wknd/ski.jpg", "Skiing in the Alps", "Powder day", new String[] {"wknd-shared:activity/skiing"}, "b2", 4000, 40);
        asset("/content/dam/private/bali-copy.jpg", "Bali again", "Same photo", new String[] {}, "a1", 900, 1);
        session.save();
        DamIndex.install(session, true);
        index();
    }

    @AfterEach
    void tearDown() {
        session.logout();
        if (repository instanceof org.apache.jackrabbit.api.JackrabbitRepository) {
            ((org.apache.jackrabbit.api.JackrabbitRepository) repository).shutdown();
        }
    }

    private void index() {
        // the async lane also holds Oak's own "counter" index
        AsyncIndexUpdate update = new AsyncIndexUpdate("async", store, org.apache.jackrabbit.oak.plugins.index.CompositeIndexEditorProvider
                .compose(java.util.Arrays.asList(editors, new org.apache.jackrabbit.oak.plugins.index.counter.NodeCounterEditorProvider())));
        update.run();
        assertFalse(update.isFailing(), "async indexing failed");
    }

    private static void folder(Node parent, String name, String title) throws Exception {
        Node f = parent.addNode(name, "sling:OrderedFolder");
        f.addNode("jcr:content", "nt:unstructured").setProperty("jcr:title", title);
    }

    private void asset(String path, String title, String description, String[] tags, String sha1, long size, int daysAgo) throws Exception {
        String parent = path.substring(0, path.lastIndexOf('/'));
        Node a = session.getNode(parent).addNode(path.substring(path.lastIndexOf('/') + 1), "dam:Asset");
        Node c = a.addNode("jcr:content", "nt:unstructured");
        Calendar modified = Calendar.getInstance();
        modified.add(Calendar.DAY_OF_MONTH, -daysAgo);
        c.setProperty("jcr:lastModified", modified);
        Node md = c.addNode("metadata", "nt:unstructured");
        md.setProperty("dc:title", title);
        md.setProperty("dc:description", description);
        md.setProperty("cq:tags", tags);
        md.setProperty("dam:sha1", sha1);
        md.setProperty("dam:size", size);
        md.setProperty("dc:format", "image/jpeg");
        Node original = c.addNode("renditions", "nt:folder").addNode("original", "nt:file");
        Node res = original.addNode("jcr:content", "nt:resource");
        res.setProperty("jcr:mimeType", "image/jpeg");
        res.setProperty("jcr:data", session.getValueFactory().createBinary(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
    }

    private DamSearch.Query query(String text) {
        DamSearch.Query q = new DamSearch.Query();
        q.fulltext = text;
        return q;
    }

    private List<String> find(String scope, DamSearch.Query q) throws Exception {
        return DamIndexQueries.assets(session, scope, q, System.currentTimeMillis());
    }

    @Test
    void theConsoleQueriesUseTheIndex() throws Exception {
        String plan = DamIndexQueries.explain(session, "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, '/content/dam') "
                + "AND CONTAINS(a.*, 'surf*') OPTION(INDEX TAG studioDam)");
        assertTrue(plan.contains("lucene:" + DamIndex.NAME), plan);
        String sha = DamIndexQueries.explain(session, "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, '/content/dam') "
                + "AND a.[jcr:content/metadata/dam:sha1] = 'a1' OPTION(INDEX TAG studioDam)");
        assertTrue(sha.contains("lucene:" + DamIndex.NAME), sha);
    }

    @Test
    void fullTextWithPrefixesExclusionsAndScope() throws Exception {
        assertEquals(List.of("/content/dam/wknd/surf/bali.jpg"), find("/content/dam/wknd", query("surf")), "prefix: surf finds Surfing");
        assertEquals(List.of("/content/dam/wknd/surf/bali.jpg", "/content/dam/private/bali-copy.jpg").size(), find("/content/dam", query("bali")).size());
        assertEquals(List.of("/content/dam/private/bali-copy.jpg"), find("/content/dam", query("bali -surfing")));
        assertEquals(List.of("/content/dam/wknd/surf/bali.jpg"), find("/content/dam", query("\"balangan beach\"")));
        assertEquals(List.of(), find("/content/dam", query("\"beach balangan\"")));
        assertEquals(List.of("/content/dam/wknd/surf/bali.jpg"), find("/content/dam", query("skiing -powder surfing")).isEmpty() ? List.of("/content/dam/wknd/surf/bali.jpg") : List.of());
    }

    @Test
    void tagsSizesDatesAndDuplicates() throws Exception {
        DamSearch.Query q = query("");
        q.tags.add("wknd-shared:activity");
        assertEquals(2, find("/content/dam", q).size(), "a parent tag finds its sub-tags");
        q.tags.clear();
        q.tags.add("wknd-shared:activity/ski");
        assertEquals(List.of(), find("/content/dam", q), "no partial tag names");
        DamSearch.Query big = query("");
        big.minSize = 1000;
        assertEquals(List.of("/content/dam/wknd/ski.jpg"), find("/content/dam", big));
        DamSearch.Query recent = query("");
        recent.modifiedDays = 7;
        List<String> r = find("/content/dam", recent);
        assertEquals(List.of("/content/dam/private/bali-copy.jpg", "/content/dam/wknd/surf/bali.jpg"), r, "newest first");
        assertEquals(List.of("/content/dam/private/bali-copy.jpg", "/content/dam/wknd/surf/bali.jpg"),
                DamIndexQueries.bySha1(session, "a1").stream().sorted().collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("/content/dam/wknd/surf"), DamIndexQueries.folders(session, "/content/dam", "camps"));
    }

    @Test
    void theSetsAPictureIsInAreFound() throws Exception {
        Node set = session.getNode("/content/dam/wknd").addNode("trip", "dam:Asset");
        Node c = set.addNode("jcr:content", "nt:unstructured");
        c.setProperty(DamSets.PN_SET, true);
        c.setProperty(DamSets.PN_MEMBERS, new String[] {"/content/dam/wknd/ski.jpg", "/content/dam/wknd/surf/bali.jpg"});
        session.getNode("/content/dam/wknd/surf/bali.jpg/jcr:content").setProperty(AssetProcessor.PN_BLOB_ID, "ab12");
        session.save();
        index();
        assertEquals(List.of("/content/dam/wknd/trip"), DamIndexQueries.setsWith(session, "/content/dam/wknd/surf/bali.jpg"));
        assertEquals(List.of("/content/dam/wknd/trip"), DamIndexQueries.setsWith(session, "/content/dam/wknd/ski.jpg"));
        assertEquals(List.of(), DamIndexQueries.setsWith(session, "/content/dam/private/bali-copy.jpg"));
        assertEquals(List.of("/content/dam/wknd/surf/bali.jpg"), DamIndexQueries.byBlob(session, "ab12"), "an asset by its blob");
        assertEquals(List.of(), DamIndexQueries.byBlob(session, "ab1"));
        String plan = DamIndexQueries.explain(session, "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, '/content/dam') "
                + "AND a.[jcr:content/studio:members] = 'x' OPTION(INDEX TAG studioDam)");
        assertTrue(plan.contains("lucene:" + DamIndex.NAME), plan);
    }

    @Test
    void newContentIsFoundAfterTheNextIndexingRun() throws Exception {
        asset("/content/dam/wknd/kangaroo.jpg", "Kangaroo crossing", "Outback", new String[] {}, "c3", 10, 0);
        session.save();
        index();
        assertEquals(List.of("/content/dam/wknd/kangaroo.jpg"), find("/content/dam", query("kangaroo")));
        assertFalse(DamIndex.outdated(session));
    }

    @Test
    void searchesSwitchToTheIndexOnlyWhenItIsBuilt() throws Exception {
        assertFalse(DamIndex.ready(session), "no status node yet");
        Node var = session.getRootNode().addNode("var", "sling:Folder").addNode("studio", "sling:Folder").addNode("dam", "sling:Folder");
        var.addNode("index", "nt:unstructured");
        session.save();
        assertTrue(DamIndex.built(session));
        assertFalse(DamIndex.ready(session));
        DamIndex.markReady(session, true);
        assertTrue(DamIndex.ready(session));
        // a newer definition: not built until the lane has run
        session.getNode(DamIndex.PATH).setProperty("reindex", true);
        session.save();
        assertFalse(DamIndex.built(session));
        index();
        assertTrue(DamIndex.built(session));
    }

    @Test
    void fullTextExpressions() {
        assertEquals("surf* bali*", DamIndexQueries.fulltext("Surf Bali"));
        assertEquals("surf* -bali", DamIndexQueries.fulltext("surf -bali"));
        assertEquals("\"balangan beach\"", DamIndexQueries.fulltext("\"Balangan Beach\""));
        assertEquals("pică*", DamIndexQueries.fulltext("Pică"));
        assertEquals("\"a b\"", DamIndexQueries.fulltext("a:(b)"), "punctuation inside a word makes a phrase, never syntax");
        assertEquals(null, DamIndexQueries.fulltext("-only"));
        assertEquals(null, DamIndexQueries.fulltext("  "));
    }
}
