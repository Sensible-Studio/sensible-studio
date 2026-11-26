/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.BLUEPRINT;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.US;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.RolloutConfig;

/** The Blueprint and Live Copy tabs of the page properties. */
@ExtendWith(SlingContextExtension.class)
class PageMsmConfigTest {

    private static final String DEFAULT = "/libs/msm/wcm/rolloutconfigs/default";
    private static final String PUSH = "/libs/msm/wcm/rolloutconfigs/pushonmodify";

    private final SlingContext context = MsmTestSupport.newContext();
    private MsmTestSupport.Services msm;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        msm = MsmTestSupport.setUp(context);
        resolver = context.resourceResolver();
        // the fixture's live copies name their configuration; let the US one inherit
        Msm.writable(resolver.getResource(Msm.configPath(US))).remove("cq:rolloutConfigs");
        Msm.commit(resolver);
    }

    private List<String> configsOf(String page) {
        return new Relationships(resolver, msm.configs).liveCopyOf(page).getRolloutConfigs().stream()
                .map(RolloutConfig::getPath).collect(Collectors.toList());
    }

    @Test
    void liveCopiesUseTheBlueprintsConfigurationsUnlessTheyNameTheirOwn() throws Exception {
        assertEquals(List.of(DEFAULT), configsOf(US + "/adventures"));
        PageMsmConfig.writeBlueprint(resolver, msm.configs, BLUEPRINT, new String[] {PUSH});
        Msm.commit(resolver);
        assertEquals(List.of(PUSH), configsOf(US + "/adventures"));
        assertEquals(List.of(PUSH), PageMsmConfig.blueprint(resolver, BLUEPRINT).get("rolloutConfigs"));

        // a page of the live copy names its own: it and its children use it
        PageMsmConfig.writeLiveCopy(resolver, msm.configs, US + "/adventures", null, new String[] {DEFAULT}, null);
        Msm.commit(resolver);
        assertEquals(List.of(DEFAULT), configsOf(US + "/adventures"));
        Map<String, Object> tab = PageMsmConfig.liveCopy(resolver, US + "/adventures", new Relationships(resolver, msm.configs).liveCopyOf(US + "/adventures"));
        assertEquals(false, tab.get("inheritRolloutConfigs"));
        assertEquals(false, tab.get("root"));

        // back to inheriting: the configuration node goes away again
        PageMsmConfig.writeLiveCopy(resolver, msm.configs, US + "/adventures", null, null, null);
        Msm.commit(resolver);
        assertNull(resolver.getResource(Msm.configPath(US + "/adventures")));
        assertEquals(List.of(PUSH), configsOf(US + "/adventures"));

        PageMsmConfig.writeBlueprint(resolver, msm.configs, BLUEPRINT, new String[0]);
        Msm.commit(resolver);
        assertEquals(List.of(DEFAULT), configsOf(US + "/adventures"));
    }

    @Test
    void theRootHoldsDeepAndExclusions() throws Exception {
        PageMsmConfig.writeLiveCopy(resolver, msm.configs, US, false, null, new String[] {"/adventures", "", "faqs"});
        Msm.commit(resolver);
        LiveCopyImpl lc = new Relationships(resolver, msm.configs).liveCopyOf(US);
        assertFalse(lc.isDeep());
        assertEquals(List.of("adventures", "faqs"), List.copyOf(lc.getExclusions()));
        assertThrows(WCMException.class,
                () -> PageMsmConfig.writeLiveCopy(resolver, msm.configs, US + "/magazine", true, null, null), "not the root");
        assertThrows(WCMException.class,
                () -> PageMsmConfig.writeLiveCopy(resolver, msm.configs, US, null, new String[] {"/libs/nothing"}, null));
    }
}
