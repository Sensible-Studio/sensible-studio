/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The service end to end, without a job manager (events then run inline). */
@ExtendWith(SlingContextExtension.class)
class WorkflowServiceTest {

    private static final String DESIGN = ModelStore.CUSTOM + "/review";
    private static final String PAGE = "/content/site/en/page";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private WorkflowService service;
    private String model;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "status", "ready");
        context.registerService(com.adobe.granite.workflow.exec.WorkflowProcess.class, new BuiltInProcesses.SetProperties(),
                "process.label", "Set Payload Properties");
        service = context.registerInjectActivateService(new WorkflowService());
        ModelStore store = new ModelStore(context.resourceResolver());
        store.create("Review", "review", null);
        String flow = DESIGN + "/jcr:content/flow";
        String process = String.valueOf(store.addStep(flow, ModelStore.STEP_PROCESS, "Mark", null).get("path"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("PROCESS", "Set Payload Properties");
        meta.put("PROCESS_ARGS", "reviewed=pending");
        context.resourceResolver().getResource(process + "/metaData").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).putAll(meta);
        String participant = String.valueOf(store.addStep(flow, ModelStore.STEP_PARTICIPANT, "Approve", null).get("path"));
        context.resourceResolver().getResource(participant + "/metaData").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("PARTICIPANT", "editors");
        context.resourceResolver().commit();
        model = store.sync(DESIGN).id;
    }

    /** The mock resolver has no user; the service decides by user ID. */
    private ResourceResolver as(String user) {
        return new org.apache.sling.api.wrappers.ResourceResolverWrapper(context.resourceResolver()) {
            @Override
            public String getUserID() {
                return user;
            }
        };
    }

    private Resource instance(String path) {
        context.resourceResolver().refresh();
        return context.resourceResolver().getResource(path);
    }

    private Resource activeItem(String instancePath) {
        for (Resource item : Engine.items(instance(instancePath))) {
            if (Engine.ITEM_ACTIVE.equals(item.getValueMap().get("status", ""))) {
                return item;
            }
        }
        return null;
    }

    @Test
    void startDelegateCompleteAndDelete() throws Exception {
        ResourceResolver admin = as("admin");
        ResourceResolver someone = as("someone");
        String id = service.start(admin, model, null, PAGE, Collections.singletonMap("workflowTitle", "Check"));
        Resource item = activeItem(id);
        assertNotNull(item);
        assertEquals("editors", item.getValueMap().get("assignee", ""));

        String first = item.getPath();
        assertThrows(SecurityException.class, () -> service.delegate(someone, first, "someone"));
        service.delegate(admin, item.getPath(), "reviewers");
        item = activeItem(id);
        assertEquals("reviewers", item.getValueMap().get("assignee", ""));
        assertEquals("editors", item.getValueMap().get("delegatedFrom", ""));
        assertThrows(IllegalArgumentException.class, () -> service.delegate(admin, activeItem(id).getPath(), " "));

        assertThrows(IllegalArgumentException.class, () -> service.deleteInstance(admin, id)); // still running
        assertThrows(SecurityException.class, () -> service.complete(someone, activeItem(id).getPath(), null, null));

        service.suspend(admin, id, true);
        String itemPath = item.getPath();
        assertThrows(IllegalArgumentException.class, () -> service.complete(admin, itemPath, null, "ok"));
        service.suspend(admin, id, false);
        service.complete(admin, itemPath, null, "ok");
        assertEquals(Engine.STATUS_COMPLETED, instance(id).getValueMap().get("status", ""));

        service.deleteInstance(admin, id);
        assertNull(instance(id));
    }

    @Test
    void launcherStartsOncePerPayload() throws Exception {
        Map<String, Object> l = new HashMap<>();
        l.put("glob", "/content/site(/.*)/jcr:content");
        l.put("eventType", "16");
        l.put("nodetype", "cq:PageContent");
        l.put("conditions", new String[] {"status==ready"});
        l.put("workflow", model);
        Launchers.save(context.resourceResolver(), null, l);

        LauncherListener listener = context.registerInjectActivateService(new LauncherListener());
        String content = PAGE + "/jcr:content";
        List<ResourceChange> change = Collections.singletonList(
                new ResourceChange(ChangeType.CHANGED, content, false, null, Collections.singleton("jcr:title"), null));
        listener.onChange(change);
        listener.onChange(change); // the first workflow is still waiting for its participant
        context.resourceResolver().refresh();
        List<Resource> all = Instances.all(context.resourceResolver());
        assertEquals(1, all.size());
        assertEquals(content, all.get(0).getChild("data").getValueMap().get("payload", ""));
        assertEquals(Launchers.CUSTOM, all.get(0).getChild("metaData").getValueMap().get("launcher", "").replaceAll("/[^/]+$", ""));
        // the process step wrote to the payload
        assertEquals("pending", context.resourceResolver().getResource(content).getValueMap().get("reviewed", ""));

        // a change elsewhere, or one that does not satisfy the condition, starts nothing
        listener.onChange(Arrays.asList(new ResourceChange(ChangeType.CHANGED, "/content/other/jcr:content", false, null, null, null)));
        context.resourceResolver().refresh();
        assertEquals(1, Instances.all(context.resourceResolver()).size());
    }

    @Test
    void defaultModelsAreSyncedAndCopiedForEditing() throws Exception {
        String def = ModelStore.DEFAULT + "/request_for_review";
        context.create().resource(def, "jcr:primaryType", "cq:Page");
        context.create().resource(def + "/jcr:content", "jcr:title", "Request for Review", "sling:resourceType", ModelStore.MODEL_PAGE_TYPE);
        context.create().resource(def + "/jcr:content/flow", "sling:resourceType", ModelStore.FLOW_TYPE);
        context.create().resource(def + "/jcr:content/flow/participant", "sling:resourceType", ModelStore.STEP_PARTICIPANT, "jcr:title", "Review");
        context.create().resource(def + "/jcr:content/flow/participant/metaData", "PARTICIPANT", "administrators");
        context.resourceResolver().commit();

        service.syncDefaults();
        context.resourceResolver().refresh();
        assertNotNull(context.resourceResolver().getResource("/var/workflow/models/request_for_review/nodes"));
        service.syncDefaults(); // nothing more to do
        ModelStore store = new ModelStore(context.resourceResolver());
        Map<String, Object> listed = store.list().stream().filter(m -> "request_for_review".equals(m.get("name"))).findFirst().get();
        assertEquals("default", listed.get("type"));
        assertEquals("1.0", listed.get("version"));

        String copy = store.copyToCustom(def);
        assertEquals(ModelStore.CUSTOM + "/request_for_review", copy);
        listed = store.list().stream().filter(m -> "request_for_review".equals(m.get("name"))).findFirst().get();
        assertEquals("custom", listed.get("type"));
        assertEquals(Boolean.FALSE, listed.get("synced"));
        assertEquals(copy, store.copyToCustom(def)); // already copied: the same copy
        assertThrows(IllegalArgumentException.class, () -> store.copyToCustom(copy));
    }

    @Test
    void overdueStepsAdvanceAndEndedWorkflowsArePurged() throws Exception {
        // the participant step times out after one second, handled by the auto advancer
        String participant = DESIGN + "/jcr:content/flow/participant";
        Map<String, Object> timeout = new HashMap<>();
        timeout.put("timeoutMillis", "1");
        timeout.put("timeoutHandler", Engine.AUTO_ADVANCER);
        context.resourceResolver().getResource(participant + "/metaData").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).putAll(timeout);
        context.resourceResolver().commit();
        new ModelStore(context.resourceResolver()).sync(DESIGN);

        String id = service.start(as("admin"), model, null, PAGE, null);
        Resource item = activeItem(id);
        assertNotNull(item.getValueMap().get("dueTime"));
        long later = System.currentTimeMillis() + 5_000;
        WorkflowMaintenance.Result r = WorkflowMaintenance.sweep(context.resourceResolver(), later, 30);
        assertEquals(1, r.overdue.size());
        service.timeout(r.overdue.get(0)[0], r.overdue.get(0)[1]);
        Resource done = instance(id);
        assertEquals(Engine.STATUS_COMPLETED, done.getValueMap().get("status", ""));
        assertEquals(Engine.TIMEOUT_USER, done.getChild("workItems/" + item.getName()).getValueMap().get("completedBy", ""));
        assertEquals(0, WorkflowMaintenance.sweep(context.resourceResolver(), later, 30).overdue.size());

        // kept for 30 days, then purged with its day folder
        assertEquals(0, WorkflowMaintenance.sweep(context.resourceResolver(), later, 30).removed);
        assertEquals(1, WorkflowMaintenance.sweep(context.resourceResolver(), later + 31L * 86_400_000L, 30).removed);
        assertNull(instance(id));
        assertNull(instance(id.substring(0, id.lastIndexOf('/'))));
    }

    @Test
    void transientWorkflowsAreRemovedShortlyAfterTheyEnd() throws Exception {
        context.resourceResolver().getResource(DESIGN + "/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("transient", "true");
        context.resourceResolver().commit();
        new ModelStore(context.resourceResolver()).sync(DESIGN);
        String id = service.start(as("admin"), model, null, PAGE, null);
        assertEquals(Boolean.TRUE, instance(id).getValueMap().get("transient", Boolean.class));
        service.complete(as("admin"), activeItem(id).getPath(), null, null);
        long now = System.currentTimeMillis();
        assertEquals(0, WorkflowMaintenance.sweep(context.resourceResolver(), now, 0).removed); // just ended
        assertEquals(1, WorkflowMaintenance.sweep(context.resourceResolver(), now + 61_000, 0).removed);
        assertNull(instance(id));
    }

    @Test
    void stagesFollowTheSteps() throws Exception {
        org.apache.sling.api.resource.ModifiableValueMap content = context.resourceResolver().getResource(DESIGN + "/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        content.put("cq:workflowStages", new String[] {"Preparation", " ", "Approval"});
        String flow = DESIGN + "/jcr:content/flow";
        context.resourceResolver().getResource(flow + "/process/metaData").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("workflowStage", "Preparation");
        context.resourceResolver().getResource(flow + "/participant/metaData").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("workflowStage", "Approval");
        context.resourceResolver().commit();
        ModelStore store = new ModelStore(context.resourceResolver());
        store.sync(DESIGN);
        assertEquals(Arrays.asList("Preparation", "Approval"), store.design(DESIGN).get("stages"));

        String id = service.start(as("admin"), model, null, PAGE, null);
        Map<String, Object> summary = Instances.summary(instance(id));
        assertEquals(Arrays.asList("Preparation", "Approval"), summary.get("stages"));
        assertEquals("Approval", summary.get("stage"));
        service.complete(as("admin"), activeItem(id).getPath(), null, null);
        assertEquals("Approval", Instances.summary(instance(id)).get("stage")); // ended: the last stage reached
    }

    @Test
    void waitingProcessStepsTimeOutToo() throws Exception {
        org.apache.sling.api.resource.ModifiableValueMap meta = context.resourceResolver()
                .getResource(DESIGN + "/jcr:content/flow/process/metaData").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        meta.put("PROCESS_AUTO_ADVANCE", "false");
        meta.put("timeoutMillis", "1");
        meta.put("timeoutHandler", Engine.AUTO_ADVANCER);
        context.resourceResolver().commit();
        new ModelStore(context.resourceResolver()).sync(DESIGN);

        String id = service.start(as("admin"), model, null, PAGE, null);
        Resource waiting = activeItem(id);
        assertEquals("Mark", waiting.getValueMap().get("nodeTitle", ""));
        assertNotNull(waiting.getValueMap().get("dueTime"));
        WorkflowMaintenance.Result r = WorkflowMaintenance.sweep(context.resourceResolver(), System.currentTimeMillis() + 5_000, 30);
        service.timeout(r.overdue.get(0)[0], r.overdue.get(0)[1]);
        assertEquals("Approve", activeItem(id).getValueMap().get("nodeTitle", "")); // moved on to the participant
    }

    @Test
    void purgeSettingsAndPurgeNow() throws Exception {
        WorkflowMaintenance maintenance = context.registerInjectActivateService(new WorkflowMaintenance());
        assertEquals(30, maintenance.purgeDays());
        maintenance.setPurgeDays(7);
        assertEquals(7, maintenance.purgeDays());
        assertEquals(7, context.getService(org.osgi.service.cm.ConfigurationAdmin.class).getConfiguration(WorkflowMaintenance.PID, "?")
                .getProperties().get("purge.days"));
        assertThrows(IllegalArgumentException.class, () -> maintenance.setPurgeDays(-1));

        String day = Engine.INSTANCES + "/2020-01-01";
        java.util.Calendar old = java.util.Calendar.getInstance();
        old.add(java.util.Calendar.DAY_OF_MONTH, -10);
        context.create().resource(day + "/old_1", "modelId", model, "status", "COMPLETED", "endTime", old);
        context.create().resource(day + "/old_2", "modelId", model, "status", "ABORTED", "endTime", java.util.Calendar.getInstance());
        context.resourceResolver().commit();
        assertEquals(0, maintenance.purgeNow(30));
        assertEquals(1, maintenance.purgeNow(5));
        assertEquals(1, maintenance.lastRemoved());
        assertNull(instance(day + "/old_1"));
        assertNotNull(instance(day + "/old_2"));
    }
}
