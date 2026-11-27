/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void createsAFragmentWithItsMasterAndVariationsBelowIt() throws Exception {
        PageManagerImpl pm = new PageManagerImpl(resolver);
        Resource xf = XfConversion.createFragment(resolver, pm, "/content/experience-fragments/site", "Winter Offer", null, TEMPLATE);
        resolver.commit();
        assertEquals("/content/experience-fragments/site/winter-offer", xf.getPath());
        assertTrue(XfConversion.isFragment(xf));
        Resource master = resolver.getResource(xf.getPath() + "/master/jcr:content");
        assertEquals(Boolean.TRUE, master.getValueMap().get("cq:xfMasterVariation", Boolean.class));
        assertEquals("web", master.getValueMap().get("cq:xfVariantType", String.class));
        assertNotNull(master.getChild("root/container"), "the template's initial content");

        // a page made below the fragment is a variation, not a second master
        com.day.cq.wcm.api.Page second = pm.create(xf.getPath(), "email", TEMPLATE, "Email", false);
        Resource v = second.adaptTo(Resource.class);
        v.getChild("jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).remove("cq:xfVariantType");
        XfConversion.markVariation(v);
        assertEquals("web", v.getChild("jcr:content").getValueMap().get("cq:xfVariantType", String.class));
        assertNull(v.getChild("jcr:content").getValueMap().get("cq:xfMasterVariation"));

        // a fragment holds variations only; a taken name is refused when it was asked for
        assertThrows(IllegalArgumentException.class, () -> XfConversion.createFragment(resolver, pm, xf.getPath(), "Inner", null, TEMPLATE));
        resolver.revert();
        assertThrows(com.day.cq.wcm.api.WCMException.class,
                () -> XfConversion.createFragment(resolver, pm, "/content/experience-fragments/site", "Again", "winter-offer", TEMPLATE));
        resolver.revert();
        // without a name asked for, the next free one
        assertEquals("winter-offer_1", XfConversion.createFragment(resolver, pm, "/content/experience-fragments/site", "Winter Offer", null, TEMPLATE).getName());
        resolver.commit();
    }

    @Test
    void aFolderMadeHereIsListedWhileEmpty() throws Exception {
        Resource f = SitesServlet.createFolder(resolver, "/content/experience-fragments/site", "Campaigns 2027", null);
        assertEquals("campaigns-2027", f.getName());
        assertEquals("Campaigns 2027", f.getValueMap().get("jcr:title", String.class));
        assertTrue(SitesServlet.isListed(f), "empty, and still shown");
        context.create().resource("/content/experience-fragments/site/silent", "jcr:primaryType", "sling:Folder");
        assertTrue(!SitesServlet.isListed(resolver.getResource("/content/experience-fragments/site/silent")), "a folder of somebody else stays hidden while empty");
        assertThrows(IllegalArgumentException.class, () -> SitesServlet.createFolder(resolver, "/content/experience-fragments/site", "Campaigns 2027", null));
        assertThrows(IllegalArgumentException.class, () -> SitesServlet.createFolder(resolver, PAGE, "In a page", null));
        assertThrows(IllegalArgumentException.class, () -> SitesServlet.createFolder(resolver, "/content/experience-fragments/site", " ", null));
    }

    @Test
    void aCopyOfAPublishedPageIsNotPublished() throws Exception {
        context.create().resource(PAGE + "/below", "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/below/jcr:content", "jcr:primaryType", "cq:PageContent", "cq:lastReplicationAction", "Activate");
        org.apache.sling.api.resource.ModifiableValueMap m = resolver.getResource(PAGE + "/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        m.put("cq:lastReplicationAction", "Activate");
        m.put("cq:lastReplicatedBy", "admin");
        resolver.commit();
        Resource copy = SitesServlet.copy(resolver, PAGE, "/content/site/en");
        assertEquals("/content/site/en/page_1", copy.getPath(), "the next free name beside the original");
        assertNull(copy.getChild("jcr:content").getValueMap().get("cq:lastReplicationAction"));
        assertNull(copy.getChild("jcr:content").getValueMap().get("cq:lastReplicatedBy"));
        assertNull(copy.getChild("below/jcr:content").getValueMap().get("cq:lastReplicationAction"), "the pages below too");
        assertEquals("title", copy.getChild("jcr:content/root/title").getValueMap().get("p", String.class), "the content came along");
        assertEquals("Activate", resolver.getResource(PAGE + "/jcr:content").getValueMap().get("cq:lastReplicationAction"), "the original keeps its state");
        assertThrows(IllegalArgumentException.class, () -> SitesServlet.copy(resolver, PAGE, PAGE + "/below"));
    }
}
