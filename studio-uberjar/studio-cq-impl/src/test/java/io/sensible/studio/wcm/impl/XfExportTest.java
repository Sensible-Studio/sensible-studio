/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
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

/** Generate Variations' export: chosen variations become the variations of a new experience fragment. */
@ExtendWith(SlingContextExtension.class)
class XfExportTest {

    private static final String TEMPLATE = "/conf/site/settings/wcm/templates/xf-web";
    private static final String FOLDER = "/content/experience-fragments/site/en";
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/content/experience-fragments/site", "jcr:primaryType", "sling:Folder");
        context.create().resource(FOLDER, "jcr:primaryType", "sling:Folder");
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
        // the site's components, proxies of the core ones; another site's title too
        context.create().resource("/apps/site/components/title", "jcr:primaryType", "cq:Component", "sling:resourceSuperType", "core/wcm/components/title/v3/title");
        context.create().resource("/apps/site/components/text", "jcr:primaryType", "cq:Component", "sling:resourceSuperType", "core/wcm/components/text/v2/text");
        context.create().resource("/apps/aaa/components/title", "jcr:primaryType", "cq:Component", "sling:resourceSuperType", "core/wcm/components/title/v2/title");
        resolver = context.resourceResolver();
        resolver.commit();
    }

    @Test
    void makesAFragmentWithAVariationForEachOneChosen() throws Exception {
        Map<String, Object> r = XfExport.export(resolver, FOLDER, "Surf camp hero", TEMPLATE, new String[] {
            "0:title:Catch your first wave", "0:text:Five days of lessons <for all>.", "0:button:Book now",
            "3:title:Costa Rica is calling", "3:text:Line one\nLine two", "3:button:"});
        assertEquals(FOLDER + "/surf-camp-hero", r.get("fragment"));
        assertEquals(List.of(FOLDER + "/surf-camp-hero/master", FOLDER + "/surf-camp-hero/variation-2"), r.get("variations"));

        Resource xf = resolver.getResource(FOLDER + "/surf-camp-hero/jcr:content");
        assertEquals(XfConversion.XF_TYPE, xf.getResourceType());
        Resource master = resolver.getResource(FOLDER + "/surf-camp-hero/master/jcr:content");
        assertEquals(Boolean.TRUE, master.getValueMap().get("cq:xfMasterVariation", Boolean.class));
        assertEquals(TEMPLATE, master.getValueMap().get("cq:template", String.class));

        List<String> types = new ArrayList<>();
        for (Resource c : master.getChild("root/container").getChildren()) {
            types.add(c.getResourceType());
        }
        // the site's title and text; no button of the site: the core one
        assertEquals(List.of("site/components/title", "site/components/text", "core/wcm/components/button/v2/button"), types);
        assertEquals("Catch your first wave", master.getChild("root/container/title").getValueMap().get("jcr:title", String.class));
        assertEquals("<p>Five days of lessons &lt;for all&gt;.</p>", master.getChild("root/container/text").getValueMap().get("text", String.class));
        Resource second = resolver.getResource(FOLDER + "/surf-camp-hero/variation-2/jcr:content/root/container");
        assertEquals("<p>Line one</p><p>Line two</p>", second.getChild("text").getValueMap().get("text", String.class));
        // an empty part is left out
        assertNull(second.getChild("button"));
        assertEquals("Variation 2", resolver.getResource(FOLDER + "/surf-camp-hero/variation-2/jcr:content").getValueMap().get("jcr:title", String.class));
    }

    @Test
    void checksWhatItIsGiven() {
        assertThrows(IllegalArgumentException.class, () -> XfExport.export(resolver, "/content/site", "X", TEMPLATE, new String[] {"0:title:x"}));
        assertThrows(IllegalArgumentException.class, () -> XfExport.export(resolver, FOLDER, " ", TEMPLATE, new String[] {"0:title:x"}));
        assertThrows(IllegalArgumentException.class, () -> XfExport.export(resolver, FOLDER, "X", TEMPLATE, new String[] {"0:image:x"}));
        assertThrows(IllegalArgumentException.class, () -> XfExport.export(resolver, FOLDER, "X", TEMPLATE, new String[] {"0:title:  "}));
        assertThrows(IllegalArgumentException.class, () -> XfExport.export(resolver, FOLDER, "X", "/conf/none", new String[] {"0:title:x"}));
        assertEquals("site", XfExport.siteOf(FOLDER));
    }
}
