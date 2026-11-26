/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jcr.Node;

import org.apache.sling.api.adapter.AdapterManager;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.PageRevisions;

/** Preview Version (the overlay resolver) on a real Oak repository. */
@ExtendWith(SlingContextExtension.class)
class VersionPreviewTest {

    private static final String PAGE = "/content/site/en";
    private static final String CONTENT = PAGE + "/jcr:content";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;
    private String version;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/content/site", "jcr:primaryType", "sling:Folder");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(CONTENT, "jcr:primaryType", "cq:PageContent", "jcr:title", "English",
                "sling:resourceType", "site/components/page", "cq:tags", new String[] {"a:b"});
        context.create().resource(CONTENT + "/root", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/container");
        context.create().resource(CONTENT + "/root/text", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/text",
                "text", "<p>Hello</p>", "rank", 3L);
        context.create().resource(CONTENT + "/root/old", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/title",
                "jcr:title", "Going away");
        context.create().resource(PAGE + "/child", "jcr:primaryType", "cq:Page");
        resolver = context.resourceResolver();
        resolver.commit();
        version = new PageRevisions(resolver).create(PAGE, "v1", "first").getId();

        // what changed since
        resolver.getResource(CONTENT + "/root/text").adaptTo(ModifiableValueMap.class).put("text", "<p>Hello world</p>");
        resolver.delete(resolver.getResource(CONTENT + "/root/old"));
        context.create().resource(CONTENT + "/root/image", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/image",
                "fileReference", "/content/dam/a.jpg");
        resolver.getResource(CONTENT).adaptTo(ModifiableValueMap.class).put("cq:lastModifiedBy", "editor");
        resolver.commit();
    }

    private ResourceResolver overlay(AdapterManager adapters) throws Exception {
        Node frozen = VersionPreview.frozenContent(resolver, PAGE, version);
        return VersionPreview.resolver(resolver, PAGE, frozen, adapters);
    }

    @Test
    void theOverlayShowsTheVersionsContent() throws Exception {
        ResourceResolver r = overlay(null);
        Resource text = r.getResource(CONTENT + "/root/text");
        assertEquals("<p>Hello</p>", text.getValueMap().get("text", String.class));
        assertEquals(3L, text.getValueMap().get("rank", Long.class));
        assertEquals("site/components/text", text.getResourceType());
        assertEquals("nt:unstructured", text.getValueMap().get("jcr:primaryType", String.class));
        assertSame(r, text.getResourceResolver());
        assertNotNull(r.getResource(CONTENT + "/root/old"), "removed since, there in the version");
        assertNull(r.getResource(CONTENT + "/root/image"), "added since, not in the version");
        assertNull(r.getResource(CONTENT + "/root/text").getValueMap().get("jcr:frozenUuid"));

        Resource content = r.getResource(CONTENT);
        assertEquals("cq:PageContent", content.getValueMap().get("jcr:primaryType", String.class));
        assertEquals(List.of("a:b"), List.of(content.getValueMap().get("cq:tags", String[].class)));
        List<String> names = new ArrayList<>();
        r.getResource(CONTENT + "/root").listChildren().forEachRemaining(c -> names.add(c.getName()));
        assertEquals(List.of("text", "old"), names);

        // around the content: the page and its children as they are, the content swapped
        Resource page = r.getResource(PAGE);
        List<String> pageChildren = new ArrayList<>();
        for (Resource c : r.getChildren(page)) {
            pageChildren.add(c.getName() + (c.getValueMap().get("text") == null && c.getPath().equals(CONTENT)
                    && c.getResourceResolver() == r ? ":frozen" : ""));
        }
        assertEquals(List.of("jcr:content:frozen", "child"), pageChildren);
        assertEquals("<p>Hello</p>", page.getChild("jcr:content/root/text").getValueMap().get("text", String.class),
                "a page reads its content through the overlay");
        assertSame(r, page.getResourceResolver());
        assertEquals(PAGE, r.getResource(PAGE + "/child").getParent().getPath());
        assertEquals(PAGE, text.getParent().getParent().getParent().getPath());
        assertEquals(CONTENT + "/root", r.getResource(text, "..").getPath());
        assertEquals("<p>Hello</p>", r.getResource(content, "root/text").getValueMap().get("text", String.class));
        assertEquals("<p>Hello</p>", r.resolve(CONTENT + "/root/text").getValueMap().get("text", String.class));
        assertThrows(UnsupportedOperationException.class, r::commit);
    }

    @Test
    void adaptersSeeTheOverlay() throws Exception {
        AdapterManager adapters = new AdapterManager() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T getAdapter(Object adaptable, Class<T> type) {
                return type == String.class ? (T) ("adapted " + ((ResourceResolver) adaptable).getResource(CONTENT).getValueMap()
                        .get("jcr:title", String.class)) : null;
            }
        };
        ResourceResolver r = overlay(adapters);
        assertEquals("adapted English", r.adaptTo(String.class));
        assertNotNull(r.adaptTo(javax.jcr.Session.class), "the rest comes from the real resolver");
    }

    @Test
    void aDeletedPageIsPreviewedFromItsRecord() throws Exception {
        context.registerAdapter(ResourceResolver.class, com.day.cq.wcm.api.PageManager.class,
                (java.util.function.Function<ResourceResolver, com.day.cq.wcm.api.PageManager>) OakPages::pageManager);
        new VersionRestore(resolver).delete(List.of(PAGE));
        assertNull(resolver.getResource(PAGE));
        Node frozen = VersionPreview.frozenContent(resolver, resolver, PAGE, version);
        ResourceResolver r = VersionPreview.resolver(resolver, PAGE, frozen, null);
        Resource page = r.getResource(PAGE);
        assertEquals("cq:Page", page.getValueMap().get("jcr:primaryType", String.class));
        assertEquals("<p>Hello</p>", page.getChild("jcr:content/root/text").getValueMap().get("text", String.class));
        Resource requested = r.resolve(PAGE + ".html");
        assertEquals(PAGE, requested.getPath());
        assertEquals(".html", requested.getResourceMetadata().getResolutionPathInfo());
        assertEquals("/content/site", page.getParent().getPath());
        assertTrue(VersionPreviewServlet.parentReadable(resolver, PAGE));
        assertTrue(!VersionPreviewServlet.parentReadable(resolver, "/nowhere/at/all"));

        // the page's images are requested on their own: below the deleted page, they resolve to the version too
        Resource image = r.resolve(PAGE + "/_jcr_content/root/text.coreimg.60.jpeg/1660323785307/photo.jpeg");
        assertEquals(CONTENT + "/root/text", image.getPath());
        assertEquals(".coreimg.60.jpeg/1660323785307/photo.jpeg", image.getResourceMetadata().getResolutionPathInfo());
        assertTrue(org.apache.sling.api.resource.ResourceUtil.isNonExistingResource(r.resolve(PAGE + "/jcr:content/root/missing.html")));
    }

    @Test
    void thePagesOwnResourcesPointBackIntoThePreview() {
        String html = "<img src=\"/site/en/_jcr_content/root/image.coreimg.jpeg/1/a%20b.jpeg\" "
                + "data-cmp-src=\"/site/en/_jcr_content/root/image.coreimg.60{.width}.jpeg/1/a.jpeg\" "
                + "srcset=\"/content/site/en/jcr:content/root/image.coreimg.100.jpeg 100w,/site/en/_jcr_content/root/image.coreimg.200.jpeg 200w\">"
                + "<meta content=\"http://localhost:4502/site/en/_jcr_content/root/image.coreimg.jpeg\">"
                + "<a href=\"/site/en/news.html\">news</a><img src=\"/content/dam/site/a.jpeg\"><img src=\"/content/site/en2/_jcr_content/x.jpeg\">";
        String out = VersionPreviewServlet.rewrite(html, PAGE, "v-1", "", "/site/en");
        String base = "/bin/studio/versionpreview.html?path=%2Fcontent%2Fsite%2Fen&amp;version=v-1&amp;res=/content/site/en/jcr:content/root/";
        assertTrue(out.contains("src=\"" + base + "image.coreimg.jpeg/1/a%20b.jpeg\""), out); // decoded, then encoded once
        assertTrue(out.contains("data-cmp-src=\"" + base + "image.coreimg.60{.width}.jpeg/1/a.jpeg\""), out);
        assertTrue(out.contains("srcset=\"" + base + "image.coreimg.100.jpeg 100w," + base + "image.coreimg.200.jpeg 200w\""), out);
        assertTrue(out.contains("<meta content=\"" + base + "image.coreimg.jpeg\">"), out);
        assertTrue(out.contains("href=\"/site/en/news.html\""), "links to other pages stay as they are");
        assertTrue(out.contains("src=\"/content/dam/site/a.jpeg\""), "and the DAM");
        assertTrue(out.contains("src=\"/content/site/en2/_jcr_content/x.jpeg\""), "and pages whose path merely starts the same");
    }

    @Test
    void anUploadedImageIsReadFromTheVersion() throws Exception {
        // an image component with its own file (no DAM reference), versioned, then replaced
        javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
        Node photo = session.getNode(CONTENT + "/root").addNode("photo", "nt:unstructured");
        photo.setProperty("sling:resourceType", "site/components/image");
        Node file = photo.addNode("file", "nt:file").addNode("jcr:content", "nt:resource");
        file.setProperty("jcr:mimeType", "image/png");
        file.setProperty("jcr:data", session.getValueFactory().createBinary(new java.io.ByteArrayInputStream("old image".getBytes())));
        session.save();
        String withPhoto = new PageRevisions(resolver).create(PAGE, "v2", "with a photo").getId();
        file.setProperty("jcr:data", session.getValueFactory().createBinary(new java.io.ByteArrayInputStream("new image".getBytes())));
        session.save();

        ResourceResolver r = VersionPreview.resolver(resolver, PAGE, VersionPreview.frozenContent(resolver, PAGE, withPhoto), null);
        Resource f = r.getResource(CONTENT + "/root/photo/file");
        try (java.io.InputStream in = f.adaptTo(java.io.InputStream.class)) {
            assertEquals("old image", new String(in.readAllBytes()));
        }
        Resource data = f.getChild("jcr:content");
        assertEquals("image/png", data.getValueMap().get("jcr:mimeType", String.class));
        for (int i = 0; i < 2; i++) { // a fresh stream each time
            try (java.io.InputStream in = data.getValueMap().get("jcr:data", java.io.InputStream.class)) {
                assertEquals("old image", new String(in.readAllBytes()));
            }
        }
        // listed like the repository lists it (an image servlet checks for jcr:data), read untyped as a stream too
        assertTrue(data.getValueMap().containsKey("jcr:data"));
        try (java.io.InputStream in = (java.io.InputStream) data.getValueMap().get("jcr:data")) {
            assertEquals("old image", new String(in.readAllBytes()));
        }
        assertNull(data.getValueMap().get("jcr:data", String.class), "a binary is only given as a stream");
        assertNull(r.getResource(CONTENT + "/root/text").adaptTo(java.io.InputStream.class), "not a file");
    }

    @Test
    void unknownVersionsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> VersionPreview.frozenContent(resolver, PAGE, "nope"));
        assertThrows(IllegalArgumentException.class, () -> VersionPreview.frozenContent(resolver, "/content/site/none", version));
    }
}
