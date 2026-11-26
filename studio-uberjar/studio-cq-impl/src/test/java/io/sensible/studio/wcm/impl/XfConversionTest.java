/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

@ExtendWith(SlingContextExtension.class)
class XfConversionTest {

    private static final String PAGE = "/content/site/en/page";
    private static final String TEMPLATE = "/conf/site/settings/wcm/templates/xf-web";
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/content/site/en", "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "sling:resourceType", "site/components/page");
        context.create().resource(PAGE + "/jcr:content/root", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/container");
        for (String n : new String[] {"title", "text", "image", "teaser"}) {
            context.create().resource(PAGE + "/jcr:content/root/" + n, "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/" + n, "p", n);
        }
        context.create().resource("/content/experience-fragments/site", "jcr:primaryType", "sling:Folder");
        context.create().resource(TEMPLATE, "jcr:primaryType", "cq:Template");
        context.create().resource(TEMPLATE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "XF Web", "status", "enabled");
        context.create().resource(TEMPLATE + "/initial", "jcr:primaryType", "cq:Page");
        context.create().resource(TEMPLATE + "/initial/jcr:content", "jcr:primaryType", "cq:PageContent", "sling:resourceType", "site/components/xfpage",
                "cq:xfVariantType", "web", "cq:template", TEMPLATE);
        context.create().resource(TEMPLATE + "/initial/jcr:content/root", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/container");
        context.create().resource(TEMPLATE + "/initial/jcr:content/root/container", "jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/container");
        context.create().resource(TEMPLATE + "/structure", "jcr:primaryType", "cq:Page");
        context.create().resource(TEMPLATE + "/structure/jcr:content", "jcr:primaryType", "cq:PageContent", "sling:resourceType", "site/components/xfpage");
        context.create().resource(TEMPLATE + "/policies", "jcr:primaryType", "cq:Page");
        context.create().resource(TEMPLATE + "/policies/jcr:content", "jcr:primaryType", "cq:PageContent");
        resolver = context.resourceResolver();
        resolver.commit();
    }

    @Test
    void listsExperienceFragmentTemplates() {
        List<Map<String, Object>> t = XfConversion.templates(resolver, "/content/experience-fragments/site");
        assertEquals(1, t.size());
        assertEquals(TEMPLATE, t.get(0).get("path"));
        assertEquals("web", t.get(0).get("variantType"));
    }

    @Test
    void movesTheComponentsIntoAFragmentAndReferencesIt() throws Exception {
        Map<String, Object> r = XfConversion.convert(resolver, new String[] {PAGE + "/jcr:content/root/text", PAGE + "/jcr:content/root/image"},
                "/content/experience-fragments/site", "Promo Block", TEMPLATE, "site/components/experiencefragment");
        assertEquals("/content/experience-fragments/site/promo-block", r.get("fragment"));
        assertEquals("/content/experience-fragments/site/promo-block/master", r.get("variation"));

        Resource xf = resolver.getResource("/content/experience-fragments/site/promo-block/jcr:content");
        assertEquals(XfConversion.XF_TYPE, xf.getResourceType());
        assertEquals("Promo Block", xf.getValueMap().get("jcr:title", String.class));
        Resource master = resolver.getResource("/content/experience-fragments/site/promo-block/master/jcr:content");
        assertEquals("site/components/xfpage", master.getResourceType());
        assertEquals(Boolean.TRUE, master.getValueMap().get("cq:xfMasterVariation", Boolean.class));
        assertEquals(TEMPLATE, master.getValueMap().get("cq:template", String.class));
        List<String> moved = new ArrayList<>();
        for (Resource c : master.getChild("root/container").getChildren()) {
            moved.add(c.getName());
        }
        assertEquals(Arrays.asList("text", "image"), moved);
        assertEquals("text", resolver.getResource(master.getPath() + "/root/container/text").getValueMap().get("p", String.class));

        // the page: title, the fragment component where the text was, teaser
        List<String> names = new ArrayList<>();
        for (Resource c : resolver.getResource(PAGE + "/jcr:content/root").getChildren()) {
            names.add(c.getName());
        }
        assertEquals(Arrays.asList("title", "experiencefragment", "teaser"), names);
        Resource component = resolver.getResource((String) r.get("component"));
        assertNotNull(component);
        assertEquals("site/components/experiencefragment", component.getResourceType());
        assertEquals(r.get("variation"), component.getValueMap().get("fragmentVariationPath", String.class));
        assertNull(resolver.getResource(PAGE + "/jcr:content/root/text"));
        assertTrue(!resolver.hasChanges());
    }
}
