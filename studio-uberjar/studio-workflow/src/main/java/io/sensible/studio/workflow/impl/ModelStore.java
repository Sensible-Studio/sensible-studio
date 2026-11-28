/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.adobe.granite.workflow.model.WorkflowNode;

/**
 * Workflow models as AEM keeps them. The design model, edited in the model editor, is a page below
 * {@code /conf/global/settings/workflow/models} (custom) or {@code /libs/settings/workflow/models} (default) whose
 * {@code jcr:content/flow} holds the steps in order; OR and AND splits hold one {@code cq/flow/components/parsys} per
 * branch. Syncing turns it into the runtime model below {@code /var/workflow/models}, a graph of nodes and
 * transitions the engine runs.
 */
final class ModelStore {

    static final String CUSTOM = "/conf/global/settings/workflow/models";
    static final String DEFAULT = "/libs/settings/workflow/models";
    static final String RUNTIME = "/var/workflow/models";
    static final String MODEL_PAGE_TYPE = "cq/workflow/components/pages/model";
    static final String FLOW_TYPE = "foundation/components/parsys";
    static final String BRANCH_TYPE = "cq/flow/components/parsys";

    static final String STEP_PROCESS = "cq/workflow/components/model/process";
    static final String STEP_PARTICIPANT = "cq/workflow/components/model/participant";
    static final String STEP_DYNAMIC_PARTICIPANT = "cq/workflow/components/model/dynamic_participant";
    static final String STEP_OR = "cq/workflow/components/model/or";
    static final String STEP_AND = "cq/workflow/components/model/and";
    static final String STEP_GOTO = "cq/workflow/components/model/goto";

    static final String GOTO_PROCESS = "com.adobe.granite.workflow.core.process.GotoProcess";
    static final String DIRTY = "studio:modified";
    static final String SYNCED = "studio:synced";
    /** On a runtime model: the fingerprint of the design it was made from. */
    static final String DESIGN = "studio:design";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*");

    private final ResourceResolver resolver;

