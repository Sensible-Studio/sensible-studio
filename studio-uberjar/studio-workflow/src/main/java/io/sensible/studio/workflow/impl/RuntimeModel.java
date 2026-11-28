/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.adobe.granite.workflow.model.WorkflowModel;
import com.adobe.granite.workflow.model.WorkflowNode;
import com.adobe.granite.workflow.model.WorkflowTransition;

/**
 * A runtime workflow model as the engine runs it: nodes and transitions. Stored below
 * {@code /var/workflow/models/<id>} as {@code nodes/<nodeId>} and {@code transitions/<n>}, each with a
 * {@code metaData} child.
 */
final class RuntimeModel implements WorkflowModel {

    final String id;
    /** Changed through the API since it was read: {@code deployModel} writes it as it is. */
    boolean built;
    String title;
    String description;
    String version = "1.0";
    final Meta metaData = new Meta();
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final List<Transition> transitions = new ArrayList<>();

    RuntimeModel(String id) {
        this.id = id;
    }

    Node add(String type, String title, String description, Map<String, Object> metaData) {
        Node n = new Node("node" + nodes.size(), type, title, description, new Meta(metaData));
        nodes.put(n.id, n);
        return n;
    }

    Transition connect(Node from, Node to, String rule, Map<String, Object> metaData) {
        Transition t = new Transition(from, to, rule == null ? "" : rule, new Meta(metaData));
        transitions.add(t);
        from.out.add(t);
        to.in.add(t);
        return t;
    }

    /** Reads a stored runtime model. */
    static RuntimeModel read(Resource model) {
        return read(model, model.getPath());
    }

    /** Reads a model kept somewhere else than under its id: the copy an instance runs on. */
    static RuntimeModel read(Resource model, String id) {
        ValueMap vm = model.getValueMap();
        RuntimeModel m = new RuntimeModel(id);
        m.title = vm.get("title", vm.get("jcr:title", model.getName()));
        m.description = vm.get("description", String.class);
        m.version = vm.get("version", "1.0");
        m.metaData.putAll(Meta.of(model.getChild("metaData")));
        Resource nodes = model.getChild("nodes");
        if (nodes != null) {
            for (Resource n : nodes.getChildren()) {
                ValueMap nv = n.getValueMap();
                Node node = new Node(n.getName(), nv.get("type", WorkflowNode.TYPE_PROCESS), nv.get("title", n.getName()),
                        nv.get("description", String.class), Meta.of(n.getChild("metaData")));
                m.nodes.put(node.id, node);
            }
        }
        Resource transitions = model.getChild("transitions");
        if (transitions != null) {
            for (Resource t : transitions.getChildren()) {
                ValueMap tv = t.getValueMap();
                Node from = m.nodes.get(tv.get("from", ""));
                Node to = m.nodes.get(tv.get("to", ""));
                if (from != null && to != null) {
                    m.connect(from, to, tv.get("rule", ""), Meta.of(t.getChild("metaData")));
                }
            }
        }
        return m;
    }

