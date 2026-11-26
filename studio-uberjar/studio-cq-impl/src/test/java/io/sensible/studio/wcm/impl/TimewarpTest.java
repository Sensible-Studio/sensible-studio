/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.jcr.version.Version;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Revision;

/** Timewarp renders a page from the version in force at a moment. */
@ExtendWith(SlingContextExtension.class)
class TimewarpTest {

    private static final String PAGE = "/content/site/en";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;
    private Revision first;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/content/site", "jcr:primaryType", "sling:Folder");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "One",
                "sling:resourceType", "site/components/page");
        context.create().resource(PAGE + "/jcr:content/root", "jcr:primaryType", "nt:unstructured", "sling:resourceType",
                "site/components/container");
        context.create().resource(PAGE + "/jcr:content/root/text", "jcr:primaryType", "nt:unstructured", "sling:resourceType",
                "site/components/text", "text", "old");
        // an editable template whose structure adds a header before the page's own root
        String tpl = "/conf/site/settings/wcm/templates/t";
        context.create().resource(tpl, "jcr:primaryType", "cq:Template");
        context.create().resource(tpl + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "T");
        context.create().resource(tpl + "/structure", "jcr:primaryType", "cq:Page");
        context.create().resource(tpl + "/structure/jcr:content", "jcr:primaryType", "cq:PageContent", "sling:resourceType", "site/components/page");
        context.create().resource(tpl + "/structure/jcr:content/header", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/xf");
        context.create().resource(tpl + "/structure/jcr:content/root", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/container", "editable", true);
        context.create().resource(tpl + "/policies", "jcr:primaryType", "cq:Page");
        context.create().resource(tpl + "/policies/jcr:content", "jcr:primaryType", "cq:PageContent");
        context.create().resource(tpl + "/initial", "jcr:primaryType", "cq:Page");
        context.create().resource(tpl + "/initial/jcr:content", "jcr:primaryType", "cq:PageContent", "sling:resourceType", "site/components/page");
        resolver = context.resourceResolver();
        resolver.getResource(PAGE + "/jcr:content").adaptTo(ModifiableValueMap.class).put("cq:template", tpl);
        resolver.commit();
        first = new PageRevisions(resolver).create(PAGE, null, "first");
        Thread.sleep(20);
        // after the version: a title change, a new component, a removed one
        resolver.getResource(PAGE + "/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:title", "Two");
        resolver.getResource(PAGE + "/jcr:content/root/text").adaptTo(ModifiableValueMap.class).put("text", "new");
        context.create().resource(PAGE + "/jcr:content/root/title", "jcr:primaryType", "nt:unstructured", "sling:resourceType",
                "site/components/title");
        resolver.commit();
    }

    @Test
    void picksTheNewestVersionBeforeTheTime() throws Exception {
        long created = first.getCreated().getTimeInMillis();
        Version before = TimewarpFilter.versionAt(resolver, PAGE + "/jcr:content", created - 1);
        assertNull(before, "no version yet");
        Version at = TimewarpFilter.versionAt(resolver, PAGE + "/jcr:content", created);
        assertNotNull(at);
        assertEquals(first.getVersionName(), at.getName());
        assertEquals(first.getVersionName(), TimewarpFilter.versionAt(resolver, PAGE + "/jcr:content", System.currentTimeMillis()).getName());
    }

    @Test
    void theWarpedRequestSeesTheOldContentAtTheLivePaths() throws Exception {
        context.currentResource(PAGE);
        context.requestPathInfo().setExtension("html");
        context.request().setParameterMap(Collections.singletonMap("timewarp", String.valueOf(System.currentTimeMillis())));
        List<SlingHttpServletRequest> seen = new ArrayList<>();
        new TimewarpFilter().doFilter(context.request(), context.response(), (rq, rs) -> seen.add((SlingHttpServletRequest) rq));
        SlingHttpServletRequest warped = seen.get(0);
        assertEquals(first.getVersionName(), warped.getAttribute(TimewarpFilter.ATTR_VERSION));

        ResourceResolver rr = warped.getResourceResolver();
        Resource content = rr.getResource(PAGE + "/jcr:content");
        assertNotNull(content);
        assertEquals(PAGE + "/jcr:content", content.getPath());
        assertEquals("One", content.getValueMap().get("jcr:title", String.class));
        assertEquals("cq:PageContent", content.getValueMap().get("jcr:primaryType", String.class));
        assertEquals("site/components/page", content.getResourceType());

        Resource text = content.getChild("root/text");
        assertNotNull(text);
        assertEquals("old", text.getValueMap().get("text", String.class));
        assertEquals("site/components/text", text.getResourceType());
        assertEquals(PAGE + "/jcr:content/root", text.getParent().getPath());

        List<String> children = new ArrayList<>();
        for (Resource c : rr.getResource(PAGE + "/jcr:content/root").getChildren()) {
            children.add(c.getName());
        }
        assertEquals(Collections.singletonList("text"), children, "the title did not exist yet");
        assertNull(rr.getResource(PAGE + "/jcr:content/root/title"));

        // the templated view of the warped content merges the structure, like the live one
        com.day.cq.wcm.api.TemplatedResource merged = content.adaptTo(com.day.cq.wcm.api.TemplatedResource.class);
        assertNotNull(merged, "adapts through the warp");
        List<String> structure = new ArrayList<>();
        for (Resource c : merged.getChildren()) {
            structure.add(c.getName());
        }
        List<String> liveStructure = new ArrayList<>();
        for (Resource c : TemplatedResourceImpl.of(resolver.getResource(PAGE + "/jcr:content")).getChildren()) {
            liveStructure.add(c.getName());
        }
        assertEquals(liveStructure, structure, "same merged structure as the live page");
        assertTrue(structure.contains("header") && structure.contains("root"), structure.toString());
        assertEquals("One", rr.adaptTo(com.day.cq.wcm.api.PageManager.class).getPage(PAGE).getTitle());

        // the page node itself is live, its content child is from the version
        Resource page = rr.getResource(PAGE);
        assertEquals("One", page.getChild("jcr:content").getValueMap().get("jcr:title", String.class));
        assertTrue(new PageManagerImpl(rr).getPage(PAGE).getTitle().equals("One"));
    }

    @Test
    void withoutAVersionBeforeTheTimeTheCurrentContentRenders() throws Exception {
        context.currentResource(PAGE);
        context.requestPathInfo().setExtension("html");
        context.request().setParameterMap(Collections.singletonMap("timewarp", "1"));
        List<SlingHttpServletRequest> seen = new ArrayList<>();
        new TimewarpFilter().doFilter(context.request(), context.response(), (rq, rs) -> seen.add((SlingHttpServletRequest) rq));
        assertNull(seen.get(0).getAttribute(TimewarpFilter.ATTR_VERSION));
        assertEquals("Two", seen.get(0).getResourceResolver().getResource(PAGE + "/jcr:content").getValueMap().get("jcr:title", String.class));
    }
}