    ModelStore(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    // ---------------------------------------------------------------------------------------------- listing

    /** All models: design models (custom first wins over default) and runtime models without a design. */
    List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        collectDesign(DEFAULT, DEFAULT, false, byName);
        collectDesign(CUSTOM, CUSTOM, true, byName);
        Resource runtime = resolver.getResource(RUNTIME);
        if (runtime != null) {
            collectRuntime(runtime, byName);
        }
        List<Map<String, Object>> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    private void collectDesign(String root, String base, boolean custom, Map<String, Map<String, Object>> out) {
        Resource r = resolver.getResource(root);
        if (r == null) {
            return;
        }
        for (Resource child : r.getChildren()) {
            Resource content = child.getChild("jcr:content");
            if (content != null && MODEL_PAGE_TYPE.equals(content.getValueMap().get("sling:resourceType", String.class))) {
                String name = child.getPath().substring(base.length() + 1);
                out.put(name, summary(name, child, custom));
            } else if (!child.getName().startsWith("jcr:") && !child.getName().startsWith("rep:")) {
                collectDesign(child.getPath(), base, custom, out);
            }
        }
    }

    private void collectRuntime(Resource folder, Map<String, Map<String, Object>> out) {
        for (Resource child : folder.getChildren()) {
            if (child.getChild("nodes") != null) {
                String name = child.getPath().substring(RUNTIME.length() + 1);
                out.computeIfAbsent(name, n -> {
                    ValueMap vm = child.getValueMap();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", child.getPath());
                    m.put("name", n);
                    m.put("title", vm.get("title", n));
                    m.put("description", vm.get("description", String.class));
                    m.put("version", vm.get("version", "1.0"));
                    m.put("type", "runtime");
                    m.put("editable", false);
                    m.put("synced", true);
                    return m;
                });
            } else if (!child.getName().startsWith("jcr:") && !child.getName().startsWith("rep:")) {
                collectRuntime(child, out);
            }
        }
    }

    /** The stages of a model ({@code cq:workflowStages} of the design), in order. */
    static String[] stages(ValueMap content) {
        String[] s = content.get("cq:workflowStages", String[].class);
        if (s == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(s).filter(x -> x != null && !x.trim().isEmpty()).map(String::trim).toArray(String[]::new);
    }

    private Map<String, Object> summary(String name, Resource page, boolean custom) {
        ValueMap content = page.getChild("jcr:content").getValueMap();
        Resource runtime = resolver.getResource(RUNTIME + "/" + name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", RUNTIME + "/" + name);
        m.put("name", name);
        m.put("designPath", page.getPath());
        m.put("title", content.get("jcr:title", page.getName()));
        m.put("description", content.get("jcr:description", String.class));
        m.put("type", custom ? "custom" : "default");
        m.put("editable", custom);
        m.put("transient", "true".equals(content.get("transient", String.class)));
        m.put("stages", java.util.Arrays.asList(stages(content)));
        m.put("version", runtime == null ? null : runtime.getValueMap().get("version", "1.0"));
        m.put("synced", runtime != null && !content.get(DIRTY, false));
        m.put("lastModified", iso(content.get("cq:lastModified", content.get("jcr:created", Calendar.class))));
        m.put("lastModifiedBy", content.get("cq:lastModifiedBy", content.get("jcr:createdBy", String.class)));
        return m;
    }

    // ---------------------------------------------------------------------------------------------- design

    /** The design model at {@code designPath} as a tree of steps for the editor. */
    Map<String, Object> design(String designPath) {
        Resource page = requireDesign(designPath);
        Resource content = page.getChild("jcr:content");
        Map<String, Object> out = new LinkedHashMap<>(summary(nameOf(page), page, page.getPath().startsWith(CUSTOM + "/")));
        Resource flow = content.getChild("flow");
        out.put("flowPath", content.getPath() + "/flow");
        out.put("steps", flow == null ? Collections.emptyList() : steps(flow));
        return out;
    }

    private String nameOf(Resource page) {
        String p = page.getPath();
        return p.startsWith(CUSTOM + "/") ? p.substring(CUSTOM.length() + 1) : p.substring(DEFAULT.length() + 1);
    }

    private List<Map<String, Object>> steps(Resource parsys) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Resource step : parsys.getChildren()) {
            String rt = step.getValueMap().get("sling:resourceType", String.class);
            if (rt == null) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("path", step.getPath());
            s.put("name", step.getName());
            s.put("resourceType", rt);
            s.put("title", step.getValueMap().get("jcr:title", String.class));
            s.put("description", step.getValueMap().get("jcr:description", String.class));
            Map<String, Object> meta = new LinkedHashMap<>(Meta.of(step.getChild("metaData")));
            s.put("metaData", meta);
            if (isSplit(rt)) {
                List<Map<String, Object>> branches = new ArrayList<>();
                for (Resource b : branchesOf(step)) {
                    Map<String, Object> bj = new LinkedHashMap<>();
                    bj.put("path", b.getPath());
                    bj.put("name", b.getName());
                    bj.put("steps", steps(b));
                    branches.add(bj);
                }
                s.put("branches", branches);
                s.put("split", STEP_AND.equals(rt) ? "and" : "or");
            }
            out.add(s);
        }
        return out;
    }

    static boolean isSplit(String rt) {
        return STEP_OR.equals(rt) || STEP_AND.equals(rt);
    }

    /** The branch parsys children of a split, in numeric order (1, 2, ...). */
    static List<Resource> branchesOf(Resource split) {
        List<Resource> out = new ArrayList<>();
        for (Resource c : split.getChildren()) {
            if (BRANCH_TYPE.equals(c.getValueMap().get("sling:resourceType", String.class)) || c.getName().matches("\\d+")) {
                out.add(c);
            }
        }
        out.sort((a, b) -> Integer.compare(num(a.getName()), num(b.getName())));
        return out;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    Map<String, Object> create(String title, String name, String description) throws PersistenceException {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("A title is required");
        }
        String n = name == null || name.trim().isEmpty() ? nameFrom(title) : name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Not a valid model name: " + n);
        }
        Resource root = folders(CUSTOM);
        if (root.getChild(n) != null || resolver.getResource(RUNTIME + "/" + n) != null) {
            throw new IllegalArgumentException("A model named " + n + " already exists");
        }
        Map<String, Object> page = new HashMap<>();
        page.put("jcr:primaryType", "cq:Page");
        Resource p = resolver.create(root, n, page);
        Map<String, Object> content = new HashMap<>();
        content.put("jcr:primaryType", "cq:PageContent");
        content.put("jcr:title", title.trim());
        if (description != null && !description.trim().isEmpty()) {
            content.put("jcr:description", description.trim());
        }
        content.put("sling:resourceType", MODEL_PAGE_TYPE);
        content.put("cq:template", "/libs/cq/workflow/templates/model");
        stamp(content);
        Resource c = resolver.create(p, "jcr:content", content);
        Map<String, Object> flow = new HashMap<>();
        flow.put("jcr:primaryType", "nt:unstructured");
        flow.put("sling:resourceType", FLOW_TYPE);
        resolver.create(c, "flow", flow);
        resolver.commit();
        sync(p.getPath());
        return design(p.getPath());
    }

