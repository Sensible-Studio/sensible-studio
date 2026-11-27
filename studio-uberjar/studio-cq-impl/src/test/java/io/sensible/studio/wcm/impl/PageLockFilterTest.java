/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class PageLockFilterTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void a_locked_page_is_changed_by_its_owner_only() {
        context.create().resource("/content/site/en/jcr:content", Pages.PN_LOCK_OWNER, "ana");
        context.create().resource("/content/site/en/jcr:content/root/text");
        context.create().resource("/content/site/en/child/jcr:content");
        context.create().resource("/content/site/free/jcr:content");
        var rr = context.resourceResolver();
        // its components, a component that is not there yet, its properties, the page itself
        assertEquals("ana", PageLockFilter.lockedBy(rr, "/content/site/en/jcr:content/root/text", "dan"));
        assertEquals("ana", PageLockFilter.lockedBy(rr, "/content/site/en/jcr:content/root/new_1", "dan"));
        assertEquals("ana", PageLockFilter.lockedBy(rr, "/content/site/en/jcr:content", "dan"));
        assertEquals("ana", PageLockFilter.lockedBy(rr, "/content/site/en", "dan"));
        assertNull(PageLockFilter.lockedBy(rr, "/content/site/en/jcr:content/root/text", "ana"));
        // the pages below are pages of their own; so is a page nobody locked, and what is not a page
        assertNull(PageLockFilter.lockedBy(rr, "/content/site/en/child/jcr:content/root", "dan"));
        assertNull(PageLockFilter.lockedBy(rr, "/content/site/free/jcr:content/root", "dan"));
        assertNull(PageLockFilter.lockedBy(rr, "/content/site/nothing/here", "dan"));
        assertNull(PageLockFilter.lockedBy(rr, "/conf/site/en/jcr:content", "dan"));
    }

    @Test
    void says_who_changed_the_page_since_the_editor_read_it() {
        java.time.Instant t = java.time.Instant.parse("2026-11-27T10:00:00Z");
        assertEquals("ana", PageLockFilter.changedBy("2026-11-27T09:00:00Z", t, "ana", "dan"));
        // my own change, a page read after the change, a page nobody dated, a date that is none
        assertNull(PageLockFilter.changedBy("2026-11-27T09:00:00Z", t, "dan", "dan"));
        assertNull(PageLockFilter.changedBy("2026-11-27T10:00:00Z", t, "ana", "dan"));
        assertNull(PageLockFilter.changedBy("2026-11-27T09:00:00Z", null, null, "dan"));
        assertNull(PageLockFilter.changedBy("yesterday", t, "ana", "dan"));
    }
}
