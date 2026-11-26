/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.WCMException;

/** Blueprint configurations and Create Site on the WKND language masters (the structure observed on AEM 6.5). */
@ExtendWith(SlingContextExtension.class)
class BlueprintSitesTest {

    private static final String SITE = "/content/wknd/language-masters";
    private static final String BP = "/apps/msm/wknd_blueprint";

    private final SlingContext context = MsmTestSupport.newContext();
    private MsmTestSupport.Services msm;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        msm = MsmTestSupport.setUp(context);
        resolver = context.resourceResolver();
        // in WKND the language masters root is a page; the fixture keeps only its folder
        resolver.getResource(SITE).adaptTo(ModifiableValueMap.class).put("jcr:primaryType", "cq:Page");
        context.create().resource(SITE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Language Masters");
        context.create().resource("/apps/msm", "jcr:primaryType", "sling:Folder");
        context.create().resource(BP, "jcr:primaryType", "cq:Page");
        context.create().resource(BP + "/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "WKND Site Blueprint",
                "sling:resourceType", BlueprintSites.RT_BLUEPRINT, "sitePath", SITE);
        context.create().resource("/content/sites", "jcr:primaryType", "sling:Folder");
        context.registerAdapter(ResourceResolver.class, com.day.cq.wcm.api.PageManager.class,
                (java.util.function.Function<ResourceResolver, com.day.cq.wcm.api.PageManager>) io.sensible.studio.wcm.impl.TestPageManagers::of);
    }

    private ValueMap props(String path) {
        return resolver.getResource(path).getValueMap();
    }

    @Test
    void listsBlueprintsWithTheirLanguagesAndChapters() throws Exception {
        List<Map<String, Object>> all = BlueprintSites.blueprints(resolver);
        assertEquals(1, all.size());
        assertEquals(SITE, all.get(0).get("sitePath"));
        Map<String, Object> s = BlueprintSites.structure(resolver, BP);
        assertEquals(3, ((List<?>) s.get("languages")).size());
        assertTrue(s.get("chapters").toString().contains("magazine"));
        assertTrue(s.get("languages").toString().contains("title=English"), s.get("languages").toString());
        assertEquals("German (Switzerland)", BlueprintSites.languageName("de_ch"));
        assertEquals(null, BlueprintSites.languageName("language-masters"));
    }

    @Test
    void createsTheSiteLikeAem() throws Exception {
        BlueprintSites.SiteRequest req = new BlueprintSites.SiteRequest();
        req.blueprint = BP;
        req.parent = "/content/sites";
        req.title = "My Site";
        req.owner = "admin";
        req.languages.add(SITE + "/en");
        req.languages.add(SITE + "/fr");
        req.chapters.add("magazine");
        req.chapters.add("faqs");
        BlueprintSites.createSite(resolver, msm.rollouts, req);
        Msm.commit(resolver);
        String root = "/content/sites/my-site";
        assertEquals("My Site", props(root + "/jcr:content").get("jcr:title", String.class));
        assertEquals("admin", props(root + "/jcr:content").get("cq:siteOwner", String.class));
        ValueMap rootCfg = props(root + "/jcr:content/cq:LiveSyncConfig");
        assertEquals(SITE, rootCfg.get("cq:master", String.class));
        assertFalse(rootCfg.get("cq:isDeep", true), "the site root is a shallow live copy");
        assertFalse(props(root + "/en/jcr:content/cq:LiveSyncConfig").get("cq:isDeep", true), "so are the languages");
        assertTrue(props(root + "/en/magazine/jcr:content/cq:LiveSyncConfig").get("cq:isDeep", false), "chapters are deep");
        assertNotNull(resolver.getResource(root + "/en/faqs"));
        assertNull(resolver.getResource(root + "/en/adventures"), "chapters not chosen are left out");
        assertNotNull(resolver.getResource(root + "/fr"));
        assertNull(resolver.getResource(root + "/fr/magazine"), "French has no magazine");
        assertNull(resolver.getResource(root + "/es"));
        // the magazine chapter is a working live copy
        assertTrue(msm.relationships.hasLiveRelationship(resolver.getResource(root + "/en/magazine")));
    }