    void delete(String designPath) throws PersistenceException {
        Resource page = requireDesign(designPath);
        if (!page.getPath().startsWith(CUSTOM + "/")) {
            throw new IllegalArgumentException("Default models cannot be deleted");
        }
        Resource runtime = resolver.getResource(RUNTIME + "/" + nameOf(page));
        resolver.delete(page);
        if (runtime != null) {
            resolver.delete(runtime);
        }
        resolver.commit();
    }

    /** Adds a step of {@code resourceType} to a flow or branch, before the sibling {@code before} (null: at the end). */
    Map<String, Object> addStep(String containerPath, String resourceType, String title, String before)
            throws PersistenceException, RepositoryException {
        Resource container = requireEditable(containerPath);
        String base = resourceType.substring(resourceType.lastIndexOf('/') + 1);
        String name = base;
        for (int i = 0; container.getChild(name) != null; i++) {
            name = base + "_" + i;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("sling:resourceType", resourceType);
        if (title != null && !title.isEmpty()) {
            props.put("jcr:title", title);
        }
        Resource step = resolver.create(container, name, props);
        Map<String, Object> meta = new HashMap<>();
        meta.put("jcr:primaryType", "nt:unstructured");
        if (STEP_PROCESS.equals(resourceType)) {
            meta.put("PROCESS_AUTO_ADVANCE", "true");
        } else if (STEP_GOTO.equals(resourceType)) {
            meta.put("PROCESS", GOTO_PROCESS);
            meta.put("PROCESS_AUTO_ADVANCE", "false");
        }
        resolver.create(step, "metaData", meta);
        if (isSplit(resourceType)) {
            ModifiableValueMap sv = step.adaptTo(ModifiableValueMap.class);
            sv.put("branches", "2");
            if (STEP_OR.equals(resourceType)) {
                sv.put("orSplit", "true");
            }
            branches(step, 2);
        }
        order(container, name, before);
        touch(containerPath);
        resolver.commit();
        return Collections.singletonMap("path", step.getPath());
    }

    /** Moves a step to another flow or branch (or within one), before {@code before} (null: at the end). */
    void moveStep(String stepPath, String containerPath, String before) throws PersistenceException, RepositoryException {
        Resource step = requireEditable(stepPath);
        Resource container = requireEditable(containerPath);
        if (containerPath.equals(stepPath) || containerPath.startsWith(stepPath + "/")) {
            throw new IllegalArgumentException("A step cannot be moved into itself");
        }
        String name = step.getName();
        if (!step.getParent().getPath().equals(container.getPath())) {
            String target = name;
            for (int i = 0; container.getChild(target) != null; i++) {
                target = name + "_" + i;
            }
            javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
            if (session != null) {
                session.move(step.getPath(), container.getPath() + "/" + target);
            } else {
                copy(step, container, target);
                resolver.delete(step);
            }
            name = target;
        }
        order(resolver.getResource(container.getPath()), name, before);
        touch(containerPath);
        resolver.commit();
    }

    void deleteStep(String stepPath) throws PersistenceException {
        Resource step = requireEditable(stepPath);
        touch(stepPath);
        resolver.delete(step);
        resolver.commit();
    }

    /** Makes the branch parsys children of a split match its {@code branches} property (2 to 5). */
    void normalizeBranches(String splitPath) throws PersistenceException {
        Resource split = requireEditable(splitPath);
        int n;
        try {
            n = Integer.parseInt(split.getValueMap().get("branches", "2"));
        } catch (NumberFormatException e) {
            n = 2;
        }
        branches(split, Math.max(2, Math.min(5, n)));
        touch(splitPath);
        resolver.commit();
    }

    private void branches(Resource split, int n) throws PersistenceException {
        List<Resource> existing = branchesOf(split);
        for (int i = existing.size() + 1; i <= n; i++) {
            Map<String, Object> b = new HashMap<>();
            b.put("jcr:primaryType", "nt:unstructured");
            b.put("sling:resourceType", BRANCH_TYPE);
            resolver.create(split, String.valueOf(i), b);
        }
        for (int i = existing.size(); i > n; i--) {
            resolver.delete(existing.get(i - 1));
        }
    }

    /** Marks the design model containing {@code path} as modified (after a step dialog was saved, too). */
    void touch(String path) {
        int i = path.indexOf("/jcr:content");
        Resource content = resolver.getResource(i < 0 ? path + "/jcr:content" : path.substring(0, i) + "/jcr:content");
        if (content == null) {
            return;
        }
        ModifiableValueMap vm = content.adaptTo(ModifiableValueMap.class);
        if (vm != null) {
            vm.put(DIRTY, true);
            vm.put("cq:lastModified", Calendar.getInstance());
            vm.put("cq:lastModifiedBy", resolver.getUserID());
        }
    }

    void touchAndCommit(String path) throws PersistenceException {
        touch(path);
        resolver.commit();
    }

    // ---------------------------------------------------------------------------------------------- sync

    /** Builds the runtime model from the design model and stores it below {@code /var/workflow/models}. */
    RuntimeModel sync(String designPath) throws PersistenceException {
        Resource page = requireDesign(designPath);
        String name = nameOf(page);
        Resource content = page.getChild("jcr:content");
        RuntimeModel model = build(RUNTIME + "/" + name, content);
        Resource existing = resolver.getResource(model.id);
        String version = "1.0";
        if (existing != null) {
            version = next(existing.getValueMap().get("version", "1.0"));
            resolver.delete(existing);
        }
        model.version = version;
        write(model, page.getPath());
        Resource written = resolver.getResource(model.id);
        ModifiableValueMap wv = written == null ? null : written.adaptTo(ModifiableValueMap.class);
        if (wv != null) {
            wv.put(DESIGN, fingerprint(content));
        }
        ModifiableValueMap cv = page.getPath().startsWith(CUSTOM + "/") ? content.adaptTo(ModifiableValueMap.class) : null;
        if (cv != null) {
            cv.remove(DIRTY);
            cv.put(SYNCED, Calendar.getInstance());
        }
        resolver.commit();
        return model;
    }

    /**
     * Syncs the default models (shipped below {@code /libs}) that have no runtime model yet, and those
     * whose design is not the one their runtime model was made from: nobody can press Sync on a model
     * that came with the product, so a model changed by an upgrade went on running as it was before
     * (28.11.2026). Returns how many.
     */
    int syncDefaults() throws PersistenceException {
        int n = 0;
        for (Map<String, Object> m : list()) {
            if (!"default".equals(m.get("type")) || m.get("designPath") == null) {
                continue;
            }
            boolean stale = m.get("version") == null;
            if (!stale) {
                Resource design = resolver.getResource(m.get("designPath") + "/jcr:content");
                Resource runtime = resolver.getResource(String.valueOf(m.get("id")));
                stale = design != null && runtime != null && !fingerprint(design).equals(runtime.getValueMap().get(DESIGN, ""));
            }
            if (stale) {
                sync(String.valueOf(m.get("designPath")));
                n++;
            }
        }
        return n;
    }

    /** What a design says, in one line: names, and every property that is not the repository's own. */
    static String fingerprint(Resource design) {
        StringBuilder sb = new StringBuilder();
        dump(design, sb);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", d[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static void dump(Resource r, StringBuilder sb) {
        sb.append('{').append(r.getName());
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(r.getValueMap()).entrySet()) {
            String k = e.getKey();
            if (k.startsWith("jcr:created") || k.startsWith("jcr:lastModified") || k.startsWith("cq:lastModified") || k.equals("jcr:uuid")
                    || k.startsWith("studio:")) {
                continue;
            }
            Object v = e.getValue();
            sb.append('|').append(k).append('=').append(v instanceof Object[] ? java.util.Arrays.toString((Object[]) v) : String.valueOf(v));
        }
        for (Resource c : r.getChildren()) {
            dump(c, sb);
        }
        sb.append('}');
    }

