/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static io.sensible.studio.wcm.impl.WcmTestSupport.TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.TemplatedResource;
import com.day.cq.wcm.api.policies.ContentPolicy;

/** The template editor's server side on the WKND fixture's "Content Page" template. */
@ExtendWith(SlingContextExtension.class)
class TemplateEditorServletTest {

    private static final String STRUCTURE = TEMPLATE + "/structure/jcr:content";
    private static final String CONTAINER = STRUCTURE + "/root/container";
    private static final String CONTAINER_TYPE = "wknd/components/container";

    private final SlingContext context = WcmTestSupport.newContext();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        resolver = context.resourceResolver();
        // as in real templates: the template's parts are pages, the structure names its template
        for (String part : new String[] {"structure", "initial"}) {
            resolver.getResource(TEMPLATE + "/" + part).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                    .put("jcr:primaryType", "cq:Page");
        }
        context.create().resource("/apps/wknd/components/text", "jcr:primaryType", "cq:Component", "jcr:title", "Text",
                "componentGroup", "WKND.Content");
        resolver.getResource(STRUCTURE).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("cq:template", TEMPLATE);
        if (resolver.getResource(CONTAINER) == null) {
            context.create().resource(CONTAINER, "sling:resourceType", CONTAINER_TYPE);
        }
        resolver.getResource(CONTAINER).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("editable", true);
        if (resolver.getResource(STRUCTURE + "/root/header") == null) {
            context.create().resource(STRUCTURE + "/root/header", "sling:resourceType", "wknd/components/experiencefragment");
        }
    }

    private TemplateEditorServlet.Tpl tpl() {
        return TemplateEditorServlet.Tpl.of(resolver, STRUCTURE + "/root");
    }

    @Test
    void describesTheTemplateAndItsLocks() {
        Map<String, Object> d = tpl().describe();
        assertEquals(TEMPLATE, d.get("path"));
        assertEquals("Content Page", d.get("title"));
        assertEquals("draft", d.get("status"));
        assertEquals("/conf/wknd", d.get("conf"));
        Map<?, ?> unlocked = (Map<?, ?>) d.get("unlocked");
        assertEquals(true, unlocked.get(CONTAINER));
        assertEquals(false, unlocked.get(STRUCTURE + "/root/header"));
        assertEquals(false, unlocked.get(STRUCTURE + "/root"));
    }

    @Test
    void refusesPathsOutsideTemplates() {
        assertThrows(IllegalArgumentException.class, () -> TemplateEditorServlet.Tpl.of(resolver, EN + "/jcr:content"));
        assertThrows(IllegalArgumentException.class, () -> TemplateEditorServlet.Tpl.of(resolver, TEMPLATE + "/../x"));
        assertThrows(IllegalArgumentException.class, () -> tpl().setLocked(TEMPLATE + "/initial/jcr:content/root", true));
    }

    @Test
    void createsAndAssignsAPolicy() throws Exception {
        Map<String, Object> info = tpl().policyInfo(CONTAINER, CONTAINER_TYPE, null);
        assertEquals("Main container", ((Map<?, ?>) info.get("policy")).get("title"), "the fixture maps it");
        assertEquals(3, ((List<?>) info.get("policies")).size());

        Map<String, Object> made = tpl().assignPolicy(CONTAINER, CONTAINER_TYPE, null, null, true, "Main area", "For articles", null);
        resolver.commit();
        String policy = (String) ((Map<?, ?>) made.get("policy")).get("path");
        assertTrue(policy.startsWith("/conf/wknd/settings/wcm/policies/wknd/components/container/policy_"), policy);
        Resource mapping = resolver.getResource(TEMPLATE + "/policies/jcr:content/root/container");
        assertEquals(TemplateEditorServlet.RT_MAPPING, mapping.getResourceType());
        assertEquals(policy.substring("/conf/wknd/settings/wcm/policies/".length()), mapping.getValueMap().get("cq:policy", String.class));

        // what pages using the template now get
        ContentPolicy p = new ContentPolicyManagerImpl(resolver, new PageManagerImpl(resolver)).getPolicy(resolver.getResource(CONTAINER));
        assertEquals("Main area", p.getTitle());

        info = tpl().policyInfo(CONTAINER, CONTAINER_TYPE, null);
        assertEquals(policy, ((Map<?, ?>) info.get("policy")).get("path"));
        assertEquals(List.of(TEMPLATE), ((List<?>) info.get("usedBy")).stream().map(m -> ((Map<?, ?>) m).get("path")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void assignsThePolicyOfAComponentInsideAContainer() throws Exception {
        tpl().assignPolicy(CONTAINER, "wknd/components/title", CONTAINER, "/conf/wknd/settings/wcm/policies/wknd/components/title/policy_title",
                false, null, null, null);
        resolver.commit();
        Resource mapping = resolver.getResource(TEMPLATE + "/policies/jcr:content/root/container/wknd/components/title");
        assertNotNull(mapping);
        assertEquals("wknd/components/title/policy_title", mapping.getValueMap().get("cq:policy", String.class));
        tpl().assignPolicy(CONTAINER, "wknd/components/text", CONTAINER, null, true, "Text", null, null);
        resolver.commit();
        assertEquals(TemplateEditorServlet.RT_MAPPING,
                resolver.getResource(TEMPLATE + "/policies/jcr:content/root/container/wknd/components/text").getResourceType());
        assertThrows(IllegalArgumentException.class, () -> tpl().assignPolicy(CONTAINER, "wknd/components/title", CONTAINER,
                "/conf/wknd/settings/wcm/policies/wknd/components/text/policy_text", false, null, null, null), "a policy of another type");
    }

    @Test
    void copiesAPolicy() throws Exception {
        context.create().resource("/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main/extra",
                "x", "y");
        resolver.getResource("/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("components", new String[] {"group:WKND.Content"});
        Map<String, Object> made = tpl().assignPolicy(CONTAINER, CONTAINER_TYPE, null, null, true, "Copy", null,
                "/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main");
        resolver.commit();
        Resource copy = resolver.getResource((String) ((Map<?, ?>) made.get("policy")).get("path"));
        assertEquals("Copy", copy.getValueMap().get("jcr:title", String.class));
        assertEquals("group:WKND.Content", copy.getValueMap().get("components", String[].class)[0]);
        assertNotNull(copy.getChild("extra"));
    }

    @Test
    void locksUnlocksAndSetsTheStatus() throws Exception {
        tpl().setLocked(CONTAINER, true);
        resolver.commit();
        assertFalse(resolver.getResource(CONTAINER).getValueMap().containsKey("editable"));
        tpl().setLocked(STRUCTURE + "/root/header", false);
        resolver.commit();
        assertEquals(true, resolver.getResource(STRUCTURE + "/root/header").getValueMap().get("editable", Boolean.class));
        assertEquals("enabled", tpl().setStatus("enabled").get("status"));
        assertThrows(IllegalArgumentException.class, () -> tpl().setStatus("published"));
    }

    @Test
    void anUnlockedPartWithoutPageContentIsThePagesToFill() {
        context.create().resource(STRUCTURE + "/root/aside", "sling:resourceType", CONTAINER_TYPE, "editable", true, "layout", "responsiveGrid");
        TemplatedResource root = resolver.getResource(EN + "/jcr:content/root").adaptTo(TemplatedResource.class);
        Resource aside = root.getChild("aside");
        assertEquals(EN + "/jcr:content/root/aside", aside.getPath(), "answers at the page's path");
        assertEquals(CONTAINER_TYPE, aside.getResourceType());
        assertEquals("responsiveGrid", aside.getValueMap().get("layout", String.class));
        assertFalse(aside.getChildren().iterator().hasNext());
        assertEquals(TEMPLATE + "/structure/jcr:content/root/header", root.getChild("header").getPath(), "locked parts stay the template's");
    }

    @Test
    void listsComponentsInVisibleGroups() {
        List<Map<String, Object>> all = TemplateEditorServlet.components(resolver);
        assertFalse(all.isEmpty());
        assertTrue(all.stream().noneMatch(c -> String.valueOf(c.get("group")).startsWith(".")));
        assertTrue(all.stream().anyMatch(c -> "wknd/components/title".equals(c.get("resourceType"))));
    }
}
