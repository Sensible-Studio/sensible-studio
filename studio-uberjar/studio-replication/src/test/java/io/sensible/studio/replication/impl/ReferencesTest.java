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

    @Test
    void aTemplateTheInstallationBroughtIsOnEveryInstance() {
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.add(java.util.Calendar.DAY_OF_YEAR, -3);
        java.util.Calendar later = java.util.Calendar.getInstance();
        context.create().resource("/conf/global/settings/wcm/templates/page", "jcr:createdBy", "sling-package-install", "jcr:created", then);
        context.create().resource("/conf/global/settings/wcm/templates/page/jcr:content", "jcr:title", "Content page");
        context.create().resource("/conf/site/settings/wcm/templates/changed", "jcr:createdBy", "sling-package-install", "jcr:created", then);
        context.create().resource("/conf/site/settings/wcm/templates/changed/jcr:content", "cq:lastModified", later);
        context.create().resource("/conf/site/settings/wcm/templates/mine", "jcr:createdBy", "ana", "jcr:created", then);
        context.create().resource("/content/site/page", "jcr:createdBy", "sling-package-install", "jcr:created", then);
        var rr = context.resourceResolver();
        assertTrue(References.cameWithTheInstallation(rr.getResource("/conf/global/settings/wcm/templates/page")));
        // changed since, made by a person, not a configuration, not there
        assertTrue(!References.cameWithTheInstallation(rr.getResource("/conf/site/settings/wcm/templates/changed")));
        assertTrue(!References.cameWithTheInstallation(rr.getResource("/conf/site/settings/wcm/templates/mine")));
        assertTrue(!References.cameWithTheInstallation(rr.getResource("/content/site/page")));
        assertTrue(!References.cameWithTheInstallation(null));
    }
}