    /**
     * Copies a default model to {@code /conf/global/settings/workflow/models} so it can be edited (the copy then
     * replaces it, under the same name); returns the copy's design path.
     */
    String copyToCustom(String defaultPath) throws PersistenceException {
        Resource page = requireDesign(defaultPath);
        if (!page.getPath().startsWith(DEFAULT + "/")) {
            throw new IllegalArgumentException("Only default models are copied for editing: " + defaultPath);
        }
        String name = nameOf(page);
        String target = CUSTOM + "/" + name;
        if (resolver.getResource(target) == null) {
            String parent = target.substring(0, target.lastIndexOf('/'));
            folders(parent);
            resolver.copy(page.getPath(), parent);
            ModifiableValueMap cv = resolver.getResource(target + "/jcr:content").adaptTo(ModifiableValueMap.class);
            cv.put(DIRTY, true);
            cv.put("cq:lastModified", Calendar.getInstance());
            cv.put("cq:lastModifiedBy", resolver.getUserID());
            resolver.commit();
        }
        return target;
    }

    static String next(String version) {
        try {
            String[] p = version.split("\\.");
            return p[0] + "." + (Integer.parseInt(p.length > 1 ? p[1] : "0") + 1);
        } catch (NumberFormatException e) {
            return "1.0";
        }
    }

