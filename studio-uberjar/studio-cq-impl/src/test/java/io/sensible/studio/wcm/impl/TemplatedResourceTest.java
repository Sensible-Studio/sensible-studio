/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static io.sensible.studio.wcm.impl.WcmTestSupport.TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import javax.script.SimpleBindings;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.TemplatedResource;
import com.day.cq.wcm.foundation.TemplatedContainer;

@ExtendWith(SlingContextExtension.class)
class TemplatedResourceTest {

    private final SlingContext context = WcmTestSupport.newContext();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        String root = TEMPLATE + "/structure/jcr:content/root";
        context.create().resource(root + "/header", "sling:resourceType", "wknd/components/experiencefragment");
        context.create().resource(root + "/footer", "sling:resourceType", "wknd/components/experiencefragment");
    }

    private static List<String> names(Iterable<Resource> it) {
        List<String> out = new ArrayList<>();
        it.forEach(r -> out.add(r.getName()));
        return out;
    }

    @Test
    void structureOnlyPartsAreMergedInStructureOrder() {
        Resource root = context.resourceResolver().getResource(EN + "/jcr:content/root");
        TemplatedResource merged = root.adaptTo(TemplatedResource.class);
        // structure order: container, header, footer (fixture created container first)
        assertEquals(List.of("container", "header", "footer"), names(merged.getChildren()));
        Resource header = merged.getChild("header");
        assertEquals(TEMPLATE + "/structure/jcr:content/root/header", header.getPath(), "locked part comes from the template");
        Resource container = merged.getChild("container");
        assertEquals(EN + "/jcr:content/root/container", container.getPath(), "page content wins");
        assertEquals(List.of("title", "container"), names(container.getChildren()), "editable: only authored children");
    }

    @Test
    void aLockedPartTakesItsPropertiesFromTheTemplate() {
        context.resourceResolver().getResource(TEMPLATE + "/structure/jcr:content/root")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("layout", "responsiveGrid");
        Resource content = context.resourceResolver().getResource(EN + "/jcr:content");
        TemplatedResource page = content.adaptTo(TemplatedResource.class);
        Resource root = page.getChild("root");
        assertEquals(EN + "/jcr:content/root", root.getPath());
        assertEquals("responsiveGrid", root.getValueMap().get("layout", String.class), "the structure's layout");
        assertEquals("responsiveGrid", root.adaptTo(org.apache.sling.api.resource.ValueMap.class).get("layout", String.class));
        // the page content node keeps its own properties; an editable part is the page's
        assertNull(page.getValueMap().get("editable"));
        assertEquals(content.getValueMap().get("jcr:title", String.class), page.getValueMap().get("jcr:title", String.class));
        Resource container = root.getChild("container");
        assertNull(container.getValueMap().get("editable"), "the unlocked container keeps the page's properties");
    }

    @Test
    void templatedContainerListsTopLevelResources() {
        Resource content = context.resourceResolver().getResource(EN + "/jcr:content");
        TemplatedContainer tc = new TemplatedContainer();
        SimpleBindings b = new SimpleBindings();
        b.put(SlingBindings.RESOURCE, content);
        tc.init(b);
        assertEquals(List.of("root"), names(tc.getStructureResources()));
    }

    @Test
    void notMergedOutsideEditableTemplates() {
        assertNull(context.resourceResolver().getResource(EN + "/faqs/jcr:content").adaptTo(TemplatedResource.class));
    }

    @Test
    void gridClasses() {
        context.create().resource(EN + "/jcr:content/root/container/title/cq:responsive/default", "width", "6", "offset", "3");
        context.create().resource(EN + "/jcr:content/root/container/title/cq:responsive/phone", "behavior", "hide");
        Resource title = context.resourceResolver().getResource(EN + "/jcr:content/root/container/title");
        assertEquals("aem-GridColumn aem-GridColumn--default--6 aem-GridColumn--offset--default--3 aem-GridColumn--phone--hide",
                ResponsiveGridServlet.columnClasses(title));
        assertEquals("aem-Grid aem-Grid--12 aem-Grid--default--12",
                ResponsiveGridServlet.gridClasses(context.resourceResolver().getResource(EN + "/jcr:content/root")));
    }
}
