/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * The parts of a component's {@code cq:editConfig} the page editor needs, as plain maps:
 * <ul>
 *   <li>{@code cq:inplaceEditing}: {@code editorType} ({@code text}, {@code title}, {@code plaintext},
 *       {@code image}, {@code hybrid}), {@code active}, {@code configPath} and the configuration subtree
 *       (text: {@code rtePlugins}, {@code uiSettings}; title: {@code titleTag}; hybrid: {@code cq:childEditors});</li>
 *   <li>{@code cq:actions}: the toolbar actions AEM would show ({@code EDIT}, {@code COPYMOVE}, ...);</li>
 *   <li>{@code cq:actionConfigs}: extra toolbar actions ({@code icon}, {@code text}, {@code handler}), e.g. the
 *       Experience Fragment's "Edit" that opens the fragment's variation. The editor runs the handlers it knows.</li>
 * </ul>
 * {@link #describe} also gives the component's icon ({@code cq:icon}, inherited along the super types).
 */
final class EditConfig {

    private static final int MAX_DEPTH = 8;

    final Map<String, Object> inplace;
    final String[] actions;
    final List<Map<String, Object>> actionConfigs;

    private EditConfig(Map<String, Object> inplace, String[] actions, List<Map<String, Object>> actionConfigs) {
        this.inplace = inplace;
        this.actions = actions;
        this.actionConfigs = actionConfigs;
    }

    static EditConfig of(Resource def) {
        Resource editConfig = def == null ? null : def.getChild("cq:editConfig");
        if (editConfig == null) {
            return null;
        }
        Map<String, Object> inplace = null;
        Resource ipe = editConfig.getChild("cq:inplaceEditing");
        if (ipe != null) {
            inplace = tree(ipe, 0);
            Resource children = editConfig.getChild("cq:childEditors");
            if (children != null) {
                inplace.put("cq:childEditors", tree(children, 0));
            }
        }
        String[] actions = editConfig.getValueMap().get("cq:actions", String[].class);
        List<Map<String, Object>> actionConfigs = null;
        Resource configs = editConfig.getChild("cq:actionConfigs");
        if (configs != null) {
            actionConfigs = new ArrayList<>();
            for (Resource a : configs.getChildren()) {
                ValueMap av = a.getValueMap();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", a.getName());
                m.put("icon", av.get("icon", String.class));
                m.put("text", av.get("text", String.class));
                m.put("handler", av.get("handler", String.class));
                actionConfigs.add(m);
            }
        }
        return inplace == null && actions == null && actionConfigs == null ? null : new EditConfig(inplace, actions, actionConfigs);
    }

    /** Properties and children of a node as nested maps ({@code jcr:*} properties left out). */
    static Map<String, Object> tree(Resource r, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        ValueMap vm = r.getValueMap();
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            if (e.getKey().startsWith("jcr:")) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof Object[]) {
                m.put(e.getKey(), new ArrayList<>(Arrays.asList((Object[]) v)));
            } else if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                m.put(e.getKey(), v);
            } else if (v != null) {
                m.put(e.getKey(), String.valueOf(v));
            }
        }
        if (depth < MAX_DEPTH) {
            for (Resource child : r.getChildren()) {
                m.put(child.getName(), tree(child, depth + 1));
            }
        }
        return m;
    }

    /** The first edit config in a component's super type chain that defines each part. */
    static Map<String, Object> describe(com.day.cq.wcm.api.components.Component component) {
        Map<String, Object> inplace = null;
        List<String> actions = null;
        List<Map<String, Object>> actionConfigs = null;
        String icon = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (com.day.cq.wcm.api.components.Component c = component; c != null && seen.add(c.getPath()); c = c.getSuperComponent()) {
            if (icon == null && c.getProperties() != null) {
                icon = c.getProperties().get("cq:icon", String.class);
            }
            EditConfig ec = c instanceof ComponentDefinitions.Snapshot ? ((ComponentDefinitions.Snapshot) c).editConfig
                    : of(c.adaptTo(Resource.class));
            if (ec == null) {
                continue;
            }
            if (inplace == null && ec.inplace != null) {
                inplace = ec.inplace;
            }
            if (actions == null && ec.actions != null) {
                actions = Arrays.asList(ec.actions);
            }
            if (actionConfigs == null && ec.actionConfigs != null) {
                actionConfigs = ec.actionConfigs;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inplace", inplace);
        m.put("actions", actions);
        m.put("actionConfigs", actionConfigs);
        m.put("icon", icon);
        return m;
    }
}
