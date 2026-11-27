/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PageRedirectFilterTest {

    @Test
    void a_page_sends_its_visitors_where_it_says() {
        assertEquals("/content/site/us/en.html", PageRedirectFilter.target("/content/site", "/content/site/us/en"));
        assertEquals("/content/site/us/en.html", PageRedirectFilter.target("/content/site", " /content/site/us/en.html "));
        assertEquals("/content/dam/site/a.pdf", PageRedirectFilter.target("/content/site", "/content/dam/site/a.pdf"));
        assertEquals("https://example.com/x", PageRedirectFilter.target("/content/site", "https://example.com/x"));
    }

    @Test
    void not_to_itself_and_not_to_what_is_no_address() {
        assertNull(PageRedirectFilter.target("/content/site", null));
        assertNull(PageRedirectFilter.target("/content/site", "  "));
        assertNull(PageRedirectFilter.target("/content/site", "/content/site"));
        assertNull(PageRedirectFilter.target("/content/site", "/content/site.html"));
        assertNull(PageRedirectFilter.target("/content/site", "javascript:alert(1)"));
        assertNull(PageRedirectFilter.target("/content/site", "/apps/x"));
    }
}
