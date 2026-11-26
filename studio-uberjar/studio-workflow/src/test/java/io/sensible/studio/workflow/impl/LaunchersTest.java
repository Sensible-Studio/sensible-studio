/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class LaunchersTest {

    private static final String MODEL = "/var/workflow/models/review";
    private static final List<String> WORKFLOW_USERS = Arrays.asList("studio-workflow-service", "workflow-process-service");

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp() {
        context.create().resource(MODEL, "title", "Review");
        context.create().resource(MODEL + "/nodes");
        context.create().resource("/content/site/en/page", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/site/en/page/jcr:content", "jcr:primaryType", "cq:PageContent", "status", "ready",
                "tags", new String[] {"a", "b"});
        context.create().resource("/content/site/en/page/jcr:content/settings", "autoReview", "true");
    }

    private Launchers.Launcher save(Object... kv) throws Exception {
        Map<String, Object> v = new HashMap<>();
        v.put("workflow", MODEL);
        for (int i = 0; i < kv.length; i += 2) {
            v.put((String) kv[i], kv[i + 1]);
        }
        return Launchers.save(context.resourceResolver(), null, v);
    }

    private static Launchers.Change modified(String path, String... props) {
        return new Launchers.Change(Launchers.MODIFIED, path, Arrays.asList(props), "editor");
    }

    private Resource res(String path) {
        return context.resourceResolver().getResource(path);
    }

    @Test
    void savesListsTogglesAndDeletes() throws Exception {
        Launchers.Launcher l = save("glob", "/content/site(/.*)/jcr:content", "eventType", "16", "nodetype", "cq:PageContent",
                "conditions", new String[] {"status==ready", " "}, "excludeList", new String[] {"jcr:lastModified", "event-user-data:changedByWorkflowProcess"},
                "description", "Review edited pages", "enabled", "true");
        assertTrue(l.path.startsWith(Launchers.CUSTOM + "/"));
        assertEquals(Collections.singletonList("status==ready"), l.conditions);
        assertEquals(Arrays.asList("jcr:lastModified", Launchers.BY_WORKFLOW), l.exclude);
        assertEquals(1, Launchers.list(context.resourceResolver()).size());

        Launchers.setEnabled(context.resourceResolver(), l.path, false);
        assertFalse(Launchers.list(context.resourceResolver()).get(0).enabled);

        Map<String, Object> edit = new HashMap<>();
        edit.put("glob", "/content/other(/.*)");
        edit.put("workflow", MODEL);
        edit.put("eventType", "1");
        Launchers.Launcher edited = Launchers.save(context.resourceResolver(), l.path, edit);
        assertEquals(Launchers.CREATED, edited.eventType);
        assertEquals(l.path, edited.path);
        assertTrue(edited.conditions.isEmpty());

        Launchers.delete(context.resourceResolver(), l.path);
        assertTrue(Launchers.list(context.resourceResolver()).isEmpty());
    }

    @Test
    void rejectsInvalidLaunchers() {
        assertThrows(IllegalArgumentException.class, () -> save("glob", ""));
        assertThrows(IllegalArgumentException.class, () -> save("glob", "/content/(unclosed"));
        assertThrows(IllegalArgumentException.class, () -> save("glob", "/content", "workflow", "/var/workflow/models/missing"));
        assertThrows(IllegalArgumentException.class, () -> save("glob", "/content", "eventType", "4"));
        assertThrows(IllegalArgumentException.class, () -> Launchers.delete(context.resourceResolver(), "/libs/settings/workflow/launcher/config/x"));
    }

    @Test
    void defaultLaunchersAreListedAndCustomOnesReplaceThem() throws Exception {
        context.create().resource(Launchers.DEFAULT + "/assets", "glob", "/content/dam(/.*)", "eventType", 1L, "workflow", MODEL,
                "enabled", true, "condition", "dc:format!=");
        context.create().resource(Launchers.DEFAULT + "/pages", "glob", "/content(/.*)", "workflow", MODEL, "enabled", false);
        context.create().resource(Launchers.CUSTOM + "/pages", "glob", "/content/site(/.*)", "workflow", MODEL, "enabled", true);
        List<Launchers.Launcher> all = Launchers.list(context.resourceResolver());
        assertEquals(2, all.size());
        assertEquals("default", all.get(0).toJson().get("type"));
        assertEquals(Collections.singletonList("dc:format!="), all.get(0).conditions);
        assertTrue(all.get(1).custom);
        assertEquals("/content/site(/.*)", all.get(1).glob);
    }

    @Test
    void matchesEventTypePathNodeTypeAndConditions() throws Exception {
        Launchers.Launcher l = save("glob", "/content/site(/.*)/jcr:content", "eventType", "16", "nodetype", "cq:PageContent",
                "conditions", new String[] {"status==ready", "tags==b", "settings/autoReview==true", "missing!=x"});
        String content = "/content/site/en/page/jcr:content";
        assertTrue(Launchers.matches(l, modified(content, "jcr:title"), res(content), WORKFLOW_USERS));
        // other event type, other path, other node type
        assertFalse(Launchers.matches(l, new Launchers.Change(Launchers.CREATED, content, null, "editor"), res(content), WORKFLOW_USERS));
        assertFalse(Launchers.matches(l, modified("/content/site/en/page"), res("/content/site/en/page"), WORKFLOW_USERS));
        assertFalse(Launchers.matches(l, modified("/content/other/jcr:content"), res(content), WORKFLOW_USERS));
        // a condition that does not hold
        res(content).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("status", "draft");
        assertFalse(Launchers.matches(l, modified(content, "status"), res(content), WORKFLOW_USERS));
        // disabled
        Launchers.setEnabled(context.resourceResolver(), l.path, false);
        res(content).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("status", "ready");
        Launchers.Launcher off = Launchers.list(context.resourceResolver()).get(0);
        assertFalse(Launchers.matches(off, modified(content, "jcr:title"), res(content), WORKFLOW_USERS));
    }

    @Test
    void excludeListSkipsBookkeepingChangesAndWorkflowWrites() throws Exception {
        Launchers.Launcher l = save("glob", "/content/site(/.*)/jcr:content", "eventType", "16", "nodetype", "",
                "excludeList", "jcr:lastModified,cq:lastModified.*,event-user-data:changedByWorkflowProcess");
        String content = "/content/site/en/page/jcr:content";
        assertFalse(Launchers.matches(l, modified(content, "jcr:lastModified", "cq:lastModifiedBy"), res(content), WORKFLOW_USERS));
        assertTrue(Launchers.matches(l, modified(content, "jcr:lastModified", "jcr:title"), res(content), WORKFLOW_USERS));
        assertTrue(Launchers.matches(l, modified(content), res(content), WORKFLOW_USERS)); // names unknown: launch
        Launchers.Change byWorkflow = new Launchers.Change(Launchers.MODIFIED, content, Collections.singletonList("status"), "studio-workflow-service");
        assertFalse(Launchers.matches(l, byWorkflow, res(content), WORKFLOW_USERS));
    }

    @Test
    void removalsMatchOnPathAlone() throws Exception {
        Launchers.Launcher l = save("glob", "/content/site(/.*)", "eventType", "2", "nodetype", "cq:Page", "conditions", "status==ready");
        assertTrue(Launchers.matches(l, new Launchers.Change(Launchers.REMOVED, "/content/site/en/gone", null, "editor"), null, WORKFLOW_USERS));
    }

    @Test
    void conditions() {
        Resource r = res("/content/site/en/page/jcr:content");
        assertTrue(Launchers.condition(r, "status==ready"));
        assertFalse(Launchers.condition(r, "status!=ready"));
        assertTrue(Launchers.condition(r, "status!="));
        assertFalse(Launchers.condition(r, "nothing!="));
        assertTrue(Launchers.condition(r, "nothing=="));
        assertTrue(Launchers.condition(r, "tags==a"));
        assertTrue(Launchers.condition(r, "settings/autoReview==true"));
        assertTrue(Launchers.condition(r, "nonsense")); // not understood: ignored
    }

    @Test
    void aPayloadIsBusyWhileItsWorkflowRunsAndJustAfter() {
        String day = Engine.INSTANCES + "/2026-10-17";
        context.create().resource(day + "/review_1", "modelId", MODEL, "status", "RUNNING");
        context.create().resource(day + "/review_1/data", "payload", "/content/a");
        Calendar longAgo = Calendar.getInstance();
        longAgo.add(Calendar.MINUTE, -5);
        context.create().resource(day + "/review_2", "modelId", MODEL, "status", "COMPLETED", "endTime", longAgo);
        context.create().resource(day + "/review_2/data", "payload", "/content/b");
        context.create().resource(day + "/review_3", "modelId", MODEL, "status", "COMPLETED", "endTime", Calendar.getInstance());
        context.create().resource(day + "/review_3/data", "payload", "/content/c");
        assertTrue(WorkflowService.busy(context.resourceResolver(), MODEL, "/content/a"));
        assertFalse(WorkflowService.busy(context.resourceResolver(), MODEL, "/content/b"));
        assertTrue(WorkflowService.busy(context.resourceResolver(), MODEL, "/content/c"));
        assertFalse(WorkflowService.busy(context.resourceResolver(), "/var/workflow/models/other", "/content/a"));
    }
}