    /** The node structure to store (see {@link #read}). */
    Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("title", title);
        out.put("description", description);
        out.put("version", version);
        List<Map<String, Object>> ns = new ArrayList<>();
        for (Node n : nodes.values()) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("id", n.id);
            j.put("type", n.type);
            j.put("title", n.title);
            j.put("description", n.description);
            j.put("metaData", n.metaData);
            ns.add(j);
        }
        out.put("nodes", ns);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Transition t : transitions) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("from", t.from.id);
            j.put("to", t.to.id);
            j.put("rule", t.rule);
            j.put("metaData", t.metaData);
            ts.add(j);
        }
        out.put("transitions", ts);
        return out;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public void setTitle(String title) {
        this.title = title;
        built = true;
    }

    @Override
    public void setDescription(String description) {
        this.description = description;
        built = true;
    }

    @Override
    public WorkflowNode createNode() {
        return createNode(null, WorkflowNode.TYPE_PROCESS, null);
    }

    @Override
    public WorkflowNode createNode(String title, String type, String description) {
        begin();
        int n = nodes.size();
        while (nodes.containsKey("node" + n)) {
            n++;
        }
        Node node = new Node("node" + n, type == null ? WorkflowNode.TYPE_PROCESS : type, title, description, new Meta());
        nodes.put(node.id, node);
        return node;
    }

    @Override
    public WorkflowTransition createTransition() {
        begin();
        Transition t = new Transition(null, null, "", new Meta());
        transitions.add(t);
        return t;
    }

    @Override
    public WorkflowTransition createTransition(WorkflowNode from, WorkflowNode to, String rule) {
        begin();
        return connect(own(from), own(to), rule, null);
    }

    @Override
    public void setRootNode(WorkflowNode node) {
        retype(node, WorkflowNode.TYPE_START);
    }

    @Override
    public void setEndNode(WorkflowNode node) {
        retype(node, WorkflowNode.TYPE_END);
    }

    private void retype(WorkflowNode node, String type) {
        built = true;
        for (Node n : nodes.values()) {
            if (type.equals(n.type) && n != node) {
                n.type = WorkflowNode.TYPE_PROCESS;
            }
        }
        own(node).type = type;
    }

    private Node own(WorkflowNode node) {
        Node n = node == null ? null : nodes.get(node.getId());
        if (n == null) {
            throw new IllegalArgumentException("Not a step of this model: " + (node == null ? null : node.getId()));
        }
        return n;
    }

    /**
     * A new model is start and end with one line between them, so that it can run as it is. The first
     * step or line put in by code takes that line away: the code draws its own.
     */
    private void begin() {
        if (!built && nodes.size() == 2 && transitions.size() == 1) {
            Transition only = transitions.get(0);
            if (WorkflowNode.TYPE_START.equals(only.from.type) && WorkflowNode.TYPE_END.equals(only.to.type)) {
                transitions.clear();
                only.from.out.clear();
                only.to.in.clear();
            }
        }
        built = true;
    }

    @Override
    public void validate() throws com.adobe.granite.workflow.model.ValidationException {
        Node start = (Node) getRootNode();
        Node end = (Node) getEndNode();
        if (start == null || end == null) {
            throw new com.adobe.granite.workflow.model.ValidationException("The model needs a start and an end");
        }
        for (Transition t : transitions) {
            if (t.from == null || t.to == null) {
                throw new com.adobe.granite.workflow.model.ValidationException("A transition leads from nowhere or to nowhere");
            }
        }
        for (Node n : nodes.values()) {
            String name = n.title == null || n.title.isEmpty() ? n.id : n.title;
            if (n != end && n.out.isEmpty()) {
                throw new com.adobe.granite.workflow.model.ValidationException("Nothing follows the step " + name);
            }
            if (n != start && n.in.isEmpty()) {
                throw new com.adobe.granite.workflow.model.ValidationException("Nothing leads to the step " + name);
            }
            if (WorkflowNode.TYPE_PROCESS.equals(n.type) && n.metaData.get("PROCESS") == null) {
                throw new com.adobe.granite.workflow.model.ValidationException("The step " + name + " names no process (PROCESS)");
            }
            if (WorkflowNode.TYPE_PARTICIPANT.equals(n.type) && n.metaData.get("PARTICIPANT") == null) {
                throw new com.adobe.granite.workflow.model.ValidationException("The step " + name + " is for nobody (PARTICIPANT)");
            }
            if (WorkflowNode.TYPE_DYNAMIC_PARTICIPANT.equals(n.type) && n.metaData.get("DYNAMIC_PARTICIPANT") == null) {
                throw new com.adobe.granite.workflow.model.ValidationException("The step " + name + " names no chooser (DYNAMIC_PARTICIPANT)");
            }
        }
    }

    @Override
    public List<WorkflowNode> getNodes() {
        return new ArrayList<>(nodes.values());
    }

    @Override
    public WorkflowNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    @Override
    public List<WorkflowTransition> getTransitions() {
        return new ArrayList<>(transitions);
    }

    @Override
    public WorkflowNode getRootNode() {
        for (Node n : nodes.values()) {
            if (WorkflowNode.TYPE_START.equals(n.type)) {
                return n;
            }
        }
        return null;
    }

    @Override
    public WorkflowNode getEndNode() {
        for (Node n : nodes.values()) {
            if (WorkflowNode.TYPE_END.equals(n.type)) {
                return n;
            }
        }
        return null;
    }

    @Override
    public MetaDataMap getMetaDataMap() {
        return metaData;
    }

    static final class Node implements WorkflowNode {
        final String id;
        String type;
        String title;
        String description;
        final Meta metaData;
        final List<Transition> out = new ArrayList<>();
        final List<Transition> in = new ArrayList<>();

        Node(String id, String type, String title, String description, Meta metaData) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.description = description;
            this.metaData = metaData;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public void setTitle(String title) {
            this.title = title;
        }

        @Override
        public void setDescription(String description) {
            this.description = description;
        }

        @Override
        public void setType(String type) {
            this.type = type;
        }

        @Override
        public MetaDataMap getMetaDataMap() {
            return metaData;
        }

        @Override
        public List<WorkflowTransition> getTransitions() {
            return Collections.unmodifiableList(new ArrayList<>(out));
        }

        @Override
        public List<WorkflowTransition> getIncomingTransitions() {
            return Collections.unmodifiableList(new ArrayList<>(in));
        }
    }

    static final class Transition implements WorkflowTransition {
        Node from;
        Node to;
        String rule;
        final Meta metaData;

        Transition(Node from, Node to, String rule, Meta metaData) {
            this.from = from;
            this.to = to;
            this.rule = rule;
            this.metaData = metaData;
        }

        boolean isDefault() {
            return "true".equals(metaData.get("isDefault", String.class));
        }

        @Override
        public WorkflowNode getFrom() {
            return from;
        }

        @Override
        public WorkflowNode getTo() {
            return to;
        }

        @Override
        public String getRule() {
            return rule;
        }

        @Override
        public void setFrom(WorkflowNode node) {
            if (from != null) {
                from.out.remove(this);
            }
            from = (Node) node;
            if (from != null) {
                from.out.add(this);
            }
        }

        @Override
        public void setTo(WorkflowNode node) {
            if (to != null) {
                to.in.remove(this);
            }
            to = (Node) node;
            if (to != null) {
                to.in.add(this);
            }
        }

        @Override
        public void setRule(String rule) {
            this.rule = rule == null ? "" : rule;
        }

        @Override
        public MetaDataMap getMetaDataMap() {
            return metaData;
        }
    }
}
