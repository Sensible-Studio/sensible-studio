/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class DamDownloadsTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/trip", "jcr:primaryType", "sling:Folder");
        file("/content/dam/trip/a.jpg/jcr:content/renditions/original", "AAA");
        file("/content/dam/trip/b.jpg/jcr:content/renditions/original", "BBBB");
        resolver.commit();
    }

    private void file(String path, String text) throws Exception {
        Resource parent = context.create().resource(path.substring(0, path.lastIndexOf('/')));
        Resource f = resolver.create(parent, path.substring(path.lastIndexOf('/') + 1), Map.of("jcr:primaryType", "nt:file"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("jcr:primaryType", "nt:resource");
        c.put("jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        c.put("jcr:mimeType", "image/jpeg");
        resolver.create(f, "jcr:content", c);
    }

    private static List<String[]> entries() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"trip/a.jpg", "/content/dam/trip/a.jpg/jcr:content/renditions/original"});
        l.add(new String[] {"trip/b.jpg", "/content/dam/trip/b.jpg/jcr:content/renditions/original"});
        l.add(new String[] {"trip/gone.jpg", "/content/dam/trip/gone.jpg/jcr:content/renditions/original"});
        return l;
    }

    @Test
    void anArchiveIsPreparedAndKeptForItsOwner() throws Exception {
        String id = DamDownloads.create(resolver, "alice", "trip.zip", entries());
        Map<String, Object> queued = DamDownloads.list(resolver, "alice").get(0);
        assertEquals("queued", queued.get("status"));
        assertEquals(3L, queued.get("files"));
        assertTrue(DamDownloads.list(resolver, "bob").isEmpty(), "nobody else sees it");

        DamDownloads.build(resolver, id);
        Map<String, Object> ready = DamDownloads.list(resolver, "alice").get(0);
        assertEquals("ready", ready.get("status"), String.valueOf(ready));
        assertEquals(3L, ready.get("done"));
        assertTrue((Long) ready.get("size") > 0);
        assertNull(resolver.getResource(DamDownloads.ROOT + "/" + id).getValueMap().get("studio:entries"), "the list goes once built");

        Resource archive = resolver.getResource(DamDownloads.ROOT + "/" + id + "/" + DamDownloads.ARCHIVE + "/jcr:content");
        assertNotNull(archive);
        Map<String, String> files = new LinkedHashMap<>();
        try (InputStream in = archive.adaptTo(InputStream.class); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(Map.of("trip/a.jpg", "AAA", "trip/b.jpg", "BBBB"), files, "a file removed since is left out");

        assertThrows(IllegalArgumentException.class, () -> DamDownloads.remove(resolver, "bob", id));
        DamDownloads.remove(resolver, "alice", id);
        assertTrue(DamDownloads.list(resolver, "alice").isEmpty());
    }

    @Test
    void expiredDownloadsGoAndAPersonKeepsAtMostTwenty() throws Exception {
        for (int i = 0; i < DamDownloads.MAX_PER_USER + 3; i++) {
            DamDownloads.create(resolver, "alice", "n" + i + ".zip", entries());
        }
        List<Map<String, Object>> mine = DamDownloads.list(resolver, "alice");
        assertEquals(DamDownloads.MAX_PER_USER, mine.size());
        assertTrue(mine.stream().noneMatch(m -> Arrays.asList("n0.zip", "n1.zip", "n2.zip").contains(m.get("name"))), "the oldest went");

        Calendar later = Calendar.getInstance();
        later.add(Calendar.DAY_OF_MONTH, DamDownloads.KEEP_DAYS + 1);
        assertEquals(DamDownloads.MAX_PER_USER, DamDownloads.purge(resolver, later));
        assertTrue(DamDownloads.list(resolver, "alice").isEmpty());
    }
}
