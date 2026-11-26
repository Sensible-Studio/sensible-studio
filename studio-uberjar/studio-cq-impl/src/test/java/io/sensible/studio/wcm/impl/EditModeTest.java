/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class EditModeTest {

    private final SlingContext context = WcmTestSupport.newContext();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
    }

    private Resource res(String rel) {
        return context.resourceResolver().getResource(EN + "/jcr:content" + rel);
    }

    private String renderTitle(String wcmmode) throws Exception {
        WcmComponentFilter filter = new WcmComponentFilter();
        if (wcmmode != null) {
            context.request().setParameterMap(Collections.singletonMap("wcmmode", wcmmode));
        }
        context.requestPathInfo().setExtension("html");
        context.currentResource(res(""));
        filter.doFilter(context.request(), context.response(), (rq, rs) -> {
            context.currentResource(res("/root/container/title"));
            filter.doFilter(rq, rs, (rq2, rs2) -> rs2.getWriter().write("TITLE"));
        });
        return context.response().getOutputAsString();
    }

    @Test
    void editModeAddsEditorAttributes() throws Exception {
        String html = renderTitle("edit");
        assertTrue(html.contains("data-studio-path=\"" + EN + "/jcr:content/root/container/title\""), html);
        assertTrue(html.contains("data-studio-type=\"wknd/components/title\""), html);
        assertTrue(html.contains("data-studio-title=\"Title\""), html);
        assertTrue(html.contains("data-studio-dialog=\"true\""), html);
        assertTrue(html.contains(">TITLE</div>"), html);
    }

    @Test
    void publishedMarkupHasNoEditorAttributes() throws Exception {
        String html = renderTitle(null);
        assertFalse(html.contains("data-studio-"), html);
        assertTrue(html.startsWith("<div class=\"title"), html);
    }

    @Test
    void metaWalksSuperTypes() {
        ComponentManagerImpl cm = new ComponentManagerImpl(context.resourceResolver());
        Resource container = res("/root/container");
        EditMeta meta = EditMeta.of(container, cm.getComponentOfResource(container));
        assertTrue(meta.container);
        assertFalse(meta.dialog);
        assertEquals("Container", meta.title);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGroupInAPolicyHoldsTheComponentsOfLibsToo() throws Exception {
        context.create().resource("/libs/dam/components/dynamicmedia", "jcr:primaryType", "cq:Component", "jcr:title", "Dynamic Media",
                "componentGroup", "Dynamic Media");
        // overlaid in /apps: counted once, with the overlay's definition
        context.create().resource("/libs/core/wcm/components/download", "jcr:primaryType", "cq:Component", "jcr:title", "Download (libs)",
                "componentGroup", "Dynamic Media");
        context.create().resource("/apps/core/wcm/components/download", "jcr:primaryType", "cq:Component", "jcr:title", "Download",
                "componentGroup", "Dynamic Media");
        // hidden in another group
        context.create().resource("/libs/dam/components/hidden", "jcr:primaryType", "cq:Component", "jcr:title", "Hidden", "componentGroup", ".hidden");
        context.resourceResolver().getResource("/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("components", new String[] {"/apps/wknd/components/container", "group:Dynamic Media"});
        context.resourceResolver().commit();

        Map<String, Object> info = new EditorServlet().pageInfo(context.resourceResolver(), EN);
        List<String> main = (List<String>) ((Map<String, Object>) info.get("containers")).get(EN + "/jcr:content/root/container");
        assertEquals(List.of("wknd/components/container", "core/wcm/components/download", "dam/components/dynamicmedia"), main);
        Map<String, Object> components = (Map<String, Object>) info.get("components");
        assertEquals("Download", ((Map<String, Object>) components.get("core/wcm/components/download")).get("title"));
        assertEquals("Dynamic Media", ((Map<String, Object>) components.get("dam/components/dynamicmedia")).get("group"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageInfoListsContainersAndAllowedComponents() {
        Map<String, Object> info = new EditorServlet().pageInfo(context.resourceResolver(), EN);
        assertNotNull(info);
        Map<String, Object> page = (Map<String, Object>) info.get("page");
        assertEquals("WKND Adventures and Travel", page.get("title"));
        assertEquals(WcmTestSupport.TEMPLATE, page.get("template"));
        Map<String, Object> containers = (Map<String, Object>) info.get("containers");
        List<String> main = (List<String>) containers.get(EN + "/jcr:content/root/container");
        assertEquals(List.of("wknd/components/container", "wknd/components/title"), main);
        assertTrue(containers.containsKey(EN + "/jcr:content/root"), "root container has no allowed components");
        Map<String, Object> components = (Map<String, Object>) info.get("components");
        Map<String, Object> title = (Map<String, Object>) components.get("wknd/components/title");
        assertEquals("Title", title.get("title"));
        assertEquals(Boolean.TRUE, title.get("dialog"));
        Map<String, Object> mappings = (Map<String, Object>) info.get("assetMappings");
        List<Map<String, Object>> main2 = (List<Map<String, Object>>) mappings.get(EN + "/jcr:content/root/container");
        assertEquals("image/*", main2.get(0).get("mimeType"));
        assertEquals("wknd/components/title", main2.get(0).get("resourceType"));
        assertNull(new EditorServlet().pageInfo(context.resourceResolver(), "/content/nope"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPagesBelowARoot() {
        Map<String, Object> r = new EditorServlet().pages(context.resourceResolver(), "/content", 3, false);
        List<Map<String, Object>> pages = (List<Map<String, Object>>) r.get("pages");
        assertEquals("/content/wknd", pages.get(0).get("path"));
        assertEquals(1, pages.get(0).get("level"));
        assertTrue(pages.stream().anyMatch(p -> EN.equals(p.get("path")) && Integer.valueOf(3).equals(p.get("level"))));
        assertNull(new EditorServlet().pages(context.resourceResolver(), "/apps", 2, false));
    }

    @Test
    void assetTypeFilter() {
        assertTrue(EditorServlet.matchesType("images", "image/png"));
        assertFalse(EditorServlet.matchesType("images", "application/pdf"));
        assertTrue(EditorServlet.matchesType("documents", "application/pdf"));
        assertTrue(EditorServlet.matchesType("all", "video/mp4"));
    }

    @Test
    void jsonEscapes() {
        assertEquals("{\"a\":\"x\\\"\\u003c\",\"b\":[1,true]}",
                EditorJson.write(new java.util.LinkedHashMap<String, Object>() {
                    {
                        put("a", "x\"<");
                        put("b", List.of(1, true));
                        put("c", null);
                    }
                }));
    }
}
