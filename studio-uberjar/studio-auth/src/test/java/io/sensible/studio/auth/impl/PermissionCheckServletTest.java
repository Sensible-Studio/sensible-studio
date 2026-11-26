package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PermissionCheckServletTest {

    @Test
    void thePathAskedAbout() {
        assertEquals("/content/brand2/en/club.html", PermissionCheckServlet.pathOf("/content/brand2/en/club.html"));
        assertEquals("/content/a/b.model.json", PermissionCheckServlet.pathOf("/content/a/b.model.json?x=1#top"));
        assertEquals("/content/a/b.html", PermissionCheckServlet.pathOf("/content/a/b.html#x?y"));
    }

    @Test
    void whatIsNotAPath() {
        for (String bad : new String[] {null, "", "content/a.html", "http://elsewhere/a.html", "/content/../etc.html", "/content/a/..",
                "/content\\a.html", "/content/a\n.html"}) {
            assertNull(PermissionCheckServlet.pathOf(bad), String.valueOf(bad));
        }
    }
}
