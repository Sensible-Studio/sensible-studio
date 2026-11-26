/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.linkchecker.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class LinksTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        session = context.resourceResolver().adaptTo(Session.class);
        Node content = session.getRootNode().addNode("content", "sling:Folder");
        Node site = content.addNode("site", "cq:Page");
        site.addNode("jcr:content", "cq:PageContent").setProperty("jcr:title", "Site");
        Node en = site.addNode("en", "cq:Page");
        Node jc = en.addNode("jcr:content", "cq:PageContent");
        jc.setProperty("jcr:title", "English");
        jc.setProperty("cq:template", "/conf/site/settings/wcm/templates/page");
        Node root = jc.addNode("root", "nt:unstructured");
        root.addNode("text", "nt:unstructured").setProperty("text",
                "<p>See <a href=\"https://example.com/a?x=1&amp;y=2\">this</a>, or https://example.org/b. "
                        + "And <a href=\"/content/site/missing.html#top\">a gone page</a> and <a href=\"/content/site/en.html\">home</a>. "
                        + "Not http://localhost:4502/x.</p>");
        root.addNode("teaser", "nt:unstructured").setProperty("linkURL", "/content/site/en");
        root.addNode("image", "nt:unstructured").setProperty("fileReference", "/content/dam/nope.jpg");
        Node about = en.addNode("about", "cq:Page");
        about.addNode("jcr:content", "cq:PageContent").addNode("root", "nt:unstructured").addNode("button", "nt:unstructured")
                .setProperty("link", "https://example.com/a?x=1&y=2");
        session.save();
    }

    @Test
    void findsExternalAndInternalLinksWithTheirPlaces() throws Exception {
        Map<String, Links.Found> found = Links.collect(session, Collections.singletonList("/content"));
        assertTrue(found.containsKey("https://example.com/a?x=1&y=2"), "&amp; decoded, the same URL from two pages");
        assertEquals(2, found.get("https://example.com/a?x=1&y=2").referrers.size());
        assertTrue(found.containsKey("https://example.org/b"), "the full stop that ends the sentence is not part of the URL");
        assertFalse(found.keySet().stream().anyMatch(u -> u.contains("localhost")), "links to this instance are not external");
        assertEquals(Links.INTERNAL, found.get("/content/site/missing.html").kind);
        assertTrue(found.containsKey("/content/site/en.html"));
        assertTrue(found.containsKey("/content/site/en"), "a link field");
        assertTrue(found.containsKey("/content/dam/nope.jpg"), "an image reference");
        assertFalse(found.keySet().stream().anyMatch(u -> u.startsWith("/conf")), "cq:template is not an authored link");
        assertEquals("/content/site/en/jcr:content/root/text@text",
                found.get("https://example.org/b").referrers.iterator().next());
    }

    @Test
    void storesKeepsStatusAndForgetsUnusedLinks() throws Exception {
        Links.store(session, Links.collect(session, Collections.singletonList("/content")));
        Node n = Links.find(session, "https://example.org/b");
        assertNotNull(n);
        assertEquals(Links.PENDING, n.getProperty("status").getString());
        Links.record(n, new Links.Check(true, 200, null, null));
        session.save();

        session.getNode("/content/site/en/about").remove();
        session.save();
        Links.ScanResult r = Links.store(session, Links.collect(session, Collections.singletonList("/content")));
        assertEquals(0, r.added);
        assertEquals(Links.VALID, Links.find(session, "https://example.org/b").getProperty("status").getString(), "status kept");
        assertEquals(1, Links.find(session, "https://example.com/a?x=1&y=2").getProperty("referrers").getValues().length);

        session.getNode("/content/site/en/jcr:content/root/text").remove();
        session.save();
        r = Links.store(session, Links.collect(session, Collections.singletonList("/content")));
        assertNull(Links.find(session, "https://example.org/b"), "nobody uses it any more");
        assertTrue(r.removed >= 1);
    }

    @Test
    void checksInternalLinksAgainstTheRepository() throws Exception {
        assertTrue(Links.checkInternal(session, "/content/site/en.html").valid, "the page behind the .html");
        assertTrue(Links.checkInternal(session, "/content/site/en").valid);
        assertTrue(Links.checkInternal(session, "/content/site/en.mobile.html#x").valid, "selectors and fragment");
        Links.Check gone = Links.checkInternal(session, "/content/site/missing.html");
        assertFalse(gone.valid);
        assertEquals("Not found in the repository", gone.error);
        assertFalse(Links.checkInternal(session, "/content/dam/nope.jpg").valid);
    }

    @Test
    void describesWhereALinkIsWritten() throws Exception {
        Map<String, Object> r = Links.referrerJson(session, "/content/site/en/jcr:content/root/teaser@linkURL");
        assertEquals("/content/site/en", r.get("owner"));
        assertEquals("English", r.get("ownerTitle"));
        assertEquals("page", r.get("ownerKind"));
        assertEquals("root/teaser", r.get("component"));
        assertEquals("linkURL", r.get("property"));
    }

    @Test
    void trimsTheUrlsFoundInText() {
        assertEquals("https://a.b/c", Links.trimUrl("https://a.b/c)."));
        assertEquals("https://a.b/c?x=1&y=2", Links.trimUrl("https://a.b/c?x=1&amp;y=2"));
        assertTrue(Links.isLocal("http://localhost:4502/content"));
        assertFalse(Links.isLocal("https://localhost.example.com/"));
    }
}
