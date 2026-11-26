/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Search Forms of the Pages console: the filters, who changes them, and the searches on page properties. */
@ExtendWith(SlingContextExtension.class)
class SiteSearchFormsTest {

    private static final String SITE = "/content/wknd";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp() {
        context.create().resource("/conf", "jcr:primaryType", "sling:Folder");
        page(SITE, "WKND");
        page(SITE + "/surf", "Surf Camp", "region", "Asia", "audience", new String[] {"family", "experts"}, "pageTitle", "Waves of Bali");
        page(SITE + "/ski", "Ski Touring", "region", "Europe", "audience", new String[] {"experts"});
        page(SITE + "/bike", "Cycling Tuscany", "region", "Europe");
        page(SITE + "/bike/day1", "Day one", "region", "Europe", "pageTitle", "Florence by bike");
    }

    private void page(String path, String title, Object... props) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        Object[] all = new Object[props.length + 6];
        all[0] = "jcr:primaryType";
        all[1] = "cq:PageContent";
        all[2] = "jcr:title";
        all[3] = title;
        all[4] = "cq:lastModified";
        all[5] = Calendar.getInstance();
        System.arraycopy(props, 0, all, 6, props.length);
        context.create().resource(path + "/jcr:content", all);
    }

    private ResourceResolver as(String user) {
        return new ResourceResolverWrapper(context.resourceResolver()) {
            @Override
            public String getUserID() {
                return user;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> facetTypes(Map<String, Object> form) {
        return ((List<Map<String, Object>>) form.get("facets")).stream().map(f -> (String) f.get("type")).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static List<String> paths(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("items")).stream().map(i -> (String) i.get("path")).sorted().collect(Collectors.toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDefaultFormUntilAnAdminChangesIt() throws Exception {
        Map<String, Object> form = SiteSearchForms.read(as("admin"));
        assertEquals(SiteSearchForms.BUILT_IN, facetTypes(form));
        assertEquals(false, form.get("custom"));
        assertEquals(true, form.get("canEdit"));
        assertEquals(false, SiteSearchForms.read(as("editor")).get("canEdit"));

        String facets = "[{\"type\":\"tags\",\"label\":\"Topics\",\"root\":\"wknd:\"},"
                + "{\"type\":\"options\",\"label\":\"Region\",\"property\":\"region\",\"options\":[{\"value\":\"Asia\"},{\"value\":\"Europe\",\"label\":\"In Europe\"}]},"
                + "{\"type\":\"text\",\"label\":\"Browser title\",\"property\":\"pageTitle\",\"description\":\"What the tab shows\"},"
                + "{\"type\":\"path\",\"label\":\"Where\"}]";
        Map<String, Object> saved = SiteSearchForms.save(as("admin"), facets);
        assertEquals(List.of("tags", "options", "text", "path"), facetTypes(saved));
        assertEquals(true, saved.get("custom"));
        assertEquals("admin", saved.get("modifiedBy"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) SiteSearchForms.read(as("editor")).get("facets");
        assertEquals("Topics", list.get(0).get("label"));
        assertEquals("wknd:", list.get(0).get("root"));
        List<Map<String, Object>> options = (List<Map<String, Object>>) list.get(1).get("options");
        assertEquals("Asia", options.get(0).get("label"));
        assertEquals("In Europe", options.get(1).get("label"));
        assertEquals("What the tab shows", list.get(2).get("description"));
        assertEquals("Where", list.get(3).get("label"));

        Map<String, Object> reset = SiteSearchForms.reset(as("admin"));
        assertEquals(SiteSearchForms.BUILT_IN, facetTypes(reset));
        assertNull(context.resourceResolver().getResource(SiteSearchForms.STORE));
    }

    @Test
    void onlyAdminChangesTheForm() {
        assertThrows(SecurityException.class, () -> SiteSearchForms.save(as("editor"), "[]"));
        assertThrows(SecurityException.class, () -> SiteSearchForms.reset(as("editor")));
        assertNull(context.resourceResolver().getResource(SiteSearchForms.STORE));
    }

    @Test
    void refusesWhatTheRailCannotShow() {
        ResourceResolver admin = as("admin");
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.save(admin, "{}"));
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.save(admin, "[{\"type\":\"colors\"}]"));
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.save(admin, "[{\"type\":\"path\"},{\"type\":\"path\"}]"));
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.save(admin, "[{\"type\":\"text\",\"property\":\"a/b\"}]"));
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.save(admin, "[{\"type\":\"options\",\"property\":\"region\",\"options\":[]}]"));
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.save(admin, "[{\"type\":\"tags\",\"root\":\"not a tag\"}]"));
        // an empty form is a form: the rail shows only the full text
        assertEquals(List.of(), facetTypes(assertDoesNotThrowSave(admin, "[]")));
    }

    private static Map<String, Object> assertDoesNotThrowSave(ResourceResolver r, String json) {
        try {
            return SiteSearchForms.save(r, json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsTheValuesThePagesHave() {
        Map<String, Object> region = SiteSearchForms.values(as("editor"), SITE, "region", 50);
        List<Map<String, Object>> values = (List<Map<String, Object>>) region.get("values");
        assertEquals("Europe", values.get(0).get("value"));
        assertEquals(3, values.get(0).get("count"));
        assertEquals("Asia", values.get(1).get("value"));
        assertEquals(false, region.get("more"));
        List<Map<String, Object>> audience = (List<Map<String, Object>>) SiteSearchForms.values(as("editor"), SITE, "audience", 1).get("values");
        assertEquals(1, audience.size());
        assertEquals("experts", audience.get(0).get("value"));
        assertEquals(true, SiteSearchForms.values(as("editor"), SITE, "audience", 1).get("more"));
        assertThrows(IllegalArgumentException.class, () -> SiteSearchForms.values(as("editor"), SITE, "../x", 50));
    }

    @Test
    void searchesOnPageProperties() {
        SiteRail rail = new SiteRail(as("editor"));
        SiteRail.Criteria c = new SiteRail.Criteria();
        c.root = SITE;
        c.meta.put("region", Set.of("Europe"));
        assertEquals(List.of(SITE + "/bike", SITE + "/bike/day1", SITE + "/ski"), paths(rail.search(c)));
        // one of the values of a property; a multi-value property matches on any of its values
        c.meta.put("audience", Set.of("family", "experts"));
        assertEquals(List.of(SITE + "/ski"), paths(rail.search(c)));
        c.meta.clear();
        c.metaText.put("pageTitle", "BALI");
        assertEquals(List.of(SITE + "/surf"), paths(rail.search(c)));
        c.metaText.put("pageTitle", "by");
        c.meta.put("region", Set.of("Asia"));
        assertTrue(paths(rail.search(c)).isEmpty());
        c.meta.clear();
        assertEquals(List.of(SITE + "/bike/day1"), paths(rail.search(c)));
        assertFalse(paths(rail.search(c)).contains(SITE + "/bike"));
    }
}
