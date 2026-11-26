/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.WCMException;

/** The Blueprints console: a configuration's properties, and removing one; the product's own stay as they are. */
@ExtendWith(SlingContextExtension.class)
class BlueprintConfigsTest {

    private static final String SITE = "/content/wknd/language-masters";
    private static final String BP = "/apps/msm/wknd_blueprint";
    private static final String XF = "/libs/msm/experience-fragments/blueprintconfigs/xf";

    private final SlingContext context = MsmTestSupport.newContext();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        MsmTestSupport.setUp(context);
        resolver = context.resourceResolver();
        resolver.getResource(SITE).adaptTo(ModifiableValueMap.class).put("jcr:primaryType", "cq:Page");
        context.create().resource(SITE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Language Masters");
        context.create().resource("/apps/msm", "jcr:primaryType", "sling:Folder");
        context.create().resource(BP, "jcr:primaryType", "cq:Page");
        context.create().resource(BP + "/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "WKND Site Blueprint",
                "sling:resourceType", BlueprintSites.RT_BLUEPRINT, "sitePath", SITE);
        context.create().resource(XF, "jcr:primaryType", "cq:Page");
        context.create().resource(XF + "/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "Experience Fragments",
                "sling:resourceType", BlueprintSites.RT_BLUEPRINT, "sitePath", SITE);
    }

    @Test
    void changesTheProperties() throws Exception {
        Map<String, Object> bp = BlueprintSites.updateBlueprint(resolver, BP, " WKND ", "Every WKND site", "/content/wknd/us/en");
        assertEquals("WKND", bp.get("title"));
        assertEquals("Every WKND site", bp.get("description"));
        assertEquals("/content/wknd/us/en", bp.get("sitePath"));
        assertEquals(false, bp.get("readOnly"));
        bp = BlueprintSites.updateBlueprint(resolver, BP, "WKND", "", "/content/wknd/us/en");
        assertNull(bp.get("description"));

        assertThrows(WCMException.class, () -> BlueprintSites.updateBlueprint(resolver, BP, " ", null, SITE));
        assertThrows(WCMException.class, () -> BlueprintSites.updateBlueprint(resolver, BP, "T", null, "/content/nowhere"));
        assertThrows(WCMException.class, () -> BlueprintSites.updateBlueprint(resolver, SITE, "T", null, SITE));
    }

    @Test
    void theProductsOwnStayAsTheyAre() {
        Map<String, Object> xf = BlueprintSites.blueprints(resolver).stream().filter(b -> XF.equals(b.get("path"))).findFirst().get();
        assertEquals(true, xf.get("readOnly"));
        assertThrows(WCMException.class, () -> BlueprintSites.updateBlueprint(resolver, XF, "Mine", null, SITE));
        assertThrows(WCMException.class, () -> BlueprintSites.deleteBlueprint(resolver, XF));
    }

    @Test
    void removesAConfiguration() throws Exception {
        assertTrue(BlueprintSites.isEditable(BP));
        BlueprintSites.deleteBlueprint(resolver, BP);
        assertNull(resolver.getResource(BP));
        assertFalse(BlueprintSites.blueprints(resolver).stream().anyMatch(b -> BP.equals(b.get("path"))));
        assertThrows(WCMException.class, () -> BlueprintSites.deleteBlueprint(resolver, BP));
    }

    @Test
    void namesTheSitesMadeFromASource() {
        java.util.List<LiveCopyIndex.Entry> copies = java.util.Arrays.asList(
                new LiveCopyIndex.Entry("/content/wknd/us/en", SITE + "/en"),
                new LiveCopyIndex.Entry("/content/wknd/us/es", SITE + "/es"),
                new LiveCopyIndex.Entry("/content/wknd/ca/en", SITE + "/en"),
                new LiveCopyIndex.Entry("/content/brand2", SITE),
                new LiveCopyIndex.Entry("/content/x/deep", SITE + "/en/deeper"),
                new LiveCopyIndex.Entry("/content/other/en", "/content/elsewhere/en"));
        assertEquals(java.util.Arrays.asList("/content/brand2", "/content/wknd/ca", "/content/wknd/us"), MsmServlet.sitesOf(SITE, copies));
    }
}
