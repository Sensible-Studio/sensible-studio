/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class RootHomeFilterTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void the_only_site_is_home() {
        context.create().resource("/content/wknd/jcr:content", "jcr:title", "WKND Site");
        context.create().resource("/content/wknd/us/jcr:content");
        context.create().resource("/content/usergenerated/x");
        Map<String, String> sites = RootHomeFilter.sites(context.resourceResolver());
        assertEquals("{/content/wknd=WKND Site}", sites.toString());
        assertEquals("/content/wknd.html", RootHomeFilter.target("", sites));
    }

    @Test
    void several_sites_are_listed_unless_a_home_is_configured() {
        context.create().resource("/content/wknd/jcr:content", "jcr:title", "WKND <Site>");
        context.create().resource("/content/wknd/us/jcr:content");
        context.create().resource("/content/brand2/jcr:content");
        context.create().resource("/content/brand2/en/jcr:content");
        context.create().resource("/content/forms/jcr:content");
        context.create().resource("/content/forms/wknd/jcr:content");
        context.create().resource("/content/hidden/jcr:content", "hideInNav", true);
        context.create().resource("/content/hidden/en/jcr:content");
        context.create().resource("/content/empty/jcr:content");
        Map<String, String> sites = RootHomeFilter.sites(context.resourceResolver());
        assertEquals(2, sites.size());
        assertNull(RootHomeFilter.target("", sites));
        assertEquals("/content/wknd/us/en.html", RootHomeFilter.target("/content/wknd/us/en", sites));
        assertEquals("https://example.com/", RootHomeFilter.target("https://example.com/", sites));
        String html = RootHomeFilter.list("", sites);
        assertTrue(html.contains("<a href=\"/content/wknd.html\">WKND &lt;Site&gt;</a>"));
        assertTrue(html.contains("<a href=\"/content/brand2.html\">brand2</a>"));
    }

    @Test
    void no_site_leaves_the_answer_alone() {
        assertNull(RootHomeFilter.target("", RootHomeFilter.sites(context.resourceResolver())));
    }

    @Test
    void a_site_of_one_page_is_home_when_there_is_no_other_and_an_empty_instance_says_so() {
        assertTrue(RootHomeFilter.list("", RootHomeFilter.sites(context.resourceResolver())).contains("Nothing is published here yet"));
        context.create().resource("/content/shop/jcr:content", "jcr:title", "Shop", "sling:resourceType", "studio/starter/components/page");
        context.create().resource("/content/holder/jcr:content", "jcr:title", "Holds things");
        Map<String, String> sites = RootHomeFilter.sites(context.resourceResolver());
        assertEquals("{/content/shop=Shop}", sites.toString());
        assertEquals("/content/shop.html", RootHomeFilter.target("", sites));
    }
}
