/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.exec.ParticipantStepChooser;
import com.adobe.granite.workflow.exec.WorkflowProcess;

/**
 * The workflow service: knows the registered process steps and participant choosers, checks who may start,
 * complete or terminate what, and runs the engine in Sling jobs (topic {@value #TOPIC}), so a workflow survives a
 * restart and long process steps do not hold up requests.
 *
 * <p>The engine works with the service user of subservice {@code workflow} ({@code studio-workflow-service}); while
 * that user does not exist yet (repoinit runs at startup), it falls back to an administrative resolver, which the
 * bundle is whitelisted for.
 */
@Component(service = {WorkflowService.class, JobConsumer.class}, property = JobConsumer.PROPERTY_TOPICS + "=" + WorkflowService.TOPIC)
public class WorkflowService implements JobConsumer {

    static final String TOPIC = "studio/workflow/event";
    static final String SUBSERVICE = "workflow";
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile JobManager jobManager;

    private final List<Registered<WorkflowProcess>> processes = new CopyOnWriteArrayList<>();
    private final List<Registered<ParticipantStepChooser>> choosers = new CopyOnWriteArrayList<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    static final class Registered<T> {
        final T service;
        final String label;
        final String className;

        Registered(T service, String label) {
            this(service, label, service.getClass().getName());
        }

        Registered(T service, String label, String className) {
            this.service = service;
            this.label = label;
            this.className = className;
        }
    }

    // ---- steps written for the older API (com.day.cq.workflow): taken in under the Granite one

    /** What stands for an older service here, to find it again when it goes. */
    private final Map<Object, Object> stoodFor = new ConcurrentHashMap<>();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindOldProcess(com.day.cq.workflow.exec.WorkflowProcess process, Map<String, Object> props) {
        WorkflowProcess as = (item, session, args) -> {
            try {
                process.execute(Legacy.old(item, com.day.cq.workflow.exec.WorkItem.class),
                        LegacyWorkflowService.session(session, null), Legacy.old(args, com.day.cq.workflow.metadata.MetaDataMap.class));
            } catch (com.day.cq.workflow.WorkflowException e) {
                throw new com.adobe.granite.workflow.WorkflowException(e.getMessage(), e);
            }
        };
        stoodFor.put(process, as);
        processes.add(new Registered<>(as, label(props, "process.label", process), process.getClass().getName()));
    }

