/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnalyticsFilterTest {

    @Test
    void addresses_become_scripts_and_what_is_not_an_address_is_left_out() {
        assertEquals("<script src=\"https://assets.adobedtm.com/x/launch-1.min.js\" async></script>\n"
                        + "<script src=\"/etc.clientlibs/site/analytics.js?a=1&amp;b=2\" async></script>\n",
                AnalyticsFilter.tags(new String[] {" https://assets.adobedtm.com/x/launch-1.min.js ", "",
                        "javascript:alert(1)", "\"><script>alert(1)</script>", "https://a.b/c d.js",
                        "/etc.clientlibs/site/analytics.js?a=1&b=2", null}, true));
        assertEquals("<script src=\"//cdn.example.com/a.js\"></script>\n",
                AnalyticsFilter.tags(new String[] {"//cdn.example.com/a.js"}, false));
        assertEquals("", AnalyticsFilter.tags(null, true));
        assertEquals("<script src=\"https://a.b/x&quot;y.js\" async></script>\n",
                AnalyticsFilter.tags(new String[] {"https://a.b/x\"y.js"}, true));
    }

    @Test
    void they_go_at_the_end_of_the_head() {
        assertEquals("<html><head><title>t</title>S</head><body></body></html>",
                AnalyticsFilter.inject("<html><head><title>t</title></head><body></body></html>", "S"));
        assertEquals("<html>S<body class=\"x\"></body></html>", AnalyticsFilter.inject("<html><body class=\"x\"></body></html>", "S"));
        assertEquals("Shello", AnalyticsFilter.inject("hello", "S"));
    }
}
