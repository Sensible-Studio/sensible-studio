/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class ErrorPageServletTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void the_nearest_error_page_above_the_address_answers() {
        context.create().resource("/content/wknd/us/en/errors/404/jcr:content");
        context.create().resource("/content/wknd/us/en/magazine");
        assertEquals("/content/wknd/us/en/errors/404",
                ErrorPageServlet.errorPage(context.resourceResolver(), "/content/wknd/us/en/magazine/nope.html", "404"));
        assertEquals("/content/wknd/us/en/errors/404",
                ErrorPageServlet.errorPage(context.resourceResolver(), "/content/wknd/us/en/a/b/c", "404"));
        // the live site's short addresses
        assertEquals("/content/wknd/us/en/errors/404",
                ErrorPageServlet.errorPage(context.resourceResolver(), "/wknd/us/en/nope.html", "404"));
    }

    @Test
    void a_site_without_one_and_another_site_get_none() {
        context.create().resource("/content/wknd/us/en/errors/404/jcr:content");
        assertNull(ErrorPageServlet.errorPage(context.resourceResolver(), "/content/wknd/ca/fr/nope.html", "404"));
        assertNull(ErrorPageServlet.errorPage(context.resourceResolver(), "/content/other/nope.html", "404"));
        assertNull(ErrorPageServlet.errorPage(context.resourceResolver(), "/bin/nope", "404"));
        // a folder named errors/404 that is not a page
        context.create().resource("/content/x/errors/404");
        assertNull(ErrorPageServlet.errorPage(context.resourceResolver(), "/content/x/y.html", "404"));
    }

    @Test
    void a_page_is_wanted_for_html_and_bare_addresses_only() {
        assertTrue(ErrorPageServlet.wantsPage("/content/a/b.html", "html"));
        assertTrue(ErrorPageServlet.wantsPage("/content/a/b", null));
        assertTrue(ErrorPageServlet.wantsPage("/content/a/b.html", null));
        assertFalse(ErrorPageServlet.wantsPage("/content/a/b.json", "json"));
        assertFalse(ErrorPageServlet.wantsPage("/content/a/b.png", null));
    }

    @Test
    void messages_are_escaped() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot;", ErrorPageServlet.html("<b> & \"x\""));
        assertEquals("a\\\"b\\\\c\\u000a", ErrorPageServlet.json("a\"b\\c\n"));
    }
}
