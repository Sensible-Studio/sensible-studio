/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class SitemapServletTest {

    private static final String EN = WcmTestSupport.EN;
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
    }

    private ModifiableValueMap content(String page) {
        return context.resourceResolver().getResource(page + "/jcr:content").adaptTo(ModifiableValueMap.class);
    }

    @Test
    void onlyAPageMarkedGenerateSitemapHasOne() {
        Resource en = context.resourceResolver().getResource(EN);
        assertFalse(SitemapServlet.isSitemapRoot(en));
        content(EN).put("sling:sitemapRoot", true);
        assertTrue(SitemapServlet.isSitemapRoot(en));
        content(EN).put("sling:sitemapRoot", "true");
        assertTrue(SitemapServlet.isSitemapRoot(en));
    }

    @Test
    void listsTheRootAndThePagesBelowWithoutNoindexAndRedirects() {
        Resource en = context.resourceResolver().getResource(EN);
        List<String> all = SitemapServlet.entries(en, 100).stream().map(e -> e.path).collect(Collectors.toList());
        assertEquals(EN, all.get(0));
        assertTrue(all.contains(EN + "/adventures"), all.toString());

        content(EN + "/adventures").put("cq:robotsTags", new String[] {"nofollow", "noindex"});
        List<String> without = SitemapServlet.entries(en, 100).stream().map(e -> e.path).collect(Collectors.toList());
        assertFalse(without.contains(EN + "/adventures"));
        assertEquals(all.size() - 1, without.size());

        content(EN + "/adventures").remove("cq:robotsTags");
        content(EN + "/adventures").put("cq:redirectTarget", "/content/elsewhere");
        assertFalse(SitemapServlet.entries(en, 100).stream().anyMatch(e -> e.path.equals(EN + "/adventures")));

        assertEquals(1, SitemapServlet.entries(en, 1).size());
    }

    private void page(String path, String... props) {
        java.util.Map<String, Object> content = new java.util.HashMap<>();
        content.put("jcr:primaryType", "cq:PageContent");
        for (int i = 0; i + 1 < props.length; i += 2) {
            content.put(props[i], props[i + 1]);
        }
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        context.create().resource(path + "/jcr:content", content);
    }

    private List<String> paths(List<SitemapServlet.Entry> entries) {
        return entries.stream().map(e -> e.path).collect(Collectors.toList());
    }

    @Test
    void aRootBelowARootHasItsOwnSitemap() {
        content(EN).put("sling:sitemapRoot", true);
        content(EN + "/adventures").put("sling:sitemapRoot", true);
        Resource en = context.resourceResolver().getResource(EN);
        List<String> top = paths(SitemapServlet.entries(en, 100));
        assertFalse(top.contains(EN + "/adventures"), top.toString());
        assertFalse(top.contains(EN + "/adventures/bali-surf-camp"), top.toString());
        assertTrue(top.contains(EN + "/faqs"), top.toString());
        List<String> adventures = paths(SitemapServlet.entries(context.resourceResolver().getResource(EN + "/adventures"), 100));
        assertEquals(EN + "/adventures", adventures.get(0));
        assertTrue(adventures.contains(EN + "/adventures/bali-surf-camp"));

        List<String> roots = SitemapServlet.roots(context.resourceResolver().getResource("/content/wknd")).stream()
                .map(Resource::getPath).collect(Collectors.toList());
        assertEquals(List.of(EN, EN + "/adventures"), roots);
    }

    @Test
    void languageRootsAndWhatTheyAreCalled() {
        page("/content/wknd/ca", "jcr:language", "en_CA");
        page("/content/wknd/ca/en");
        page("/content/wknd/ca/fr", "jcr:language", "fr_ca");
        page("/content/wknd/ch", "jcr:language", "de_ch");
        page("/content/wknd/ch/de", "jcr:language", "de_ch");
        page("/content/wknd/language-masters");
        page("/content/wknd/language-masters/en");
        Languages l = Languages.of(context.resourceResolver().getResource(EN + "/faqs"));
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (Languages.Root r : l.roots) {
            tags.put(r.path, r.hreflang);
        }
        // us/en: no jcr:language anywhere, but its parent is a country; ca (Catalan's code) is Canada here
        assertEquals("en-US", tags.get(EN));
        assertEquals("en-CA", tags.get("/content/wknd/ca/en"));
        assertEquals("fr-CA", tags.get("/content/wknd/ca/fr"));
        assertEquals("de-CH", tags.get("/content/wknd/ch/de"));
        assertEquals("en", tags.get("/content/wknd/language-masters/en"));
        assertFalse(tags.containsKey("/content/wknd/ca"), tags.toString());
        assertEquals(5, tags.size(), tags.toString());
    }

    @Test
    void versionsAreThoseInASitemap() {
        page("/content/wknd/ca", "jcr:language", "en_CA");
        page("/content/wknd/ca/en");
        page("/content/wknd/ca/en/faqs");
        page("/content/wknd/ca/fr", "jcr:language", "fr_ca");
        page("/content/wknd/ca/fr/faqs", "cq:robotsTags", "noindex");
        page("/content/wknd/language-masters");
        page("/content/wknd/language-masters/en");
        page("/content/wknd/language-masters/en/faqs");
        content(EN).put("sling:sitemapRoot", true);
        Languages l = Languages.of(context.resourceResolver().getResource(EN));
        // only us/en is in a sitemap: no other version to name
        assertTrue(l.versions(context.resourceResolver(), EN + "/faqs").isEmpty());

        context.resourceResolver().getResource("/content/wknd/ca/en/jcr:content").adaptTo(ModifiableValueMap.class).put("sling:sitemapRoot", true);
        context.resourceResolver().getResource("/content/wknd/ca/fr/jcr:content").adaptTo(ModifiableValueMap.class).put("sling:sitemapRoot", true);
        List<Languages.Version> v = l.versions(context.resourceResolver(), EN + "/faqs");
        // itself, Canada's English; not the French one (noindex) nor the language master (no sitemap)
        assertEquals(List.of("en-US", "en-CA"), v.stream().map(x -> x.hreflang).collect(Collectors.toList()));
        assertEquals("/content/wknd/ca/en/faqs", v.get(1).path);
        // a page that exists in one language only
        assertTrue(l.versions(context.resourceResolver(), EN + "/adventures").isEmpty());
    }

    @Test
    void robotsTxt() {
        assertEquals("User-agent: *\nDisallow:\n", SitemapServlet.robotsTxt(null, List.of()));
        assertEquals("User-agent: *\nDisallow:\n\nSitemap: https://x/a.sitemap.xml\nSitemap: https://x/b.sitemap.xml\n",
                SitemapServlet.robotsTxt("  ", List.of("https://x/a.sitemap.xml", "https://x/b.sitemap.xml")));
        assertEquals("User-agent: *\nDisallow: /private\n", SitemapServlet.robotsTxt("User-agent: *\r\nDisallow: /private", List.of("https://x/a.sitemap.xml")));
    }

    @Test
    void escapesAndFormats() {
        assertEquals("https://x/a?b=1&amp;c=&lt;2&gt;", SitemapServlet.escape("https://x/a?b=1&c=<2>"));
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(0);
        assertEquals("1970-01-01T00:00:00Z", SitemapServlet.w3c(c));
    }
}