    /** The node graph of a design model (see the class comment). */
    static RuntimeModel build(String id, Resource content) {
        ValueMap cv = content.getValueMap();
        RuntimeModel m = new RuntimeModel(id);
        m.title = cv.get("jcr:title", content.getParent().getName());
        m.description = cv.get("jcr:description", String.class);
        for (String key : new String[] {"transient", "multiResourceSupport"}) {
            String v = cv.get(key, String.class);
            if (v != null) {
                m.metaData.put(key, v);
            }
        }
        String[] stages = stages(cv);
        if (stages.length > 0) {
            m.metaData.put("workflowStages", stages);
        }
        RuntimeModel.Node start = m.add(WorkflowNode.TYPE_START, "Start", null, null);
        Map<String, RuntimeModel.Node> byPath = new HashMap<>();
        List<RuntimeModel.Node> last = Collections.singletonList(start);
        Resource flow = content.getChild("flow");
        if (flow != null) {
            last = chain(m, flow, last, null, byPath);
        }
        RuntimeModel.Node end = m.add(WorkflowNode.TYPE_END, "End", null, null);
        for (RuntimeModel.Node n : last) {
            m.connect(n, end, "", null);
        }
        // Goto steps point at a design path; resolve it to the node id
        for (RuntimeModel.Node n : m.nodes.values()) {
            String target = n.metaData.get("TARGET_NODE_PATH", String.class);
            if (target != null && n.metaData.get("TARGET_NODE_ID") == null) {
                RuntimeModel.Node t = byPath.get(flowRelative(target));
                if (t != null) {
                    n.metaData.put("TARGET_NODE_ID", t.id);
                }
            }
        }
        return m;
    }

    /** {@code .../jcr:content/flow/a/1/b} -> {@code a/1/b}, so paths of moved or copied models still match. */
    static String flowRelative(String path) {
        int i = path.indexOf("/jcr:content/flow/");
        return i < 0 ? path : path.substring(i + "/jcr:content/flow/".length());
    }

