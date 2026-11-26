/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Annotate: the marks a note can have (box, ellipse, arrow, a line by hand, a moment of a video) and @mentions. */
@ExtendWith(SlingContextExtension.class)
class DamAnnotationsTest {

    private static final String ASSET = "/content/dam/wknd/surf.jpg";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp() {
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource(ASSET, "jcr:primaryType", "dam:Asset");
        context.create().resource(ASSET + "/jcr:content", "jcr:primaryType", "dam:AssetContent");
        context.create().resource(SlingRoot.ROOT, "jcr:primaryType", "sling:Folder");
    }

    /** Where the notifications are kept, made by the repoinit on an instance. */
    private static final class SlingRoot {
        static final String ROOT = DamNotifications.ROOT;
    }

    private Resource asset() {
        return context.resourceResolver().getResource(ASSET);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsTheShapeOfAMark() throws Exception {
        ResourceResolver r = context.resourceResolver();
        DamComments.add(r, asset(), "Box", new DamComments.Region(0.1, 0.1, 0.2, 0.2), null);
        DamComments.add(r, asset(), "Round", DamComments.Region.of("ellipse", null, 0.5, 0.5, 0.3, 0.2), "#1473e6");
        DamComments.Region arrow = DamComments.Region.of("arrow", "0.8,0.2 0.4,0.6", 0, 0, 0, 0);
        DamComments.add(r, asset(), "Look here", arrow, "#d7373f");
        DamComments.Region line = DamComments.Region.of("free", "0.1,0.9 0.2,0.85 0.3,0.9", 0, 0, 0, 0);
        line.time = 12.5;
        DamComments.add(r, asset(), "At 12 s", line, null);
        r.commit();

        List<Map<String, Object>> list = (List<Map<String, Object>>) DamComments.list(r, asset()).get("comments");
        assertEquals(4, list.size());
        Map<String, Object> box = (Map<String, Object>) list.get(0).get("region");
        assertNull(box.get("shape"), "a box is the default mark");
        assertEquals("ellipse", ((Map<String, Object>) list.get(1).get("region")).get("shape"));
        assertEquals("#1473e6", list.get(1).get("color"));
        Map<String, Object> a = (Map<String, Object>) list.get(2).get("region");
        assertEquals("arrow", a.get("shape"));
        // the points in order: the arrow points from the first to the last
        assertEquals(Arrays.asList(Arrays.asList(0.8, 0.2), Arrays.asList(0.4, 0.6)), a.get("points"));
        // its box is the one the points fit in
        assertEquals(0.4, (Double) a.get("x"), 1e-9);
        assertEquals(0.2, (Double) a.get("y"), 1e-9);
        assertEquals(0.4, (Double) a.get("w"), 0.0);
        assertEquals(0.4, (Double) a.get("h"), 1e-9);
        Map<String, Object> f = (Map<String, Object>) list.get(3).get("region");
        assertEquals("free", f.get("shape"));
        assertEquals(3, ((List<Object>) f.get("points")).size());
        assertEquals(12.5, (Double) f.get("time"), 1e-9);
    }

    @Test
    void refusesMarksOffThePicture() {
        assertThrows(IllegalArgumentException.class, () -> DamComments.Region.of("star", null, 0.1, 0.1, 0.1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> DamComments.Region.of("arrow", "0.5,0.5", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DamComments.Region.of("arrow", "0.5,0.5 1.5,0.5", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DamComments.Region.of("free", "0.5;0.5 0.6,0.6", 0, 0, 0, 0));
        assertThrows(NumberFormatException.class, () -> DamComments.Region.of("free", "a,b 0.6,0.6", 0, 0, 0, 0));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= DamComments.MAX_POINTS; i++) {
            many.append("0.5,0.5 ");
        }
        assertThrows(IllegalArgumentException.class, () -> DamComments.Region.of("free", many.toString(), 0, 0, 0, 0));
        DamComments.Region late = DamComments.Region.of("rect", null, 0.1, 0.1, 0.1, 0.1);
        late.time = -1.0;
        assertThrows(IllegalArgumentException.class, () -> DamComments.add(context.resourceResolver(), asset(), "x", late, null));
        // a straight line across has a box with some height
        DamComments.Region flat = DamComments.Region.of("arrow", "0.1,0.5 0.9,0.5", 0, 0, 0, 0);
        assertTrue(flat.valid());
    }

    @Test
    void findsTheMentions() {
        assertEquals(new ArrayList<>(List.of("anna", "bob.smith@example.com", "carl_2")),
                new ArrayList<>(DamNotifications.mentionsIn("@anna and @bob.smith@example.com, see @carl_2. Thanks @anna")));
        assertTrue(DamNotifications.mentionsIn("mail me at jane@example.com").isEmpty(), "an address is not a mention");
        assertTrue(DamNotifications.mentionsIn("no one").isEmpty());
        assertEquals(List.of("anna"), new ArrayList<>(DamNotifications.mentionsIn("(@anna)")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsEachPersonsNotificationsApart() throws Exception {
        ResourceResolver r = context.resourceResolver();
        DamNotifications.add(r, "anna", "bob", ASSET, "c1", "@anna is this the right crop?");
        DamNotifications.add(r, "carl", "bob", ASSET, "c1", "@carl too");
        r.commit();
        Resource anna = DamNotifications.folder(r, "anna", false);
        Resource carl = DamNotifications.folder(r, "carl", false);
        assertFalse(anna.getPath().contains("anna"), "no user id in a path");
        assertEquals(1, count(anna));
        assertEquals(1, count(carl));
        Resource n = anna.listChildren().next();
        assertEquals("anna", n.getValueMap().get("user", String.class));
        assertEquals("bob", n.getValueMap().get("from", String.class));
        assertEquals(ASSET, n.getValueMap().get("path", String.class));
        assertEquals(false, n.getValueMap().get("read", Boolean.class));
        assertNull(DamNotifications.folder(r, "dora", false));

        for (int i = 0; i < DamNotifications.MAX_PER_USER + 5; i++) {
            DamNotifications.add(r, "carl", "bob", ASSET, "c" + i, "again");
        }
        r.commit();
        assertEquals(DamNotifications.MAX_PER_USER, count(DamNotifications.folder(r, "carl", false)));
    }

    private static int count(Resource r) {
        int n = 0;
        for (Resource ignored : r.getChildren()) {
            n++;
        }
        return n;
    }
}
