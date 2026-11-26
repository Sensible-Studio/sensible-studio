/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class ReferencesTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void anImageSetNeedsItsPictures() {
        context.create().resource("/content/dam/wknd/trip/jcr:content", "studio:imageSet", true,
                "studio:members", new String[] {"/content/dam/wknd/a.jpg", "/content/dam/wknd/b.jpg", "/etc/not-media"});
        context.create().resource("/content/dam/wknd/a.jpg/jcr:content", "jcr:title", "A");
        Set<String> needed = new LinkedHashSet<>();
        References.setMembers(context.resourceResolver().getResource("/content/dam/wknd/trip"), needed);
        assertEquals(new LinkedHashSet<>(Arrays.asList("/content/dam/wknd/a.jpg", "/content/dam/wknd/b.jpg")), needed);

        // a picture, a missing file, nothing at all: no pictures needed
        needed.clear();
        References.setMembers(context.resourceResolver().getResource("/content/dam/wknd/a.jpg"), needed);
        References.setMembers(context.resourceResolver().getResource("/content/dam/wknd/gone"), needed);
        References.setMembers(null, needed);
        assertTrue(needed.isEmpty());
    }
}
