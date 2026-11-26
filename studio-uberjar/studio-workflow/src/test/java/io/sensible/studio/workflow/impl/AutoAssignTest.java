/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Auto-Assign: a page created with a template below a root starts a flow. */
@ExtendWith(SlingContextExtension.class)
class AutoAssignTest {

    private static final String TEMPLATE = "/conf/wknd/settings/wcm/templates/article-page";
    private static final String OTHER = "/conf/wknd/settings/wcm/templates/landing-page";
    private static final String MODEL = "/var/workflow/models/request_for_activation";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = context.resourceResolver();
        context.create().resource(TEMPLATE, "jcr:primaryType", "cq:Template");
        context.create().resource(TEMPLATE + "/jcr:content", "jcr:title", "Article Page");
        context.create().resource(OTHER, "jcr:primaryType", "cq:Template");
        context.create().resource(OTHER + "/jcr:content", "jcr:title", "Landing Page");
        context.create().resource(MODEL, "jcr:primaryType", "cq:WorkflowModel");
        context.create().resource("/content/wknd/us/en", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/other", "jcr:primaryType", "cq:Page");
    }

    private boolean starts(Launchers.Launcher l, String page, String template) {
        context.create().resource(page, "jcr:primaryType", "cq:Page");
        context.create().resource(page + "/jcr:content", "jcr:primaryType", "cq:PageContent", "cq:template", template);
        Launchers.Change change = new Launchers.Change(Launchers.CREATED, page, Collections.emptyList(), "author");
        return Launchers.matches(l, change, resolver.getResource(page), Collections.singletonList("workflow-service"));
    }

    @Test
    void aRuleStartsItsFlowOnPagesOfItsTemplateBelowItsRoot() throws Exception {
        AutoAssign.Rule rule = AutoAssign.save(resolver, null, TEMPLATE, MODEL, "/content/wknd/");
        assertEquals("/content/wknd", rule.rootPath);
        assertTrue(rule.path.startsWith(AutoAssign.STORE + "/"));
        List<Launchers.Launcher> launchers = AutoAssign.launchers(resolver);
        assertEquals(1, launchers.size());
        Launchers.Launcher l = launchers.get(0);
        assertEquals(MODEL, l.workflow);

        assertTrue(starts(l, "/content/wknd/us/en/news", TEMPLATE));
        assertFalse(starts(l, "/content/wknd/us/en/promo", OTHER));
        assertFalse(starts(l, "/content/other/news", TEMPLATE));
        // the root itself is not "created below it"; a look-alike path is not below it either
        assertFalse(l.pattern.matcher("/content/wknd").matches());
        assertFalse(l.pattern.matcher("/content/wkndx/page").matches());
        // a component added to a page is no page
        context.create().resource("/content/wknd/us/en/news/jcr:content/root/text", "sling:resourceType", "wknd/components/text");
        assertFalse(Launchers.matches(l, new Launchers.Change(Launchers.CREATED, "/content/wknd/us/en/news/jcr:content/root/text",
                Collections.emptyList(), "author"), resolver.getResource("/content/wknd/us/en/news/jcr:content/root/text"), Collections.emptyList()));
    }

    @Test
    void checksWhatARuleNames() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AutoAssign.save(resolver, null, "/conf/wknd/nothing", MODEL, "/content/wknd"));
        assertThrows(IllegalArgumentException.class, () -> AutoAssign.save(resolver, null, TEMPLATE, "/var/workflow/models/missing", "/content/wknd"));
        assertThrows(IllegalArgumentException.class, () -> AutoAssign.save(resolver, null, TEMPLATE, MODEL, "/etc/x"));
        assertThrows(IllegalArgumentException.class, () -> AutoAssign.save(resolver, null, TEMPLATE, MODEL, "/content/missing"));
        AutoAssign.save(resolver, null, TEMPLATE, MODEL, "/content/wknd");
        assertThrows(IllegalArgumentException.class, () -> AutoAssign.save(resolver, null, TEMPLATE, MODEL, "/content/wknd"));
    }

    @Test
    void changesAndRemovesRules() throws Exception {
        AutoAssign.Rule rule = AutoAssign.save(resolver, null, TEMPLATE, MODEL, "/content/wknd");
        AutoAssign.Rule changed = AutoAssign.save(resolver, rule.path, OTHER, MODEL, "/content/wknd/us");
        assertEquals(rule.path, changed.path);
        assertEquals(OTHER, changed.template);
        assertEquals(1, AutoAssign.list(resolver).size());
        AutoAssign.delete(resolver, rule.path);
        assertTrue(AutoAssign.list(resolver).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> AutoAssign.delete(resolver, "/conf/global/settings/workflow/launcher/config/x"));
    }

    @Test
    void listsTheTemplatesToChooseFrom() {
        assertEquals(2, AutoAssign.templates(resolver).size());
        assertEquals("Article Page", AutoAssign.templates(resolver).get(0).get("title"));
    }
}
