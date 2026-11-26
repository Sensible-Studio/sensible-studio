/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Auto-Assign, as AEM's Tools › Workflow › Auto-Assign: a rule names a page template, a workflow model and a root
 * path; a page created with that template below the root starts the model on itself.
 *
 * <pre>
 * /conf/global/settings/workflow/autoassign/&lt;name&gt;   template, workflow (runtime model), rootPath
 * </pre>
 * The rules run as launchers made for them ({@link #launchers}): page created ({@code cq:Page}) below the root, with
 * {@code jcr:content/cq:template} the template.
 */
final class AutoAssign {

    static final String STORE = "/conf/global/settings/workflow/autoassign";

    private AutoAssign() {
    }

    static final class Rule {
        String path;
        String template;
        String workflow;
        String rootPath;
        Calendar modified;
        String modifiedBy;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("template", template);
            m.put("workflow", workflow);
            m.put("rootPath", rootPath);
            if (modified != null) {
                m.put("modified", modified.toInstant().toString());
            }
            if (modifiedBy != null) {
                m.put("modifiedBy", modifiedBy);
            }
            return m;
        }

        /** The launcher that does what the rule says. */
        Launchers.Launcher launcher() {
            Launchers.Launcher l = new Launchers.Launcher();
            l.path = path;
            l.name = "Auto-assign " + template.substring(template.lastIndexOf('/') + 1);
            l.custom = true;
            l.eventType = Launchers.CREATED;
            l.glob = Pattern.quote(rootPath) + "/.+";
            l.pattern = Pattern.compile(l.glob);
            l.nodetype = "cq:Page";
            l.conditions = new ArrayList<>(Collections.singletonList("jcr:content/cq:template==" + template));
            l.exclude = new ArrayList<>();
            l.workflow = workflow;
            l.description = "Auto-Assign rule " + path;
            l.enabled = true;
            l.runModes = "author";
            return l;
        }
    }

    static List<Rule> list(ResourceResolver resolver) {
        List<Rule> out = new ArrayList<>();
        Resource store = resolver.getResource(STORE);
        if (store == null) {
            return out;
        }
        for (Resource r : store.getChildren()) {
            ValueMap vm = r.getValueMap();
            Rule rule = new Rule();
            rule.path = r.getPath();
            rule.template = vm.get("template", String.class);
            rule.workflow = vm.get("workflow", String.class);
            rule.rootPath = vm.get("rootPath", String.class);
            rule.modified = vm.get("jcr:lastModified", Calendar.class);
            rule.modifiedBy = vm.get("jcr:lastModifiedBy", String.class);
            if (rule.template != null && rule.workflow != null && rule.rootPath != null) {
                out.add(rule);
            }
        }
        out.sort((a, b) -> (a.rootPath + " " + a.template).compareTo(b.rootPath + " " + b.template));
        return out;
    }

    static List<Launchers.Launcher> launchers(ResourceResolver resolver) {
        List<Launchers.Launcher> out = new ArrayList<>();
        for (Rule r : list(resolver)) {
            out.add(r.launcher());
        }
        return out;
    }

    /** Stores a rule (a new one when {@code path} is null). The template, the model and the root must exist. */
    static Rule save(ResourceResolver resolver, String path, String template, String workflow, String rootPath) throws PersistenceException {
        template = trim(template);
        workflow = trim(workflow);
        rootPath = trim(rootPath) == null ? null : trim(rootPath).replaceAll("/+$", "");
        if (template == null || !isTemplate(resolver.getResource(template))) {
            throw new IllegalArgumentException("Choose a page template");
        }
        if (workflow == null || resolver.getResource(workflow) == null) {
            throw new IllegalArgumentException("No flow model " + workflow + " (sync the model first)");
        }
        if (rootPath == null || !rootPath.startsWith("/content/") || rootPath.contains("..") || resolver.getResource(rootPath) == null) {
            throw new IllegalArgumentException("The path is a page or folder under /content, such as /content/wknd");
        }
        for (Rule r : list(resolver)) {
            if (!r.path.equals(path) && r.template.equals(template) && r.rootPath.equals(rootPath) && r.workflow.equals(workflow)) {
                throw new IllegalArgumentException("That rule exists already");
            }
        }
        Resource node;
        if (path == null) {
            Resource store = ensure(resolver, STORE);
            String base = Launchers.slug(template.substring(template.lastIndexOf('/') + 1) + "-" + rootPath.substring(rootPath.lastIndexOf('/') + 1));
            String name = base;
            for (int i = 2; store.getChild(name) != null; i++) {
                name = base + "-" + i;
            }
            node = resolver.create(store, name, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        } else {
            node = path.startsWith(STORE + "/") ? resolver.getResource(path) : null;
            if (node == null) {
                throw new IllegalArgumentException("No Auto-Assign rule " + path);
            }
        }
        ModifiableValueMap m = node.adaptTo(ModifiableValueMap.class);
        m.put("template", template);
        m.put("workflow", workflow);
        m.put("rootPath", rootPath);
        m.put("jcr:lastModified", Calendar.getInstance());
        if (resolver.getUserID() != null) {
            m.put("jcr:lastModifiedBy", resolver.getUserID());
        }
        // as the launchers: each change is saved by the operation itself
        resolver.commit();
        for (Rule r : list(resolver)) {
            if (r.path.equals(node.getPath())) {
                return r;
            }
        }
        throw new IllegalStateException("The rule was not stored");
    }

    static void delete(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = path != null && path.startsWith(STORE + "/") ? resolver.getResource(path) : null;
        if (r == null) {
            throw new IllegalArgumentException("No Auto-Assign rule " + path);
        }
        resolver.delete(r);
        resolver.commit();
    }

    /** The editable templates of every configuration, for choosing one: path and title. */
    static List<Map<String, Object>> templates(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource conf = resolver.getResource("/conf");
        if (conf == null) {
            return out;
        }
        for (Resource c : conf.getChildren()) {
            Resource templates = c.getChild("settings/wcm/templates");
            if (templates == null) {
                continue;
            }
            for (Resource t : templates.getChildren()) {
                if (!isTemplate(t)) {
                    continue;
                }
                Resource content = t.getChild("jcr:content");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", t.getPath());
                m.put("title", content == null ? t.getName() : content.getValueMap().get("jcr:title", t.getName()));
                m.put("conf", c.getPath());
                out.add(m);
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    static boolean isTemplate(Resource r) {
        if (r == null) {
            return false;
        }
        String type = r.getValueMap().get("jcr:primaryType", String.class);
        return "cq:Template".equals(type) || r.getChild("structure") != null && r.getChild("jcr:content") != null;
    }

    private static String trim(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : path.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }
}
