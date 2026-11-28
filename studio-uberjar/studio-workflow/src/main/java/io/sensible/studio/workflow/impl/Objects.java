/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.Route;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.adobe.granite.workflow.model.WorkflowModel;
import com.adobe.granite.workflow.model.WorkflowNode;
import com.adobe.granite.workflow.model.WorkflowTransition;

/** The Granite workflow API objects over the stored instances and models. */
final class Objects {

    private Objects() {
    }

    static Date date(ValueMap vm, String name) {
        Calendar c = vm.get(name, Calendar.class);
        return c == null ? null : c.getTime();
    }

    /** The payload and metadata of an instance; metadata changes are written back by {@link #save}. */
    static final class DataImpl implements WorkflowData {
        final String payloadType;
        Object payload;
        final Meta metaData;
        private final Resource instance;

        DataImpl(Resource instance) {
            this.instance = instance;
            Resource data = instance == null ? null : instance.getChild("data");
            ValueMap dv = data == null ? ValueMap.EMPTY : data.getValueMap();
            this.payloadType = dv.get("payloadType", "JCR_PATH");
            this.payload = dv.get("payload", String.class);
            this.metaData = Meta.of(instance == null ? null : instance.getChild("metaData"));
        }

        DataImpl(String payloadType, Object payload) {
            this.instance = null;
            this.payloadType = payloadType;
            this.payload = payload;
            this.metaData = new Meta();
        }

        @Override
        public Object getPayload() {
            return payload;
        }

        @Override
        public String getPayloadType() {
            return payloadType;
        }

        @Override
        public MetaDataMap getMetaDataMap() {
            return metaData;
        }

