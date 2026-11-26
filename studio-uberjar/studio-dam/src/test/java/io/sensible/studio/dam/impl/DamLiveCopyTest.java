/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class DamLiveCopyTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:Folder");
        folder("/content/dam/brand", "Brand");
        folder("/content/dam/brand/logos", "Logos");
        asset("/content/dam/brand/logos/red.png", "Red logo", "RED");
        asset("/content/dam/brand/guide.pdf", "Guide", "PDF1");
        folder("/content/dam/markets", "Markets");
        resolver.commit();
    }

    private void folder(String path, String title) {
        context.create().resource(path, "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", title);
    }

    private void asset(String path, String title, String data) throws Exception {
        context.create().resource(path, "jcr:primaryType", "dam:Asset");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "dam:AssetContent", "cq:lastReplicated", Calendar.getInstance());
        context.create().resource(path + "/jcr:content/metadata", "jcr:primaryType", "nt:unstructured", "dc:title", title,
                "dc:description", "from the source");
        context.create().resource(path + "/jcr:content/renditions", "jcr:primaryType", "nt:folder");
        original(path, data);
        context.create().resource(path + "/jcr:content/cq:annotations/c1", "text", "a source comment");
    }

    private void original(String path, String data) throws Exception {
        Resource rends = resolver.getResource(path + "/jcr:content/renditions");
        Resource old = rends.getChild("original");
        if (old != null) {
            resolver.delete(old);
        }
        Resource f = resolver.create(rends, "original", Map.of("jcr:primaryType", "nt:file"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("jcr:primaryType", "nt:resource");
        c.put("jcr:data", new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
        Thread.sleep(5); // after the last rollout
        c.put("jcr:lastModified", Calendar.getInstance());
        resolver.create(f, "jcr:content", c);
    }

    private String text(String path) throws Exception {
        try (InputStream in = resolver.getResource(path).adaptTo(InputStream.class)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Resource r(String path) {
        return resolver.getResource(path);
    }

    private Map<String, Object> create() throws Exception {
        Map<String, Object> out = DamLiveCopy.create(resolver, r("/content/dam/brand"), r("/content/dam/markets"), "de", "Brand DE");
        resolver.commit();
        return out;
    }

    @Test
    void aLiveCopyIsTheSourceFolderWithMarkers() throws Exception {
        Map<String, Object> out = create();
        assertEquals("/content/dam/markets/de", out.get("path"));
        assertEquals(2, out.get("added"), "logos (with what is in it) and guide.pdf");
        Resource root = r("/content/dam/markets/de");
        assertEquals("/content/dam/brand", r("/content/dam/markets/de/jcr:content/cq:LiveSyncConfig").getValueMap().get("cq:master"));
        assertEquals("Brand DE", root.getChild("jcr:content").getValueMap().get("jcr:title"));
        assertEquals("root", DamLiveCopy.status(root));
        assertEquals("inherited", DamLiveCopy.status(r("/content/dam/markets/de/logos")));
        assertEquals("inherited", DamLiveCopy.status(r("/content/dam/markets/de/logos/red.png")));
        assertEquals("Red logo", r("/content/dam/markets/de/logos/red.png/jcr:content/metadata").getValueMap().get("dc:title"));
        assertEquals("RED", text("/content/dam/markets/de/logos/red.png/jcr:content/renditions/original/jcr:content"));
        assertNull(r("/content/dam/markets/de/logos/red.png/jcr:content/cq:annotations"), "comments stay with the source");
        assertNull(r("/content/dam/markets/de/logos/red.png/jcr:content").getValueMap().get("cq:lastReplicated"), "not published");
        assertNotNull(r("/content/dam/markets/de/logos/red.png/jcr:content").getValueMap().get(DamLiveCopy.PN_ROLLED_ORIGINAL));

        Map<String, Object> d = DamLiveCopy.describe(resolver, r("/content/dam/markets/de/logos/red.png"));
        assertEquals("/content/dam/brand/logos/red.png", d.get("source"));
        assertEquals("inherited", d.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> copies = (List<Map<String, Object>>) DamLiveCopy.describe(resolver, r("/content/dam/brand/logos/red.png")).get("liveCopies");
        assertEquals(1, copies.size());
        assertEquals("/content/dam/markets/de/logos/red.png", copies.get(0).get("path"));

        assertThrows(IllegalStateException.class,
                () -> DamLiveCopy.create(resolver, r("/content/dam/brand"), r("/content/dam/markets"), "de", null));
        assertThrows(IllegalArgumentException.class,
                () -> DamLiveCopy.create(resolver, r("/content/dam/brand"), r("/content/dam/brand/logos"), "inside", null));
    }

    @Test
    void aRolloutBringsChangesAndKeepsWhatIsLocal() throws Exception {
        create();
        // the live copy: one field changed there, a local file, a suspended one
        Resource copyRed = r("/content/dam/markets/de/logos/red.png");
        ModifiableValueMap md = copyRed.getChild("jcr:content/metadata").adaptTo(ModifiableValueMap.class);
        md.put("dc:description", "für Deutschland");
        DamLiveCopy.keepLocal(copyRed, List.of("dc:description"));
        asset("/content/dam/markets/de/logos/local.png", "Local", "LOCAL");
        DamLiveCopy.suspend(resolver, r("/content/dam/markets/de/guide.pdf"), true);
        resolver.commit();

        Thread.sleep(5);
        // the source changes: a field, the picture, a new file, a removed file
        r("/content/dam/brand/logos/red.png/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dc:title", "Red logo 2026");
        r("/content/dam/brand/logos/red.png/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dc:description", "new text");
        original("/content/dam/brand/logos/red.png", "RED2");
        asset("/content/dam/brand/logos/blue.png", "Blue", "BLUE");
        r("/content/dam/brand/guide.pdf/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dc:title", "Guide 2");
        // the console marks a file modified when its metadata is saved
        r("/content/dam/brand/guide.pdf/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:lastModified", Calendar.getInstance());
        r("/content/dam/brand/logos/red.png/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:lastModified", Calendar.getInstance());
        resolver.commit();
        Thread.sleep(5);

        Map<String, Object> out = DamLiveCopy.rollout(resolver, r("/content/dam/brand"));
        resolver.commit();
        assertEquals(1, out.get("added"), String.valueOf(out));
        assertEquals(1, out.get("updated"), String.valueOf(out));
        assertEquals("Red logo 2026", r("/content/dam/markets/de/logos/red.png/jcr:content/metadata").getValueMap().get("dc:title"));
        assertEquals("für Deutschland", r("/content/dam/markets/de/logos/red.png/jcr:content/metadata").getValueMap().get("dc:description"),
                "a field changed in the live copy stays");
        assertEquals("RED2", text("/content/dam/markets/de/logos/red.png/jcr:content/renditions/original/jcr:content"));
        assertNotNull(r("/content/dam/markets/de/logos/blue.png"));
        assertEquals("inherited", DamLiveCopy.status(r("/content/dam/markets/de/logos/blue.png")));
        assertNotNull(r("/content/dam/markets/de/logos/local.png"), "local files are not touched");
        assertEquals("Guide", r("/content/dam/markets/de/guide.pdf/jcr:content/metadata").getValueMap().get("dc:title"), "suspended");

        // a rollout with nothing new changes nothing
        Thread.sleep(5);
        Map<String, Object> again = DamLiveCopy.rollout(resolver, r("/content/dam/brand"));
        assertEquals(0, again.get("updated"));
        assertEquals(0, again.get("added"));

        // removed from the source: gone from the live copy; the field follows the source again
        resolver.delete(r("/content/dam/brand/logos/blue.png"));
        resolver.commit();
        DamLiveCopy.synchronize(resolver, r("/content/dam/markets/de"), false);
        assertNull(r("/content/dam/markets/de/logos/blue.png"));
        DamLiveCopy.inheritAgain(resolver, r("/content/dam/markets/de/logos/red.png"), "dc:description");
        resolver.commit();
        assertEquals("new text", r("/content/dam/markets/de/logos/red.png/jcr:content/metadata").getValueMap().get("dc:description"));

        // reset: the suspended file follows the source again
        DamLiveCopy.synchronize(resolver, r("/content/dam/markets/de"), true);
        resolver.commit();
        assertEquals("inherited", DamLiveCopy.status(r("/content/dam/markets/de/guide.pdf")));
        assertEquals("Guide 2", r("/content/dam/markets/de/guide.pdf/jcr:content/metadata").getValueMap().get("dc:title"));
    }

    @Test
    void pushOnModifyBringsTheSourcesChangesWithoutARollout() throws Exception {
        DamLiveCopy.create(resolver, r("/content/dam/brand"), r("/content/dam/markets"), "fr", "Brand FR", true);
        resolver.commit();
        assertTrue(DamLiveCopy.isPushOnModify(r("/content/dam/markets/fr")));
        assertEquals("admin".equals(resolver.getUserID()) ? "admin" : resolver.getUserID(),
                r("/content/dam/markets/fr/jcr:content/cq:LiveSyncConfig").getValueMap().get(DamLiveCopy.PN_PUSH_BY));
        // a plain live copy next to it is not pushed to
        create();
        Thread.sleep(5);
        r("/content/dam/brand/logos/red.png/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dc:title", "Pushed");
        r("/content/dam/brand/logos/red.png/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:lastModified", Calendar.getInstance());
        asset("/content/dam/brand/logos/green.png", "Green", "GREEN");
        resolver.commit();
        Thread.sleep(5);

        DamLiveCopyFollower follower = new DamLiveCopyFollower() {
            @Override
            boolean permitted(ResourceResolver service, String userId, String master, String root) {
                return true;
            }
        };
        follower.push(resolver, new java.util.TreeSet<>(List.of(
                "/content/dam/brand/logos/red.png/jcr:content/metadata",
                "/content/dam/brand/logos/green.png",
                "/content/dam/brand/logos/green.png/jcr:content/renditions/original")));
        assertEquals("Pushed", r("/content/dam/markets/fr/logos/red.png/jcr:content/metadata").getValueMap().get("dc:title"));
        assertNotNull(r("/content/dam/markets/fr/logos/green.png"));
        assertEquals("Red logo", r("/content/dam/markets/de/logos/red.png/jcr:content/metadata").getValueMap().get("dc:title"), "not pushed: no push on modify");
        assertNull(r("/content/dam/markets/de/logos/green.png"));

        // turned off: no more pushes
        DamLiveCopy.setPushOnModify(resolver, r("/content/dam/markets/fr"), false);
        resolver.commit();
        assertFalse(DamLiveCopy.isPushOnModify(r("/content/dam/markets/fr")));
        assertEquals(List.of("/content/dam/a", "/content/dam/b"), DamLiveCopyFollower.outermost(new java.util.TreeSet<>(
                List.of("/content/dam/a", "/content/dam/a/x.png", "/content/dam/b"))));
        assertEquals("/content/dam/a/x.png", DamLiveCopyFollower.itemOf("/content/dam/a/x.png/jcr:content/renditions/original"));
    }

    @Test
    void aSingleFileCanBeALiveCopy() throws Exception {
        Map<String, Object> out = DamLiveCopy.create(resolver, r("/content/dam/brand/logos/red.png"), r("/content/dam/markets"), "red.png", null, false);
        resolver.commit();
        assertEquals("/content/dam/markets/red.png", out.get("path"));
        Resource copy = r("/content/dam/markets/red.png");
        assertEquals("root", DamLiveCopy.status(copy));
        assertEquals("RED", text("/content/dam/markets/red.png/jcr:content/renditions/original/jcr:content"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> copies = (List<Map<String, Object>>) DamLiveCopy.describe(resolver, r("/content/dam/brand/logos/red.png")).get("liveCopies");
        assertEquals("/content/dam/markets/red.png", copies.get(0).get("path"), "found through its folder's list");

        Thread.sleep(5);
        r("/content/dam/brand/logos/red.png/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dc:title", "Red 2");
        r("/content/dam/brand/logos/red.png/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:lastModified", Calendar.getInstance());
        resolver.commit();
        Thread.sleep(5);
        Map<String, Object> rolled = DamLiveCopy.rollout(resolver, r("/content/dam/brand/logos/red.png"));
        resolver.commit();
        assertEquals(1, rolled.get("updated"), String.valueOf(rolled));
        assertEquals("Red 2", r("/content/dam/markets/red.png/jcr:content/metadata").getValueMap().get("dc:title"));
        assertNotNull(r("/content/dam/markets/red.png/jcr:content/cq:LiveSyncConfig"), "the configuration survives a rollout");

        DamLiveCopy.detach(resolver, copy);
        resolver.commit();
        assertNull(DamLiveCopy.status(r("/content/dam/markets/red.png")));
        assertNull(r("/content/dam/markets/jcr:content").getValueMap().get(DamLiveCopy.PN_FILE_ROOTS));
    }

    @Test
    void aDetachedLiveCopyIsOrdinaryFolders() throws Exception {
        create();
        DamLiveCopy.detach(resolver, r("/content/dam/markets/de/logos"));
        resolver.commit();
        assertNull(r("/content/dam/markets/de/jcr:content/cq:LiveSyncConfig"));
        assertNull(DamLiveCopy.status(r("/content/dam/markets/de")));
        assertNull(DamLiveCopy.status(r("/content/dam/markets/de/logos/red.png")));
        assertFalse(DamLiveCopy.hasMixin(r("/content/dam/markets/de/logos/red.png/jcr:content"), DamLiveCopy.MIX_RELATIONSHIP));
        assertTrue(((List<?>) DamLiveCopy.describe(resolver, r("/content/dam/brand")).get("liveCopies")).isEmpty());
        assertArrayEquals(new byte[] {'R', 'E', 'D'},
                text("/content/dam/markets/de/logos/red.png/jcr:content/renditions/original/jcr:content").getBytes(StandardCharsets.UTF_8));
    }
}
