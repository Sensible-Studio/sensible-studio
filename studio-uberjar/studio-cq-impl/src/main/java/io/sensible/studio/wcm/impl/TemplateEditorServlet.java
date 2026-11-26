/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.NameConstants;

/**
 * The template editor's server side, for editable templates
 * ({@code /conf/<conf>/settings/wcm/templates/<template>} with {@code structure}, {@code initial} and
 * {@code policies}), as AEM keeps them.
 * <ul>
 *   <li>{@code GET op=template&path=<template, or a path in its structure or initial content>}: title, status,
 *       the template's pages, and which structure components are unlocked ({@code editable=true});</li>
 *   <li>{@code GET op=policy&path=<component in structure or initial content>&type=<resource type>[&container=<path>]}:
 *       the policy that applies (with {@code container}: the policy of {@code type} inside that container, the
 *       "Allowed Components" entries of a layout container), the policies of that type in the template's
 *       configuration and the templates that use the selected one;</li>
 *   <li>{@code GET op=components}: every component authors may be allowed, with its group (the "Allowed
 *       Components" of a container policy);</li>
 *   <li>{@code POST op=policy&path=&type=[&container=]&policy=<path>} assigns a policy; with {@code new=true}
 *       ({@code title}, {@code description}, optional {@code copyFrom}) it first creates one in
 *       {@code <conf>/settings/wcm/policies/<type>/policy_<n>}. Its properties are then written with a Sling POST
 *       to the policy, like AEM's policy dialog does;</li>
 *   <li>{@code POST op=lock|unlock&path=<structure component>}: locks or unlocks it for authors;</li>
 *   <li>{@code POST op=status&path=<template>&status=enabled|disabled|draft}.</li>
 * </ul>
 * Everything runs with the requesting user's resolver.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/templates",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class TemplateEditorServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    static final Pattern TEMPLATE = Pattern.compile("^(/conf/[^/]+(?:/[^/]+)*?)/settings/wcm/templates/([^/]+)(/.*)?$");
    static final String RT_MAPPINGS = "wcm/core/components/policies/mappings";
    static final String RT_MAPPING = "wcm/core/components/policies/mapping";
    static final String RT_POLICY = "wcm/core/components/policy/policy";
    static final String EDITABLE = "editable";
    private static final int MAX_DEPTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ------------------------------------------------------------------ GET

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String op = request.getParameter("op");
        try {
            if ("template".equals(op)) {
                Tpl t = Tpl.of(resolver, request.getParameter("path"));
                send(response, 200, t.describe());
            } else if ("policy".equals(op)) {
                Tpl t = Tpl.of(resolver, request.getParameter("path"));
                send(response, 200, t.policyInfo(request.getParameter("path"), request.getParameter("type"),
                        request.getParameter("container")));
            } else if ("components".equals(op)) {
                send(response, 200, Collections.singletonMap("components", components(resolver)));
            } else {
                send(response, 404, Collections.singletonMap("error", "Unknown op"));
            }
        } catch (IllegalArgumentException e) {
            send(response, 400, Collections.singletonMap("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ POST

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String op = request.getParameter("op");
        try {
            Tpl t = Tpl.of(resolver, request.getParameter("path"));
            Object result;
            if ("policy".equals(op)) {
                result = t.assignPolicy(request.getParameter("path"), request.getParameter("type"), request.getParameter("container"),
                        request.getParameter("policy"), "true".equals(request.getParameter("new")), request.getParameter("title"),
                        request.getParameter("description"), request.getParameter("copyFrom"));
            } else if ("lock".equals(op) || "unlock".equals(op)) {
                result = t.setLocked(request.getParameter("path"), "lock".equals(op));
            } else if ("status".equals(op)) {
                result = t.setStatus(request.getParameter("status"));
            } else {
                send(response, 404, Collections.singletonMap("error", "Unknown op"));
                return;
            }
            t.touch(resolver.getUserID());
            resolver.commit();
            send(response, 200, result);
        } catch (IllegalArgumentException e) {
            resolver.revert();
            send(response, 400, Collections.singletonMap("error", e.getMessage()));
        } catch (PersistenceException e) {
            resolver.revert();
            send(response, 409, Collections.singletonMap("error", "Could not save: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ the template

    /** An editable template and its parts. */
    static final class Tpl {
        final ResourceResolver resolver;
        final Resource template;
        final String conf;

        private Tpl(ResourceResolver resolver, Resource template, String conf) {
            this.resolver = resolver;
            this.template = template;
            this.conf = conf;
        }

        static Tpl of(ResourceResolver resolver, String path) {
            if (path == null || path.contains("/../") || path.endsWith("/..") || path.contains("/./")) {
                throw new IllegalArgumentException("A template path is required");
            }
            Matcher m = TEMPLATE.matcher(path);
            if (!m.matches()) {
                throw new IllegalArgumentException(path + " is not in an editable template");
            }
            String tplPath = m.group(1) + "/settings/wcm/templates/" + m.group(2);
            Resource t = resolver.getResource(tplPath);
            if (t == null || !"cq:Template".equals(t.getValueMap().get("jcr:primaryType", String.class))) {
                throw new IllegalArgumentException("No editable template at " + tplPath);
            }
            return new Tpl(resolver, t, m.group(1));
        }

        String path() {
            return template.getPath();
        }

        String part(String name) {
            return path() + "/" + name;
        }

        ValueMap props() {
            Resource c = template.getChild(NameConstants.NN_CONTENT);
            return c == null ? ValueMap.EMPTY : c.getValueMap();
        }

        Map<String, Object> describe() {
            ValueMap p = props();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path());
            m.put("name", template.getName());
            m.put("title", p.get(NameConstants.PN_TITLE, template.getName()));
            m.put("description", p.get(NameConstants.PN_DESCRIPTION, String.class));
            m.put("status", p.get("status", "draft"));
            m.put("templateType", p.get("cq:templateType", String.class));
            m.put("conf", conf);
            m.put("structure", part(TemplateImpl.NN_STRUCTURE));
            m.put("initial", part(TemplateImpl.NN_INITIAL));
            m.put("policies", part(TemplateImpl.NN_POLICIES));
            m.put("thumbnail", template.getChild("thumbnail.png") != null ? part("thumbnail.png") : null);
            m.put("lastModified", EditorServlet.iso(p.get(NameConstants.PN_PAGE_LAST_MOD, Calendar.class)));
            m.put("lastModifiedBy", p.get(NameConstants.PN_PAGE_LAST_MOD_BY, String.class));
            Map<String, Object> locks = new LinkedHashMap<>();
            Resource structure = resolver.getResource(part(TemplateImpl.NN_STRUCTURE) + "/" + NameConstants.NN_CONTENT);
            if (structure != null) {
                collectLocks(structure, locks, 0);
            }
            m.put("unlocked", locks);
            return m;
        }

        /** Structure components (with a resource type) and whether authors may edit them. */
        private void collectLocks(Resource r, Map<String, Object> out, int depth) {
            if (depth > MAX_DEPTH) {
                return;
            }
            for (Resource c : r.getChildren()) {
                String n = c.getName();
                if (n.startsWith("cq:") || n.startsWith("jcr:") || n.startsWith("rep:")) {
                    continue;
                }
                if (c.getValueMap().get("sling:resourceType", String.class) != null) {
                    out.put(c.getPath(), c.getValueMap().get(EDITABLE, false));
                }
                collectLocks(c, out, depth + 1);
            }
        }

        /** The content root ({@code structure} or {@code initial}) {@code path} lies in, and the path below it. */
        String[] relative(String path) {
            for (String part : new String[] {TemplateImpl.NN_STRUCTURE, TemplateImpl.NN_INITIAL}) {
                String root = part(part) + "/" + NameConstants.NN_CONTENT;
                if (path.equals(root)) {
                    return new String[] {root, ""};
                }
                if (path.startsWith(root + "/")) {
                    return new String[] {root, path.substring(root.length() + 1)};
                }
            }
            throw new IllegalArgumentException(path + " is not in the structure or initial content of " + path());
        }

        /** Where the policy of {@code type} at {@code path} (inside {@code container}, if given) is mapped. */
        String mappingRel(String path, String type, String container) {
            String rel = relative(path)[1];
            if (container == null || container.isEmpty()) {
                return rel;
            }
            String containerRel = relative(container)[1];
            String t = relativeType(type);
            return containerRel.isEmpty() ? t : containerRel + "/" + t;
        }

        String mappingRoot() {
            return part(TemplateImpl.NN_POLICIES) + "/" + NameConstants.NN_CONTENT;
        }

        String policiesRoot(String type) {
            return conf + "/settings/wcm/policies/" + relativeType(type);
        }

        Resource policyOf(Resource mapping) {
            String ref = mapping == null ? null : mapping.getValueMap().get(Pages.PN_POLICY, String.class);
            if (ref == null || ref.isEmpty()) {
                return null;
            }
            return resolver.getResource(ref.startsWith("/") ? ref : conf + "/settings/wcm/policies/" + ref);
        }

        Map<String, Object> policyInfo(String path, String type, String container) {
            if (type == null || type.isEmpty()) {
                Resource r = resolver.getResource(path);
                type = r == null ? null : r.getResourceType();
            }
            if (relativeType(type) == null) {
                throw new IllegalArgumentException("A component type is required");
            }
            String rel = mappingRel(path, type, container);
            String mappingPath = rel.isEmpty() ? mappingRoot() : mappingRoot() + "/" + rel;
            Resource mapping = resolver.getResource(mappingPath);
            Resource policy = policyOf(mapping);
            boolean inherited = false;
            if (policy == null && (container == null || container.isEmpty()) && !rel.isEmpty()) {
                // like the content policy manager: a container's mapping for this type applies to its components
                for (String anc = Pages.parentPathOrEmpty(rel); anc != null; anc = anc.isEmpty() ? null : Pages.parentPathOrEmpty(anc)) {
                    Resource m = resolver.getResource((anc.isEmpty() ? mappingRoot() : mappingRoot() + "/" + anc) + "/" + relativeType(type));
                    policy = policyOf(m);
                    if (policy != null) {
                        inherited = true;
                        break;
                    }
                }
            }
            ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
            com.day.cq.wcm.api.components.Component component = cm.getComponent(type);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", relativeType(type));
            out.put("componentTitle", component == null ? relativeType(type) : EditMeta.of(
                    new org.apache.sling.api.resource.SyntheticResource(resolver, "/", type), component).title);
            out.put("designDialog", component != null && EditMeta.of(
                    new org.apache.sling.api.resource.SyntheticResource(resolver, "/", type), component).designDialog);
            out.put("mapping", mappingPath);
            out.put("policy", policy == null ? null : describePolicy(policy));
            out.put("inherited", inherited);
            List<Map<String, Object>> list = new ArrayList<>();
            Resource root = resolver.getResource(policiesRoot(type));
            if (root != null) {
                for (Resource p : root.getChildren()) {
                    if (RT_POLICY.equals(p.getResourceType()) || p.getValueMap().containsKey(NameConstants.PN_TITLE)) {
                        list.add(describePolicy(p));
                    }
                }
            }
            out.put("policies", list);
            out.put("usedBy", policy == null ? Collections.emptyList() : usedBy(policy));
            return out;
        }

        Map<String, Object> describePolicy(Resource p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p.getPath());
            m.put("name", p.getName());
            m.put("title", p.getValueMap().get(NameConstants.PN_TITLE, p.getName()));
            m.put("description", p.getValueMap().get(NameConstants.PN_DESCRIPTION, String.class));
            return m;
        }

        /** The templates of this configuration whose mappings name {@code policy}. */
        List<Map<String, Object>> usedBy(Resource policy) {
            String prefix = conf + "/settings/wcm/policies/";
            String ref = policy.getPath().startsWith(prefix) ? policy.getPath().substring(prefix.length()) : policy.getPath();
            List<Map<String, Object>> out = new ArrayList<>();
            Resource templates = resolver.getResource(conf + "/settings/wcm/templates");
            if (templates == null) {
                return out;
            }
            for (Resource t : templates.getChildren()) {
                Resource mappings = t.getChild(TemplateImpl.NN_POLICIES + "/" + NameConstants.NN_CONTENT);
                if (mappings != null && refers(mappings, ref, policy.getPath(), 0)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", t.getPath());
                    Resource c = t.getChild(NameConstants.NN_CONTENT);
                    m.put("title", c == null ? t.getName() : c.getValueMap().get(NameConstants.PN_TITLE, t.getName()));
                    m.put("thumbnail", t.getChild("thumbnail.png") != null ? t.getPath() + "/thumbnail.png" : null);
                    out.add(m);
                }
            }
            return out;
        }

        private static boolean refers(Resource r, String ref, String abs, int depth) {
            String v = r.getValueMap().get(Pages.PN_POLICY, String.class);
            if (ref.equals(v) || abs.equals(v)) {
                return true;
            }
            if (depth > MAX_DEPTH) {
                return false;
            }
            for (Resource c : r.getChildren()) {
                if (refers(c, ref, abs, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        Map<String, Object> assignPolicy(String path, String type, String container, String policyPath, boolean create,
                String title, String description, String copyFrom) throws PersistenceException {
            if (relativeType(type) == null || new ComponentManagerImpl(resolver).getComponent(type) == null) {
                throw new IllegalArgumentException("Unknown component " + type);
            }
            String root = policiesRoot(type);
            Resource policy;
            if (create) {
                if (title == null || title.trim().isEmpty()) {
                    throw new IllegalArgumentException("A policy title is required");
                }
                Resource parent = ensure(root, "nt:unstructured", null);
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("jcr:primaryType", "nt:unstructured");
                props.put("sling:resourceType", RT_POLICY);
                props.put(NameConstants.PN_TITLE, title.trim());
                if (description != null && !description.trim().isEmpty()) {
                    props.put(NameConstants.PN_DESCRIPTION, description.trim());
                }
                String name;
                do {
                    name = "policy_" + (Math.abs(RANDOM.nextLong()) % 900_000_000_000_000L + 100_000_000_000_000L);
                } while (parent.getChild(name) != null);
                policy = resolver.create(parent, name, props);
                if (copyFrom != null && !copyFrom.isEmpty()) {
                    Resource source = resolver.getResource(copyFrom);
                    if (source == null || !copyFrom.startsWith(root + "/")) {
                        throw new IllegalArgumentException("No policy " + copyFrom + " to copy");
                    }
                    copyContent(source, policy, true);
                }
            } else {
                policy = policyPath == null ? null : resolver.getResource(policyPath);
                if (policy == null || !policyPath.startsWith(root + "/")) {
                    throw new IllegalArgumentException("No policy " + policyPath + " for " + type);
                }
            }
            String rel = mappingRel(path, type, container);
            Resource mapping = ensure(rel.isEmpty() ? mappingRoot() : mappingRoot() + "/" + rel, "nt:unstructured", RT_MAPPINGS);
            ModifiableValueMap mv = writable(mapping);
            mv.put(Pages.PN_POLICY, policy.getPath().substring(conf.length() + "/settings/wcm/policies/".length()));
            mv.put("sling:resourceType", RT_MAPPING);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("policy", describePolicy(policy));
            out.put("mapping", mapping.getPath());
            return out;
        }

        /** Copies properties (except identity and type) and children of {@code from} into {@code to}. */
        private void copyContent(Resource from, Resource to, boolean top) throws PersistenceException {
            ModifiableValueMap target = writable(to);
            for (Map.Entry<String, Object> e : from.getValueMap().entrySet()) {
                String k = e.getKey();
                if (k.startsWith("jcr:") || k.startsWith("rep:") || k.startsWith("cq:last") || (top && "sling:resourceType".equals(k))) {
                    continue;
                }
                target.put(k, e.getValue());
            }
            for (Resource c : from.getChildren()) {
                if (c.getName().startsWith("rep:") || NameConstants.NN_CONTENT.equals(c.getName())) {
                    continue;
                }
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("jcr:primaryType", c.getValueMap().get("jcr:primaryType", "nt:unstructured"));
                copyContent(c, resolver.create(to, c.getName(), props), false);
            }
        }

        Map<String, Object> setLocked(String path, boolean locked) {
            String[] rel = relative(path);
            if (!rel[0].startsWith(part(TemplateImpl.NN_STRUCTURE)) || rel[1].isEmpty()) {
                throw new IllegalArgumentException("Only components of the structure can be locked or unlocked");
            }
            Resource r = resolver.getResource(path);
            if (r == null || r.getValueMap().get("sling:resourceType", String.class) == null) {
                throw new IllegalArgumentException("No component at " + path);
            }
            ModifiableValueMap mv = writable(r);
            if (locked) {
                mv.remove(EDITABLE);
            } else {
                mv.put(EDITABLE, true);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("unlocked", !locked);
            return out;
        }

        Map<String, Object> setStatus(String status) {
            if (!"enabled".equals(status) && !"disabled".equals(status) && !"draft".equals(status)) {
                throw new IllegalArgumentException("Status is enabled, disabled or draft");
            }
            Resource content = template.getChild(NameConstants.NN_CONTENT);
            if (content == null) {
                throw new IllegalArgumentException(path() + " has no jcr:content");
            }
            writable(content).put("status", status);
            return describe();
        }

        void touch(String user) {
            Resource content = template.getChild(NameConstants.NN_CONTENT);
            ModifiableValueMap mv = content == null ? null : content.adaptTo(ModifiableValueMap.class);
            if (mv != null) {
                mv.put(NameConstants.PN_PAGE_LAST_MOD, Calendar.getInstance());
                mv.put(NameConstants.PN_PAGE_LAST_MOD_BY, user);
            }
        }

        /** The resource at {@code path}, created (with its missing ancestors below the template or conf) if needed. */
        private Resource ensure(String path, String primaryType, String resourceType) throws PersistenceException {
            Resource r = resolver.getResource(path);
            if (r != null) {
                return r;
            }
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            if (!parentPath.startsWith(conf + "/settings/wcm/")) {
                throw new IllegalArgumentException("Cannot create " + path);
            }
            Resource parent = ensure(parentPath, primaryType, resourceType);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("jcr:primaryType", primaryType);
            if (resourceType != null) {
                props.put("sling:resourceType", resourceType);
            }
            return resolver.create(parent, path.substring(path.lastIndexOf('/') + 1), props);
        }

        private static ModifiableValueMap writable(Resource r) {
            ModifiableValueMap mv = r.adaptTo(ModifiableValueMap.class);
            if (mv == null) {
                throw new IllegalArgumentException("You cannot modify " + r.getPath());
            }
            return mv;
        }
    }

    /** {@code /apps/wknd/components/text} and {@code wknd/components/text} both give the latter. */
    static String relativeType(String type) {
        if (type == null || type.isEmpty() || type.contains("..")) {
            return null;
        }
        if (type.startsWith("/apps/")) {
            return type.substring("/apps/".length());
        }
        if (type.startsWith("/libs/")) {
            return type.substring("/libs/".length());
        }
        return type.startsWith("/") ? null : type;
    }

    // ------------------------------------------------------------------ components

    /** Components of /apps and /libs in a group authors see (not a hidden {@code .group}). */
    static List<Map<String, Object>> components(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String root : new String[] {"/apps", "/libs"}) {
            Resource r = resolver.getResource(root);
            if (r != null) {
                collect(r, cm, out, seen, 0);
            }
        }
        out.sort((a, b) -> {
            int g = String.valueOf(a.get("group")).compareToIgnoreCase(String.valueOf(b.get("group")));
            return g != 0 ? g : String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title")));
        });
        return out;
    }

    private static void collect(Resource parent, ComponentManagerImpl cm, List<Map<String, Object>> out, java.util.Set<String> seen, int depth) {
        if (depth > 10) {
            return;
        }
        for (Resource child : parent.getChildren()) {
            if (ComponentManagerImpl.isComponent(child)) {
                com.day.cq.wcm.api.components.Component c = cm.getComponent(child.getPath());
                String group = c == null ? null : c.getComponentGroup();
                String type = c == null ? null : cm.toResourceType(c.getPath());
                if (group != null && !group.startsWith(".") && type != null && seen.add(type)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", c.getPath());
                    m.put("resourceType", type);
                    m.put("title", c.getTitle() != null ? c.getTitle() : c.getName());
                    m.put("group", group);
                    out.add(m);
                }
            }
            if (!child.getName().contains(":")) {
                collect(child, cm, out, seen, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------ io

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }
}
