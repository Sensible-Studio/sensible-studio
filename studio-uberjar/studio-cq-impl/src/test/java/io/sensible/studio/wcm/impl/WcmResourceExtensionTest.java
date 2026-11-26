/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.scripting.sightly.render.AbstractRuntimeObjectModel;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class WcmResourceExtensionTest {

    private final SlingContext context = WcmTestSupport.newContext();
    private final AbstractRuntimeObjectModel model = new AbstractRuntimeObjectModel() { };

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
    }

    private Map<String, Object> opts(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void buildsPathsLikeSling() {
        Resource current = context.resourceResolver().getResource(EN + "/jcr:content/root");
        assertEquals(EN + "/jcr:content/root/container", WcmResourceExtension.buildPath("container", opts(), model, current));
        assertEquals("/content/xf/master/jcr:content",
                WcmResourceExtension.buildPath("", opts("path", "/content/xf/master/jcr:content"), model, current));
        assertEquals("/a/b/c", WcmResourceExtension.buildPath("b", opts("prependPath", "a", "appendPath", "c"), model, current));
        assertNull(WcmResourceExtension.buildPath("", opts(), model, current));
    }

    @Test
    void sameResourceWithAnotherTypeIsNotRecursion() {
        Resource teaser = context.resourceResolver().getResource(EN + "/jcr:content/root/container/title");
        context.currentResource(teaser);
        Resource delegate = new ResourceWrapper(teaser) {
            @Override
            public String getResourceType() {
                return "wknd/components/image";
            }
        };
        RequestDispatcherOptions none = new RequestDispatcherOptions();
        assertFalse(WcmResourceExtension.isRecursion(context.request(), delegate, none), "image delegate of a teaser");
        assertTrue(WcmResourceExtension.isRecursion(context.request(), teaser, none), "the resource itself");
        RequestDispatcherOptions sel = new RequestDispatcherOptions();
        sel.setAddSelectors("content");
        assertFalse(WcmResourceExtension.isRecursion(context.request(), teaser, sel), "other selectors");
    }
}
