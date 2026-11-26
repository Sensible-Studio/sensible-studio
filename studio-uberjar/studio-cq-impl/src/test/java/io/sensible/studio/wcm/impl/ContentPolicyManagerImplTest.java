/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;

@ExtendWith(SlingContextExtension.class)
class ContentPolicyManagerImplTest {

    private final SlingContext context = WcmTestSupport.newContext();
    private ContentPolicyManager cpm;

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        cpm = context.resourceResolver().adaptTo(ContentPolicyManager.class);
        assertNotNull(cpm);
    }

    private ContentPolicy policyAt(String relPath) {
        Resource r = context.resourceResolver().getResource(EN + "/jcr:content" + relPath);
        assertNotNull(r, relPath);
        return cpm.getPolicy(r);
    }

    @Test
    void structurePositionsUseExactMapping() {
        assertEquals("Page policy", policyAt("").getTitle());
        assertEquals("Root", policyAt("/root").getTitle());
        assertEquals("responsiveGrid", policyAt("/root/container").getProperties().get("layout", String.class));
    }

    @Test
    void aRenderingThatAsksForAPolicyShowsItAndTheTemplate() {
        try (io.sensible.studio.wcm.shown.Shown.Recording r = io.sensible.studio.wcm.shown.Shown.start(100)) {
            policyAt("/root/container/title");
            assertEquals(true, r.paths().contains("/conf/wknd/settings/wcm/policies/wknd/components/title/policy_title"), r.paths().toString());
            assertEquals(true, r.paths().stream().anyMatch(p -> p.startsWith("/conf/wknd/settings/wcm/templates/")), r.paths().toString());
        }
    }

    @Test
    void componentInContainerUsesResourceTypeMapping() {
        ContentPolicy title = policyAt("/root/container/title");
        assertEquals("/conf/wknd/settings/wcm/policies/wknd/components/title/policy_title", title.getPath());
        assertEquals("h2", title.getProperties().get("type", String.class));
        assertArrayEquals(new String[] {"h1", "h2", "h3"}, title.getProperties().get("allowedTypes", String[].class));
    }

    @Test
    void nestedAuthoredContainerFallsBackToNearestMappedAncestor() {
        assertEquals("Nested container", policyAt("/root/container/container").getTitle());
        assertEquals("Rich text", policyAt("/root/container/container/text").getTitle());
    }

    @Test
    void noPolicyOutsidePagesOrForStaticTemplates() {
        assertNull(cpm.getPolicy(context.resourceResolver().getResource("/conf/wknd")));
        assertNull(cpm.getPolicy(context.resourceResolver().getResource(EN + "/faqs/jcr:content")));
        assertNull(cpm.getPolicy(context.resourceResolver().getResource(EN)));
    }

    @Test
    void bindingsExposeWcmVariables() {
        Resource text = context.resourceResolver().getResource(EN + "/jcr:content/root/container/container/text");
        context.currentResource(text);
        Bindings bindings = new SimpleBindings();
        bindings.put(SlingBindings.RESOURCE, text);
        bindings.put(SlingBindings.REQUEST, context.request());

        new WcmBindingsValuesProvider().addBindings(bindings);

        assertEquals(EN, ((Page) bindings.get("currentPage")).getPath());
        assertEquals(EN, ((Page) bindings.get("resourcePage")).getPath());
        assertEquals("WKND Adventures and Travel",
                ((org.apache.sling.api.resource.ValueMap) bindings.get("pageProperties")).get("jcr:title", String.class));
        assertEquals("Rich text", ((ContentPolicy) bindings.get("currentContentPolicy")).getTitle());
        assertNotNull(bindings.get("pageManager"));

        // an include of a resource on another page keeps currentPage pinned to the request's page
        Resource other = context.resourceResolver().getResource(EN + "/adventures/jcr:content");
        Bindings included = new SimpleBindings();
        included.put(SlingBindings.RESOURCE, other);
        included.put(SlingBindings.REQUEST, context.request());
        new WcmBindingsValuesProvider().addBindings(included);
        assertEquals(EN, ((Page) included.get("currentPage")).getPath());
        assertEquals(EN + "/adventures", ((Page) included.get("resourcePage")).getPath());
    }
}