    @Test
    void refusesBadRequests() {
        BlueprintSites.SiteRequest req = new BlueprintSites.SiteRequest();
        req.blueprint = BP;
        req.parent = "/content/sites";
        req.title = "X";
        assertThrows(WCMException.class, () -> BlueprintSites.createSite(resolver, msm.rollouts, req), "no language");
        req.languages.add("/content/wknd/us/en");
        assertThrows(WCMException.class, () -> BlueprintSites.createSite(resolver, msm.rollouts, req), "not a language of the blueprint");
        assertThrows(WCMException.class, () -> BlueprintSites.createBlueprint(resolver, "../x", "T", null, SITE));
    }

    @Test
    void createsBlueprintConfigurations() throws Exception {
        String path = BlueprintSites.createBlueprint(resolver, "us_blueprint", "US", "From the US site", "/content/wknd/us/en");
        assertEquals("/apps/msm/us_blueprint", path);
        assertEquals(2, BlueprintSites.blueprints(resolver).size());
        assertEquals("cq:Page", props(path).get("jcr:primaryType", String.class));
        assertEquals(BlueprintSites.RT_BLUEPRINT, props(path + "/jcr:content").get("sling:resourceType", String.class));
    }

    @Test
    void namesFromTitles() {
        assertEquals("my-new-site", BlueprintSites.nameFrom("My new Site!"));
        assertEquals("sterreich", BlueprintSites.nameFrom("Österreich").substring(1));
    }

    @Test
    void plainCopiesCarryNoMsmState() throws Exception {
        // the blueprint site may itself hold live copies
        String mag = SITE + "/en/magazine";
        context.create().resource(mag + "/jcr:content/cq:LiveSyncConfig", "jcr:primaryType", "cq:LiveCopy",
                "cq:master", "/content/wknd/us/en/magazine", "cq:isDeep", true);
        resolver.getResource(mag + "/jcr:content").adaptTo(ModifiableValueMap.class)
                .put("jcr:mixinTypes", new String[] {"cq:LiveRelationship", "cq:LiveSync"});
        BlueprintSites.SiteRequest req = new BlueprintSites.SiteRequest();
        req.blueprint = BP;
        req.parent = "/content/sites";
        req.title = "Plain";
        req.liveCopy = false;
        req.languages.add(SITE + "/en");
        req.chapters.add("magazine");
        BlueprintSites.createSite(resolver, msm.rollouts, req);
        Msm.commit(resolver);
        String copy = "/content/sites/plain/en/magazine";
        assertNotNull(resolver.getResource(copy));
        assertNull(resolver.getResource(copy + "/jcr:content/cq:LiveSyncConfig"));
        assertFalse(Msm.hasMixin(resolver.getResource(copy + "/jcr:content"), "cq:LiveSync"));
        assertFalse(Msm.hasMixin(resolver.getResource(copy + "/jcr:content"), "cq:LiveRelationship"));
        assertFalse(msm.relationships.isInLiveCopy(resolver.getResource(copy)));
    }

    @Test
    void aNewBlueprintIsCreatedWithTheSite() throws Exception {
        Map<String, Object> s = BlueprintSites.structureOfSite(resolver, SITE);
        assertEquals(3, ((List<?>) s.get("languages")).size());
        BlueprintSites.SiteRequest req = new BlueprintSites.SiteRequest();
        req.newBlueprintSite = SITE;
        req.newBlueprintTitle = "WKND Site Blueprint";
        req.parent = "/content/sites";
        req.title = "Second";
        req.languages.add(SITE + "/en");
        BlueprintSites.createSite(resolver, msm.rollouts, req);
        Msm.commit(resolver);
        assertEquals(SITE, props("/apps/msm/wknd-site-blueprint/jcr:content").get("sitePath", String.class));
        assertEquals(SITE, props("/content/sites/second/jcr:content/cq:LiveSyncConfig").get("cq:master", String.class));
    }
}
