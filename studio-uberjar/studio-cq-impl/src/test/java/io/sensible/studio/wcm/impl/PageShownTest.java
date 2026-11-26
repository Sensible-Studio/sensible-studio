/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

import io.sensible.studio.wcm.shown.Shown;

/** What a rendering that uses the Page API records of the pages it shows (for the cache in front of a publish). */
@ExtendWith(SlingContextExtension.class)
class PageShownTest {

    private static final String EN = WcmTestSupport.EN;
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
    }

    @Test
    void aNavigationAndATeaserAreRecorded() {
        PageManager pm = context.resourceResolver().adaptTo(PageManager.class);
        try (Shown.Recording r = Shown.start(100)) {
            Page home = pm.getPage(EN);
            // a navigation: the home page's children, and theirs
            for (Iterator<Page> it = home.listChildren(); it.hasNext();) {
                it.next().getTitle();
            }
            // a teaser
            pm.getPage(EN + "/adventures/bali-surf-camp").getTitle();
            // a breadcrumb
            pm.getPage(EN + "/faqs").getAbsoluteParent(2);
            assertTrue(r.paths().contains(EN + "/jcr:content"), r.paths().toString());
            assertTrue(r.paths().contains(EN + "/*"), r.paths().toString());
            assertTrue(r.paths().contains(EN + "/faqs/jcr:content"));
            assertTrue(r.paths().contains(EN + "/adventures/bali-surf-camp/jcr:content"));
            assertTrue(r.paths().contains("/content/wknd/us/jcr:content"));
            assertFalse(r.paths().contains(EN + "/adventures/*"), "only what was listed");
        }
    }

    @Test
    void anotherPagesContentReadWithoutThePageApi() {
        ShownResourceDecorator decorator = new ShownResourceDecorator();
        try (Shown.Recording r = Shown.start(100, EN + "/faqs")) {
            // a component of FAQs that reads a sibling's title through resources, and its own content
            Resource sibling = context.resourceResolver().getResource(EN + "/adventures/jcr:content");
            assertEquals(null, decorator.decorate(sibling), "nothing decorated");
            decorator.decorate(context.resourceResolver().getResource(EN + "/faqs/jcr:content"));
            decorator.decorate(context.resourceResolver().getResource(EN + "/adventures"));
            Shown.content("/content/dam/wknd/a.jpg/jcr:content/renditions/original");
            Shown.content(EN + "/magazine/jcr:content/root/container/title");
            Shown.content(EN + "/jcr:contentx/y");
            assertEquals(java.util.Set.of(EN + "/adventures/jcr:content", EN + "/magazine/jcr:content"), r.paths());
        }
        // outside a recording it does nothing
        assertEquals(null, decorator.decorate(context.resourceResolver().getResource(EN + "/adventures/jcr:content")));
    }

    private void findTagged(String basePath) {
        try {
            new TagManagerImpl(context.resourceResolver()).find(basePath, new String[] {"wknd:activity/surfing"});
        } catch (RuntimeException e) {
            // the mock resolver runs no queries: the recording is what counts here
        }
    }

    @Test
    void aSearchShowsWhereItLooks() {
        try (Shown.Recording r = Shown.start(100)) {
            findTagged(EN + "/adventures/");
            assertFalse(r.isUnknown());
            assertTrue(r.paths().contains(EN + "/adventures"), r.paths().toString());
        }
    }

    @Test
    void aSearchOfEverythingCannotBeToldInAdvance() {
        try (Shown.Recording r = Shown.start(100)) {
            context.resourceResolver().adaptTo(PageManager.class).getPage(EN);
            findTagged("/content");
            assertTrue(r.isUnknown());
            assertTrue(r.why().startsWith("content found by its tags in "), r.why());
        }
    }
}
