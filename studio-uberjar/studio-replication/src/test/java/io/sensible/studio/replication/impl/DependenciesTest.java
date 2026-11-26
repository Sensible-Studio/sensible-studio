package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class DependenciesTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private void page(String path, Object... content) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        Object[] props = new Object[content.length + 2];
        props[0] = "jcr:primaryType";
        props[1] = "cq:PageContent";
        System.arraycopy(content, 0, props, 2, content.length);
        context.create().resource(path + "/jcr:content", props);
    }

    @Test
    void whatAPageShowsBesidesItself() {
        page("/conf/site/settings/wcm/templates/article");
        context.create().resource("/conf/site/settings/wcm/templates/article/structure/jcr:content/root/header",
                "fragmentVariationPath", "/content/experience-fragments/site/header/master");
        page("/content/experience-fragments/site/header/master");
        context.create().resource("/content/experience-fragments/site/header/master/jcr:content/root/logo",
                "fileReference", "/content/dam/site/logo.svg");
        page("/content/site/en/news", "cq:template", "/conf/site/settings/wcm/templates/article");
        context.create().resource("/content/site/en/news/jcr:content/root/image", "fileReference", "/content/dam/site/beach.jpg?x=1");
        context.create().resource("/content/site/en/news/jcr:content/root/teaser", "linkURL", "/content/site/en/other",
                "related", new String[] {"/content/dam/site/a.pdf", "not a path"}, "text", "see /content/dam/site/in text");
        // a child page is its own page
        page("/content/site/en/news/child");
        context.create().resource("/content/site/en/news/child/jcr:content/image", "fileReference", "/content/dam/site/child.jpg");

        LinkedHashSet<String> expected = new LinkedHashSet<>(Arrays.asList("/content/dam/site/beach.jpg", "/content/dam/site/a.pdf",
                "/conf/site/settings/wcm/templates/article", "/content/experience-fragments/site/header/master", "/content/dam/site/logo.svg"));
        assertEquals(expected, new LinkedHashSet<>(Dependencies.of(context.resourceResolver().getResource("/content/site/en/news"))));
        assertEquals("/content/dam/site/beach.jpg,/content/dam/site/a.pdf,/conf/site/settings/wcm/templates/article,"
                + "/content/experience-fragments/site/header/master,/content/dam/site/logo.svg",
                Dependencies.header(context.resourceResolver().getResource("/content/site/en/news")));
    }

    @Test
    void aTemplateThatCannotBeReadOrTooMuchSaysNothing() {
        page("/content/site/en/a", "cq:template", "/conf/gone/settings/wcm/templates/x");
        assertNull(Dependencies.of(context.resourceResolver().getResource("/content/site/en/a")));
        page("/content/site/en/b");
        for (int i = 0; i <= Dependencies.MAX_PATHS; i++) {
            context.create().resource("/content/site/en/b/jcr:content/i" + i, "fileReference", "/content/dam/site/" + i + ".jpg");
        }
        assertNull(Dependencies.header(context.resourceResolver().getResource("/content/site/en/b")));
    }

    @Test
    void escapedForTheHeader() {
        page("/content/site/en/c");
        context.create().resource("/content/site/en/c/jcr:content/i", "fileReference", "/content/dam/site/a,b%.jpg");
        assertEquals("/content/dam/site/a%2Cb%25.jpg", Dependencies.header(context.resourceResolver().getResource("/content/site/en/c")));
        assertEquals("/content/experience-fragments/x/master", Dependencies.strip("/content/experience-fragments/x/master.html"));
    }

    @Test
    void whatTheRenderingShowedOfOtherPages() {
        page("/content/site");
        page("/content/site/en");
        page("/content/site/en/news");
        org.apache.sling.api.resource.Resource news = context.resourceResolver().getResource("/content/site/en/news");
        LinkedHashSet<String> named = new LinkedHashSet<>(Arrays.asList("/content/dam/site/beach.jpg"));
        LinkedHashSet<String> rendered = new LinkedHashSet<>(Arrays.asList(
                "/content/site/en/news/jcr:content", // itself
                "/content/site/en/*", // a navigation of the site's pages
                "/content/site/en/faqs/jcr:content", // one of them: the list covers it
                "/content/site/de/kontakt/jcr:content", // a teaser of a page elsewhere
                "/content/site/en/about/team/jcr:content", // one of a list of the pages below About: the list covers it
                "/content/site/en/about/*"));
        assertEquals(new LinkedHashSet<>(Arrays.asList("/content/dam/site/beach.jpg",
                "/content/site/en/jcr:content", "/content/site/jcr:content", // the pages above
                "/content/site/en/*", "/content/site/de/kontakt/jcr:content", "/content/site/en/about/*")),
                Dependencies.combine(news, named, rendered));
        // unknown on either side: nothing said
        assertNull(Dependencies.combine(news, null, rendered));
        assertNull(Dependencies.combine(news, named, null));
    }

    @Test
    void theRecordingOfARendering() {
        try (io.sensible.studio.wcm.shown.Shown.Recording r = io.sensible.studio.wcm.shown.Shown.start(4)) {
            io.sensible.studio.wcm.shown.Shown.page("/content/site/en");
            io.sensible.studio.wcm.shown.Shown.children("/content/site/en");
            io.sensible.studio.wcm.shown.Shown.page("/conf/site/settings"); // not content
            assertEquals(new LinkedHashSet<>(Arrays.asList("/content/site/en/jcr:content", "/content/site/en/*")), r.paths());
            // a rendering inside the rendering: what it showed is the outer one's too
            try (io.sensible.studio.wcm.shown.Shown.Recording inner = io.sensible.studio.wcm.shown.Shown.start(10)) {
                io.sensible.studio.wcm.shown.Shown.page("/content/site/de");
            }
            assertEquals(3, r.paths().size());
            io.sensible.studio.wcm.shown.Shown.page("/content/site/fr");
            io.sensible.studio.wcm.shown.Shown.page("/content/site/it"); // past the limit
            assertEquals(true, r.isUnknown());
            assertEquals(0, r.paths().size());
        }
        try (io.sensible.studio.wcm.shown.Shown.Recording r = io.sensible.studio.wcm.shown.Shown.start(10)) {
            try (io.sensible.studio.wcm.shown.Shown.Recording inner = io.sensible.studio.wcm.shown.Shown.start(10)) {
                io.sensible.studio.wcm.shown.Shown.unknown("a search");
            }
            assertEquals(true, r.isUnknown());
            // with who asked
            assertEquals("a search in " + DependenciesTest.class.getName(), r.why());
        }
        assertEquals(false, io.sensible.studio.wcm.shown.Shown.recording());
        io.sensible.studio.wcm.shown.Shown.page("/content/site/en"); // nothing recording: nothing happens
    }
}