        void save() {
            if (instance == null) {
                return;
            }
            Resource meta = instance.getChild("metaData");
            ModifiableValueMap mv = meta == null ? null : meta.adaptTo(ModifiableValueMap.class);
            if (mv != null) {
                for (Map.Entry<String, Object> e : Meta.storable(metaData).entrySet()) {
                    if (!e.getKey().startsWith("jcr:")) {
                        mv.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
    }

    /** One work item (a step the instance reached). */
    static final class ItemImpl implements WorkItem {
        private final ResourceResolver resolver;
        private final RuntimeModel model;
        private final Resource item;
        private final DataImpl data;
        private final Meta metaData;

        ItemImpl(ResourceResolver resolver, RuntimeModel model, Resource item) {
            this.resolver = resolver;
            this.model = model;
            this.item = item;
            this.data = new DataImpl(item.getParent().getParent());
            this.metaData = Meta.of(item.getChild("metaData"));
        }

        Resource resource() {
            return item;
        }

        /** Writes what a process step changed in the item and workflow metadata. */
        void save() {
            data.save();
            Resource meta = resolver.getResource(item.getPath() + "/metaData");
            ModifiableValueMap mv = meta == null ? null : meta.adaptTo(ModifiableValueMap.class);
            if (mv != null) {
                for (Map.Entry<String, Object> e : Meta.storable(metaData).entrySet()) {
                    if (!e.getKey().startsWith("jcr:")) {
                        mv.put(e.getKey(), e.getValue());
                    }
                }
            }
        }

        @Override
        public String getId() {
            return item.getPath();
        }

        @Override
        public Date getTimeStarted() {
            return date(item.getValueMap(), "startTime");
        }

        @Override
        public Date getTimeEnded() {
            return date(item.getValueMap(), "endTime");
        }

        @Override
        public Workflow getWorkflow() {
            return new WorkflowImpl(resolver, item.getParent().getParent(), model);
        }

        @Override
        public WorkflowNode getNode() {
            return model == null ? null : model.getNode(item.getValueMap().get("nodeId", ""));
        }

        @Override
        public WorkflowData getWorkflowData() {
            return data;
        }

        @Override
        public String getCurrentAssignee() {
            return item.getValueMap().get("assignee", String.class);
        }

        @Override
        public String getItemType() {
            return item.getValueMap().get("type", String.class);
        }

        @Override
        public String getItemSubType() {
            WorkflowNode node = getNode();
            return node == null ? null : node.getMetaDataMap().get("subType", String.class);
        }

        @Override
        public Date getDueTime() {
            return date(item.getValueMap(), "dueTime");
        }

        @Override
        public MetaDataMap getMetaDataMap() {
            return metaData;
        }
    }

    static final class WorkflowImpl implements Workflow {
        private final ResourceResolver resolver;
        private final Resource instance;
        private RuntimeModel model;

        WorkflowImpl(ResourceResolver resolver, Resource instance, RuntimeModel model) {
            this.resolver = resolver;
            this.instance = instance;
            this.model = model;
        }

        @Override
        public String getId() {
            return instance.getPath();
        }

        @Override
        public List<WorkItem> getWorkItems() {
            List<WorkItem> out = new ArrayList<>();
            for (Resource i : Engine.items(instance)) {
                if (Engine.ITEM_ACTIVE.equals(i.getValueMap().get("status", ""))) {
                    out.add(new ItemImpl(resolver, (RuntimeModel) getWorkflowModel(), i));
                }
            }
            return out;
        }

        @Override
        public WorkflowModel getWorkflowModel() {
            if (model == null) {
                model = Engine.modelOf(instance);
            }
            return model;
        }

        @Override
        public boolean isActive() {
            String s = getState();
            return Engine.STATUS_RUNNING.equals(s) || Engine.STATUS_SUSPENDED.equals(s);
        }

        @Override
        public String getState() {
            return instance.getValueMap().get("status", Engine.STATUS_RUNNING);
        }

        @Override
        public String getInitiator() {
            return instance.getValueMap().get("initiator", String.class);
        }

        @Override
        public Date getTimeStarted() {
            return date(instance.getValueMap(), "startTime");
        }

        @Override
        public Date getTimeEnded() {
            return date(instance.getValueMap(), "endTime");
        }

        @Override
        public WorkflowData getWorkflowData() {
            return new DataImpl(instance);
        }

        @Override
        public MetaDataMap getMetaDataMap() {
            return Meta.of(instance.getChild("metaData"));
        }
    }

    static final class RouteImpl implements Route {
        private final String id;
        private final String name;
        private final boolean isDefault;
        private final List<WorkflowTransition> destinations;

        RouteImpl(String id, String name, boolean isDefault, List<WorkflowTransition> destinations) {
            this.id = id;
            this.name = name;
            this.isDefault = isDefault;
            this.destinations = destinations;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean hasDefault() {
            return isDefault;
        }

        @Override
        public List<WorkflowTransition> getDestinations() {
            return destinations;
        }
    }

    /** The routes a work item can be completed along (see {@link WorkflowSession#getRoutes}). */
    static List<Route> routes(RuntimeModel.Node node, boolean expand) {
        List<Route> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (expand && node.out.size() == 1 && WorkflowNode.TYPE_OR_SPLIT.equals(node.out.get(0).to.type)) {
            for (RuntimeModel.Transition t : node.out.get(0).to.out) {
                out.add(new RouteImpl(t.to.id, routeName(t), t.isDefault(), Collections.singletonList(t)));
            }
            return out;
        }
        List<WorkflowTransition> all = new ArrayList<>(node.out);
        String name = node.out.size() == 1 ? routeName(node.out.get(0)) : "Next";
        out.add(new RouteImpl(node.out.size() == 1 ? node.out.get(0).to.id : "default", name, true, all));
        return out;
    }

    private static String routeName(RuntimeModel.Transition t) {
        RuntimeModel.Node to = t.to;
        if (WorkflowNode.TYPE_END.equals(to.type)) {
            return "Finish";
        }
        if (WorkflowNode.TYPE_OR_JOIN.equals(to.type) || WorkflowNode.TYPE_AND_JOIN.equals(to.type)) {
            return to.out.isEmpty() ? "Continue" : routeName(to.out.get(0));
        }
        return to.title == null || to.title.isEmpty() ? to.id : to.title;
    }

    static final class HistoryImpl implements com.adobe.granite.workflow.exec.HistoryItem {
        private final ItemImpl item;
        HistoryImpl previous;
        HistoryImpl next;

        HistoryImpl(ItemImpl item) {
            this.item = item;
        }

        @Override
        public String getAction() {
            return item.resource().getValueMap().get("status", "");
        }

        @Override
        public String getComment() {
            ValueMap vm = item.resource().getValueMap();
            return vm.get("comment", vm.get("failure", String.class));
        }

        @Override
        public Date getDate() {
            Date ended = item.getTimeEnded();
            return ended != null ? ended : item.getTimeStarted();
        }

        @Override
        public String getUserId() {
            ValueMap vm = item.resource().getValueMap();
            return vm.get("completedBy", vm.get("assignee", String.class));
        }

        @Override
        public WorkItem getWorkItem() {
            return item;
        }

        @Override
        public com.adobe.granite.workflow.exec.HistoryItem getNextHistoryItem() {
            return next;
        }

        @Override
        public com.adobe.granite.workflow.exec.HistoryItem getPreviousHistoryItem() {
            return previous;
        }
    }

    static final class ParticipantImpl implements com.adobe.granite.workflow.exec.Participant {
        private final String id;
        private final boolean group;

        ParticipantImpl(String id, boolean group) {
            this.id = id;
            this.group = group;
        }

        @Override
        public String getID() {
            return id;
        }

        @Override
        public String getName() {
            return id;
        }

        @Override
        public boolean isGroup() {
            return group;
        }
    }

    /**
     * A workflow session over a resolver. Reading works everywhere; starting and completing need the service (absent
     * inside a process step, where the step's own item is completed by the engine).
     */
    static final class SessionImpl implements WorkflowSession {
        private final ResourceResolver resolver;
        private final WorkflowService service;

        /** Inside a process step: the engine that runs it. */
        private final Engine.Inside inside;

        SessionImpl(ResourceResolver resolver, WorkflowService service) {
            this(resolver, service, null);
        }

        SessionImpl(ResourceResolver resolver, WorkflowService service, Engine.Inside inside) {
            this.resolver = resolver;
            this.service = service;
            this.inside = inside;
        }

        private WorkflowService service() throws WorkflowException {
            if (service == null) {
                throw new WorkflowException("Not available inside a process step in Sensible Studio");
            }
            return service;
        }

        @Override
        public WorkflowModel[] getModels() {
            List<WorkflowModel> out = new ArrayList<>();
            for (Map<String, Object> m : new ModelStore(resolver).list()) {
                Resource r = resolver.getResource(String.valueOf(m.get("id")));
                if (r != null && r.getChild("nodes") != null) {
                    out.add(RuntimeModel.read(r));
                }
            }
            return out.toArray(new WorkflowModel[0]);
        }

        @Override
        public WorkflowModel getModel(String id) throws WorkflowException {
            Resource r = resolver.getResource(id);
            if (r == null || r.getChild("nodes") == null) {
                throw new WorkflowException("No workflow model " + id);
            }
            return RuntimeModel.read(r);
        }

        @Override
        public Workflow startWorkflow(WorkflowModel model, WorkflowData data) throws WorkflowException {
            return startWorkflow(model, data, null);
        }

        @Override
        public Workflow startWorkflow(WorkflowModel model, WorkflowData data, Map<String, Object> metaData) throws WorkflowException {
            Map<String, Object> meta = metaData == null ? data.getMetaDataMap() : metaData;
            String path = service == null && inside != null
                    ? inside.start(model.getId(), data.getPayloadType(), String.valueOf(data.getPayload()), meta)
                    : service().start(resolver, model.getId(), data.getPayloadType(), String.valueOf(data.getPayload()), meta);
            return getWorkflow(path);
        }

        @Override
        public WorkflowData newWorkflowData(String payloadType, Object payload) {
            return new DataImpl(payloadType, payload);
        }

        @Override
        public Workflow getWorkflow(String id) throws WorkflowException {
            ResourceResolver rr = reader();
            Resource r = rr.getResource(id);
            if (r == null || r.getValueMap().get("modelId") == null || !mine(r, rr)) {
                throw new WorkflowException("No workflow " + id);
            }
            return new WorkflowImpl(rr, r, null);
        }

        @Override
        public Workflow[] getWorkflows(String[] states) {
            List<Workflow> out = new ArrayList<>();
            ResourceResolver rr = reader();
            for (Resource r : Instances.all(rr)) {
                String s = r.getValueMap().get("status", "");
                if ((states == null || states.length == 0 || java.util.Arrays.asList(states).contains(s)) && mine(r, rr)) {
                    out.add(new WorkflowImpl(rr, r, null));
                }
            }
            return out.toArray(new Workflow[0]);
        }

        @Override
        public void terminateWorkflow(Workflow workflow) throws WorkflowException {
            if (service == null && inside != null) {
                inside.terminate(workflow.getId());
                return;
            }
            service().terminate(resolver, workflow.getId());
        }

        @Override
        public void suspendWorkflow(Workflow workflow) throws WorkflowException {
            service().suspend(resolver, workflow.getId(), true);
        }

        @Override
        public void resumeWorkflow(Workflow workflow) throws WorkflowException {
            service().suspend(resolver, workflow.getId(), false);
        }

        @Override
        public WorkItem[] getActiveWorkItems() {
            List<WorkItem> out = new ArrayList<>();
            ResourceResolver rr = reader();
            List<String> me = Instances.principals(resolver, rr);
            for (Resource r : Instances.all(rr)) {
                if (!Engine.STATUS_RUNNING.equals(r.getValueMap().get("status", ""))) {
                    continue;
                }
                for (Resource i : Engine.items(r)) {
                    ValueMap iv = i.getValueMap();
                    if (Engine.ITEM_ACTIVE.equals(iv.get("status", "")) && me.contains(iv.get("assignee", ""))) {
                        out.add(new ItemImpl(rr, Engine.modelOf(r), i)); // with its model: getNode() answered null
                    }
                }
            }
            return out.toArray(new WorkItem[0]);
        }

        @Override
        public WorkItem getWorkItem(String id) throws WorkflowException {
            ResourceResolver rr = reader();
            Resource i = rr.getResource(id);
            if (i == null || i.getValueMap().get("nodeId") == null || !mine(i.getParent().getParent(), rr)) {
                throw new WorkflowException("No work item " + id);
            }
            return new ItemImpl(rr, Engine.modelOf(i.getParent().getParent()), i);
        }

        private static final String READER = "io.sensible.studio.workflow.reader";

        /**
         * What reads the instances. An author's own session may not read {@code /var/workflow}, so
         * code that asks this session for its work items got none; the engine reads for it, and
         * {@link #mine} cuts the answer down to what is the user's. The engine's resolver is kept
         * with the user's and closed with it. Inside a process step, and for an administrator,
         * the session's own resolver reads.
         */
        private ResourceResolver reader() {
            if (service == null || resolver.getResource(Engine.INSTANCES) != null) {
                return resolver;
            }
            Object kept = resolver.getPropertyMap().get(READER);
            if (kept instanceof ResourceResolver && ((ResourceResolver) kept).isLive()) {
                return (ResourceResolver) kept;
            }
            try {
                ResourceResolver engine = service.engineResolver();
                resolver.getPropertyMap().put(READER, engine);
                return engine;
            } catch (org.apache.sling.api.resource.LoginException e) {
                return resolver;
            }
        }

        private boolean mine(Resource instance, ResourceResolver rr) {
            return rr == resolver || Instances.isAdmin(resolver) || Instances.concerns(instance, Instances.principals(resolver, rr));
        }

        @Override
        public List<Route> getRoutes(WorkItem item, boolean expand) {
            return routes((RuntimeModel.Node) item.getNode(), expand);
        }

        @Override
        public void complete(WorkItem item, Route route) throws WorkflowException {
            if (service == null && inside != null) {
                inside.complete(item.getId(), route == null ? null : route.getId());
                return;
            }
            service().complete(resolver, item.getId(), route == null ? null : route.getId(), null);
        }

        @Override
        public List<com.adobe.granite.workflow.exec.HistoryItem> getHistory(Workflow workflow) throws WorkflowException {
            ResourceResolver rr = reader();
            Resource instance = rr.getResource(workflow.getId());
            if (instance == null || !mine(instance, rr)) {
                throw new WorkflowException("No workflow " + workflow.getId());
            }
            RuntimeModel model = Engine.modelOf(instance);
            List<Resource> items = new ArrayList<>(Engine.items(instance));
            items.sort((a, b) -> {
                Date x = date(a.getValueMap(), "startTime");
                Date y = date(b.getValueMap(), "startTime");
                int c = x == null || y == null ? 0 : x.compareTo(y);
                return c != 0 ? c : Integer.compare(a.getName().length(), b.getName().length()) != 0
                        ? Integer.compare(a.getName().length(), b.getName().length()) : a.getName().compareTo(b.getName());
            });
            List<HistoryImpl> out = new ArrayList<>();
            for (Resource i : items) {
                HistoryImpl h = new HistoryImpl(new ItemImpl(rr, model, i));
                if (!out.isEmpty()) {
                    h.previous = out.get(out.size() - 1);
                    h.previous.next = h;
                }
                out.add(h);
            }
            return new ArrayList<>(out);
        }

        @Override
        public void delegateWorkItem(WorkItem item, com.adobe.granite.workflow.exec.Participant participant) throws WorkflowException {
            if (participant == null || participant.getID() == null || participant.getID().trim().isEmpty()) {
                throw new WorkflowException("Say whom the step is for");
            }
            if (service == null && inside != null) {
                inside.delegate(item.getId(), participant.getID().trim());
                return;
            }
            service().delegate(resolver, item.getId(), participant.getID());
        }

        @Override
        public java.util.Iterator<com.adobe.granite.workflow.exec.Participant> getDelegates(WorkItem item) {
            List<com.adobe.granite.workflow.exec.Participant> out = new ArrayList<>();
            javax.jcr.Session session = reader().adaptTo(javax.jcr.Session.class);
            if (session instanceof org.apache.jackrabbit.api.JackrabbitSession) {
                try {
                    java.util.Iterator<org.apache.jackrabbit.api.security.user.Authorizable> all = ((org.apache.jackrabbit.api.JackrabbitSession) session)
                            .getUserManager().findAuthorizables("jcr:primaryType", null);
                    while (all.hasNext() && out.size() < 500) {
                        org.apache.jackrabbit.api.security.user.Authorizable a = all.next();
                        if (a instanceof org.apache.jackrabbit.api.security.user.User && ((org.apache.jackrabbit.api.security.user.User) a).isSystemUser()) {
                            continue;
                        }
                        out.add(new ParticipantImpl(a.getID(), a.isGroup()));
                    }
                } catch (javax.jcr.RepositoryException | RuntimeException e) {
                    // nobody to offer
                }
            }
            return out.iterator();
        }

        @Override
        public WorkItem[] getAllWorkItems() {
            List<WorkItem> out = new ArrayList<>();
            ResourceResolver rr = reader();
            for (Resource r : Instances.all(rr)) {
                if (!mine(r, rr)) {
                    continue;
                }
                for (Resource i : Engine.items(r)) {
                    if (Engine.ITEM_ACTIVE.equals(i.getValueMap().get("status", ""))) {
                        out.add(new ItemImpl(rr, Engine.modelOf(r), i));
                    }
                }
            }
            return out.toArray(new WorkItem[0]);
        }

        @Override
        public Workflow[] getAllWorkflows() {
            return getWorkflows(null);
        }

        @Override
        public void restartWorkflow(Workflow workflow) throws WorkflowException {
            ResourceResolver rr = reader();
            Resource instance = rr.getResource(workflow.getId());
            if (instance == null) {
                throw new WorkflowException("No workflow " + workflow.getId());
            }
            for (Resource i : Engine.items(instance)) {
                if (Engine.ITEM_FAILED.equals(i.getValueMap().get("status", ""))) {
                    service().retry(resolver, i.getPath());
                }
            }
        }

        @Override
        public boolean isSuperuser() {
            return service == null || Instances.isAdmin(resolver);
        }

        @Override
        public void logout() {
            // the session ends with its resolver
        }

        @Override
        public void updateWorkflowData(Workflow workflow, WorkflowData data) throws WorkflowException {
            Resource r = resolver.getResource(workflow.getId() + "/data");
            ModifiableValueMap vm = r == null ? null : r.adaptTo(ModifiableValueMap.class);
            if (vm == null) {
                throw new WorkflowException("Cannot update " + workflow.getId());
            }
            vm.put("payload", String.valueOf(data.getPayload()));
            try {
                resolver.commit();
            } catch (PersistenceException e) {
                throw new WorkflowException(e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type == ResourceResolver.class) {
                return (AdapterType) resolver;
            }
            return resolver.adaptTo(type);
        }
    }
}
