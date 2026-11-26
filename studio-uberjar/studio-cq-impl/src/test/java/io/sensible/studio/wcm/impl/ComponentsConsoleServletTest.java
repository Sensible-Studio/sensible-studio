/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The Components console's server side: list, one component, its policies and where it is used. */
@ExtendWith(SlingContextExtension.class)
class ComponentsConsoleServletTest {

    private final SlingContext context = new SlingContext();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = context.resourceResolver();
        context.create().resource("/libs/core/wcm/components/accordion/v1/accordion", "jcr:primaryType", "cq:Component",
                "jcr:title", "Accordion (v1)", "componentGroup", "Core Content", "cq:isContainer", true);
        context.create().resource("/apps/wknd/components/accordion", "jcr:primaryType", "cq:Component", "jcr:title", "Accordion",
                "componentGroup", "WKND.Content", "sling:resourceSuperType", "core/wcm/components/accordion/v1/accordion",
                "jcr:description", "Panels that open one at a time");
        context.create().resource("/apps/wknd/components/accordion/cq:dialog", "jcr:primaryType", "nt:unstructured");
        context.create().resource("/apps/wknd/components/accordion/accordion.html", "jcr:primaryType", "nt:file");
        context.load().binaryFile(new java.io.ByteArrayInputStream("# Accordion\nUse it for FAQs.".getBytes(StandardCharsets.UTF_8)),
                "/apps/wknd/components/accordion/README.md");
        context.create().resource("/libs/cq/gui/components/hiddenthing", "jcr:primaryType", "cq:Component", "componentGroup", ".hidden");
        // an overlay: the /libs one of the same type is hidden by it
        context.create().resource("/libs/wknd/components/accordion", "jcr:primaryType", "cq:Component", "jcr:title", "Old");
    }

    @Test
    void listsEveryComponentHiddenOnesToo() {
        List<Map<String, Object>> list = ComponentsConsoleServlet.list(resolver);
        assertEquals(Arrays.asList("/apps/wknd/components/accordion", "/libs/core/wcm/components/accordion/v1/accordion",
                "/libs/cq/gui/components/hiddenthing"), list.stream().map(m -> m.get("path")).collect(java.util.stream.Collectors.toList()));
        Map<String, Object> wknd = list.get(0);
        assertEquals("wknd/components/accordion", wknd.get("resourceType"));
        assertEquals("WKND.Content", wknd.get("group"));
        assertEquals("Ac", wknd.get("abbreviation"));
        assertEquals(true, list.get(1).get("container"));
        assertEquals(".hidden", list.get(2).get("group"));
        assertEquals("Hi", list.get(2).get("abbreviation"));
    }

    @Test
    void abbreviation() {
        assertEquals("Te", ComponentsConsoleServlet.abbreviation(null, "text"));
        assertEquals("3r", ComponentsConsoleServlet.abbreviation(null, "3rdparty"));
        assertEquals("XY", ComponentsConsoleServlet.abbreviation(" XY ", "Anything"));
        assertEquals("?", ComponentsConsoleServlet.abbreviation(null, "—"));
    }

    @Test
    void detailNamesWhatTheComponentIsMadeOf() {
        ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
        Map<String, Object> d = ComponentsConsoleServlet.detail(resolver, cm, resolver.getResource("/apps/wknd/components/accordion"));
        assertEquals("Panels that open one at a time", d.get("description"));
        assertEquals("/libs/core/wcm/components/accordion/v1/accordion", d.get("superTypePath"));
        assertEquals("/apps/wknd/components/accordion/cq:dialog", d.get("dialog"));
        assertNull(d.get("designDialog"));
        assertEquals(Arrays.asList("accordion.html"), d.get("scripts"));
        assertTrue(String.valueOf(d.get("readme")).startsWith("# Accordion"));
        assertEquals(1, ((List<?>) d.get("superTypes")).size());
    }

    @Test
    void policiesWithTheTemplatesThatUseThem() {
        context.create().resource("/conf/wknd/settings/wcm/policies/wknd/components/accordion/policy_1", "jcr:title", "WKND Accordion",
                "sling:resourceType", "wcm/core/components/policy/policy");
        context.create().resource("/conf/wknd/settings/wcm/templates/landing/jcr:content", "jcr:title", "Landing Page Template");
        context.create().resource("/conf/wknd/settings/wcm/templates/landing/policies/jcr:content/root/accordion",
                "cq:policy", "wknd/components/accordion/policy_1");
        context.create().resource("/conf/wknd/settings/wcm/templates/plain/jcr:content", "jcr:title", "Plain");
        List<Map<String, Object>> p = ComponentsConsoleServlet.policies(resolver, "wknd/components/accordion");
        assertEquals(1, p.size());
        assertEquals("WKND Accordion", p.get(0).get("title"));
        assertEquals("/conf/wknd", p.get(0).get("conf"));
        List<?> templates = (List<?>) p.get(0).get("templates");
        assertEquals(1, templates.size());
        assertEquals("Landing Page Template", ((Map<?, ?>) templates.get(0)).get("title"));
        assertTrue(ComponentsConsoleServlet.policies(resolver, "core/wcm/components/accordion/v1/accordion").isEmpty());
    }

    @Test
    void usageGroupsTheHitsByPage() {
        context.create().resource("/content/wknd/en/faqs", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/wknd/en/faqs/jcr:content", "jcr:title", "FAQs");
        Resource a = context.create().resource("/content/wknd/en/faqs/jcr:content/root/a");
        Resource b = context.create().resource("/content/wknd/en/faqs/jcr:content/root/b");
        Resource orphan = context.create().resource("/content/dam/x/jcr:content/data");
        Map<String, Object> u = ComponentsConsoleServlet.pagesOf(Arrays.asList(a, b, orphan).iterator());
        List<?> pages = (List<?>) u.get("pages");
        assertEquals(1, pages.size());
        Map<?, ?> faqs = (Map<?, ?>) pages.get(0);
        assertEquals("FAQs", faqs.get("title"));
        assertEquals(2, faqs.get("count"));
        assertEquals(3, u.get("uses"));
        assertFalse((Boolean) u.get("truncated"));
    }
}
