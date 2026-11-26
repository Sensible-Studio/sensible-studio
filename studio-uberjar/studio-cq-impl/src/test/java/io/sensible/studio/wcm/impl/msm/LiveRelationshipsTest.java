/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.BLUEPRINT;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.CA;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.US;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.jcr.RangeIterator;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.msm.api.LiveCopy;
import com.day.cq.wcm.msm.api.LiveRelationship;
import com.day.cq.wcm.msm.api.LiveStatus;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** The read side against the live copies of the WKND sample content (contract in {@code msm/expected.json}). */
@ExtendWith(SlingContextExtension.class)
class LiveRelationshipsTest {

    private final SlingContext context = MsmTestSupport.newContext();
    private MsmTestSupport.Services msm;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        msm = MsmTestSupport.setUp(context);
        resolver = context.resourceResolver();
    }

    private Resource res(String path) {
        Resource r = resolver.getResource(path);
        assertNotNull(r, path);
        return r;
    }

    @Test
    void packageHoldsTheStandardConfigurations() {
        Map<String, RolloutConfig> byName = msm.configs.getRolloutConfigs(resolver).stream()
                .collect(Collectors.toMap(RolloutConfig::getName, c -> c));
        assertEquals(Set.of("default", "versioned", "pushonmodify", "activate", "deactivate"), byName.keySet());
        assertEquals("targetVersion", byName.get("versioned").getActionNames().get(0));
        assertEquals(Trigger.ROLLOUT, byName.get("default").getTrigger());
        // the same actions as AEM 6.5's standard configuration, minus the targeting and field ones
        assertEquals(List.of("contentUpdate", "contentCopy", "contentDelete", "referencesUpdate", "referencesUpdateXF",
                "referencesUpdateCF", "referencesUpdateXFEmbedded", "orderChildren"), byName.get("default").getActionNames());
        assertTrue(msm.rollouts.knownActions().containsAll(byName.get("default").getActionNames()));
        assertEquals(Trigger.MODIFICATION, byName.get("pushonmodify").getTrigger());
        assertEquals(Trigger.PUBLICATION, byName.get("activate").getTrigger());
        assertEquals(Trigger.DEACTIVATION, byName.get("deactivate").getTrigger());
    }

    @Test
    void liveCopyRootsMatchTheObservedContract() throws Exception {
        String expected;
        try (InputStream in = getClass().getResourceAsStream("/msm/expected.json")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\"path\": \"([^\"]+)\",\\s*\"primaryType\": \"cq:LiveCopy\",\\s*"
                + "\"master\": \"([^\"]+)\",\\s*\"isDeep\": (true|false).*?\"masterExists\": (true|false)", Pattern.DOTALL)
                .matcher(expected);
        int checked = 0;
        int listed = 0;
        while (m.find()) {
            listed++;
            Resource root = resolver.getResource(m.group(1));
            if (root == null) {
                continue; // not in the fixture
            }
            LiveCopy copy = msm.relationships.getLiveCopy(root);
            assertNotNull(copy, root.getPath());
            assertEquals(m.group(2), copy.getBlueprintPath());
            assertEquals(Boolean.parseBoolean(m.group(3)), copy.isDeep());
            assertTrue(copy.isRoot());
            assertEquals("/libs/msm/wcm/rolloutconfigs/default", copy.getRolloutConfigs().get(0).getPath());
            assertEquals(Boolean.parseBoolean(m.group(4)), resolver.getResource(copy.getBlueprintPath()) != null);
            checked++;
        }
        assertEquals(17, listed);
        assertEquals(6, checked, "us/en, ca/en, ch/fr and three experience fragment live copies are in the fixture");
    }

    @Test
    void everyRolledOutNodeOfUsEnHasItsSource() {
        List<String> nodes = new ArrayList<>();
        collect(res(US), nodes);
        assertTrue(nodes.size() > 1000, "us/en has many rolled out nodes: " + nodes.size());
        int withSource = 0;
        for (String path : nodes) {
            Resource r = res(path);
            assertTrue(msm.relationships.hasLiveRelationship(r), path);
            LiveRelationship rel = msm.relationships.getLiveRelationship(r, true);
            assertEquals(BLUEPRINT + path.substring(US.length()), rel.getSourcePath());
            assertEquals(US, rel.getLiveCopy().getPath());
            if (rel.getStatus().isSourceExisting()) {
                withSource++;
            }
        }
        assertTrue(withSource > nodes.size() * 0.95, withSource + " of " + nodes.size() + " have their source");
    }

    private static void collect(Resource r, List<String> out) {
        if (Msm.hasMixin(r, "cq:LiveRelationship")) {
            out.add(r.getPath());
        }
        for (Resource c : r.getChildren()) {
            collect(c, out);
        }
    }

    @Test
    void pagesAreRelatedThroughTheirContent() {
        Resource page = res(US + "/adventures");
        assertTrue(msm.relationships.hasLiveRelationship(page));
        LiveRelationship rel = msm.relationships.getLiveRelationship(page, true);
        assertEquals(BLUEPRINT + "/adventures", rel.getSourcePath());
        assertTrue(rel.getStatus().isPage());
        assertFalse(rel.getStatus().isCancelled());
        assertNotNull(rel.getStatus().getLastRolledOut());
        assertEquals("admin", rel.getStatus().getLastRolledOutBy());
        assertFalse(rel.getLiveCopy().isRoot());
    }

    @Test
    void relationshipMixinAloneIsNoLiveCopy() {
        List<String> marked = new ArrayList<>();
        collect(res("/content/wknd/language-masters/es"), marked);
        assertFalse(marked.isEmpty(), "the Spanish language master keeps relationship mixins");
        for (String path : marked) {
            Resource r = res(path);
            assertFalse(msm.relationships.hasLiveRelationship(r), path);
            assertFalse(msm.relationships.isInLiveCopy(r), path);
            assertNull(msm.relationships.getLiveRelationship(r, false), path);
        }
    }

    @Test
    void blueprintKnowsItsLiveCopies() {
        Resource bp = res(BLUEPRINT);
        assertTrue(msm.relationships.isSource(bp));
        assertTrue(msm.relationships.isSource(res(BLUEPRINT + "/adventures/jcr:content")));
        assertFalse(msm.relationships.isSource(res(US)));
        Set<String> targets = targets(msm.relationships.getLiveRelationships(bp, null, null));
        assertEquals(new TreeSet<>(Set.of(US, CA)), targets);
        assertEquals(Set.of(US + "/adventures"),
                targets(msm.relationships.getLiveRelationships(res(BLUEPRINT + "/adventures"), US, null)));
        assertTrue(targets(msm.relationships.getLiveRelationships(bp, null, Trigger.MODIFICATION)).isEmpty());
        assertEquals(2, targets(msm.relationships.getLiveRelationships(bp, null, Trigger.ROLLOUT)).size());
    }

    /** What Core Components' navigation needs: a blueprint root resolves to the live copy holding the current page. */
    @Test
    void navigationRootOnTheBlueprintResolvesToTheCurrentLiveCopy() {
        String current = US + "/adventures/bali-surf-camp";
        RangeIterator it = msm.relationships.getLiveRelationships(res(BLUEPRINT), null, null);
        String found = null;
        while (it.hasNext()) {
            LiveRelationship r = (LiveRelationship) it.next();
            if (current.startsWith(r.getLiveCopy().getPath() + "/")) {
                found = r.getTargetPath();
            }
        }
        assertEquals(US, found);
    }

    private static Set<String> targets(RangeIterator it) {
        Set<String> out = new TreeSet<>();
        while (it.hasNext()) {
            out.add(((LiveRelationship) it.next()).getTargetPath());
        }
        return out;
    }

    @Test
    void cancelledComponentsAndTheirChildren() {
        String teaser = "/content/experience-fragments/wknd/us/en/featured/camping-western-australia/master/jcr:content/root/container/teaser";
        LiveStatus st = msm.relationships.getLiveRelationship(res(teaser), true).getStatus();
        assertTrue(st.isCancelled());
        assertTrue(st.isCancelledForChildren());
        Resource action = res(teaser).getChild("actions");
        if (action != null && Msm.hasMixin(action, "cq:LiveRelationship")) {
            assertTrue(msm.relationships.getLiveRelationship(action, true).getStatus().isCancelled());
        }
        String container = Msm.parentOf(teaser);
        assertFalse(msm.relationships.getLiveRelationship(res(container), true).getStatus().isCancelled());
    }

    @Test
    void cancelledPageProperties() {
        LiveStatus st = msm.relationships.getLiveRelationship(res("/content/wknd/ch/fr/jcr:content"), true).getStatus();
        assertEquals(Set.of("jcr:language"), st.getCanceledProperties());
        assertTrue(st.isPropertyCancelled("jcr:language"));
        assertFalse(st.isCancelled());
    }

    @Test
    void liveCopyOfAMissingBlueprint() {
        Resource alt = res("/content/experience-fragments/wknd/us/en/adventures/adventures-2021/alternate");
        LiveRelationship rel = msm.relationships.getLiveRelationship(alt, true);
        assertFalse(rel.getStatus().isSourceExisting());
        assertEquals("sourceMissing", ((LiveStatusImpl) rel.getStatus()).summary());
    }

    @Test
    void localNodesAreInTheLiveCopyButNotInherited() {
        Resource local = context.create().resource(US + "/jcr:content/root/container/local-text",
                "sling:resourceType", "wknd/components/text");
        assertTrue(msm.relationships.isInLiveCopy(local));
        assertFalse(msm.relationships.hasLiveRelationship(local));
        assertNull(msm.relationships.getLiveRelationship(local, false));
        assertEquals("local", ((LiveStatusImpl) msm.relationships.getRelationship(resolver, local.getPath()).getStatus()).summary());
    }
}