    void unbindOldProcess(com.day.cq.workflow.exec.WorkflowProcess process) {
        Object as = stoodFor.remove(process);
        processes.removeIf(r -> r.service == as);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindJavaProcess(com.day.cq.workflow.exec.JavaProcess process, Map<String, Object> props) {
        WorkflowProcess as = (item, session, args) -> {
            com.day.cq.workflow.exec.WorkItem oldItem = Legacy.old(item, com.day.cq.workflow.exec.WorkItem.class);
            com.day.cq.workflow.WorkflowSession oldSession = LegacyWorkflowService.session(session, null);
            try {
                if (process instanceof com.day.cq.workflow.exec.JavaProcessExt) {
                    String given = args.get("PROCESS_ARGS", "");
                    ((com.day.cq.workflow.exec.JavaProcessExt) process).execute(oldItem, oldSession,
                            given.trim().isEmpty() ? new String[0] : given.split("\\s*,\\s*"));
                } else {
                    process.execute(oldItem, oldSession);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new com.adobe.granite.workflow.WorkflowException(e.getMessage(), e);
            }
        };
        if (stoodFor.putIfAbsent(process, as) != null) {
            return; // registered under both of its types
        }
        processes.add(new Registered<>(as, label(props, "process.label", process), process.getClass().getName()));
    }

    void unbindJavaProcess(com.day.cq.workflow.exec.JavaProcess process) {
        Object as = stoodFor.remove(process);
        processes.removeIf(r -> r.service == as);
    }

    /** A step registered under the wider type only. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindJavaProcessExt(com.day.cq.workflow.exec.JavaProcessExt process, Map<String, Object> props) {
        if (!stoodFor.containsKey(process)) {
            bindJavaProcess(process, props);
        }
    }

    void unbindJavaProcessExt(com.day.cq.workflow.exec.JavaProcessExt process) {
        unbindJavaProcess(process);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindOldChooser(com.day.cq.workflow.exec.ParticipantStepChooser chooser, Map<String, Object> props) {
        ParticipantStepChooser as = (item, session, args) -> {
            try {
                return chooser.getParticipant(Legacy.old(item, com.day.cq.workflow.exec.WorkItem.class),
                        LegacyWorkflowService.session(session, null), Legacy.old(args, com.day.cq.workflow.metadata.MetaDataMap.class));
            } catch (com.day.cq.workflow.WorkflowException e) {
                throw new com.adobe.granite.workflow.WorkflowException(e.getMessage(), e);
            }
        };
        stoodFor.put(chooser, as);
        choosers.add(new Registered<>(as, label(props, "chooser.label", chooser), chooser.getClass().getName()));
    }

    void unbindOldChooser(com.day.cq.workflow.exec.ParticipantStepChooser chooser) {
        Object as = stoodFor.remove(chooser);
        choosers.removeIf(r -> r.service == as);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindProcess(WorkflowProcess process, Map<String, Object> props) {
        processes.add(new Registered<>(process, label(props, "process.label", process)));
    }

    void unbindProcess(WorkflowProcess process) {
        processes.removeIf(r -> r.service == process);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindChooser(ParticipantStepChooser chooser, Map<String, Object> props) {
        choosers.add(new Registered<>(chooser, label(props, "chooser.label", chooser)));
    }

    void unbindChooser(ParticipantStepChooser chooser) {
        choosers.removeIf(r -> r.service == chooser);
    }

    private static String label(Map<String, Object> props, String key, Object service) {
        Object l = props.get(key);
        return l == null ? service.getClass().getName() : String.valueOf(l);
    }

    final Engine.Processes registry = new Engine.Processes() {
        @Override
        public WorkflowProcess process(String name) {
            for (Registered<WorkflowProcess> r : processes) {
                if (name.equals(r.label) || name.equals(r.className)) {
                    return r.service;
                }
            }
            return null;
        }

        @Override
        public ParticipantStepChooser chooser(String name) {
            for (Registered<ParticipantStepChooser> r : choosers) {
                if (name.equals(r.label) || name.equals(r.className)) {
                    return r.service;
                }
            }
            return null;
        }
    };

    /** label -> class name of the registered process steps (the Process select of the step dialog). */
    Map<String, String> processLabels() {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Registered<WorkflowProcess> r : processes) {
            out.put(r.label, r.className);
        }
        return out;
    }

    Map<String, String> chooserLabels() {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Registered<ParticipantStepChooser> r : choosers) {
            out.put(r.label, r.className);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- operations

    /** Starts {@code modelId} on {@code payload} for the user of {@code user}; returns the instance path. */
    String start(ResourceResolver user, String modelId, String payloadType, String payload, Map<String, Object> metaData)
            throws WorkflowException {
        if ("JCR_PATH".equals(payloadType == null ? "JCR_PATH" : payloadType) && user.getResource(payload) == null) {
            throw new IllegalArgumentException("The payload " + payload + " does not exist or cannot be read");
        }
        try (ResourceResolver engineResolver = engineResolver()) {
            String path = new Engine(engineResolver, registry).create(modelId, payloadType == null ? "JCR_PATH" : payloadType,
                    payload, user.getUserID(), metaData == null ? Collections.emptyMap() : metaData);
            enqueue(path, Engine.EVENT_START, null, null, user.getUserID(), null);
            return path;
        } catch (PersistenceException | LoginException e) {
            throw new WorkflowException("Cannot start " + modelId, e);
        }
    }

    /** Completes an active work item; only its assignee (or a member of its group) and administrators may. */
    void complete(ResourceResolver user, String itemId, String routeTo, String comment) throws WorkflowException {
        try (ResourceResolver engineResolver = engineResolver()) {
            Resource item = engineResolver.getResource(itemId);
            if (item == null || item.getValueMap().get("nodeId") == null) {
                throw new IllegalArgumentException("No work item " + itemId);
            }
            ValueMap iv = item.getValueMap();
            if (!Engine.ITEM_ACTIVE.equals(iv.get("status", ""))) {
                throw new IllegalArgumentException("The step is not active any more");
            }
            String assignee = iv.get("assignee", "");
            if (!Instances.isAdmin(user) && !Instances.principals(user, engineResolver).contains(assignee)) {
                throw new SecurityException("The step is assigned to " + assignee);
            }
            Resource instance = item.getParent().getParent();
            if (!Engine.STATUS_RUNNING.equals(instance.getValueMap().get("status", ""))) {
                throw new IllegalArgumentException("The workflow is " + instance.getValueMap().get("status", "").toLowerCase() + "; resume it first");
            }
            enqueue(instance.getPath(), Engine.EVENT_COMPLETE, item.getName(), routeTo, user.getUserID(), comment);
        } catch (LoginException e) {
            throw new WorkflowException(e);
        }
    }

    /** Gives an active work item to another user or group; its assignee (or a member of its group) and administrators may. */
    void delegate(ResourceResolver user, String itemId, String assignee) throws WorkflowException {
        if (assignee == null || assignee.trim().isEmpty()) {
            throw new IllegalArgumentException("Choose whom to delegate to");
        }
        String instancePath = itemId.contains("/workItems/") ? itemId.substring(0, itemId.indexOf("/workItems/")) : itemId;
        withEngine(instancePath, e -> {
            Resource item = e.resolver().getResource(itemId);
            if (item == null || item.getValueMap().get("nodeId") == null) {
                throw new IllegalArgumentException("No work item " + itemId);
            }
            ValueMap iv = item.getValueMap();
            if (!Engine.ITEM_ACTIVE.equals(iv.get("status", ""))) {
                throw new IllegalArgumentException("The step is not active any more");
            }
            String current = iv.get("assignee", "");
            if (!Instances.isAdmin(user) && !Instances.principals(user, e.resolver()).contains(current)) {
                throw new SecurityException("The step is assigned to " + current);
            }
            org.apache.sling.api.resource.ModifiableValueMap mv = item.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
            mv.put("assignee", assignee.trim());
            mv.put("delegatedFrom", current);
            mv.put("delegatedBy", user.getUserID());
            mv.put("delegatedTime", java.util.Calendar.getInstance());
            e.resolver().commit();
        });
    }

    /** Completes an overdue step along its default route (the auto advancer timeout handler). */
    void timeout(String instancePath, String itemName) throws WorkflowException {
        withEngine(instancePath, e -> {
            Resource item = e.resolver().getResource(instancePath + "/workItems/" + itemName);
            if (item == null || !Engine.ITEM_ACTIVE.equals(item.getValueMap().get("status", ""))) {
                return;
            }
            item.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("timeoutFired", true);
            e.resolver().commit();
        });
        enqueue(instancePath, Engine.EVENT_COMPLETE, itemName, null, Engine.TIMEOUT_USER, "Timed out");
    }

    /** Removes finished (completed or aborted) instances; administrators only. */
    void deleteInstance(ResourceResolver user, String instancePath) throws WorkflowException {
        requireAdmin(user);
        withEngine(instancePath, e -> {
            Resource r = e.resolver().getResource(instancePath);
            if (r == null || r.getValueMap().get("modelId") == null) {
                throw new IllegalArgumentException("No workflow instance " + instancePath);
            }
            String status = r.getValueMap().get("status", "");
            if (!Engine.STATUS_COMPLETED.equals(status) && !Engine.STATUS_ABORTED.equals(status)) {
                throw new IllegalArgumentException("Only completed or terminated workflows can be deleted");
            }
            e.resolver().delete(r);
            e.resolver().commit();
        });
    }

    /** Writes the runtime models of default models that were installed without one (packages bring only designs). */
    void syncDefaults() {
        try (ResourceResolver engineResolver = engineResolver()) {
            int n = new ModelStore(engineResolver).syncDefaults();
            if (n > 0) {
                LOG.info("Synced {} default workflow model(s)", n);
            }
        } catch (LoginException | PersistenceException | RuntimeException e) {
            LOG.warn("Could not sync the default workflow models", e);
        }
    }

    // ---------------------------------------------------------------------------------------------- launchers

    private static final long RECENT_MS = 10_000;
    private final Object launchLock = new Object();

    /** The user the engine writes as once the service user exists. */
    String serviceUserId() {
        return "studio-workflow-service";
    }

    /**
     * Starts the model of {@code launcher} on {@code payload}, unless an instance of that model is running or
     * suspended on it, or ended on it in the last seconds (the workflow's own writes to its payload must not start it
     * again).
     */
    void launch(Launchers.Launcher launcher, String payload, String user) {
        String path;
        synchronized (launchLock) {
            try (ResourceResolver engineResolver = engineResolver()) {
                if (engineResolver.getResource(launcher.workflow) == null) {
                    LOG.warn("Launcher {}: no runtime model {}", launcher.path, launcher.workflow);
                    return;
                }
                if (busy(engineResolver, launcher.workflow, payload)) {
                    LOG.debug("Launcher {}: {} already has {}", launcher.path, payload, launcher.workflow);
                    return;
                }
                Map<String, Object> meta = new HashMap<>();
                meta.put("launcher", launcher.path);
                // a removed node cannot be a JCR_PATH payload any more; its path is still what the workflow is about
                String payloadType = engineResolver.getResource(payload) == null ? "PATH" : "JCR_PATH";
                path = new Engine(engineResolver, registry).create(launcher.workflow, payloadType, payload,
                        user == null || user.isEmpty() ? "workflow-launcher" : user, meta);
            } catch (PersistenceException | LoginException | RuntimeException e) {
                LOG.warn("Launcher {} could not start {} on {}", launcher.path, launcher.workflow, payload, e);
                return;
            }
        }
        try {
            enqueue(path, Engine.EVENT_START, null, null, user, null);
            LOG.info("Launcher {} started {} on {}", launcher.name, path, payload);
        } catch (WorkflowException e) {
            LOG.warn("Launcher {} could not run {}", launcher.path, path, e);
        }
    }

    static boolean busy(ResourceResolver resolver, String modelId, String payload) {
        long now = System.currentTimeMillis();
        for (Resource i : Instances.all(resolver)) {
            ValueMap vm = i.getValueMap();
            if (!modelId.equals(vm.get("modelId", ""))) {
                continue;
            }
            Resource data = i.getChild("data");
            if (data == null || !payload.equals(data.getValueMap().get("payload", ""))) {
                continue;
            }
            String status = vm.get("status", "");
            if (Engine.STATUS_RUNNING.equals(status) || Engine.STATUS_SUSPENDED.equals(status)) {
                return true;
            }
            java.util.Calendar end = vm.get("endTime", java.util.Calendar.class);
            if (end != null && now - end.getTimeInMillis() < RECENT_MS) {
                return true;
            }
        }
        return false;
    }

    void retry(ResourceResolver user, String itemId) throws WorkflowException {
        requireAdmin(user);
        if (!itemId.contains("/workItems/")) {
            throw new IllegalArgumentException("Not a step of a flow: " + itemId);
        }
        String instance = itemId.substring(0, itemId.indexOf("/workItems/"));
        enqueue(instance, Engine.EVENT_RETRY, itemId.substring(itemId.lastIndexOf('/') + 1), null, user.getUserID(), null);
    }

    void terminate(ResourceResolver user, String instancePath) throws WorkflowException {
        requireOwnerOrAdmin(user, instancePath);
        withEngine(instancePath, e -> e.terminate(instancePath, user.getUserID()));
    }

    void suspend(ResourceResolver user, String instancePath, boolean suspended) throws WorkflowException {
        requireOwnerOrAdmin(user, instancePath);
        withEngine(instancePath, e -> e.setSuspended(instancePath, suspended));
        if (!suspended) {
            LOG.debug("Resumed {}", instancePath);
        }
    }

    private void requireAdmin(ResourceResolver user) {
        if (!Instances.isAdmin(user)) {
            throw new SecurityException("Only administrators may do this");
        }
    }

    private void requireOwnerOrAdmin(ResourceResolver user, String instancePath) throws WorkflowException {
        if (Instances.isAdmin(user)) {
            return;
        }
        try (ResourceResolver engineResolver = engineResolver()) {
            Resource r = engineResolver.getResource(instancePath);
            if (r == null || !String.valueOf(user.getUserID()).equals(r.getValueMap().get("initiator", String.class))) {
                throw new SecurityException("Only the initiator and administrators may do this");
            }
        } catch (LoginException e) {
            throw new WorkflowException(e);
        }
    }

    interface EngineWork {
        void run(Engine engine) throws PersistenceException, WorkflowException;
    }

    private void withEngine(String instancePath, EngineWork work) throws WorkflowException {
        synchronized (locks.computeIfAbsent(instancePath, k -> new Object())) {
            try (ResourceResolver engineResolver = engineResolver()) {
                Engine engine = new Engine(engineResolver, registry);
                engine.starter = (path, user) -> enqueue(path, Engine.EVENT_START, null, null, user, null);
                work.run(engine);
            } catch (PersistenceException | LoginException e) {
                throw new WorkflowException(e);
            }
        }
    }

    private void enqueue(String instance, String event, String item, String routeTo, String user, String comment) throws WorkflowException {
        Map<String, Object> props = new HashMap<>();
        props.put("instance", instance);
        props.put("event", event);
        if (item != null) {
            props.put("item", item);
        }
        if (routeTo != null) {
            props.put("route", routeTo);
        }
        if (user != null) {
            props.put("user", user);
        }
        if (comment != null) {
            props.put("comment", comment);
        }
        JobManager jobs = jobManager;
        if (jobs != null && jobs.addJob(TOPIC, props) != null) {
            return;
        }
        // no job manager: run now
        handle(props);
    }

    @Override
    public JobResult process(Job job) {
        Map<String, Object> props = new HashMap<>();
        for (String name : job.getPropertyNames()) {
            props.put(name, job.getProperty(name));
        }
        try {
            handle(props);
            return JobResult.OK;
        } catch (IllegalArgumentException e) {
            LOG.warn("Workflow event {} dropped: {}", props, e.getMessage());
            return JobResult.CANCEL;
        } catch (WorkflowException | RuntimeException e) {
            LOG.error("Workflow event {} failed", props, e);
            return JobResult.FAILED;
        }
    }

    private void handle(Map<String, Object> p) throws WorkflowException {
        String instance = String.valueOf(p.get("instance"));
        withEngine(instance, e -> e.run(instance, String.valueOf(p.get("event")), (String) p.get("item"), (String) p.get("route"),
                (String) p.get("user"), (String) p.get("comment")));
    }

    @SuppressWarnings("deprecation")
    ResourceResolver engineResolver() throws LoginException {
        try {
            return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
        } catch (LoginException e) {
            return resolverFactory.getAdministrativeResourceResolver(null);
        }
    }
}
