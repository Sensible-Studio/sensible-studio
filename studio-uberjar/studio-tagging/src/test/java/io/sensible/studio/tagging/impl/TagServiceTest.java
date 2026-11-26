/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.tagging.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class TagServiceTest {

    private static final String ROOT = TagService.ROOT;
    private static final String PAGE = "/content/wknd/en/hiking-trip";
    private static final String ASSET = "/content/dam/wknd/trail.jpg";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private TagService tags;

    private void tag(String path, String title) {
        context.create().resource(path, "jcr:primaryType", "cq:Tag", "jcr:title", title);
    }

    @BeforeEach
    void setUp() {
        context.create().resource(ROOT, "jcr:primaryType", "sling:Folder", "languages", new String[] {"en", "de"});
        tag(ROOT + "/wknd", "WKND");
        tag(ROOT + "/wknd/activity", "Activity");
        tag(ROOT + "/wknd/activity/hiking", "Hiking");
        tag(ROOT + "/wknd/activity/hiking/alpine", "Alpine");
        tag(ROOT + "/wknd/activity/surfing", "Surfing");
        tag(ROOT + "/wknd/season", "Season");
        tag(ROOT + "/default", "Standard Tags");
        tag(ROOT + "/default/outdoor", "Outdoor");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:title", "Hiking Trip",
                "cq:tags", new String[] {"wknd:activity/hiking", "wknd:season", "outdoor"});
        context.create().resource(ASSET, "jcr:primaryType", "dam:Asset");
        context.create().resource(ASSET + "/jcr:content/metadata", "dc:title", "Trail",
                "cq:tags", new String[] {ROOT + "/wknd/activity/hiking/alpine"});
        tags = new TagService(context.resourceResolver());
    }

    private String[] tagsOf(String path) {
        return context.resourceResolver().getResource(path).getValueMap().get("cq:tags", String[].class);
    }

    @Test
    void tagIds() {
        assertEquals("wknd:", TagService.idOf(ROOT + "/wknd"));
        assertEquals("wknd:activity/hiking", TagService.idOf(ROOT + "/wknd/activity/hiking"));
        assertEquals("default:outdoor", TagService.normalize("outdoor"));
        assertEquals("wknd:activity", TagService.normalize(ROOT + "/wknd/activity/"));
        assertNull(TagService.normalize("/content/other"));
        assertTrue(TagService.matches("wknd:activity/hiking/alpine", "wknd:activity/hiking"));
        assertFalse(TagService.matches("wknd:activity/hikingx", "wknd:activity/hiking"));
        assertTrue(TagService.matches("wknd:season", "wknd:"));
        assertEquals("wknd:x", TagService.join("wknd:", "/x"));
        assertEquals("wknd:b/x", TagService.join("wknd:b", "/x"));
        assertEquals("hunting-fishing", TagService.nameFrom("Hunting & Fishing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void browsesTheTree() {
        Map<String, Object> root = tags.children(ROOT);
        List<Map<String, Object>> namespaces = (List<Map<String, Object>>) root.get("children");
        assertEquals(List.of("wknd", "default"), namespaces.stream().map(m -> m.get("name")).collect(Collectors.toList()));
        assertEquals(true, namespaces.get(0).get("namespace"));

        Map<String, Object> activity = tags.children(ROOT + "/wknd/activity");
        assertEquals(3, ((List<?>) activity.get("trail")).size());
        Map<String, Object> hiking = ((List<Map<String, Object>>) activity.get("children")).get(0);
        assertEquals("wknd:activity/hiking", hiking.get("tagID"));
        assertEquals(true, hiking.get("hasChildren"));
        assertEquals(false, hiking.get("namespace"));
    }

    @Test
    void findsReferencesInAnyForm() {
        List<Map<String, Object>> refs = tags.references(ROOT + "/wknd/activity/hiking");
        assertEquals(2, refs.size());
        assertEquals("page", refs.get(0).get("kind"));
        assertEquals("Hiking Trip", refs.get(0).get("title"));
        assertEquals("asset", refs.get(1).get("kind"));
        assertEquals("Trail", refs.get(1).get("title"));
        assertEquals(1, tags.references(ROOT + "/default/outdoor").size());
    }

    @Test
    void createsAndEditsTags() throws Exception {
        Map<String, Object> created = tags.create(ROOT + "/wknd/activity", "Hunting & Fishing", "", "With a licence");
        assertEquals(ROOT + "/wknd/activity/hunting-fishing", created.get("path"));
        assertThrows(IllegalArgumentException.class, () -> tags.create(ROOT + "/wknd/activity", "Again", "hunting-fishing", null));
        assertThrows(IllegalArgumentException.class, () -> tags.create(ROOT + "/wknd", "Bad", "a/b", null));

        Map<String, Object> edited = tags.update(ROOT + "/wknd/activity/hiking", "Hiking & trekking", "", Map.of("de", "Wandern"));
        assertEquals("Hiking & trekking", edited.get("title"));
        assertEquals(Map.of("de", "Wandern"), edited.get("localized"));
        assertEquals(List.of("en", "de"), edited.get("languages"));
        edited = tags.update(ROOT + "/wknd/activity/hiking", null, null, Map.of("de", ""));
        assertEquals(Map.of(), edited.get("localized"));
    }

    @Test
    void movingRewritesReferences() throws Exception {
        Map<String, Object> moved = tags.move(ROOT + "/wknd/activity/hiking", ROOT + "/default", "trekking");
        assertEquals(ROOT + "/default/trekking", moved.get("path"));
        assertEquals(2, moved.get("adjusted"));
        assertNotNull(context.resourceResolver().getResource(ROOT + "/default/trekking/alpine"));
        assertArrayEquals(new String[] {"default:trekking", "wknd:season", "outdoor"}, tagsOf(PAGE + "/jcr:content"));
        assertArrayEquals(new String[] {"default:trekking/alpine"}, tagsOf(ASSET + "/jcr:content/metadata"));
        assertThrows(IllegalArgumentException.class, () -> tags.move(ROOT + "/wknd/activity", ROOT + "/wknd/activity/surfing", null));
    }

    @Test
    void mergingRetagsAndRemovesTheSource() throws Exception {
        tags.merge(ROOT + "/wknd/activity/hiking", ROOT + "/default/outdoor");
        ResourceResolver rr = context.resourceResolver();
        assertNull(rr.getResource(ROOT + "/wknd/activity/hiking"));
        assertNotNull(rr.getResource(ROOT + "/default/outdoor/alpine"));
        // the page had both: merged into one value
        assertArrayEquals(new String[] {"default:outdoor", "wknd:season", "outdoor"}, tagsOf(PAGE + "/jcr:content"));
        assertArrayEquals(new String[] {"default:outdoor/alpine"}, tagsOf(ASSET + "/jcr:content/metadata"));
    }

    @Test
    void deletingRemovesReferences() throws Exception {
        Map<String, Object> result = tags.delete(Collections.singletonList(ROOT + "/wknd/activity"));
        assertEquals(2, result.get("adjusted"));
        assertArrayEquals(new String[] {"wknd:season", "outdoor"}, tagsOf(PAGE + "/jcr:content"));
        assertArrayEquals(new String[0], tagsOf(ASSET + "/jcr:content/metadata"));
        assertNull(context.resourceResolver().getResource(ROOT + "/wknd/activity"));
        assertThrows(IllegalArgumentException.class, () -> tags.delete(List.of(ROOT)));
    }
}
