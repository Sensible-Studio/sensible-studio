/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.BLUEPRINT;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.US;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** What rollouts must never do: loop, touch local content, or be pointed outside /content. */
@ExtendWith(SlingContextExtension.class)
class SafetyTest {

    private static final String ADV = "/adventures";
    private static final String CONTAINER = "/jcr:content/root/container";

    private final SlingContext context = MsmTestSupport.newContext();
    private MsmTestSupport.Services msm;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        msm = MsmTestSupport.setUp(context);
        resolver = context.resourceResolver();
    }

    private RolloutResult rollout(String source, boolean deep, Trigger trigger) throws WCMException {
        RolloutResult r = msm.rollouts.rollout(resolver, source, RolloutManagerImpl.Options.of(deep, trigger));
        Msm.commit(resolver);
        return r;
    }

    private String firstInheritedChild(String containerRel) {
        for (Resource c : resolver.getResource(BLUEPRINT + containerRel).getChildren()) {
            Resource t = resolver.getResource(US + containerRel + "/" + c.getName());
            if (t != null && Msm.hasMixin(t, "cq:LiveRelationship") && Msm.isComponent(t)) {
                return containerRel + "/" + c.getName();
            }
        }
        throw new AssertionError("no inherited child in " + containerRel);
    }

    @Test
    void noLiveCopyInsideOrAroundItsBlueprintAndNoBadNames() {
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT, BLUEPRINT + ADV, "x",
                null, true, null, null));
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT,
                "/content/wknd/./language-masters", "x", null, true, null, null));
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT, "/content/wknd", "..",
                null, true, null, null));
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT, "/content/wknd", "a/b",
                null, true, null, null));
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT, "/apps", "x",
                null, true, null, null));
        // a live copy of US (itself a live copy of the blueprint) placed inside the blueprint would copy itself forever
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, US + ADV, BLUEPRINT + ADV, "loop",
                null, true, null, null));
    }

    @Test
    void aLiveCopyMovedIntoItsBlueprintIsNotRolledOutAnyMore() throws Exception {
        // the configuration says "US is a live copy of the blueprint", but it now lives below it
        context.create().resource(BLUEPRINT + "/moved", "jcr:primaryType", "cq:Page");
        context.create().resource(BLUEPRINT + "/moved/jcr:content", "jcr:primaryType", "cq:PageContent");
        context.create().resource(BLUEPRINT + "/moved/jcr:content/cq:LiveSyncConfig", "jcr:primaryType", "cq:LiveCopy",
                "cq:master", BLUEPRINT, "cq:isDeep", true);
        msm.relationships.index.invalidate();
        RolloutResult r = rollout(BLUEPRINT, true, Trigger.ROLLOUT);
        assertTrue(r.skipped.contains(BLUEPRINT + "/moved"), r.skipped.toString());
        assertNull(resolver.getResource(BLUEPRINT + "/moved/moved"));
    }

    @Test
    void localComponentsSurviveTheDeletionOfTheirInheritedContainer() throws Exception {
        String container = firstInheritedChild(ADV + CONTAINER);
        context.create().resource(US + container + "/mine", "sling:resourceType", "wknd/components/text", "text", "<p>mine</p>");
        resolver.delete(resolver.getResource(BLUEPRINT + container));
        resolver.commit();
        rollout(BLUEPRINT + ADV, false, Trigger.ROLLOUT);
        assertNotNull(resolver.getResource(US + container + "/mine"), "the local component stays");
        assertFalse(Msm.hasMixin(resolver.getResource(US + container), "cq:LiveRelationship"),
                "its container is local now");
    }

    @Test
    void localContentCannotBeReenabledOrSynchronized() throws Exception {
        context.create().resource(US + ADV + CONTAINER + "/mine", "sling:resourceType", "wknd/components/text");
        context.create().resource(US + ADV + "/local-page", "jcr:primaryType", "cq:Page");
        context.create().resource(US + ADV + "/local-page/jcr:content", "jcr:primaryType", "cq:PageContent");
        assertThrows(WCMException.class, () -> msm.relationships.reenableRelationship(resolver,
                msm.relationships.getRelationship(resolver, US + ADV + CONTAINER + "/mine"), true));
        assertThrows(WCMException.class, () -> msm.rollouts.synchronize(resolver, US + ADV + "/local-page", false, false));
        assertNotNull(resolver.getResource(US + ADV + "/local-page"));
    }

    @Test
    void linksInLocalComponentsAreTheAuthorsOwn() throws Exception {
        String link = "<p><a href=\"" + BLUEPRINT + ADV + ".html\">blueprint</a></p>";
        context.create().resource(US + ADV + CONTAINER + "/mine", "sling:resourceType", "wknd/components/text", "text", link);
        rollout(BLUEPRINT + ADV, false, Trigger.ROLLOUT);
        assertEquals(link, resolver.getResource(US + ADV + CONTAINER + "/mine").getValueMap().get("text", String.class));
    }

    @Test
    void copiesOfInheritedContentAreLocal() throws Exception {
        String comp = firstInheritedChild(ADV + CONTAINER);
        Resource copy = resolver.copy(US + comp, US + "/jcr:content");
        MsmCopies.afterCopy(copy);
        resolver.commit();
        assertFalse(Msm.hasMixin(copy, "cq:LiveRelationship"));
        rollout(BLUEPRINT, false, Trigger.ROLLOUT);
        assertNotNull(resolver.getResource(copy.getPath()), "a copy survives the next rollout");
        // a copied live copy root stays a live copy
        context.create().resource("/content/copies", "jcr:primaryType", "sling:Folder");
        Resource root = resolver.copy(US, "/content/copies");
        MsmCopies.afterCopy(root);
        assertTrue(Msm.hasMixin(resolver.getResource(root.getPath() + "/jcr:content"), "cq:LiveRelationship"));
    }

    @Test
    void experienceFragmentLinksPointToTheLocalFragmentsWhenTheyExist() throws Exception {
        String xf = "/content/experience-fragments/wknd/language-masters/en/featured/camping-western-australia/master";
        String missing = "/content/experience-fragments/wknd/language-masters/en/contributors/nobody/master";
        context.create().resource(BLUEPRINT + ADV + CONTAINER + "/xf", "sling:resourceType", "wknd/components/experiencefragment",
                "fragmentVariationPath", xf);
        context.create().resource(BLUEPRINT + ADV + CONTAINER + "/xf2", "sling:resourceType", "wknd/components/experiencefragment",
                "fragmentVariationPath", missing);
        rollout(BLUEPRINT + ADV, false, Trigger.ROLLOUT);
        assertEquals("/content/experience-fragments/wknd/us/en/featured/camping-western-australia/master",
                resolver.getResource(US + ADV + CONTAINER + "/xf").getValueMap().get("fragmentVariationPath", String.class));
        assertEquals(missing, resolver.getResource(US + ADV + CONTAINER + "/xf2").getValueMap().get("fragmentVariationPath", String.class),
                "no local fragment: the link stays");
        assertEquals(List.of("wknd/language-masters", "wknd/us"), List.of(SyncActions.LocalizedReferencesUpdate.siteRoots(
                "/content/wknd/language-masters/en/magazine", "/content/studio-site/en/magazine")[0], "wknd/us"));
    }

    @Test
    void actionsRunInASafeOrderWhateverTheConfigurationOrder() {
        LiveCopyImpl lc = new LiveCopyImpl(US, BLUEPRINT, true, true, new String[0], () -> List.of(
                msm.configs.getRolloutConfig(resolver, "/libs/msm/wcm/rolloutconfigs/default"),
                msm.configs.getRolloutConfig(resolver, "/libs/msm/wcm/rolloutconfigs/versioned")));
        List<String> names = new java.util.ArrayList<>();
        msm.rollouts.actions(resolver, lc, null, new RolloutResult()).forEach(a -> names.add(a.getName()));
        assertEquals("targetVersion", names.get(0));
        assertEquals(List.of("contentCopy", "contentUpdate", "contentDelete"), names.subList(1, 4));
        assertTrue(names.indexOf("referencesUpdate") > names.indexOf("contentDelete"));
    }
}
