/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The Templates console's server side on the WKND fixture. */
@ExtendWith(SlingContextExtension.class)
class TemplatesConsoleServletTest {

    private static final String TYPES = "/conf/wknd/settings/wcm/template-types";
    private static final String TYPE = TYPES + "/content-page";

    private final SlingContext context = WcmTestSupport.newContext();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        WcmTestSupport.setUp(context);
        resolver = context.resourceResolver();
        context.create().resource(TYPES, "jcr:primaryType", "sling:Folder");
        context.create().resource(TYPE, "jcr:primaryType", "cq:Template", "ranking", 1L);
        context.create().resource(TYPE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "WKND Site Content Page",
                "jcr:description", "Start with a Header and Footer");
        context.create().resource(TYPE + "/structure", "jcr:primaryType", "cq:Page");
        context.create().resource(TYPE + "/structure/jcr:content", "jcr:primaryType", "cq:PageContent",
                "sling:resourceType", "wknd/components/page", "cq:lastModifiedBy", "someone");
        context.create().resource(TYPE + "/structure/jcr:content/root", "sling:resourceType", "wknd/components/container");
        context.create().resource(TYPE + "/structure/jcr:content/root/container", "sling:resourceType", "wknd/components/container",
                "editable", true);
        context.create().resource(TYPE + "/initial", "jcr:primaryType", "cq:Page");
        context.create().resource(TYPE + "/initial/jcr:content", "jcr:primaryType", "cq:PageContent", "sling:resourceType", "wknd/components/page");
        context.create().resource(TYPE + "/policies", "jcr:primaryType", "cq:Page");
        context.create().resource(TYPE + "/policies/jcr:content", "cq:policy", "wknd/components/page/policy_page",
                "sling:resourceType", "wcm/core/components/policies/mappings");
        context.create().resource("/libs/settings/wcm/template-types/html5page", "jcr:primaryType", "cq:Template");
        context.create().resource("/libs/settings/wcm/template-types/html5page/jcr:content", "jcr:primaryType", "cq:PageContent",
                "jcr:title", "HTML5 Page");
        context.create().resource("/libs/settings/wcm/template-types/empty", "jcr:primaryType", "cq:Template");
        context.create().resource("/apps/settings/wcm/template-types/empty", "jcr:primaryType", "cq:Template");
        context.create().resource("/apps/settings/wcm/template-types/empty/jcr:content", "jcr:primaryType", "cq:PageContent",
                "jcr:title", "Empty (apps)");
        context.create().resource("/conf/global", "jcr:primaryType", "sling:Folder");
        resolver.commit();
    }

    private Resource conf() {
        return TemplatesConsoleServlet.conf(resolver, "/conf/wknd");
    }

    @Test
    void listsConfigurationsGlobalFirst() {
        List<Map<String, Object>> confs = TemplatesConsoleServlet.confs(resolver);
        assertEquals("/conf/global", confs.get(0).get("path"));
        Map<String, Object> wknd = confs.stream().filter(c -> "/conf/wknd".equals(c.get("path"))).findFirst().get();
        assertEquals("Wknd", wknd.get("title"));
        assertEquals(TemplatesConsoleServlet.templates(resolver, conf()).size(), wknd.get("templates"));
    }

    @Test
    void listsTemplates() {
        Map<String, Object> t = TemplatesConsoleServlet.templates(resolver, conf()).stream()
                .filter(x -> TEMPLATE.equals(x.get("path"))).findFirst().get();
        assertEquals("Content Page", t.get("title"));
        assertEquals("draft", t.get("status"));
    }

    @Test
    void listsTemplateTypesByRankingWithAppsOverlayingLibs() {
        List<Map<String, Object>> types = TemplatesConsoleServlet.types(resolver, conf());
        List<Object> paths = types.stream().map(t -> t.get("path")).collect(Collectors.toList());
        assertEquals(TYPE, paths.get(0), "ranking 1 first");
        assertTrue(paths.contains("/apps/settings/wcm/template-types/empty"));
        assertTrue(paths.contains("/libs/settings/wcm/template-types/html5page"));
        assertTrue(!paths.contains("/libs/settings/wcm/template-types/empty"), "overlaid by /apps");
        assertEquals("WKND Site Content Page", types.get(0).get("title"));
        assertEquals("/conf/wknd", types.get(0).get("source"));
    }

    @Test
    void createsADraftTemplateFromAType() throws Exception {
        Resource t = TemplatesConsoleServlet.create(resolver, conf(), TYPE, "Event Page", "For events", null, "admin");
        resolver.commit();
        assertEquals("/conf/wknd/settings/wcm/templates/event-page", t.getPath());
        ValueMap p = t.getChild("jcr:content").getValueMap();
        assertEquals("Event Page", p.get("jcr:title"));
        assertEquals("For events", p.get("jcr:description"));
        assertEquals("draft", p.get("status"));
        assertEquals(TYPE, p.get("cq:templateType"));
        assertEquals("admin", p.get("cq:lastModifiedBy"));
        assertNull(t.getValueMap().get("ranking"));
        assertEquals(true, resolver.getResource(t.getPath() + "/structure/jcr:content/root/container").getValueMap().get("editable"));
        assertEquals(t.getPath(), resolver.getResource(t.getPath() + "/structure/jcr:content").getValueMap().get("cq:template"));
        assertNull(resolver.getResource(t.getPath() + "/structure/jcr:content").getValueMap().get("cq:lastModifiedBy"));
        assertEquals(t.getPath(), resolver.getResource(t.getPath() + "/initial/jcr:content").getValueMap().get("cq:template"));
        assertEquals("wknd/components/page/policy_page", resolver.getResource(t.getPath() + "/policies/jcr:content").getValueMap().get("cq:policy"));
        // the editor opens it
        assertEquals("Event Page", TemplateEditorServlet.Tpl.of(resolver, t.getPath() + "/structure").describe().get("title"));
        // the same title again gets its own name; an explicit name must be free
        assertEquals("event-page-1", TemplatesConsoleServlet.create(resolver, conf(), TYPE, "Event Page", null, null, "admin").getName());
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.create(resolver, conf(), TYPE, "X", null, "event-page", "admin"));
    }

    @Test
    void createRefusesTypesTheConfigurationCannotUse() {
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.create(resolver, conf(), TEMPLATE, "X", null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.create(resolver, conf(), "/content/wknd", "X", null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.create(resolver, conf(), TYPE, " ", null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.conf(resolver, "/conf/wknd/settings/wcm"));
    }

    @Test
    void copiesATemplateAsADraft() throws Exception {
        Resource copy = TemplatesConsoleServlet.copy(resolver, TemplatesConsoleServlet.template(resolver, TEMPLATE), "admin");
        resolver.commit();
        assertEquals(TEMPLATE + "-copy", copy.getPath());
        assertEquals("Content Page (Copy)", copy.getChild("jcr:content").getValueMap().get("jcr:title"));
        assertEquals("draft", copy.getChild("jcr:content").getValueMap().get("status"));
        assertEquals(copy.getPath(), resolver.getResource(copy.getPath() + "/initial/jcr:content").getValueMap().get("cq:template"));
    }

    @Test
    void deletesOnlyTemplatesNoPageUses() throws Exception {
        Map<String, Object> usage = TemplatesConsoleServlet.usage(resolver, TEMPLATE);
        assertTrue(((Number) usage.get("count")).intValue() > 0, "the fixture's pages use the content page template");
        assertThrows(IllegalStateException.class, () -> TemplatesConsoleServlet.delete(resolver, TemplatesConsoleServlet.template(resolver, TEMPLATE)));
        Resource fresh = TemplatesConsoleServlet.create(resolver, conf(), TYPE, "Unused", null, null, "admin");
        resolver.commit();
        assertEquals(0, TemplatesConsoleServlet.usage(resolver, fresh.getPath()).get("count"));
        TemplatesConsoleServlet.delete(resolver, fresh);
        resolver.commit();
        assertNull(resolver.getResource("/conf/wknd/settings/wcm/templates/unused"));
    }

    @Test
    void editsPropertiesAndTheThumbnail() throws Exception {
        Resource t = TemplatesConsoleServlet.template(resolver, TEMPLATE);
        TemplatesConsoleServlet.properties(t, " Content ", "");
        assertEquals("Content", t.getChild("jcr:content").getValueMap().get("jcr:title"));
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.properties(t, "", null));
        TemplatesConsoleServlet.thumbnail(resolver, t, new ByteArrayInputStream("png".getBytes(StandardCharsets.UTF_8)), "image/png");
        resolver.commit();
        Resource thumb = resolver.getResource(TEMPLATE + "/thumbnail.png/jcr:content");
        assertNotNull(thumb);
        assertEquals("image/png", thumb.getValueMap().get("jcr:mimeType"));
        try (InputStream in = thumb.getValueMap().get("jcr:data", InputStream.class)) {
            assertEquals("png", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(String.valueOf(TemplatesConsoleServlet.summary(t).get("thumbnail")).startsWith(TEMPLATE + "/thumbnail.png"));
    }

    @Test
    void listsThePoliciesTheTemplateMaps() {
        List<Map<String, Object>> policies = TemplatesConsoleServlet.policies(resolver, TemplatesConsoleServlet.template(resolver, TEMPLATE));
        List<Object> paths = policies.stream().map(p -> p.get("path")).collect(Collectors.toList());
        assertEquals("/conf/wknd/settings/wcm/policies/wknd/components/page/policy_page", paths.get(0));
        assertTrue(paths.contains("/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main"));
        assertEquals(paths.size(), new java.util.HashSet<>(paths).size(), "each policy once");
        assertEquals("Page policy", policies.get(0).get("title"));
        assertEquals("wknd/components/page", policies.get(0).get("component"));
    }

    @Test
    void refusesPathsThatAreNotTemplates() {
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.template(resolver, TEMPLATE + "/structure"));
        assertThrows(IllegalArgumentException.class, () -> TemplatesConsoleServlet.template(resolver, "/content/wknd"));
    }
}
