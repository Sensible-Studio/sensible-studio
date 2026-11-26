/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.BLUEPRINT;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.US;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.apache.sling.servlets.post.Modification;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.WCMException;

/** Live copy configurations written over Sling POST must name a blueprint the author can read. */
@ExtendWith(SlingContextExtension.class)
class MsmConfigGuardTest {

    private final SlingContext context = MsmTestSupport.newContext();
    private final MsmConfigGuard guard = new MsmConfigGuard();

    @BeforeEach
    void setUp() throws Exception {
        MsmTestSupport.setUp(context);
    }

    @Test
    void acceptsReadableBlueprintsAndRefusesOthers() {
        String cfg = US + "/jcr:content/cq:LiveSyncConfig";
        assertEquals(List.of(BLUEPRINT), MsmConfigGuard.masters(context.resourceResolver(), cfg + "/cq:master"));
        assertDoesNotThrow(() -> guard.process(context.request(), List.of(Modification.onModified(cfg + "/cq:master"))));
        // a copy of a whole live copy carries its configuration along
        assertEquals(List.of(BLUEPRINT), MsmConfigGuard.masters(context.resourceResolver(), US));

        context.create().resource("/content/mine", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/mine/jcr:content", "jcr:primaryType", "cq:PageContent");
        context.create().resource("/content/mine/jcr:content/cq:LiveSyncConfig", "jcr:primaryType", "cq:LiveCopy",
                "cq:master", "/content/secret/unreadable");
        assertThrows(WCMException.class, () -> guard.process(context.request(),
                List.of(Modification.onCreated("/content/mine/jcr:content/cq:LiveSyncConfig"))));
        assertThrows(WCMException.class, () -> guard.process(context.request(),
                List.of(Modification.onCopied("/content/x", "/content/mine"))));
    }

    @Test
    void configurationsInFoldersAreFound() {
        context.create().resource("/content/f", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/f/p", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/f/p/jcr:content", "jcr:primaryType", "cq:PageContent");
        context.create().resource("/content/f/p/jcr:content/cq:LiveSyncConfig", "jcr:primaryType", "cq:LiveCopy",
                "cq:master", "/content/secret/unreadable");
        assertThrows(WCMException.class, () -> guard.process(context.request(),
                List.of(Modification.onMoved("/content/g", "/content/f"))));
    }

    @Test
    void aRestoredUnreadableConfigurationIsDropped() throws Exception {
        context.create().resource("/content/mine", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/mine/jcr:content", "jcr:primaryType", "cq:PageContent",
                "jcr:mixinTypes", new String[] {"cq:LiveSync"});
        context.create().resource("/content/mine/jcr:content/cq:LiveSyncConfig", "jcr:primaryType", "cq:LiveCopy",
                "cq:master", "/content/secret/unreadable");
        context.create().resource("/content/other/jcr:content/cq:LiveSyncConfig", "jcr:primaryType", "cq:LiveCopy",
                "cq:master", BLUEPRINT);
        guard.process(context.request(), List.of(Modification.onRestore("/content/mine/jcr:content", "1.0"),
                Modification.onRestore("/content/other/jcr:content", "1.0")));
        assertNull(context.resourceResolver().getResource("/content/mine/jcr:content/cq:LiveSyncConfig"));
        assertNotNull(context.resourceResolver().getResource("/content/other/jcr:content/cq:LiveSyncConfig"));
    }
}
