/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.ParticipantStepChooser;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.model.WorkflowNode;

/**
 * Runs workflow instances. An instance lives below {@code /var/workflow/instances/server0/<date>/<name>_<n>}: its
 * state, the payload ({@code data}), the workflow metadata ({@code metaData}) and one {@code workItems} child per
 * step it reached (kept after completion, as its history).
 *
 * <p>Every call ({@link #run}) moves the instance as far as it can: process steps run one after the other until
 * the workflow ends, waits for a participant, or a process step fails or does not advance by itself. AND splits
 * follow every branch and their join waits for all of them; OR splits take the route chosen when the previous
 * participant completed, else the branch whose rule holds, else the default branch, else the first.
 */
final class Engine {

    static final String INSTANCES = "/var/workflow/instances/server0";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_ABORTED = "ABORTED";
    static final String STATUS_SUSPENDED = "SUSPENDED";
    static final String ITEM_ACTIVE = "ACTIVE";
    static final String ITEM_COMPLETED = "COMPLETED";
    static final String ITEM_FAILED = "FAILED";
    static final String ITEM_TERMINATED = "TERMINATED";

    static final String EVENT_START = "start";
    static final String EVENT_COMPLETE = "complete";
    static final String EVENT_RETRY = "retry";

    private static final int MAX_STEPS = 500;
    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);

    /** The process steps and participant choosers registered as services. */
    interface Processes {
        WorkflowProcess process(String nameOrLabel);

        ParticipantStepChooser chooser(String nameOrLabel);
    }

    private final ResourceResolver resolver;
    private final Processes processes;

    Engine(ResourceResolver resolver, Processes processes) {
        this.resolver = resolver;
        this.processes = processes;
    }

    ResourceResolver resolver() {
        return resolver;
    }

    // ---------------------------------------------------------------------------------------------- instances

    /** Creates a running instance of {@code modelId}; {@link #run} with {@link #EVENT_START} moves it. */
    String create(String modelId, String payloadType, String payload, String initiator, Map<String, Object> metaData)
            throws PersistenceException {
        Resource modelRes = resolver.getResource(modelId);
        if (modelRes == null || modelRes.getChild("nodes") == null) {
            throw new IllegalArgumentException("No runtime workflow model at " + modelId + " (sync the model first)");
        }
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalArgumentException("A payload is required");
        }
        if ("JCR_PATH".equals(payloadType) && resolver.getResource(payload) == null) {
            throw new IllegalArgumentException("The payload " + payload + " does not exist");
        }
        Resource day = new ModelStore(resolver).folders(INSTANCES + "/" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        String base = modelId.substring(modelId.lastIndexOf('/') + 1);
        int n = 1;
        while (day.getChild(base + "_" + n) != null) {
            n++;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("modelId", modelId);
        props.put("modelVersion", modelRes.getValueMap().get("version", "1.0"));
        props.put("modelTitle", modelRes.getValueMap().get("title", base));
        props.put("status", STATUS_RUNNING);
        props.put("initiator", initiator);
        props.put("startTime", Calendar.getInstance());
        if ("true".equals(modelRes.getChild("metaData") == null ? null
                : modelRes.getChild("metaData").getValueMap().get("transient", String.class))) {
            props.put("transient", true); // removed by WorkflowMaintenance soon after it ends
        }
        Resource instance = resolver.create(day, base + "_" + n, props);
        Map<String, Object> data = new HashMap<>();
        data.put("jcr:primaryType", "nt:unstructured");
        data.put("payloadType", payloadType == null ? "JCR_PATH" : payloadType);
        data.put("payload", payload.trim());
        resolver.create(instance, "data", data);
        Map<String, Object> meta = new HashMap<>(Meta.storable(metaData == null ? new HashMap<>() : metaData));
        meta.put("jcr:primaryType", "nt:unstructured");
        resolver.create(instance, "metaData", meta);
        resolver.create(instance, "workItems", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        keep(modelRes, instance, OWN_MODEL);
        resolver.commit();
        return instance.getPath();
    }

    /**
     * Handles an event of an instance: {@link #EVENT_START}; {@link #EVENT_COMPLETE} of work item {@code itemId}
     * (optionally along the route to node {@code routeTo}); {@link #EVENT_RETRY} of a failed process step.
     */
    void run(String instancePath, String event, String itemId, String routeTo, String user, String comment)
            throws PersistenceException {
        Resource instance = resolver.getResource(instancePath);
        if (instance == null) {
            throw new IllegalArgumentException("No workflow instance at " + instancePath);
        }
        if (!STATUS_RUNNING.equals(instance.getValueMap().get("status", ""))) {
            LOG.info("Ignoring {} of {}: the workflow is {}", event, instancePath, instance.getValueMap().get("status", ""));
            return;
        }
        RuntimeModel model = model(instance);
        Deque<RuntimeModel.Node> queue = new ArrayDeque<>();
        switch (event) {
            case EVENT_START:
                RuntimeModel.Node start = (RuntimeModel.Node) model.getRootNode();
                queue.addAll(successors(model, start, null, instance));
                break;
            case EVENT_COMPLETE: {
                Resource item = activeItem(instance, itemId);
                RuntimeModel.Node node = model.nodes.get(item.getValueMap().get("nodeId", ""));
                finish(item, ITEM_COMPLETED, user, comment, null);
                if (node != null) {
                    queue.addAll(successors(model, node, routeTo, instance));
                }
                break;
            }
            case EVENT_RETRY: {
                Resource item = instance.getChild("workItems/" + itemId);
                if (item == null || !ITEM_FAILED.equals(item.getValueMap().get("status", ""))) {
                    throw new IllegalArgumentException("No failed step " + itemId + " in " + instancePath);
                }
                RuntimeModel.Node node = model.nodes.get(item.getValueMap().get("nodeId", ""));
                finish(item, ITEM_TERMINATED, user, "Retried", null);
                if (node != null) {
                    queue.add(node);
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown workflow event " + event);
        }
        // what was decided is kept before anything runs: a process step that fails takes back what it
        // wrote itself, and took the completion of the step before it back with it, so that step
        // waited again beside the failed one (28.11.2026)
        resolver.commit();
        drain(model, instance.getPath(), queue);
        resolver.commit();
    }

    /** Ends a running instance: active work items are terminated, the status becomes ABORTED. */
    void terminate(String instancePath, String user) throws PersistenceException {
        Resource instance = resolver.getResource(instancePath);
        if (instance == null) {
            throw new IllegalArgumentException("No workflow instance at " + instancePath);
        }
        for (Resource item : items(instance)) {
            String s = item.getValueMap().get("status", "");
            if (ITEM_ACTIVE.equals(s) || ITEM_FAILED.equals(s)) {
                finish(item, ITEM_TERMINATED, user, "Workflow terminated", null);
            }
        }
        ModifiableValueMap vm = instance.adaptTo(ModifiableValueMap.class);
        vm.put("status", STATUS_ABORTED);
        vm.put("endTime", Calendar.getInstance());
        vm.put("terminatedBy", user);
        resolver.commit();
    }

    void setSuspended(String instancePath, boolean suspended) throws PersistenceException {
        Resource instance = resolver.getResource(instancePath);
        if (instance == null) {
            throw new IllegalArgumentException("No workflow instance at " + instancePath);
        }
        ModifiableValueMap vm = instance.adaptTo(ModifiableValueMap.class);
        String status = vm.get("status", "");
        if (suspended && STATUS_RUNNING.equals(status)) {
            vm.put("status", STATUS_SUSPENDED);
        } else if (!suspended && STATUS_SUSPENDED.equals(status)) {
            vm.put("status", STATUS_RUNNING);
        }
        resolver.commit();
    }

    // ---------------------------------------------------------------------------------------------- execution

    private void drain(RuntimeModel model, String instancePath, Deque<RuntimeModel.Node> queue) throws PersistenceException {
        int steps = 0;
        while (!queue.isEmpty()) {
            if (++steps > MAX_STEPS) {
                LOG.warn("Workflow {} stopped after {} steps without waiting: a loop?", instancePath, MAX_STEPS);
                break;
            }
            Resource instance = resolver.getResource(instancePath);
            if (instance == null || !STATUS_RUNNING.equals(instance.getValueMap().get("status", ""))) {
                break; // a step ended the flow: nothing of it goes on
            }
            RuntimeModel.Node node = queue.poll();
            switch (node.type) {
                case WorkflowNode.TYPE_END:
                    if (!hasActive(instance) && queue.isEmpty()) {
                        ModifiableValueMap vm = instance.adaptTo(ModifiableValueMap.class);
                        vm.put("status", STATUS_COMPLETED);
                        vm.put("endTime", Calendar.getInstance());
                    }
                    break;
                case WorkflowNode.TYPE_START:
                case WorkflowNode.TYPE_OR_JOIN:
                case WorkflowNode.TYPE_AND_SPLIT:
                case WorkflowNode.TYPE_OR_SPLIT:
                    queue.addAll(successors(model, node, null, instance));
                    break;
                case WorkflowNode.TYPE_AND_JOIN: {
                    if (arrive(instance, node)) {
                        queue.addAll(successors(model, node, null, instance));
                    }
                    break;
                }
                case WorkflowNode.TYPE_PARTICIPANT: {
                    String who = assignee(node.metaData.get("PARTICIPANT", String.class));
                    Resource item = newItem(instance, node, who);
                    timeout(item, node);
                    if (!known(who)) {
                        fail(item, NOBODY.replace("{}", who));
                    }
                    break;
                }
                case WorkflowNode.TYPE_DYNAMIC_PARTICIPANT: {
                    Resource item = newItem(instance, node, null);
                    timeout(item, node);
                    String chooserName = node.metaData.get("DYNAMIC_PARTICIPANT", String.class);
                    ParticipantStepChooser chooser = chooserName == null ? null : processes.chooser(chooserName);
                    if (chooser == null) {
                        fail(item, "No participant chooser " + chooserName);
                        break;
                    }
                    try {
                        String who = assignee(chooser.getParticipant(new Objects.ItemImpl(resolver, model, item), session(), node.metaData));
                        item.adaptTo(ModifiableValueMap.class).put("assignee", who);
                        if (!known(who)) {
                            fail(item, NOBODY.replace("{}", who));
                        }
                    } catch (WorkflowException | RuntimeException e) {
                        fail(item, message(e));
                    }
                    break;
                }
                case WorkflowNode.TYPE_PROCESS:
                default:
                    queue.addAll(process(model, instance, node));
                    break;
            }
            resolver.commit();
        }
    }

    /** Runs a process step; returns where the workflow goes next (nothing when it waits or failed). */
    private List<RuntimeModel.Node> process(RuntimeModel model, Resource instance, RuntimeModel.Node node) {
        Resource item = newItem(instance, node, null);
        String name = node.metaData.get("PROCESS", String.class);
        if (ModelStore.GOTO_PROCESS.equals(name)) {
            finish(item, ITEM_COMPLETED, PROCESS_USER, null, null);
            RuntimeModel.Node target = model.nodes.get(String.valueOf(node.metaData.get("TARGET_NODE_ID")));
            String rule = node.metaData.get("rule", String.class);
            if (rule == null) {
                rule = node.metaData.get("script", String.class);
            }
            if (target != null && holds(rule, seenBy(instance, item))) {
                return Collections.singletonList(target);
            }
            return successors(model, node, null, instance);
        }
        WorkflowProcess process = name == null || name.isEmpty() ? null : processes.process(name);
        if (process == null) {
            fail(item, name == null || name.isEmpty() ? "The step has no process" : "No process step " + name + " is registered");
            return Collections.emptyList();
        }
        Objects.ItemImpl workItem = new Objects.ItemImpl(resolver, model, item);
        runningItem = item.getPath();
        runningInstance = instance.getPath();
        completedItself = false;
        chosenRoute = null;
        endedItsFlow = false;
        try {
            process.execute(workItem, session(), new Meta(node.metaData));
            workItem.save();
        } catch (WorkflowException | RuntimeException e) {
            LOG.warn("Process step {} of {} failed", name, instance.getPath(), e);
            aside.clear();
            resolver.revert();
            Resource again = resolver.getResource(item.getPath());
            if (again == null) {
                again = newItem(resolver.getResource(instance.getPath()), node, null);
            }
            fail(again, message(e));
            return Collections.emptyList();
        }
        if (endedItsFlow) {
            aside.clear();
            try {
                terminate(instance.getPath(), PROCESS_USER);
            } catch (PersistenceException e) {
                throw new IllegalStateException("Cannot end " + instance.getPath(), e);
            }
            return Collections.emptyList();
        }
        List<RuntimeModel.Node> also = new ArrayList<>(aside);
        aside.clear();
        if (completedItself) {
            Resource own = resolver.getResource(item.getPath());
            if (own != null && ITEM_ACTIVE.equals(own.getValueMap().get("status", ""))) {
                finish(own, ITEM_COMPLETED, PROCESS_USER, null, null);
            }
            also.addAll(successors(model, node, chosenRoute, resolver.getResource(instance.getPath())));
            return also;
        }
        if ("false".equals(node.metaData.get("PROCESS_AUTO_ADVANCE", String.class))) {
            if (!also.isEmpty()) {
                // it waits itself, but it sent another step of its flow on
                Resource waiting = resolver.getResource(item.getPath());
                if (waiting != null && ITEM_ACTIVE.equals(waiting.getValueMap().get("status", ""))) {
                    timeout(waiting, node);
                }
                return also;
            }
            Resource waiting = resolver.getResource(item.getPath());
            if (waiting != null && ITEM_ACTIVE.equals(waiting.getValueMap().get("status", ""))) {
                timeout(waiting, node); // the process completes the item itself, later, unless it times out
            }
            return Collections.emptyList();
        }
        Resource done = resolver.getResource(item.getPath());
        if (done != null && ITEM_ACTIVE.equals(done.getValueMap().get("status", ""))) {
            finish(done, ITEM_COMPLETED, PROCESS_USER, null, null);
        }
        also.addAll(successors(model, node, null, resolver.getResource(instance.getPath())));
        return also;
    }

    /**
     * Where the workflow goes after {@code node}. An OR split after it takes {@code routeTo} when given (the branch
     * whose first node that is), else the branch whose rule holds, else the default branch, else the first.
     */
    List<RuntimeModel.Node> successors(RuntimeModel model, RuntimeModel.Node node, String routeTo, Resource instance) {
        List<RuntimeModel.Node> out = new ArrayList<>();
        if (WorkflowNode.TYPE_OR_SPLIT.equals(node.type)) {
            RuntimeModel.Transition t = choose(node.out, routeTo, instance);
            if (t != null) {
                out.add(t.to);
            }
            return out;
        }
        for (RuntimeModel.Transition t : node.out) {
            if (routeTo != null && WorkflowNode.TYPE_OR_SPLIT.equals(t.to.type)) {
                out.addAll(successors(model, t.to, routeTo, instance));
            } else if (routeTo != null && node.out.size() > 1 && !t.to.id.equals(routeTo)) {
                continue;
            } else {
                out.add(t.to);
            }
        }
        return out;
    }

    private RuntimeModel.Transition choose(List<RuntimeModel.Transition> options, String routeTo, Resource instance) {
        if (routeTo != null) {
            for (RuntimeModel.Transition t : options) {
                if (t.to.id.equals(routeTo)) {
                    return t;
                }
            }
        }
        for (RuntimeModel.Transition t : options) {
            if (t.rule != null && !t.rule.trim().isEmpty() && holds(t.rule, seenBy(instance, null))) {
                return t;
            }
        }
        for (RuntimeModel.Transition t : options) {
            if (t.isDefault()) {
                return t;
            }
        }
        return options.isEmpty() ? null : options.get(0);
    }

    /**
     * Rules Sensible Studio can evaluate: empty or {@code true} hold, {@code false} does not. Scripts (ECMA) are not run; a rule
     * that is a script counts as not holding, so the default branch is taken.
     */
    static boolean holds(String rule) {
        return holds(rule, null);
    }

    /** With the instance at hand a rule that is a script is run (see {@link Rules}). */
    static boolean holds(String rule, java.util.function.Supplier<Map<String, Object>> vars) {
        if (rule == null || rule.trim().isEmpty()) {
            return true;
        }
        String r = rule.trim();
        if ("true".equalsIgnoreCase(r)) {
            return true;
        }
        if ("false".equalsIgnoreCase(r)) {
            return false;
        }
        if (r.replaceAll("\\s+", "").matches("functioncheck\\(\\)\\{returntrue;?\\}")) {
            return true;
        }
        if (vars == null) {
            return false;
        }
        try {
            Boolean answer = Rules.check(r, vars.get());
            if (answer == null) {
                LOG.warn("A rule without a function check() counts as no: {}", r);
                return false;
            }
            return answer;
        } catch (LinkageError e) {
            LOG.warn("Rule scripts cannot be run here (no Rhino): the rule counts as no: {}", r);
            return false;
        } catch (RuntimeException e) {
            LOG.warn("The rule failed and counts as no: {} ({})", r, e.getMessage());
            return false;
        }
    }

    /** What a rule script sees, as in AEM. */
    private java.util.function.Supplier<Map<String, Object>> seenBy(Resource instance, Resource item) {
        return () -> {
            Map<String, Object> vars = new HashMap<>();
            Resource current = instance == null ? null : resolver.getResource(instance.getPath());
            if (current != null) {
                Objects.WorkflowImpl workflow = new Objects.WorkflowImpl(resolver, current, null);
                vars.put("workflow", workflow);
                vars.put("workflowData", workflow.getWorkflowData());
            }
            if (item != null) {
                vars.put("workItem", new Objects.ItemImpl(resolver, null, item));
            }
            vars.put("workflowSession", session());
            vars.put("jcrSession", resolver.adaptTo(javax.jcr.Session.class));
            vars.put("resolver", resolver);
            return vars;
        };
    }

    /** Counts the arrival of a branch at an AND join; true once all branches arrived (and resets the count). */
    private boolean arrive(Resource instance, RuntimeModel.Node join) {
        ModifiableValueMap vm = instance.adaptTo(ModifiableValueMap.class);
        String key = "join_" + join.id;
        long count = vm.get(key, 0L) + 1;
        if (count >= join.in.size()) {
            vm.remove(key);
            return true;
        }
        vm.put(key, count);
        return false;
    }

    // ---------------------------------------------------------------------------------------------- items

    private Resource newItem(Resource instance, RuntimeModel.Node node, String assignee) {
        try {
            ModifiableValueMap iv = instance.adaptTo(ModifiableValueMap.class);
            long seq = iv.get("itemCounter", 0L) + 1;
            iv.put("itemCounter", seq);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("jcr:primaryType", "nt:unstructured");
            props.put("nodeId", node.id);
            props.put("nodeTitle", node.title == null ? node.id : node.title);
            props.put("type", node.type);
            props.put("status", ITEM_ACTIVE);
            props.put("startTime", Calendar.getInstance());
            if (assignee != null) {
                props.put("assignee", assignee);
            }
            String stage = node.metaData.get("workflowStage", String.class);
            if (stage != null && !stage.isEmpty()) {
                props.put("workflowStage", stage);
            }
            Resource items = instance.getChild("workItems");
            if (items == null) {
                items = resolver.create(instance, "workItems", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
            }
            Resource item = resolver.create(items, "item_" + seq, props);
            resolver.create(item, "metaData", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
            return item;
        } catch (PersistenceException e) {
            throw new IllegalStateException("Cannot record a work item of " + instance.getPath(), e);
        }
    }

    /** AEM's timeout handler that completes an overdue step along its default route. */
    static final String AUTO_ADVANCER = "com.adobe.granite.workflow.console.timeout.autoadvance.AutoAdvancer";
    static final String TIMEOUT_USER = "workflow-timeout";

    /**
     * Records when a participant step is due: {@code timeoutMillis} of the step, which AEM's dialogs store in
     * seconds despite the name, with a {@code timeoutHandler}. {@link WorkflowMaintenance} handles overdue items.
     */
    private static void timeout(Resource item, RuntimeModel.Node node) {
        long seconds;
        try {
            seconds = Long.parseLong(String.valueOf(node.metaData.get("timeoutMillis", "0")).trim());
        } catch (NumberFormatException e) {
            seconds = 0;
        }
        String handler = node.metaData.get("timeoutHandler", String.class);
        if (seconds <= 0 || handler == null || handler.isEmpty()) {
            return;
        }
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(due.getTimeInMillis() + seconds * 1000);
        ModifiableValueMap vm = item.adaptTo(ModifiableValueMap.class);
        vm.put("dueTime", due);
        vm.put("timeoutHandler", handler);
    }

    private static void finish(Resource item, String status, String user, String comment, String failure) {
        ModifiableValueMap vm = item.adaptTo(ModifiableValueMap.class);
        vm.put("status", status);
        vm.put("endTime", Calendar.getInstance());
        if (user != null) {
            vm.put("completedBy", user);
        }
        if (comment != null && !comment.isEmpty()) {
            vm.put("comment", comment);
        }
        if (failure != null) {
            vm.put("failure", failure);
        }
    }

    private static void fail(Resource item, String message) {
        ModifiableValueMap vm = item.adaptTo(ModifiableValueMap.class);
        vm.put("status", ITEM_FAILED);
        vm.put("failure", message);
        vm.put("failedAt", Calendar.getInstance());
    }

    private Resource activeItem(Resource instance, String itemId) {
        Resource item = instance.getChild("workItems/" + itemId);
        if (item == null || !ITEM_ACTIVE.equals(item.getValueMap().get("status", ""))) {
            throw new IllegalArgumentException("No active step " + itemId + " in " + instance.getPath());
        }
        return item;
    }

    static List<Resource> items(Resource instance) {
        List<Resource> out = new ArrayList<>();
        Resource items = instance.getChild("workItems");
        if (items != null) {
            for (Resource i : items.getChildren()) {
                out.add(i);
            }
        }
        return out;
    }

    private static boolean hasActive(Resource instance) {
        for (Resource i : items(instance)) {
            String s = i.getValueMap().get("status", "");
            if (ITEM_ACTIVE.equals(s) || ITEM_FAILED.equals(s)) {
                return true;
            }
        }
        return false;
    }

    RuntimeModel model(Resource instance) {
        RuntimeModel m = modelOf(instance);
        if (m == null) {
            throw new IllegalArgumentException("The model " + instance.getValueMap().get("modelId", "") + " of " + instance.getPath() + " is gone");
        }
        return m;
    }

    /** Under an instance: the model as it was when the instance started. */
    static final String OWN_MODEL = "model";

    /**
     * The model an instance runs on: its own copy, made when it started. A sync writes the runtime
     * model anew, and its steps are numbered by position, so a flow under way would find another step
     * (or none) under the number it stands on. Instances from before the copy read the runtime model.
     */
    static RuntimeModel modelOf(Resource instance) {
        Resource m = modelResource(instance);
        return m == null ? null : RuntimeModel.read(m, instance.getValueMap().get("modelId", m.getPath()));
    }

    static Resource modelResource(Resource instance) {
        Resource own = instance.getChild(OWN_MODEL);
        if (own != null && own.getChild("nodes") != null) {
            return own;
        }
        return instance.getResourceResolver().getResource(instance.getValueMap().get("modelId", ""));
    }

    private void keep(Resource source, Resource parent, String name) throws PersistenceException {
        Map<String, Object> props = new HashMap<>();
        for (Map.Entry<String, Object> e : source.getValueMap().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("jcr:") || k.equals("jcr:title") || k.equals("jcr:description")) {
                props.put(k, e.getValue());
            }
        }
        props.put("jcr:primaryType", "nt:unstructured");
        Resource copy = resolver.create(parent, name, props);
        for (Resource child : source.getChildren()) {
            keep(child, copy, child.getName());
        }
    }

    /** {@code /home/users/a/admin} (as older models store it) -> {@code admin}. */
    /** A step given to nobody would wait for ever in no one's Inbox: it fails instead, and can be retried. */
    static final String NOBODY = "The step is for {}, who is not a user or a group here";

    private boolean known(String id) {
        javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
        if (!(session instanceof org.apache.jackrabbit.api.JackrabbitSession)) {
            return true;
        }
        try {
            return ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager().getAuthorizable(id) != null;
        } catch (javax.jcr.RepositoryException | RuntimeException e) {
            return true; // not known whether: the step waits, as before
        }
    }

    String assignee(String participant) {
        if (participant == null || participant.isEmpty()) {
            return "administrators";
        }
        if (participant.startsWith("/home/")) {
            Resource r = resolver.getResource(participant);
            ValueMap vm = r == null ? null : r.getValueMap();
            String id = vm == null ? null : vm.get("rep:authorizableId", String.class);
            return id != null ? id : participant.substring(participant.lastIndexOf('/') + 1);
        }
        return participant;
    }

    private WorkflowSession session() {
        return new Objects.SessionImpl(resolver, null, new Inside());
    }

    /** Started from inside a step: handed to whoever runs flows, once the new instance is saved. */
    interface Starter {
        void started(String instancePath, String user) throws WorkflowException;
    }

    Starter starter;

    // the step being run, and what it asked for through its session
    private String runningItem;
    private String runningInstance;
    private boolean completedItself;
    private String chosenRoute;
    private boolean endedItsFlow;
    private final Deque<RuntimeModel.Node> aside = new ArrayDeque<>();

    /**
     * What a process step may do to flows through the session it is given, as in AEM: complete its
     * own step along a route it chooses (a step with Handler Advance off), complete another waiting
     * step of its flow, end its flow or another, start a flow. Until 28.11.2026 each of these threw
     * "Not available inside a process step".
     */
    final class Inside {
        void complete(String itemPath, String routeTo) throws WorkflowException {
            Resource item = resolver.getResource(itemPath);
            if (item == null || !ITEM_ACTIVE.equals(item.getValueMap().get("status", ""))) {
                throw new WorkflowException("No waiting step " + itemPath);
            }
            Resource instance = item.getParent().getParent();
            if (!instance.getPath().equals(runningInstance)) {
                throw new WorkflowException("A step completes steps of its own flow only: " + itemPath + " belongs to another");
            }
            if (itemPath.equals(runningItem)) {
                completedItself = true;
                chosenRoute = routeTo;
                return; // the engine finishes the item when the step returns, and goes where it said
            }
            RuntimeModel model = model(instance);
            RuntimeModel.Node node = model.nodes.get(item.getValueMap().get("nodeId", ""));
            finish(item, ITEM_COMPLETED, PROCESS_USER, null, null);
            if (node != null) {
                aside.addAll(successors(model, node, routeTo, instance));
            }
        }

        void terminate(String instancePath) throws WorkflowException {
            if (instancePath.equals(runningInstance)) {
                endedItsFlow = true;
                return;
            }
            try {
                Engine.this.terminate(instancePath, PROCESS_USER);
            } catch (PersistenceException | RuntimeException e) {
                throw new WorkflowException("Cannot end " + instancePath, e);
            }
        }

        String start(String modelId, String payloadType, String payload, Map<String, Object> metaData) throws WorkflowException {
            if (starter == null) {
                throw new WorkflowException("Flows cannot be started from here");
            }
            Resource running = runningInstance == null ? null : resolver.getResource(runningInstance);
            String initiator = running == null ? PROCESS_USER : running.getValueMap().get("initiator", PROCESS_USER);
            try {
                String path = create(modelId, payloadType == null ? "JCR_PATH" : payloadType, payload, initiator,
                        metaData == null ? Collections.emptyMap() : metaData);
                starter.started(path, initiator);
                return path;
            } catch (PersistenceException | RuntimeException e) {
                throw new WorkflowException("Cannot start " + modelId + ": " + e.getMessage(), e);
            }
        }
    }

    static final String PROCESS_USER = "workflow-process-service";

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
