/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class AssetOriginalServletTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private void asset(String path, String mime, boolean withOriginal) {
        context.create().resource(path, "jcr:primaryType", "dam:Asset");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "dam:AssetContent");
        context.create().resource(path + "/jcr:content/metadata", "dc:format", mime);
        if (withOriginal) {
            context.create().resource(path + "/jcr:content/renditions/original", "jcr:primaryType", "nt:file");
            context.create().resource(path + "/jcr:content/renditions/original/jcr:content", "jcr:primaryType", "nt:resource", "jcr:mimeType", mime);
        }
    }

    @Test
    void theAssetsUrlIsItsOriginal() {
        asset("/content/dam/wknd/surf.jpg", "image/jpeg", true);
        asset("/content/dam/wknd/new.jpg", "image/jpeg", false);
        assertEquals("/content/dam/wknd/surf.jpg/jcr:content/renditions/original",
                AssetOriginalServlet.original(context.resourceResolver().getResource("/content/dam/wknd/surf.jpg")).getPath());
        // not processed yet, or no asset at all: nothing to stream
        assertNull(AssetOriginalServlet.original(context.resourceResolver().getResource("/content/dam/wknd/new.jpg")));
        assertNull(AssetOriginalServlet.original(null));
    }

    @Test
    void anSvgAtItsAssetUrlIsSandboxedToo() {
        asset("/content/dam/wknd/logo.svg", "image/svg+xml", true);
        asset("/content/dam/wknd/drawing.svg", "image/svg+xml", false);
        asset("/content/dam/wknd/surf.jpg", "image/jpeg", true);
        // the asset itself (its URL streams the original), the original's own node, an asset known by its metadata only
        assertTrue(ActiveContentFilter.active(ActiveContentFilter.mimeOf(context.resourceResolver().getResource("/content/dam/wknd/logo.svg"))));
        assertTrue(ActiveContentFilter.active(ActiveContentFilter.mimeOf(
                context.resourceResolver().getResource("/content/dam/wknd/logo.svg/jcr:content/renditions/original"))));
        assertTrue(ActiveContentFilter.active(ActiveContentFilter.mimeOf(context.resourceResolver().getResource("/content/dam/wknd/drawing.svg"))));
        assertFalse(ActiveContentFilter.active(ActiveContentFilter.mimeOf(context.resourceResolver().getResource("/content/dam/wknd/surf.jpg"))));
    }
}
