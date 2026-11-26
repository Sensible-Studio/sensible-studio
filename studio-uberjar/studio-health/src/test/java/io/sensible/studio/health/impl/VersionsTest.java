/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class VersionsTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private static int count(VersionHistory h) throws Exception {
        int n = -1; // minus the root version
        for (javax.jcr.version.VersionIterator it = h.getAllVersions(); it.hasNext(); it.nextVersion()) {
            n++;
        }
        return n;
    }

    @Test
    void keepsTheNewestVersionsAndTheLabelledOnes() throws Exception {
        Session session = context.resourceResolver().adaptTo(Session.class);
        Node page = session.getRootNode().addNode("content", "nt:unstructured").addNode("page", "nt:unstructured");
        page.addMixin("mix:versionable");
        session.save();
        VersionManager vm = session.getWorkspace().getVersionManager();
        String first = null;
        for (int i = 0; i < 8; i++) {
            page.setProperty("n", i);
            session.save();
            javax.jcr.version.Version v = vm.checkpoint(page.getPath());
            if (i == 0) {
                first = v.getName();
            }
        }
        VersionHistory history = vm.getVersionHistory(page.getPath());
        history.addVersionLabel(first, "launch", false);
        assertEquals(8, count(history));

        // keepDays 0: only the newest 3 stay, plus the labelled one
        Versions.Result r = Versions.purge(session, Collections.singletonList("/content"), 3, 0);
        session.save();
        assertEquals(1, r.nodes);
        assertEquals(8, r.versions);
        assertEquals(4, r.removed);
        assertEquals(4, count(history));
        assertTrue(history.hasVersionLabel("launch"), "the labelled version stays");

        // everything younger than 30 days stays, whatever keep says
        r = Versions.purge(session, Collections.singletonList("/content"), 1, 30);
        assertEquals(0, r.removed);
    }
}
