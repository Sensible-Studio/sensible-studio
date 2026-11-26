/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.BLUEPRINT;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.CA;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.US;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.LiveAction;
import com.day.cq.wcm.msm.api.LiveActionFactory;
import com.day.cq.wcm.msm.api.LiveRelationship;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** Rollouts on the WKND sample content: what changes in the live copies and what authors keep. */
@ExtendWith(SlingContextExtension.class)
class RolloutTest {

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

    private Resource res(String path) {
        Resource r = resolver.getResource(path);
        assertNotNull(r, path);
        return r;
    }

    private ValueMap props(String path) {
        return res(path).getValueMap();
    }

    private void set(String path, String key, Object value) {
        res(path).adaptTo(ModifiableValueMap.class).put(key, value);
    }

    private void page(String path, String title) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", title);
    }

    private RolloutResult rollout(String source, boolean deep) throws WCMException {
        RolloutResult r = msm.rollouts.rollout(resolver, source, RolloutManagerImpl.Options.of(deep, Trigger.ROLLOUT));
        Msm.commit(resolver);
        return r;
    }

    /** First component of a container in the source that the US copy inherited. */
    private String firstInheritedChild(String containerRel) {
        for (Resource c : res(BLUEPRINT + containerRel).getChildren()) {
            Resource t = resolver.getResource(US + containerRel + "/" + c.getName());
            if (t != null && Msm.hasMixin(t, "cq:LiveRelationship")) {
                return containerRel + "/" + c.getName();
            }
        }
        throw new AssertionError("no inherited child in " + containerRel);
    }

    @Test
    void deepRolloutOfTheWholeBlueprintKeepsWkndIntact() throws Exception {
        String langUs = props(US + "/jcr:content").get("jcr:language", String.class);
        RolloutResult r = rollout(BLUEPRINT, true);
        assertTrue(r.conflicts.isEmpty(), r.conflicts.toString());
        assertTrue(r.updated.contains(US) && r.updated.contains(CA), r.updated.toString());
        assertTrue(r.updated.contains(US + ADV));
        assertTrue(r.nodes > 1000, "nodes " + r.nodes);
        assertTrue(r.unknownActions.isEmpty());
        // page level jcr:* properties are not synchronised (AEM's default exclusions)
        assertEquals(langUs, props(US + "/jcr:content").get("jcr:language", String.class));
        // the live copy configuration stays
        assertNotNull(resolver.getResource(US + "/jcr:content/cq:LiveSyncConfig"));
        assertTrue(Msm.hasMixin(res(US + "/jcr:content"), "cq:LiveSync"));
        // every page of the blueprint exists in the copy
        for (Resource p : res(BLUEPRINT + ADV).getChildren()) {
            if (Msm.isPage(p)) {
                assertNotNull(resolver.getResource(US + ADV + "/" + p.getName()), p.getPath());
            }
        }
        assertEquals(SyncActions.user(resolver), props(US + "/jcr:content").get("cq:lastRolledoutBy", String.class));
        assertTrue(System.currentTimeMillis() - props(US + "/jcr:content").get("cq:lastRolledout", Calendar.class).getTimeInMillis() < 60_000);
    }

    @Test
    void changedAddedAndRemovedComponents() throws Exception {
        String rel = ADV + CONTAINER;
        String changed = firstInheritedChild(rel);
        set(BLUEPRINT + changed, "jcr:title", "Changed on the blueprint");
        set(BLUEPRINT + changed, "newProp", "x");
        res(BLUEPRINT + changed).adaptTo(ModifiableValueMap.class).remove("id");
        context.create().resource(BLUEPRINT + rel + "/brand-new", "sling:resourceType", "wknd/components/text",
                "text", "<p>new</p>");
        context.create().resource(US + rel + "/local", "sling:resourceType", "wknd/components/text", "text", "<p>mine</p>");
        Calendar before = props(US + ADV + "/jcr:content").get("cq:lastRolledout", Calendar.class);

        rollout(BLUEPRINT + ADV, false);

        ValueMap t = props(US + changed);
        assertEquals("Changed on the blueprint", t.get("jcr:title", String.class), "component jcr:title rolls out");
        assertEquals("x", t.get("newProp", String.class));
        assertFalse(t.containsKey("id"));
        Resource created = res(US + rel + "/brand-new");
        assertTrue(Msm.hasMixin(created, "cq:LiveRelationship"));
        assertEquals("<p>new</p>", created.getValueMap().get("text", String.class));
        assertNotNull(created.getValueMap().get("cq:lastRolledout", Calendar.class));
        assertNotNull(resolver.getResource(US + rel + "/local"), "local components stay");
        assertNull(resolver.getResource(US + rel + "/local/jcr:mixinTypes"));
        assertTrue(props(US + ADV + "/jcr:content").get("cq:lastRolledout", Calendar.class).after(before));

        // removed on the blueprint: removed in the copy, local stays
        resolver.delete(res(BLUEPRINT + rel + "/brand-new"));
        rollout(BLUEPRINT + ADV, false);
        assertNull(resolver.getResource(US + rel + "/brand-new"));
        assertNotNull(resolver.getResource(US + rel + "/local"));
        // ca/en got the same
        assertEquals("Changed on the blueprint", props(CA + changed).get("jcr:title", String.class));
    }

    @Test
    void aSingleComponentRollsOutOnItsOwn() throws Exception {
        String rel = ADV + CONTAINER;
        String changed = firstInheritedChild(rel);
        String other = null;
        for (Resource c : res(BLUEPRINT + rel).getChildren()) {
            if (!(rel + "/" + c.getName()).equals(changed) && resolver.getResource(US + rel + "/" + c.getName()) != null) {
                other = rel + "/" + c.getName();
                break;
            }
        }
        assertNotNull(other);
        set(BLUEPRINT + changed, "jcr:title", "Only this one");
        set(BLUEPRINT + other, "jcr:title", "Not yet");

        RolloutResult r = rollout(BLUEPRINT + changed, false);

        assertEquals("Only this one", props(US + changed).get("jcr:title", String.class), "the component rolled out");
        assertEquals("Only this one", props(CA + changed).get("jcr:title", String.class), "to every live copy");
        assertFalse("Not yet".equals(props(US + other).get("jcr:title", String.class)), "its siblings did not");
        assertTrue(r.updated.contains(US + ADV), r.updated.toString());
    }

    @Test
    void cancelledComponentsKeepTheirContentUntilReenabled() throws Exception {
        String comp = firstInheritedChild(ADV + CONTAINER);
        LiveRelationship rel = msm.relationships.getLiveRelationship(res(US + comp), false);
        msm.relationships.cancelRelationship(resolver, rel, true, true);
        set(US + comp, "jcr:title", "Local title");
        set(BLUEPRINT + comp, "jcr:title", "Blueprint title");
        rollout(BLUEPRINT + ADV, false);
        assertEquals("Local title", props(US + comp).get("jcr:title", String.class));
        assertEquals("cancelled", ((LiveStatusImpl) msm.relationships.getLiveRelationship(res(US + comp), true).getStatus()).summary());

        msm.relationships.reenableRelationship(resolver, rel, false);
        msm.rollouts.synchronize(resolver, US + comp, false, false);
        Msm.commit(resolver);
        assertEquals("Blueprint title", props(US + comp).get("jcr:title", String.class));
        assertFalse(Msm.hasMixin(res(US + comp), "cq:LiveSyncCancelled"));
    }

    @Test
    void cancelledPropertiesKeepTheirLocalValue() throws Exception {
        String comp = firstInheritedChild(ADV + CONTAINER);
        LiveRelationship rel = msm.relationships.getLiveRelationship(res(US + comp), false);
        msm.relationships.cancelPropertyRelationship(resolver, rel, new String[] {"./jcr:title"}, true);
        assertArrayEquals(new String[] {"jcr:title"}, props(US + comp).get("cq:propertyInheritanceCancelled", String[].class));
        set(US + comp, "jcr:title", "Mine");
        set(BLUEPRINT + comp, "jcr:title", "Theirs");
        set(BLUEPRINT + comp, "other", "synced");
        rollout(BLUEPRINT + ADV, false);
        assertEquals("Mine", props(US + comp).get("jcr:title", String.class));
        assertEquals("synced", props(US + comp).get("other", String.class));

        msm.relationships.reenablePropertyRelationship(resolver, rel, new String[] {"jcr:title"}, true);
        assertFalse(Msm.hasMixin(res(US + comp), "cq:PropertyLiveSyncCancelled"));
        rollout(BLUEPRINT + ADV, false);
        assertEquals("Theirs", props(US + comp).get("jcr:title", String.class));
    }

    @Test
    void cancelledChildNodeNamesAreKeptToo() throws Exception {
        LiveRelationship rel = msm.relationships.getLiveRelationship(res(US + ADV + "/jcr:content"), false);
        msm.relationships.cancelPropertyRelationship(resolver, rel, new String[] {"/cq:featuredimage"}, true);
        context.create().resource(US + ADV + "/jcr:content/cq:featuredimage", "fileReference", "/content/dam/mine.jpg");
        context.create().resource(BLUEPRINT + ADV + "/jcr:content/cq:featuredimage", "fileReference", "/content/dam/theirs.jpg");
        rollout(BLUEPRINT + ADV, false);
        assertEquals("/content/dam/mine.jpg", props(US + ADV + "/jcr:content/cq:featuredimage").get("fileReference", String.class));
    }

    @Test
    void newMovedAndDeletedPages() throws Exception {
        page(BLUEPRINT + ADV + "/new-trip", "New trip");
        context.create().resource(BLUEPRINT + ADV + "/new-trip/jcr:content/root", "sling:resourceType", "wknd/components/container");
        page(US + ADV + "/local-trip", "Only in the US");

        RolloutResult r = rollout(BLUEPRINT + ADV, true);
        assertTrue(r.created.contains(US + ADV + "/new-trip"), r.created.toString());
        assertTrue(r.created.contains(CA + ADV + "/new-trip"));
        Resource content = res(US + ADV + "/new-trip/jcr:content");
        assertTrue(Msm.hasMixin(content, "cq:LiveRelationship"));
        assertEquals("New trip", content.getValueMap().get("jcr:title", String.class), "a new page gets all its properties");
        assertTrue(Msm.hasMixin(res(US + ADV + "/new-trip/jcr:content/root"), "cq:LiveRelationship"));
        assertTrue(msm.relationships.hasLiveRelationship(res(US + ADV + "/new-trip")));

        resolver.delete(res(BLUEPRINT + ADV + "/new-trip"));
        resolver.commit();
        r = rollout(BLUEPRINT + ADV, true);
        assertTrue(r.deleted.contains(US + ADV + "/new-trip"), r.deleted.toString());
        assertNull(resolver.getResource(US + ADV + "/new-trip"));
        assertNotNull(resolver.getResource(US + ADV + "/local-trip"), "local pages stay");
    }

    @Test
    void localPageWithTheNameOfANewBlueprintPageIsMovedAside() throws Exception {
        page(US + ADV + "/same-name", "Local");
        page(BLUEPRINT + ADV + "/same-name", "Blueprint");
        RolloutResult r = rollout(BLUEPRINT + ADV, true);
        assertTrue(r.renamed.contains(US + ADV + "/same-name_msm_moved"), r.renamed.toString());
        assertEquals("Local", props(US + ADV + "/same-name_msm_moved/jcr:content").get("jcr:title", String.class));
        assertEquals("Blueprint", props(US + ADV + "/same-name/jcr:content").get("jcr:title", String.class));
        assertTrue(r.created.contains(CA + ADV + "/same-name"));
    }

    @Test
    void withoutConflictHandlingTheLocalPageStays() throws Exception {
        msm.rollouts.conflictHandling = false;
        page(US + ADV + "/same-name", "Local");
        page(BLUEPRINT + ADV + "/same-name", "Blueprint");
        RolloutResult r = rollout(BLUEPRINT + ADV, true);
        assertEquals(List.of(US + ADV + "/same-name"), r.conflicts);
        assertEquals("Local", props(US + ADV + "/same-name/jcr:content").get("jcr:title", String.class));
        assertTrue(r.created.contains(CA + ADV + "/same-name"));
    }

    @Test
    void suspendedPagesAreSkippedUntilReset() throws Exception {
        String page = US + ADV;
        LiveRelationship rel = msm.relationships.getLiveRelationship(res(page), false);
        msm.relationships.cancelRelationship(resolver, rel, false, true);
        String comp = firstInheritedChild(ADV + CONTAINER);
        set(BLUEPRINT + comp, "jcr:title", "While suspended");
        RolloutResult r = rollout(BLUEPRINT + ADV, false);
        assertTrue(r.skipped.contains(page));
        assertFalse("While suspended".equals(props(US + comp).get("jcr:title", String.class)));
        assertTrue(msm.relationships.getLiveRelationship(res(US + comp), true).getStatus().isCancelled(),
                "the components of a suspended page count as cancelled");

        RolloutResult reset = msm.rollouts.synchronize(resolver, page, false, true);
        Msm.commit(resolver);
        assertTrue(reset.updated.contains(page));
        assertEquals("While suspended", props(US + comp).get("jcr:title", String.class));
        assertFalse(Msm.hasMixin(res(page + "/jcr:content"), "cq:LiveSyncCancelled"));
    }

    @Test
    void childrenFollowTheBlueprintOrder() {
        // the mock repository cannot order nodes, so the order itself is checked; JCR applies it
        assertEquals(List.of("b", "local", "a", "c"),
                SyncActions.wantedOrder(List.of("a", "local", "b", "c"), List.of("b", "a", "new", "c")));
        assertEquals(List.of("x", "a"), SyncActions.wantedOrder(List.of("x", "a"), List.of("a")));
    }

    @Test
    void linksIntoTheBlueprintPointToTheLiveCopy() throws Exception {
        context.create().resource(BLUEPRINT + ADV + CONTAINER + "/link-test", "sling:resourceType", "wknd/components/text",
                "text", "<a href=\"" + BLUEPRINT + "/faqs.html\">FAQ</a> <a href=\"" + BLUEPRINT + "/nowhere.html\">x</a>",
                "linkURL", BLUEPRINT + ADV, "other", "/content/wknd/language-masters/en-gb");
        rollout(BLUEPRINT + ADV, false);
        ValueMap t = props(US + ADV + CONTAINER + "/link-test");
        assertEquals("<a href=\"" + US + "/faqs.html\">FAQ</a> <a href=\"" + BLUEPRINT + "/nowhere.html\">x</a>",
                t.get("text", String.class), "only links to pages that exist in the live copy");
        assertEquals(US + ADV, t.get("linkURL", String.class));
        assertEquals("/content/wknd/language-masters/en-gb", t.get("other", String.class));
        assertEquals(BLUEPRINT + ADV, props(BLUEPRINT + ADV + CONTAINER + "/link-test").get("linkURL", String.class));
    }

    @Test
    void createDeepAndShallowLiveCopies() throws Exception {
        context.create().resource("/content/wknd/gb", "jcr:primaryType", "cq:Page");
        RolloutResult r = msm.rollouts.createLiveCopy(resolver, BLUEPRINT, "/content/wknd/gb", "en", "WKND UK", true, null, null);
        Msm.commit(resolver);
        String gb = "/content/wknd/gb/en";
        assertTrue(r.created.contains(gb + ADV), r.created.toString());
        ValueMap cfg = props(gb + "/jcr:content/cq:LiveSyncConfig");
        assertEquals("cq:LiveCopy", cfg.get("jcr:primaryType", String.class));
        assertEquals(BLUEPRINT, cfg.get("cq:master", String.class));
        assertTrue(cfg.get("cq:isDeep", Boolean.class));
        assertFalse(cfg.containsKey("cq:rolloutConfigs"), "no configuration given: inherited (default)");
        ValueMap content = props(gb + "/jcr:content");
        assertEquals("WKND UK", content.get("jcr:title", String.class));
        assertEquals(Set.of("cq:LiveRelationship", "cq:LiveSync"), Msm.mixins(res(gb + "/jcr:content")));
        assertTrue(msm.relationships.hasLiveRelationship(res(gb + ADV)));
        assertTrue(targetsOf(BLUEPRINT).contains(gb), "the blueprint index knows the new live copy");

        RolloutResult shallow = msm.rollouts.createLiveCopy(resolver, BLUEPRINT, "/content/wknd/gb", "shallow", null, false,
                new String[] {"/libs/msm/wcm/rolloutconfigs/pushonmodify"}, null);
        Msm.commit(resolver);
        assertEquals(List.of("/content/wknd/gb/shallow"), shallow.created, "shallow: the root page only");
        assertArrayEquals(new String[] {"/libs/msm/wcm/rolloutconfigs/pushonmodify"},
                props("/content/wknd/gb/shallow/jcr:content/cq:LiveSyncConfig").get("cq:rolloutConfigs", String[].class));
        assertTrue(msm.relationships.getLiveCopy(res("/content/wknd/gb/shallow")).isTrigger(Trigger.MODIFICATION));

        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT, BLUEPRINT, "inside", null, true, null, null));
        assertThrows(WCMException.class, () -> msm.rollouts.createLiveCopy(resolver, BLUEPRINT, "/content/wknd/gb", "en", null, true, null, null));
    }

    private Set<String> targetsOf(String source) {
        Set<String> out = new java.util.TreeSet<>();
        javax.jcr.RangeIterator it = msm.relationships.getLiveRelationships(res(source), null, null);
        while (it.hasNext()) {
            out.add(((LiveRelationship) it.next()).getTargetPath());
        }
        return out;
    }

    @Test
    void detachTheRootOrOneChildPage() throws Exception {
        LiveRelationship child = msm.relationships.getLiveRelationship(res(CA + ADV), false);
        msm.relationships.endRelationship(resolver, child, true);
        assertArrayEquals(new String[] {"adventures"}, props(CA + "/jcr:content/cq:LiveSyncConfig").get("cq:excludedPaths", String[].class));
        assertFalse(msm.relationships.isInLiveCopy(res(CA + ADV)));
        assertFalse(Msm.hasMixin(res(CA + ADV + "/jcr:content"), "cq:LiveRelationship"));
        set(BLUEPRINT + ADV + "/jcr:content", "navTitle", "Changed");
        RolloutResult r = rollout(BLUEPRINT, true);
        assertFalse(r.updated.contains(CA + ADV));
        assertTrue(r.updated.contains(US + ADV));

        LiveRelationship root = msm.relationships.getLiveRelationship(res(US), false);
        msm.relationships.endRelationship(resolver, root, true);
        assertNull(resolver.getResource(US + "/jcr:content/cq:LiveSyncConfig"));
        assertFalse(msm.relationships.isInLiveCopy(res(US + ADV)));
        assertTrue(Msm.mixins(res(US + "/jcr:content")).isEmpty());
        assertFalse(targetsOf(BLUEPRINT).contains(US));
    }

    @Test
    void customActionsFromProjects() throws Exception {
        List<String> seen = new ArrayList<>();
        LiveActionFactory<LiveAction> factory = new LiveActionFactory<LiveAction>() {
            @Override
            public LiveAction createAction(Resource config) {
                return new LiveAction() {
                    @Override
                    public String getName() {
                        return "stampAction";
                    }

                    @Override
                    public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave,
                            boolean reset) {
                        seen.add(relation.getTargetPath());
                    }
                };
            }

            @Override
            public String createsAction() {
                return "stampAction";
            }
        };
        msm.rollouts.addFactory("stampAction", factory);
        context.create().resource("/apps/msm/wcm/rolloutconfigs/custom", "jcr:primaryType", "cq:RolloutConfig",
                "cq:trigger", "rollout");
        context.create().resource("/apps/msm/wcm/rolloutconfigs/custom/contentUpdate", "jcr:primaryType", "cq:LiveSyncAction");
        context.create().resource("/apps/msm/wcm/rolloutconfigs/custom/stampAction", "jcr:primaryType", "cq:LiveSyncAction");
        context.create().resource("/apps/msm/wcm/rolloutconfigs/custom/workflow", "jcr:primaryType", "cq:LiveSyncAction");
        set(US + "/jcr:content/cq:LiveSyncConfig", "cq:rolloutConfigs", new String[] {"/apps/msm/wcm/rolloutconfigs/custom"});
        RolloutResult r = rollout(BLUEPRINT + ADV, false);
        assertTrue(seen.contains(US + ADV + "/jcr:content"), seen.toString());
        assertTrue(seen.stream().noneMatch(p -> p.startsWith(CA)), "ca/en keeps the default configuration");
        assertEquals(List.of("workflow"), r.unknownActions);
    }

    @Test
    void triggerFiltersLiveCopies() throws Exception {
        String comp = firstInheritedChild(ADV + CONTAINER);
        set(BLUEPRINT + comp, "jcr:title", "Pushed");
        RolloutResult none = msm.rollouts.rollout(resolver, BLUEPRINT + ADV, RolloutManagerImpl.Options.of(false, Trigger.MODIFICATION));
        assertTrue(none.updated.isEmpty());
        set(CA + "/jcr:content/cq:LiveSyncConfig", "cq:rolloutConfigs",
                new String[] {"/libs/msm/wcm/rolloutconfigs/default", "/libs/msm/wcm/rolloutconfigs/pushonmodify"});
        RolloutResult pushed = msm.rollouts.rollout(resolver, BLUEPRINT + ADV, RolloutManagerImpl.Options.of(false, Trigger.MODIFICATION));
        Msm.commit(resolver);
        assertEquals(List.of(CA + ADV), pushed.updated);
        assertEquals("Pushed", props(CA + comp).get("jcr:title", String.class));
    }
}
