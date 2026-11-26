/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Workflow launchers: configurations that start a model when content changes, stored like AEM's below
 * {@code /conf/global/settings/workflow/launcher/config} (custom) and {@code /libs/settings/workflow/launcher/config}
 * (default). A launcher has an event type ({@value #CREATED} created, {@value #MODIFIED} modified, {@value #REMOVED}
 * removed), a path regular expression ({@code glob}), a node type, conditions ({@code name==value},
 * {@code name!=value}, {@code name!=} for "has a value"; the name may be a relative path), an exclude list (property
 * names, as regular expressions, whose change alone does not start anything, and
 * {@code event-user-data:changedByWorkflowProcess} for changes made by workflows), the runtime model to start and
 * whether it is enabled.
 */
final class Launchers {

    static final String DEFAULT = "/libs/settings/workflow/launcher/config";
    static final String CUSTOM = "/conf/global/settings/workflow/launcher/config";
    static final int CREATED = 1;
    static final int REMOVED = 2;
    static final int MODIFIED = 16;
    static final String BY_WORKFLOW = "event-user-data:changedByWorkflowProcess";

    private Launchers() {
    }

    static final class Launcher {
        String path;
        String name;
        boolean custom;
        int eventType;
        String glob;
        Pattern pattern;
        String nodetype;
        List<String> conditions = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        String workflow;
        String description;
        boolean enabled;
        String runModes;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("name", name);
            m.put("type", custom ? "custom" : "default");
            m.put("eventType", eventType);
            m.put("glob", glob);
            m.put("nodetype", nodetype);
            m.put("conditions", conditions);
            m.put("excludeList", exclude);
            m.put("workflow", workflow);
            m.put("description", description);
            m.put("enabled", enabled);
            m.put("runModes", runModes);
            m.put("valid", pattern != null);
            return m;
        }

        /** Whether the launcher runs on an authoring instance (Sensible Studio is one). */
        boolean onAuthor() {
            return runModes == null || runModes.trim().isEmpty() || runModes.contains("author");
        }
    }

    static Launcher read(Resource r, boolean custom) {
        ValueMap vm = r.getValueMap();
        Launcher l = new Launcher();
        l.path = r.getPath();
        l.name = r.getName();
        l.custom = custom;
        l.eventType = vm.get("eventType", MODIFIED);
        l.glob = vm.get("glob", "");
        try {
            l.pattern = l.glob.isEmpty() ? null : Pattern.compile(l.glob);
        } catch (PatternSyntaxException e) {
            l.pattern = null;
        }
        l.nodetype = vm.get("nodetype", "");
        String[] conds = vm.get("conditions", String[].class);
        if (conds != null) {
            for (String c : conds) {
                if (c != null && !c.trim().isEmpty()) {
                    l.conditions.add(c.trim());
                }
            }
        }
        String legacy = vm.get("condition", "");
        if (!legacy.trim().isEmpty() && !l.conditions.contains(legacy.trim())) {
            l.conditions.add(legacy.trim());
        }
        for (String e : vm.get("excludeList", "").split(",")) {
            if (!e.trim().isEmpty()) {
                l.exclude.add(e.trim());
            }
        }
        l.workflow = vm.get("workflow", "");
        l.description = vm.get("description", String.class);
        l.enabled = vm.get("enabled", Boolean.FALSE) || "true".equals(vm.get("enabled", ""));
        l.runModes = vm.get("runModes", "author");
        return l;
    }

    /** All launchers, default ones first; a custom one with the same name replaces the default. */
    static List<Launcher> list(ResourceResolver resolver) {
        Map<String, Launcher> byName = new LinkedHashMap<>();
        for (String root : Arrays.asList(DEFAULT, CUSTOM)) {
            Resource folder = resolver.getResource(root);
            if (folder == null) {
                continue;
            }
            for (Resource c : folder.getChildren()) {
                if (!c.getName().startsWith("jcr:") && !c.getName().startsWith("rep:")) {
                    byName.put(c.getName(), read(c, root.equals(CUSTOM)));
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    // ---------------------------------------------------------------------------------------------- editing

    /** Creates ({@code path} null) or updates a custom launcher; returns it. */
    static Launcher save(ResourceResolver resolver, String path, Map<String, Object> values) throws PersistenceException {
        String glob = String.valueOf(values.getOrDefault("glob", "")).trim();
        if (glob.isEmpty()) {
            throw new IllegalArgumentException("The path (glob) is required");
        }
        try {
            Pattern.compile(glob);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("The path is not a valid regular expression: " + e.getDescription());
        }
        String workflow = String.valueOf(values.getOrDefault("workflow", "")).trim();
        if (workflow.isEmpty()) {
            throw new IllegalArgumentException("The workflow model is required");
        }
        if (resolver.getResource(workflow) == null) {
            throw new IllegalArgumentException("No runtime workflow model at " + workflow + " (sync the model first)");
        }
        int eventType = Integer.parseInt(String.valueOf(values.getOrDefault("eventType", MODIFIED)));
        if (eventType != CREATED && eventType != MODIFIED && eventType != REMOVED) {
            throw new IllegalArgumentException("Unknown event type " + eventType);
        }
        Resource target;
        if (path == null || path.isEmpty()) {
            Resource folder = new ModelStore(resolver).folders(CUSTOM);
            String base = slug(String.valueOf(values.getOrDefault("name", "")));
            if (base.isEmpty()) {
                base = slug(glob);
            }
            if (base.isEmpty()) {
                base = "launcher";
            }
            String name = base;
            for (int i = 1; folder.getChild(name) != null; i++) {
                name = base + "_" + i;
            }
            target = resolver.create(folder, name, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        } else {
            target = resolver.getResource(path);
            if (target == null || !path.startsWith(CUSTOM + "/")) {
                throw new IllegalArgumentException("Not a custom launcher: " + path);
            }
        }
        ModifiableValueMap vm = target.adaptTo(ModifiableValueMap.class);
        vm.put("glob", glob);
        vm.put("eventType", (long) eventType);
        vm.put("nodetype", String.valueOf(values.getOrDefault("nodetype", "nt:unstructured")).trim());
        vm.put("conditions", strings(values.get("conditions")).toArray(new String[0]));
        vm.remove("condition");
        vm.put("excludeList", String.join(",", strings(values.get("excludeList"))));
        vm.put("workflow", workflow);
        vm.put("description", String.valueOf(values.getOrDefault("description", "")));
        vm.put("enabled", Boolean.parseBoolean(String.valueOf(values.getOrDefault("enabled", "true"))));
        vm.put("runModes", String.valueOf(values.getOrDefault("runModes", "author")));
        resolver.commit();
        return read(resolver.getResource(target.getPath()), true);
    }

    static void setEnabled(ResourceResolver resolver, String path, boolean enabled) throws PersistenceException {
        Resource r = requireCustom(resolver, path);
        r.adaptTo(ModifiableValueMap.class).put("enabled", enabled);
        resolver.commit();
    }

    static void delete(ResourceResolver resolver, String path) throws PersistenceException {
        resolver.delete(requireCustom(resolver, path));
        resolver.commit();
    }

    private static Resource requireCustom(ResourceResolver resolver, String path) {
        Resource r = path == null ? null : resolver.getResource(path);
        if (r == null || !path.startsWith(CUSTOM + "/")) {
            throw new IllegalArgumentException("Not a custom launcher: " + path);
        }
        return r;
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection) {
            for (Object o : (Collection<?>) v) {
                add(out, o);
            }
        } else if (v instanceof Object[]) {
            for (Object o : (Object[]) v) {
                add(out, o);
            }
        } else if (v != null) {
            for (String s : String.valueOf(v).split("[,\\n]")) {
                add(out, s);
            }
        }
        return out;
    }

    private static void add(List<String> out, Object o) {
        if (o != null && !String.valueOf(o).trim().isEmpty()) {
            out.add(String.valueOf(o).trim());
        }
    }

    static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    // ---------------------------------------------------------------------------------------------- matching

    /** One change of the repository, as the listener sees it. */
    static final class Change {
        final int eventType;
        final String path;
        final Collection<String> properties;
        final String userId;

        Change(int eventType, String path, Collection<String> properties, String userId) {
            this.eventType = eventType;
            this.path = path;
            this.properties = properties == null ? Collections.emptyList() : properties;
            this.userId = userId;
        }
    }

    /**
     * Whether {@code launcher} starts its workflow for {@code change}; {@code resource} is the changed resource (null
     * when it was removed) and {@code workflowUsers} the users the workflow engine writes as.
     */
    static boolean matches(Launcher launcher, Change change, Resource resource, Collection<String> workflowUsers) {
        if (!launcher.enabled || !launcher.onAuthor() || launcher.pattern == null || launcher.eventType != change.eventType) {
            return false;
        }
        if (!launcher.pattern.matcher(change.path).matches()) {
            return false;
        }
        if (launcher.exclude.contains(BY_WORKFLOW) && change.userId != null && workflowUsers.contains(change.userId)) {
            return false;
        }
        if (change.eventType == MODIFIED && !change.properties.isEmpty() && allExcluded(launcher.exclude, change.properties)) {
            return false;
        }
        if (change.eventType == REMOVED || resource == null) {
            return change.eventType == REMOVED; // nothing left to check node type and conditions on
        }
        if (!launcher.nodetype.isEmpty() && !isNodeType(resource, launcher.nodetype)) {
            return false;
        }
        for (String c : launcher.conditions) {
            if (!condition(resource, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allExcluded(List<String> exclude, Collection<String> properties) {
        List<Pattern> patterns = new ArrayList<>();
        for (String e : exclude) {
            if (e.startsWith("event-user-data:")) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(e));
            } catch (PatternSyntaxException ex) {
                patterns.add(Pattern.compile(Pattern.quote(e)));
            }
        }
        if (patterns.isEmpty()) {
            return false;
        }
        for (String p : properties) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (patterns.stream().noneMatch(x -> x.matcher(name).matches())) {
                return false;
            }
        }
        return true;
    }

    static boolean isNodeType(Resource resource, String type) {
        if (type.equals(resource.getValueMap().get("jcr:primaryType", String.class))) {
            return true;
        }
        String[] mixins = resource.getValueMap().get("jcr:mixinTypes", String[].class);
        if (mixins != null && Arrays.asList(mixins).contains(type)) {
            return true;
        }
        Node node = resource.adaptTo(Node.class);
        try {
            return node != null && node.isNodeType(type);
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** {@code name==value}, {@code name!=value}; {@code name!=} holds when the property has a non-empty value. */
    static boolean condition(Resource resource, String condition) {
        int ne = condition.indexOf("!=");
        int eq = condition.indexOf("==");
        boolean negate = ne >= 0 && (eq < 0 || ne < eq);
        int at = negate ? ne : eq;
        if (at <= 0) {
            return true; // not a condition Sensible Studio understands: ignore rather than block every launch
        }
        String name = condition.substring(0, at).trim();
        String expected = condition.substring(at + 2).trim();
        List<String> values = new ArrayList<>();
        Resource holder = resource;
        int slash = name.lastIndexOf('/');
        if (slash > 0) {
            holder = resource.getChild(name.substring(0, slash));
            name = name.substring(slash + 1);
        }
        Object v = holder == null ? null : holder.getValueMap().get(name);
        if (v instanceof Object[]) {
            for (Object o : (Object[]) v) {
                values.add(String.valueOf(o));
            }
        } else if (v != null) {
            values.add(String.valueOf(v));
        }
        boolean has = values.contains(expected) || expected.isEmpty() && values.stream().allMatch(String::isEmpty);
        return negate != has;
    }

    static Map<String, Object> values(Map<String, String[]> params) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            String[] v = e.getValue();
            if (e.getKey().equals("conditions") || e.getKey().equals("excludeList")) {
                out.put(e.getKey(), v);
            } else if (v != null && v.length > 0) {
                out.put(e.getKey(), v[0]);
            }
        }
        return out;
    }
}
