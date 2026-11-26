/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class TargetingTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;
    private static final String CONTAINER = "/content/site/en/jcr:content/root/container";

    @BeforeEach
    void setUp() throws Exception {
        session = context.resourceResolver().adaptTo(Session.class);
        Node en = session.getRootNode().addNode("content", "sling:Folder").addNode("site", "cq:Page").addNode("en", "cq:Page");
        Node jc = en.addNode("jcr:content", "cq:PageContent");
        jc.setProperty("cq:conf", "/conf/site");
        Node container = jc.addNode("root", "nt:unstructured").addNode("container", "nt:unstructured");
        Node title = container.addNode("title", "nt:unstructured");
        title.setProperty("sling:resourceType", "site/components/title");
        title.setProperty("jcr:title", "Hello");
        title.addNode("cq:responsive", "nt:unstructured").addNode("default", "nt:unstructured").setProperty("width", "6");
        container.addNode("text", "nt:unstructured").setProperty("sling:resourceType", "site/components/text");
        session.getRootNode().addNode("conf", "sling:Folder").addNode("site", "sling:Folder");
        session.save();
    }

    private List<String> names(String parent) throws Exception {
        List<String> out = new ArrayList<>();
        for (NodeIterator it = session.getNode(parent).getNodes(); it.hasNext();) {
            out.add(it.nextNode().getName());
        }
        return out;
    }

    @Test
    void targetsAndUntargetsKeepingPlaceAndLayout() throws Exception {
        Node t = Targeting.target(session, CONTAINER + "/title");
        session.save();
        assertEquals(CONTAINER + "/title", t.getPath());
        assertEquals(Targeting.TARGET_TYPE, t.getProperty("sling:resourceType").getString());
        assertEquals("site/components/title", t.getNode("default").getProperty("sling:resourceType").getString());
        assertTrue(t.hasNode("cq:responsive"), "layout stays on the wrapper");
        assertFalse(t.getNode("default").hasNode("cq:responsive"));
        assertEquals(List.of("title", "text"), names(CONTAINER), "same position in the container");

        Targeting.addExperience(session, t.getPath(), "summer", "Summer visitors", "/conf/site/settings/wcm/segments/summer", null);
        session.save();
        Node exp = t.getNode("summer");
        assertEquals("Summer visitors", exp.getProperty("experienceTitle").getString());
        assertEquals("Hello", exp.getProperty("jcr:title").getString(), "the component's own title is untouched");
        assertEquals("/conf/site/settings/wcm/segments/summer", exp.getProperty("cq:segment").getString());
        List<Map<String, Object>> exps = Targeting.experiences(t);
        assertEquals(2, exps.size());
        assertEquals("default", exps.get(0).get("name"));
        assertEquals("summer", exps.get(1).get("name"));

        exp.setProperty("jcr:title", "Summer sale");
        Node back = Targeting.untarget(session, t.getPath(), "summer");
        session.save();
        assertEquals(CONTAINER + "/title", back.getPath());
        assertEquals("site/components/title", back.getProperty("sling:resourceType").getString());
        assertEquals("Summer sale", back.getProperty("jcr:title").getString());
        assertFalse(back.hasProperty("cq:segment"));
        assertFalse(back.hasProperty("experienceTitle"));
        assertTrue(back.hasNode("cq:responsive"));
        assertEquals(List.of("title", "text"), names(CONTAINER));
    }

    @Test
    void segmentsLiveUnderTheSiteConfiguration() throws Exception {
        assertEquals("/conf/site", Segments.confOf(context.resourceResolver(), "/content/site/en"));
        Map<String, Object> cmp = new java.util.LinkedHashMap<>(Map.of("type", "comparison", "property", "profile/age", "operator", "greater-than", "value", "30", "dataType", "number"));
        Map<String, Object> expr = new java.util.LinkedHashMap<>(Map.of("type", "and", "items", List.of(cmp)));
        Segments.Segment s = Segments.save(session, "/conf/site", "over30", "Over thirty", null, 5L, expr);
        session.save();
        assertEquals("/conf/site/settings/wcm/segments/over30", s.path);
        assertEquals(Segments.SEGMENT_PAGE_TYPE, session.getNode(s.path + "/jcr:content").getProperty("sling:resourceType").getString());
        // laid out as AEM lays traits out
        Node traits = session.getNode(s.path + "/jcr:content/traits");
        assertEquals(Segments.LOGIC_AND, traits.getProperty("sling:resourceType").getString());
        Node item = traits.getNode("andpar/item_0");
        assertEquals(Segments.PROPERTY_VALUE, item.getProperty("sling:resourceType").getString());
        assertEquals("profile/age", item.getNode("left").getProperty("property").getString());
        assertEquals("30", item.getNode("right").getProperty("value").getString());
        List<Segments.Segment> list = Segments.forPage(context.resourceResolver(), "/content/site/en");
        assertEquals(1, list.size());
        assertEquals("Over thirty", list.get(0).title);
        assertEquals(5L, list.get(0).boost);
        assertEquals("and", list.get(0).expression.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) ((List<Object>) list.get(0).expression.get("items")).get(0);
        assertEquals("greater-than", back.get("operator"));
        assertEquals("30", back.get("value"));
        Segments.remove(session, s.path);
        session.save();
        assertNull(context.resourceResolver().getResource(s.path));
    }

    @Test
    void splitsTheVisitorsOfAnAbTest() throws Exception {
        String path = CONTAINER + "/title";
        Targeting.target(session, path);
        Targeting.addExperience(session, path, "b", "Variant B", null, null);
        session.save();
        Node t = session.getNode(path);
        assertEquals("targeting", Targeting.mode(t));

        java.util.Map<String, Object> shares = new java.util.LinkedHashMap<>();
        shares.put("default", 70L);
        shares.put("b", 20L);
        IllegalArgumentException sum = assertThrows(IllegalArgumentException.class, () -> Targeting.setSplit(session, path, "abtest", shares));
        assertTrue(sum.getMessage().contains("add up to 90%"));
        shares.put("b", 30.5);
        assertThrows(IllegalArgumentException.class, () -> Targeting.setSplit(session, path, "abtest", shares));
        shares.put("b", 30L);
        shares.put("ghost", 0L);
        assertThrows(IllegalArgumentException.class, () -> Targeting.setSplit(session, path, "abtest", shares));
        shares.remove("ghost");
        java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
        one.put("default", 100L);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Targeting.setSplit(session, path, "abtest", one)).getMessage().contains("at least two"));
        session.refresh(false);

        Targeting.setSplit(session, path, "abtest", shares);
        session.save();
        assertEquals("abtest", Targeting.mode(t));
        List<java.util.Map<String, Object>> exps = Targeting.experiences(t);
        assertEquals(70L, exps.get(0).get("share"));
        assertEquals(30L, exps.get(1).get("share"));

        // a new experience takes no visitors until it is given a share
        Targeting.addExperience(session, path, "c", "Variant C", null, "b");
        session.save();
        assertEquals(0L, Targeting.experiences(t).get(2).get("share"));

        // back to audiences: the shares are kept for the next test
        Targeting.setSplit(session, path, "targeting", null);
        session.save();
        assertEquals("targeting", Targeting.mode(t));
        assertEquals(70L, Targeting.experiences(t).get(0).get("share"));

        // the experience kept when targeting is removed is a plain component again
        Node kept = Targeting.untarget(session, path, "b");
        session.save();
        assertFalse(kept.hasProperty(Targeting.PROP_SHARE));
    }
}
