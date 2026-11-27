/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PageModifiedTest {

    @Test
    void a_change_of_a_pages_content_dates_the_page() {
        assertEquals("/content/site/en/jcr:content", PageModified.contentOf("/content/site/en/jcr:content/root/text"));
        assertEquals("/content/site/en/jcr:content", PageModified.contentOf("/content/site/en/jcr:content/root/text/text"));
        assertEquals("/content/site/en/jcr:content", PageModified.contentOf("/content/site/en/jcr:content/jcr:title"));
        assertEquals("/content/site/en/jcr:content", PageModified.contentOf("/content/site/en/jcr:content"));
    }

    @Test
    void not_what_only_records_publishing_a_lock_or_the_date_itself_and_not_media() {
        assertNull(PageModified.contentOf("/content/site/en/jcr:content/cq:lastReplicated"));
        assertNull(PageModified.contentOf("/content/site/en/jcr:content/cq:lastReplicationAction_preview"));
        assertNull(PageModified.contentOf("/content/site/en/jcr:content/cq:lastModified"));
        assertNull(PageModified.contentOf("/content/site/en/jcr:content/" + Pages.PN_LOCK_OWNER));
        assertNull(PageModified.contentOf("/content/dam/site/a.png/jcr:content/metadata"));
        assertNull(PageModified.contentOf("/content/site/en"));
        assertNull(PageModified.contentOf("/conf/site/settings/wcm/templates/page/jcr:content"));
    }
}