    /**
     * Appends the steps of {@code parsys} after {@code prev}; returns the nodes the next step connects from. The first
     * connection of the chain carries {@code firstMeta} (the rule and default flag of an OR branch).
     */
    private static List<RuntimeModel.Node> chain(RuntimeModel m, Resource parsys, List<RuntimeModel.Node> prev,
            Map<String, Object> firstMeta, Map<String, RuntimeModel.Node> byPath) {
        List<RuntimeModel.Node> current = prev;
        Map<String, Object> pendingMeta = firstMeta;
        for (Resource step : parsys.getChildren()) {
            ValueMap sv = step.getValueMap();
            String rt = sv.get("sling:resourceType", String.class);
            if (rt == null) {
                continue;
            }
            Meta meta = Meta.of(step.getChild("metaData"));
            String title = sv.get("jcr:title", step.getName());
            String description = sv.get("jcr:description", String.class);
            if (isSplit(rt)) {
                boolean and = STEP_AND.equals(rt);
                RuntimeModel.Node split = m.add(and ? WorkflowNode.TYPE_AND_SPLIT : WorkflowNode.TYPE_OR_SPLIT, title, description, meta);
                RuntimeModel.Node join = m.add(and ? WorkflowNode.TYPE_AND_JOIN : WorkflowNode.TYPE_OR_JOIN, title, null, null);
                byPath.put(flowRelative(step.getPath()), split);
                link(m, current, split, pendingMeta);
                pendingMeta = null;
                List<Resource> branches = branchesOf(step);
                for (int i = 0; i < branches.size(); i++) {
                    Map<String, Object> branchMeta = new LinkedHashMap<>();
                    if (!and) {
                        int b = i + 1;
                        branchMeta.put("isDefault", String.valueOf("true".equals(meta.get("default" + b, String.class))));
                        String rule = meta.get("rule" + b, String.class);
                        if (rule == null) {
                            rule = meta.get("script" + b, String.class);
                        }
                        if (rule != null) {
                            branchMeta.put("rule", rule);
                        }
                    }
                    List<RuntimeModel.Node> ends = chain(m, branches.get(i), Collections.singletonList(split), branchMeta, byPath);
                    link(m, ends, join, ends.size() == 1 && ends.get(0) == split ? branchMeta : null);
                }
                current = Collections.singletonList(join);
                continue;
            }
            String type;
            if (STEP_PARTICIPANT.equals(rt) || (meta.get("PARTICIPANT") != null && meta.get("PROCESS") == null)) {
                type = WorkflowNode.TYPE_PARTICIPANT;
            } else if (STEP_DYNAMIC_PARTICIPANT.equals(rt) || meta.get("DYNAMIC_PARTICIPANT") != null) {
                type = WorkflowNode.TYPE_DYNAMIC_PARTICIPANT;
            } else {
                type = WorkflowNode.TYPE_PROCESS;
                if (STEP_GOTO.equals(rt)) {
                    meta.put("PROCESS", GOTO_PROCESS);
                } else if (meta.get("PROCESS") == null && sv.get("PROCESS", String.class) != null) {
                    meta.put("PROCESS", sv.get("PROCESS", String.class));
                }
            }
            meta.put("sling:resourceType", rt);
            RuntimeModel.Node node = m.add(type, title, description, meta);
            byPath.put(flowRelative(step.getPath()), node);
            link(m, current, node, pendingMeta);
            pendingMeta = null;
            current = Collections.singletonList(node);
        }
        return current;
    }

    private static void link(RuntimeModel m, List<RuntimeModel.Node> from, RuntimeModel.Node to, Map<String, Object> meta) {
        for (RuntimeModel.Node f : from) {
            String rule = meta == null ? "" : String.valueOf(meta.getOrDefault("rule", ""));
            Map<String, Object> tm = meta == null ? null : new LinkedHashMap<>(meta);
            if (tm != null) {
                tm.remove("rule");
            }
            m.connect(f, to, rule, tm);
        }
    }

    /**
     * Writes a model built in code as what flows run on. It has no design from then on (a design it was made
     * with is removed): the flow editor draws designs, and this one was not drawn there.
     */
    RuntimeModel deploy(RuntimeModel model) throws PersistenceException, com.adobe.granite.workflow.model.ValidationException {
        model.validate();
        if (!model.id.startsWith(RUNTIME + "/")) {
            throw new IllegalArgumentException("Not a model id: " + model.id);
        }
        Resource existing = resolver.getResource(model.id);
        if (existing != null) {
            model.version = next(existing.getValueMap().get("version", "1.0"));
            resolver.delete(existing);
        }
        Resource design = resolver.getResource(CUSTOM + "/" + model.id.substring(RUNTIME.length() + 1));
        if (design != null) {
            resolver.delete(design);
        }
        write(model, null);
        resolver.commit();
        model.built = false;
        return model;
    }

    /** Removes a model that has no design (one built in code). */
    void deleteRuntime(String id) throws PersistenceException {
        Resource runtime = id.startsWith(RUNTIME + "/") ? resolver.getResource(id) : null;
        if (runtime == null || runtime.getChild("nodes") == null) {
            throw new IllegalArgumentException("No workflow model " + id);
        }
        if (resolver.getResource(DEFAULT + "/" + runtime.getName()) != null) {
            throw new IllegalArgumentException("Default models cannot be deleted");
        }
        resolver.delete(runtime);
        resolver.commit();
    }

