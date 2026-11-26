/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class DamHousekeepingTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private static String id(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    void sendsTheBlobsAssetsReferTo() throws Exception {
        // other tests deliver originals on demand, which marks their blobs in use in this static list
        DamHousekeeping.forgetInUse();
        ResourceResolver resolver = context.resourceResolver();
        FakeDamClient dam = new FakeDamClient();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        // no asset refers to a blob yet: nothing is sent
        Map<String, Object> out = DamHousekeeping.gc(resolver, dam, true);
        assertEquals("no asset refers to a blob", out.get("skipped"));
        assertNull(dam.gcKeep);

        context.create().resource("/content/dam/a.jpg/jcr:content", "studio:blobId", id('a'));
        context.create().resource("/content/dam/f/b.jpg/jcr:content", "studio:blobId", id('b'));
        context.create().resource("/content/dam/f/c.jpg/jcr:content", "studio:blobId", "not-a-blob");
        // a rendition's own properties are not looked at
        context.create().resource("/content/dam/f/b.jpg/jcr:content/renditions/x/jcr:content", "studio:blobId", id('d'));
        DamHousekeeping.inUse(id('e'));

        out = DamHousekeeping.gc(resolver, dam, true);
        assertEquals(2L, out.get("referenced"));
        assertEquals(new TreeSet<>(Set.of(id('a'), id('b'), id('e'))), dam.gcKeep);
        assertEquals(2L, ((Map<?, ?>) out.get("blobs")).get("removed"));
    }
}
