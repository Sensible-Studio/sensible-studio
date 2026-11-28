/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.exec.ParticipantStepChooser;
import com.adobe.granite.workflow.exec.Route;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.model.WorkflowNode;

@ExtendWith(SlingContextExtension.class)
class EngineTest {

    private static final String MODEL = ModelStore.CUSTOM + "/review";
    private static final String FLOW = MODEL + "/jcr:content/flow";
    private static final String PAGE = "/content/site/en/page";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final Map<String, WorkflowProcess> registered = new HashMap<>();
    private final List<String> ran = new ArrayList<>();
    private Engine engine;
    private ModelStore models;

    private final Engine.Processes processes = new Engine.Processes() {
        @Override
        public WorkflowProcess process(String name) {
            return registered.get(name);
        }

        @Override
        public ParticipantStepChooser chooser(String name) {
            return "initiator".equals(name) ? (item, session, args) -> item.getWorkflow().getInitiator() : null;
        }
    };

    private void step(String path, String type, String title, Object... meta) {
        context.create().resource(path, "sling:resourceType", type, "jcr:title", title);
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < meta.length; i += 2) {
            m.put((String) meta[i], meta[i + 1]);
        }
        context.create().resource(path + "/metaData", m);
    }

    private void model(String path, String title) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        context.create().resource(path + "/jcr:content", "jcr:title", title, "sling:resourceType", ModelStore.MODEL_PAGE_TYPE);
        context.create().resource(path + "/jcr:content/flow", "sling:resourceType", ModelStore.FLOW_TYPE);
    }

    @BeforeEach
    void setUp() {
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:title", "Page");
        registered.put("Set Payload Properties", new BuiltInProcesses.SetProperties());
        registered.put("Record", (item, session, args) -> {
            ran.add(item.getNode().getTitle());
            item.getWorkflowData().getMetaDataMap().put("recorded", ran.size());
        });

        // review: set status, approve (participant), OR [reject: goto approve | publish: record], end
        model(MODEL, "Review");
        step(FLOW + "/process", ModelStore.STEP_PROCESS, "Mark in review", "PROCESS", "Set Payload Properties",
                "PROCESS_ARGS", "status=review", "PROCESS_AUTO_ADVANCE", "true");
        step(FLOW + "/participant", ModelStore.STEP_PARTICIPANT, "Approve", "PARTICIPANT", "reviewers");
        context.create().resource(FLOW + "/or", "sling:resourceType", ModelStore.STEP_OR, "jcr:title", "Decision", "branches", "2");
        context.create().resource(FLOW + "/or/metaData", "default2", "true");
        context.create().resource(FLOW + "/or/1", "sling:resourceType", ModelStore.BRANCH_TYPE);
        step(FLOW + "/or/1/goto", ModelStore.STEP_GOTO, "Reject", "TARGET_NODE_PATH", FLOW + "/participant");
        context.create().resource(FLOW + "/or/2", "sling:resourceType", ModelStore.BRANCH_TYPE);
        step(FLOW + "/or/2/process", ModelStore.STEP_PROCESS, "Publish", "PROCESS", "Record", "PROCESS_AUTO_ADVANCE", "true");

        ResourceResolver rr = context.resourceResolver();
        engine = new Engine(rr, processes);
        models = new ModelStore(rr);
    }

    private Resource instance(String path) {
        return context.resourceResolver().getResource(path);
    }

    private List<Resource> active(String path) {
        return Engine.items(instance(path)).stream().filter(i -> Engine.ITEM_ACTIVE.equals(i.getValueMap().get("status", "")))
                .collect(Collectors.toList());
    }

    @Test
    void syncBuildsTheRuntimeGraph() throws Exception {
        RuntimeModel m = models.sync(MODEL);
        assertEquals("/var/workflow/models/review", m.id);
        List<String> types = m.nodes.values().stream().map(n -> n.type).collect(Collectors.toList());
        assertEquals(List.of("START", "PROCESS", "PARTICIPANT", "OR_SPLIT", "OR_JOIN", "PROCESS", "PROCESS", "END"), types);
        RuntimeModel.Node gotoNode = m.nodes.get("node5");
        assertEquals(ModelStore.GOTO_PROCESS, gotoNode.metaData.get("PROCESS"));
        assertEquals("node2", gotoNode.metaData.get("TARGET_NODE_ID"));
        // stored and read back the same
        RuntimeModel read = RuntimeModel.read(context.resourceResolver().getResource(m.id));
        assertEquals(m.nodes.size(), read.nodes.size());
        assertEquals(m.transitions.size(), read.transitions.size());
        assertTrue(read.nodes.get("node3").out.get(1).isDefault());
        assertEquals("1.0", read.version);
        assertEquals("1.1", models.sync(MODEL).version);
    }

    @Test
    void runsProcessesAndWaitsForParticipants() throws Exception {
        models.sync(MODEL);
        Map<String, Object> meta = new HashMap<>();
        meta.put("workflowTitle", "Review the page");
        String id = engine.create("/var/workflow/models/review", "JCR_PATH", PAGE, "author", meta);
        engine.run(id, Engine.EVENT_START, null, null, "author", null);

        assertEquals("review", context.resourceResolver().getResource(PAGE + "/jcr:content").getValueMap().get("status"));
        List<Resource> waiting = active(id);
        assertEquals(1, waiting.size());
        assertEquals("reviewers", waiting.get(0).getValueMap().get("assignee"));

        // the participant chooses between the two branches of the OR split
        RuntimeModel model = engine.model(instance(id));
        List<Route> routes = Objects.routes(model.nodes.get(waiting.get(0).getValueMap().get("nodeId", "")), true);
        assertEquals(List.of("Reject", "Publish"), routes.stream().map(Route::getName).collect(Collectors.toList()));
        assertTrue(routes.get(1).hasDefault());

        // reject: back to the participant
        engine.run(id, Engine.EVENT_COMPLETE, waiting.get(0).getName(), routes.get(0).getId(), "reviewer", "Fix the title");
        waiting = active(id);
        assertEquals(1, waiting.size());
        assertEquals("Approve", waiting.get(0).getValueMap().get("nodeTitle"));

        // approve without choosing: the default branch
        engine.run(id, Engine.EVENT_COMPLETE, waiting.get(0).getName(), null, "reviewer", null);
        assertEquals(List.of("Publish"), ran);
        assertEquals(Engine.STATUS_COMPLETED, instance(id).getValueMap().get("status"));
        assertEquals(1L, instance(id + "/metaData").getValueMap().get("recorded", 0L));
        assertEquals("Review the page", Instances.summary(instance(id)).get("title"));
    }

    @Test
    void andSplitWaitsForAllBranches() throws Exception {
        String and = ModelStore.CUSTOM + "/parallel";
        model(and, "Parallel");
        String f = and + "/jcr:content/flow";
        context.create().resource(f + "/and", "sling:resourceType", ModelStore.STEP_AND, "branches", "2");
        context.create().resource(f + "/and/1", "sling:resourceType", ModelStore.BRANCH_TYPE);
        step(f + "/and/1/a", ModelStore.STEP_PARTICIPANT, "Legal", "PARTICIPANT", "legal");
        context.create().resource(f + "/and/2", "sling:resourceType", ModelStore.BRANCH_TYPE);
        step(f + "/and/2/b", ModelStore.STEP_PROCESS, "Translate", "PROCESS", "Record");
        step(f + "/after", ModelStore.STEP_PROCESS, "Publish", "PROCESS", "Record");
        models.sync(and);

        String id = engine.create("/var/workflow/models/parallel", "JCR_PATH", PAGE, "author", null);
        engine.run(id, Engine.EVENT_START, null, null, "author", null);
        assertEquals(List.of("Translate"), ran); // the other branch waits for legal
        assertEquals(Engine.STATUS_RUNNING, instance(id).getValueMap().get("status"));
        Resource legal = active(id).get(0);
        engine.run(id, Engine.EVENT_COMPLETE, legal.getName(), null, "legal", null);
        assertEquals(List.of("Translate", "Publish"), ran);
        assertEquals(Engine.STATUS_COMPLETED, instance(id).getValueMap().get("status"));
    }

    @Test
    void failedStepsCanBeRetried() throws Exception {
        String m = ModelStore.CUSTOM + "/fragile";
        model(m, "Fragile");
        step(m + "/jcr:content/flow/process", ModelStore.STEP_PROCESS, "Flaky", "PROCESS", "Flaky");
        step(m + "/jcr:content/flow/who", ModelStore.STEP_DYNAMIC_PARTICIPANT, "Back to you", "DYNAMIC_PARTICIPANT", "initiator");
        models.sync(m);
        String id = engine.create("/var/workflow/models/fragile", "JCR_PATH", PAGE, "author", null);
        engine.run(id, Engine.EVENT_START, null, null, "author", null);
        Map<String, Object> summary = Instances.summary(instance(id));
        assertEquals(true, summary.get("failed"));
        Resource failed = Engine.items(instance(id)).get(0);
        assertEquals("No process step Flaky is registered", failed.getValueMap().get("failure"));

        registered.put("Flaky", (item, session, args) -> {
            throw new WorkflowException("still broken");
        });
        engine.run(id, Engine.EVENT_RETRY, failed.getName(), null, "admin", null);
        Resource second = Engine.items(instance(id)).get(1);
        assertEquals("still broken", second.getValueMap().get("failure"));

        registered.put("Flaky", (item, session, args) -> ran.add("ok"));
        engine.run(id, Engine.EVENT_RETRY, second.getName(), null, "admin", null);
        List<Resource> waiting = active(id);
        assertEquals("author", waiting.get(0).getValueMap().get("assignee"));
        assertFalse(Boolean.TRUE.equals(Instances.summary(instance(id)).get("failed")));
    }

    @Test
    void terminatesAndValidates() throws Exception {
        models.sync(MODEL);
        assertThrows(IllegalArgumentException.class, () -> engine.create("/var/workflow/models/review", "JCR_PATH", "/content/missing", "a", null));
        assertThrows(IllegalArgumentException.class, () -> engine.create("/var/workflow/models/none", "JCR_PATH", PAGE, "a", null));
        String id = engine.create("/var/workflow/models/review", "JCR_PATH", PAGE, "author", null);
        engine.run(id, Engine.EVENT_START, null, null, "author", null);
        engine.terminate(id, "admin");
        assertEquals(Engine.STATUS_ABORTED, instance(id).getValueMap().get("status"));
        assertTrue(active(id).isEmpty());
        // events of a finished workflow are ignored
        engine.run(id, Engine.EVENT_START, null, null, "author", null);
        assertEquals(Engine.STATUS_ABORTED, instance(id).getValueMap().get("status"));
    }

    @Test
    void editsDesignModels() throws Exception {
        Map<String, Object> created = models.create("Hello World", "", "A test");
        assertEquals(ModelStore.CUSTOM + "/hello-world", created.get("designPath"));
        String flow = created.get("flowPath").toString();
        String split = (String) models.addStep(flow, ModelStore.STEP_OR, "Choose", null).get("path");
        assertNotNull(context.resourceResolver().getResource(split + "/2"));
        context.resourceResolver().getResource(split).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("branches", "3");
        models.normalizeBranches(split);
        assertNotNull(context.resourceResolver().getResource(split + "/3"));
        String p = (String) models.addStep(split + "/1", ModelStore.STEP_PROCESS, "Step", null).get("path");
        assertEquals("true", context.resourceResolver().getResource(p + "/metaData").getValueMap().get("PROCESS_AUTO_ADVANCE"));
        models.moveStep(p, flow, null);
        assertNull(context.resourceResolver().getResource(p));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) models.design(ModelStore.CUSTOM + "/hello-world").get("steps");
        assertEquals(2, steps.size());
        assertEquals(3, ((List<?>) steps.get(0).get("branches")).size());
        Map<String, Object> listed = models.list().stream().filter(x -> "hello-world".equals(x.get("name"))).findFirst().get();
        assertEquals(false, listed.get("synced"));
        assertEquals("custom", listed.get("type"));
        models.sync(ModelStore.CUSTOM + "/hello-world");
        listed = models.list().stream().filter(x -> "hello-world".equals(x.get("name"))).findFirst().get();
        assertEquals(true, listed.get("synced"));
        models.delete(ModelStore.CUSTOM + "/hello-world");
        assertNull(context.resourceResolver().getResource("/var/workflow/models/hello-world"));
    }

    @Test
    void rules() {
        assertTrue(Engine.holds(""));
        assertTrue(Engine.holds("function check() {\n\treturn true;\n}"));
        assertFalse(Engine.holds("false"));
        assertFalse(Engine.holds("function check() { return payload.length > 3; }"));
        // with the instance at hand a script is run
        java.util.function.Supplier<java.util.Map<String, Object>> vars = () -> java.util.Collections.singletonMap("payload", "/content/site");
        assertTrue(Engine.holds("function check() { return payload.length > 3; }", vars));
        assertFalse(Engine.holds("function check() { return payload.indexOf('/conf') == 0; }", vars));
        assertTrue(Engine.holds("function check() { return String(payload).indexOf('/content/') == 0 && payload.startsWith('/content'); }", vars));
        assertFalse(Engine.holds("var x = 1;", vars)); // no check(): no
        assertFalse(Engine.holds("function check() { return nothing.here; }", vars)); // it fails: no
        assertEquals(WorkflowNode.TYPE_START, "START");
        assertEquals("a=1", "a=" + BuiltInProcesses.SetProperties.pairs("a = 1, b=").get("a"));
    }
}