    private void write(RuntimeModel model, String designPath) throws PersistenceException {
        Resource parent = folders(model.id.substring(0, model.id.lastIndexOf('/')));
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("title", model.title == null ? "" : model.title);
        if (model.description != null) {
            props.put("description", model.description);
        }
        props.put("version", model.version);
        if (designPath != null) {
            props.put("designPath", designPath);
        }
        props.put("jcr:lastModified", Calendar.getInstance());
        props.put("jcr:lastModifiedBy", resolver.getUserID());
        Resource r = resolver.create(parent, model.id.substring(model.id.lastIndexOf('/') + 1), props);
        resolver.create(r, "metaData", withType(Meta.storable(model.metaData)));
        Resource nodes = resolver.create(r, "nodes", withType(new HashMap<>()));
        for (RuntimeModel.Node n : model.nodes.values()) {
            Map<String, Object> np = withType(new HashMap<>());
            np.put("type", n.type);
            np.put("title", n.title == null ? "" : n.title);
            if (n.description != null) {
                np.put("description", n.description);
            }
            Resource nr = resolver.create(nodes, n.id, np);
            resolver.create(nr, "metaData", withType(Meta.storable(n.metaData)));
        }
        Resource transitions = resolver.create(r, "transitions", withType(new HashMap<>()));
        int i = 0;
        for (RuntimeModel.Transition t : model.transitions) {
            Map<String, Object> tp = withType(new HashMap<>());
            tp.put("from", t.from.id);
            tp.put("to", t.to.id);
            tp.put("rule", t.rule);
            Resource tr = resolver.create(transitions, "transition" + i++, tp);
            resolver.create(tr, "metaData", withType(Meta.storable(t.metaData)));
        }
    }

    private static Map<String, Object> withType(Map<String, Object> m) {
        Map<String, Object> out = new HashMap<>(m);
        out.put("jcr:primaryType", "nt:unstructured");
        return out;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    Resource requireDesign(String designPath) {
        if (designPath == null || !(designPath.startsWith(CUSTOM + "/") || designPath.startsWith(DEFAULT + "/"))) {
            throw new IllegalArgumentException("Not a workflow model: " + designPath);
        }
        Resource page = resolver.getResource(designPath);
        Resource content = page == null ? null : page.getChild("jcr:content");
        if (content == null || !MODEL_PAGE_TYPE.equals(content.getValueMap().get("sling:resourceType", String.class))) {
            throw new IllegalArgumentException("No workflow model at " + designPath);
        }
        return page;
    }

    private Resource requireEditable(String path) {
        if (path == null || !path.startsWith(CUSTOM + "/") || !path.contains("/jcr:content/flow")) {
            throw new IllegalArgumentException("Not an editable model step or branch: " + path);
        }
        Resource r = resolver.getResource(path);
        if (r == null) {
            throw new IllegalArgumentException("Nothing at " + path);
        }
        return r;
    }

    private void order(Resource container, String name, String before) throws RepositoryException {
        Node node = container.adaptTo(Node.class);
        if (node == null) {
            return;
        }
        if (before != null && !before.isEmpty() && node.hasNode(before) && !before.equals(name)) {
            node.orderBefore(name, before);
        } else if (before == null || before.isEmpty()) {
            node.orderBefore(name, null);
        }
    }

    private void copy(Resource src, Resource dest, String name) throws PersistenceException {
        Resource copy = resolver.create(dest, name, new HashMap<>(src.getValueMap()));
        for (Resource c : src.getChildren()) {
            copy(c, copy, c.getName());
        }
    }

    /** Creates the folders of {@code path} that are missing. */
    Resource folders(String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) {
            return r;
        }
        int slash = path.lastIndexOf('/');
        Resource parent = folders(slash <= 0 ? "/" : path.substring(0, slash));
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "sling:Folder");
        return resolver.create(parent, path.substring(path.lastIndexOf('/') + 1), props);
    }

    private void stamp(Map<String, Object> props) {
        props.put("cq:lastModified", Calendar.getInstance());
        props.put("cq:lastModifiedBy", resolver.getUserID());
    }

    static String nameFrom(String title) {
        String n = title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "model" : n;
    }

    static String iso(Calendar c) {
        return c == null ? null : Instant.ofEpochMilli(c.getTimeInMillis()).toString();
    }
}
