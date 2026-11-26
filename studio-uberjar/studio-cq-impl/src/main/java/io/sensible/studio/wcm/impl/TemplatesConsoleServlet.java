/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.NameConstants;

/**
 * The Templates console's server side ({@code Tools > General > Templates}): the configurations under
 * {@code /conf}, their editable templates and the template types new templates start from, as AEM keeps them.
 * <ul>
 *   <li>{@code GET op=confs}: the configurations, with how many templates each has;</li>
 *   <li>{@code GET op=templates&conf=}: the templates of a configuration;</li>
 *   <li>{@code GET op=types&conf=}: the template types available to it (its own, {@code /conf/global},
 *       {@code /apps/settings} and {@code /libs/settings}), ordered by {@code ranking};</li>
 *   <li>{@code GET op=usage&path=<template>}: the pages made from the template;</li>
 *   <li>{@code GET op=policies&path=<template>}: the content policies the template maps (what AEM publishes with
 *       it);</li>
 *   <li>{@code POST op=create&conf=&type=&title=[&description=][&name=]}: a new draft template, a copy of the
 *       type's structure, initial content, policies and thumbnail;</li>
 *   <li>{@code POST op=copy&path=}: a draft copy of a template next to it;</li>
 *   <li>{@code POST op=delete&path=}: deletes a template no page uses;</li>
 *   <li>{@code POST op=properties&path=&title=&description=}: the template's title and description;</li>
 *   <li>{@code POST op=thumbnail&path=} with a {@code file} upload, or {@code delete=true}: its
 *       {@code thumbnail.png}.</li>
 * </ul>
 * Status changes (enable, disable) go through {@link TemplateEditorServlet}. Everything runs with the requesting
 * user's resolver.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/templateconsole",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class TemplatesConsoleServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    static final String CONF = "/conf";
    static final String TEMPLATES = "/settings/wcm/templates";
    static final String TYPES = "/settings/wcm/template-types";
    static final String THUMBNAIL = "thumbnail.png";
    static final long MAX_THUMBNAIL = 10L * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_PAGES_SCANNED = 50000;
    /** Properties the repository sets itself: a copy gets its own. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:baseVersion", "jcr:predecessors", "jcr:versionHistory",
            "jcr:isCheckedOut", "jcr:lockOwner", "jcr:lockIsDeep", "jcr:frozenUuid", "jcr:frozenPrimaryType"));
    private static final Set<String> FOLDERS = new HashSet<>(Arrays.asList("sling:Folder", "sling:OrderedFolder", "nt:folder"));

    // ------------------------------------------------------------------ GET

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String op = request.getParameter("op");
        try {
            Object result;
            if ("confs".equals(op)) {
                result = Collections.singletonMap("confs", confs(resolver));
            } else if ("templates".equals(op)) {
                result = Collections.singletonMap("templates", templates(resolver, conf(resolver, request.getParameter("conf"))));
            } else if ("types".equals(op)) {
                result = Collections.singletonMap("types", types(resolver, conf(resolver, request.getParameter("conf"))));
            } else if ("policies".equals(op)) {
                result = Collections.singletonMap("policies", policies(resolver, template(resolver, request.getParameter("path"))));
            } else if ("usage".equals(op)) {
                result = usage(resolver, template(resolver, request.getParameter("path")).getPath());
            } else {
                send(response, 404, Collections.singletonMap("error", "Unknown op"));
                return;
            }
            send(response, 200, result);
        } catch (IllegalArgumentException e) {
            send(response, 400, Collections.singletonMap("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ POST

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String op = request.getParameter("op");
        String user = resolver.getUserID();
        try {
            Object result;
            int status = 200;
            if ("create".equals(op)) {
                Resource t = create(resolver, conf(resolver, request.getParameter("conf")), request.getParameter("type"),
                        request.getParameter("title"), request.getParameter("description"), request.getParameter("name"), user);
                result = summary(t);
                status = 201;
            } else if ("copy".equals(op)) {
                result = summary(copy(resolver, template(resolver, request.getParameter("path")), user));
                status = 201;
            } else if ("delete".equals(op)) {
                result = delete(resolver, template(resolver, request.getParameter("path")));
            } else if ("properties".equals(op)) {
                Resource t = template(resolver, request.getParameter("path"));
                properties(t, request.getParameter("title"), request.getParameter("description"));
                touch(t, user);
                result = summary(t);
            } else if ("thumbnail".equals(op)) {
                Resource t = template(resolver, request.getParameter("path"));
                if ("true".equals(request.getParameter("delete"))) {
                    Resource thumb = t.getChild(THUMBNAIL);
                    if (thumb != null) {
                        resolver.delete(thumb);
                    }
                } else {
                    RequestParameter file = request.getRequestParameter("file");
                    if (file == null || file.isFormField()) {
                        throw new IllegalArgumentException("Choose an image to upload");
                    }
                    if (file.getSize() > MAX_THUMBNAIL) {
                        throw new IllegalArgumentException("The image is larger than 10 MB");
                    }
                    String type = file.getContentType();
                    if (type == null || !type.startsWith("image/")) {
                        throw new IllegalArgumentException("The thumbnail must be an image");
                    }
                    try (InputStream in = file.getInputStream()) {
                        thumbnail(resolver, t, in, type);
                    }
                }
                touch(t, user);
                result = summary(t);
            } else {
                send(response, 404, Collections.singletonMap("error", "Unknown op"));
                return;
            }
            resolver.commit();
            send(response, status, result);
        } catch (IllegalArgumentException e) {
            resolver.revert();
            send(response, 400, Collections.singletonMap("error", e.getMessage()));
        } catch (IllegalStateException e) {
            resolver.revert();
            send(response, 409, Collections.singletonMap("error", e.getMessage()));
        } catch (PersistenceException e) {
            resolver.revert();
            send(response, 409, Collections.singletonMap("error", "Could not save: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ configurations

    /** The configurations directly under {@code /conf}, Global first. */
    static List<Map<String, Object>> confs(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = resolver.getResource(CONF);
        if (root == null) {
            return out;
        }
        for (Resource c : root.getChildren()) {
            if (c.getName().startsWith("rep:") || c.getName().contains(":")) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.getPath());
            m.put("name", c.getName());
            m.put("title", confTitle(c));
            Resource templates = resolver.getResource(c.getPath() + TEMPLATES);
            int n = 0;
            if (templates != null) {
                for (Resource t : templates.getChildren()) {
                    if (isTemplate(t)) {
                        n++;
                    }
                }
            }
            m.put("templates", n);
            out.add(m);
        }
        out.sort((a, b) -> {
            boolean ga = "global".equals(a.get("name"));
            boolean gb = "global".equals(b.get("name"));
            if (ga != gb) {
                return ga ? -1 : 1;
            }
            return String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title")));
        });
        return out;
    }

    static String confTitle(Resource conf) {
        String t = conf.getValueMap().get(NameConstants.PN_TITLE, String.class);
        Resource content = conf.getChild(NameConstants.NN_CONTENT);
        if (t == null && content != null) {
            t = content.getValueMap().get(NameConstants.PN_TITLE, String.class);
        }
        if (t != null && !t.trim().isEmpty()) {
            return t;
        }
        String n = conf.getName();
        return n.isEmpty() ? n : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }

    /** A configuration path: {@code /conf/<name>} (or deeper), existing. */
    static Resource conf(ResourceResolver resolver, String path) {
        if (path == null || !path.startsWith(CONF + "/") || path.contains("/..") || path.contains("/./") || path.contains("/settings/")) {
            throw new IllegalArgumentException("A configuration under /conf is required");
        }
        Resource r = resolver.getResource(path);
        if (r == null) {
            throw new IllegalArgumentException("No configuration at " + path);
        }
        return r;
    }

    // ------------------------------------------------------------------ templates

    static boolean isTemplate(Resource r) {
        return r != null && "cq:Template".equals(r.getValueMap().get("jcr:primaryType", String.class));
    }

    /** An editable template, validated by {@link TemplateEditorServlet.Tpl}. */
    static Resource template(ResourceResolver resolver, String path) {
        TemplateEditorServlet.Tpl t = TemplateEditorServlet.Tpl.of(resolver, path);
        if (!t.path().equals(path)) {
            throw new IllegalArgumentException(path + " is not a template");
        }
        return t.template;
    }

    static List<Map<String, Object>> templates(ResourceResolver resolver, Resource conf) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource folder = resolver.getResource(conf.getPath() + TEMPLATES);
        if (folder != null) {
            for (Resource t : folder.getChildren()) {
                if (isTemplate(t)) {
                    out.add(summary(t));
                }
            }
        }
        return out;
    }

    static Map<String, Object> summary(Resource t) {
        Resource content = t.getChild(NameConstants.NN_CONTENT);
        ValueMap p = content == null ? ValueMap.EMPTY : content.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", t.getPath());
        m.put("name", t.getName());
        m.put("title", p.get(NameConstants.PN_TITLE, t.getName()));
        m.put("description", p.get(NameConstants.PN_DESCRIPTION, String.class));
        m.put("status", p.get("status", "draft"));
        String type = p.get("cq:templateType", String.class);
        m.put("templateType", type);
        Resource typeRes = type == null ? null : t.getResourceResolver().getResource(type);
        m.put("templateTypeTitle", typeRes == null ? null : title(typeRes));
        m.put("thumbnail", thumbnailOf(t));
        m.put("lastModified", EditorServlet.iso(p.get(NameConstants.PN_PAGE_LAST_MOD, Calendar.class)));
        m.put("lastModifiedBy", p.get(NameConstants.PN_PAGE_LAST_MOD_BY, String.class));
        m.put("created", EditorServlet.iso(t.getValueMap().get("jcr:created", Calendar.class)));
        return m;
    }

    /** The thumbnail's path, with its modification time so a new upload is not answered from the cache. */
    static String thumbnailOf(Resource t) {
        Resource thumb = t.getChild(THUMBNAIL);
        if (thumb == null) {
            return null;
        }
        Resource c = thumb.getChild(NameConstants.NN_CONTENT);
        Calendar mod = c == null ? null : c.getValueMap().get("jcr:lastModified", Calendar.class);
        return thumb.getPath() + (mod == null ? "" : "?ck=" + mod.getTimeInMillis());
    }

    static String title(Resource tplOrType) {
        Resource content = tplOrType.getChild(NameConstants.NN_CONTENT);
        String t = content == null ? null : content.getValueMap().get(NameConstants.PN_TITLE, String.class);
        return t == null || t.trim().isEmpty() ? tplOrType.getName() : t;
    }

    // ------------------------------------------------------------------ template types

    /** Where a configuration's template types come from, most specific first. */
    static List<String> typeRoots(Resource conf) {
        List<String> roots = new ArrayList<>();
        roots.add(conf.getPath() + TYPES);
        if (!conf.getPath().equals(CONF + "/global")) {
            roots.add(CONF + "/global" + TYPES);
        }
        roots.add("/apps/settings/wcm/template-types");
        roots.add("/libs/settings/wcm/template-types");
        return roots;
    }

    static List<Map<String, Object>> types(ResourceResolver resolver, Resource conf) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String root : typeRoots(conf)) {
            Resource folder = resolver.getResource(root);
            if (folder == null) {
                continue;
            }
            for (Resource t : folder.getChildren()) {
                // a type in /apps overlays the one of the same name in /libs
                if (!isTemplate(t) || (root.startsWith("/libs/") && names.contains("apps:" + t.getName()))) {
                    continue;
                }
                if (root.startsWith("/apps/")) {
                    names.add("apps:" + t.getName());
                }
                Resource content = t.getChild(NameConstants.NN_CONTENT);
                ValueMap p = content == null ? ValueMap.EMPTY : content.getValueMap();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", t.getPath());
                m.put("name", t.getName());
                m.put("title", title(t));
                m.put("description", p.get(NameConstants.PN_DESCRIPTION, String.class));
                m.put("thumbnail", thumbnailOf(t));
                m.put("ranking", t.getValueMap().get("ranking", p.get("ranking", 1000L)));
                m.put("source", root.substring(0, root.indexOf(TYPES)));
                out.add(m);
            }
        }
        out.sort((a, b) -> {
            int r = Long.compare(((Number) a.get("ranking")).longValue(), ((Number) b.get("ranking")).longValue());
            return r != 0 ? r : String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title")));
        });
        return out;
    }

    // ------------------------------------------------------------------ create, copy, delete

    static Resource create(ResourceResolver resolver, Resource conf, String typePath, String title, String description, String name,
            String user) throws PersistenceException {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("The template needs a title");
        }
        if (typePath == null || !typeRoots(conf).stream().anyMatch(r -> typePath.startsWith(r + "/"))) {
            throw new IllegalArgumentException("Choose one of the template types of " + conf.getPath());
        }
        Resource type = resolver.getResource(typePath);
        if (!isTemplate(type)) {
            throw new IllegalArgumentException("No template type at " + typePath);
        }
        Resource folder = ensureFolder(resolver, conf.getPath() + TEMPLATES);
        String base = name != null && !name.trim().isEmpty() ? validName(name) : Pages.toValidName(title.trim());
        if (name != null && !name.trim().isEmpty() && folder.getChild(base) != null) {
            throw new IllegalArgumentException("A template named " + base + " already exists");
        }
        String n = unique(folder, base);
        Resource t = copyTree(resolver, type, folder, n);
        Resource content = t.getChild(NameConstants.NN_CONTENT);
        if (content == null) {
            content = resolver.create(t, NameConstants.NN_CONTENT, props("jcr:primaryType", NameConstants.NT_PAGE_CONTENT));
        }
        ModifiableValueMap mv = writable(content);
        mv.remove("ranking");
        mv.put(NameConstants.PN_TITLE, title.trim());
        if (description != null && !description.trim().isEmpty()) {
            mv.put(NameConstants.PN_DESCRIPTION, description.trim());
        } else {
            mv.remove(NameConstants.PN_DESCRIPTION);
        }
        mv.put("cq:templateType", type.getPath());
        mv.put("status", "draft");
        writable(t).remove("ranking");
        pointPartsAt(t);
        touch(t, user);
        return t;
    }

    static Resource copy(ResourceResolver resolver, Resource template, String user) throws PersistenceException {
        Resource folder = template.getParent();
        Resource t = copyTree(resolver, template, folder, unique(folder, template.getName() + "-copy"));
        ModifiableValueMap mv = writable(t.getChild(NameConstants.NN_CONTENT));
        mv.put(NameConstants.PN_TITLE, title(template) + " (Copy)");
        mv.put("status", "draft");
        pointPartsAt(t);
        touch(t, user);
        return t;
    }

    static Map<String, Object> delete(ResourceResolver resolver, Resource template) throws PersistenceException {
        Map<String, Object> used = usage(resolver, template.getPath());
        if (((Number) used.get("count")).intValue() > 0) {
            throw new IllegalStateException("“" + title(template) + "” is used by " + used.get("count")
                    + " page(s) and cannot be deleted. Disable it instead.");
        }
        String path = template.getPath();
        resolver.delete(template);
        return Collections.singletonMap("deleted", path);
    }

    /** The structure and initial content name their template, as in AEM. */
    private static void pointPartsAt(Resource t) {
        for (String part : new String[] {TemplateImpl.NN_STRUCTURE, TemplateImpl.NN_INITIAL}) {
            Resource c = t.getResourceResolver().getResource(t.getPath() + "/" + part + "/" + NameConstants.NN_CONTENT);
            if (c != null) {
                ModifiableValueMap mv = writable(c);
                mv.put(NameConstants.PN_TEMPLATE, t.getPath());
                mv.remove(NameConstants.PN_PAGE_LAST_MOD);
                mv.remove(NameConstants.PN_PAGE_LAST_MOD_BY);
                for (String k : new ArrayList<>(mv.keySet())) {
                    if (k.startsWith("cq:lastReplicat")) {
                        mv.remove(k);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ properties, thumbnail

    static void properties(Resource template, String title, String description) {
        if (title != null && title.trim().isEmpty()) {
            throw new IllegalArgumentException("The template needs a title");
        }
        ModifiableValueMap mv = writable(template.getChild(NameConstants.NN_CONTENT));
        if (title != null) {
            mv.put(NameConstants.PN_TITLE, title.trim());
        }
        if (description != null) {
            if (description.trim().isEmpty()) {
                mv.remove(NameConstants.PN_DESCRIPTION);
            } else {
                mv.put(NameConstants.PN_DESCRIPTION, description.trim());
            }
        }
    }

    static void thumbnail(ResourceResolver resolver, Resource template, InputStream data, String mimeType) throws PersistenceException {
        Resource old = template.getChild(THUMBNAIL);
        if (old != null) {
            resolver.delete(old);
        }
        Resource file = resolver.create(template, THUMBNAIL, props("jcr:primaryType", "nt:file"));
        Map<String, Object> content = props("jcr:primaryType", "nt:resource");
        content.put("jcr:data", data);
        content.put("jcr:mimeType", mimeType);
        content.put("jcr:lastModified", Calendar.getInstance());
        resolver.create(file, NameConstants.NN_CONTENT, content);
    }

    static void touch(Resource template, String user) {
        Resource content = template.getChild(NameConstants.NN_CONTENT);
        ModifiableValueMap mv = content == null ? null : content.adaptTo(ModifiableValueMap.class);
        if (mv != null) {
            mv.put(NameConstants.PN_PAGE_LAST_MOD, Calendar.getInstance());
            mv.put(NameConstants.PN_PAGE_LAST_MOD_BY, user);
        }
    }

    // ------------------------------------------------------------------ policies

    /**
     * The policies the template's mappings point at ({@code policies/jcr:content/**@cq:policy}, relative to
     * {@code <conf>/settings/wcm/policies}, else {@code /conf/global}), each once, in mapping order.
     */
    static List<Map<String, Object>> policies(ResourceResolver resolver, Resource template) {
        String conf = template.getPath().substring(0, template.getPath().indexOf(TEMPLATES));
        Set<String> seen = new java.util.LinkedHashSet<>();
        Resource mappings = template.getChild(TemplateImpl.NN_POLICIES + "/" + NameConstants.NN_CONTENT);
        if (mappings != null) {
            collectPolicies(mappings, seen, 0);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String rel : seen) {
            Resource policy = null;
            for (String root : new String[] {conf, CONF + "/global"}) {
                policy = resolver.getResource(root + "/settings/wcm/policies/" + rel);
                if (policy != null) {
                    break;
                }
            }
            if (policy == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", policy.getPath());
            m.put("title", policy.getValueMap().get(NameConstants.PN_TITLE, policy.getName()));
            m.put("component", rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : rel);
            out.add(m);
        }
        return out;
    }

    private static void collectPolicies(Resource r, Set<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        String rel = r.getValueMap().get("cq:policy", String.class);
        if (rel != null && !rel.isEmpty() && !rel.startsWith("/") && !rel.contains("..")) {
            out.add(rel);
        }
        for (Resource c : r.getChildren()) {
            collectPolicies(c, out, depth + 1);
        }
    }

    // ------------------------------------------------------------------ usage

    /** The pages (under {@code /content}, not in the DAM) whose {@code cq:template} is the template. */
    static Map<String, Object> usage(ResourceResolver resolver, String template) {
        List<String> pages = new ArrayList<>();
        int[] count = {0};
        int[] scanned = {0};
        Resource content = resolver.getResource("/content");
        if (content != null) {
            scan(content, template, pages, count, scanned, 0);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count[0]);
        m.put("pages", pages);
        m.put("complete", scanned[0] < MAX_PAGES_SCANNED);
        return m;
    }

    private static void scan(Resource parent, String template, List<String> pages, int[] count, int[] scanned, int depth) {
        if (depth > MAX_DEPTH || scanned[0] >= MAX_PAGES_SCANNED) {
            return;
        }
        for (Resource c : parent.getChildren()) {
            String name = c.getName();
            if (name.contains(":") || (depth == 0 && ("dam".equals(name) || "cq:tags".equals(name)))) {
                continue;
            }
            String type = c.getValueMap().get("jcr:primaryType", String.class);
            if (NameConstants.NT_PAGE.equals(type)) {
                scanned[0]++;
                Resource pc = c.getChild(NameConstants.NN_CONTENT);
                if (pc != null && template.equals(pc.getValueMap().get(NameConstants.PN_TEMPLATE, String.class))) {
                    count[0]++;
                    if (pages.size() < 50) {
                        pages.add(c.getPath());
                    }
                }
                scan(c, template, pages, count, scanned, depth + 1);
            } else if (type == null || FOLDERS.contains(type)) {
                scan(c, template, pages, count, scanned, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Copies {@code source} (properties, mixins and children, binaries included) to {@code parent/name}. */
    static Resource copyTree(ResourceResolver resolver, Resource source, Resource parent, String name) throws PersistenceException {
        return copyTree(resolver, source, parent, name, 0);
    }

    private static Resource copyTree(ResourceResolver resolver, Resource source, Resource parent, String name, int depth)
            throws PersistenceException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(source.getPath() + " is nested too deeply to copy");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.getValueMap().entrySet()) {
            String k = e.getKey();
            if (PROTECTED.contains(k) || k.startsWith("rep:") || e.getValue() == null) {
                continue;
            }
            props.put(k, e.getValue());
        }
        props.putIfAbsent("jcr:primaryType", "nt:unstructured");
        Resource copy = resolver.create(parent, name, props);
        for (Resource c : source.getChildren()) {
            if (c.getName().startsWith("rep:")) {
                continue; // access control stays with the original
            }
            copyTree(resolver, c, copy, c.getName(), depth + 1);
        }
        return copy;
    }

    private static Resource ensureFolder(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) {
            return r;
        }
        String parentPath = path.substring(0, path.lastIndexOf('/'));
        Resource parent = ensureFolder(resolver, parentPath);
        return resolver.create(parent, path.substring(path.lastIndexOf('/') + 1), props("jcr:primaryType", "sling:Folder"));
    }

    static String validName(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("The name may use letters, digits, - and _");
        }
        return n;
    }

    private static String unique(Resource parent, String base) {
        if (parent.getChild(base) == null) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = base + "-" + i;
            if (parent.getChild(candidate) == null) {
                return candidate;
            }
        }
    }

    private static Map<String, Object> props(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static ModifiableValueMap writable(Resource r) {
        ModifiableValueMap mv = r == null ? null : r.adaptTo(ModifiableValueMap.class);
        if (mv == null) {
            throw new IllegalArgumentException("You cannot modify " + (r == null ? "the template" : r.getPath()));
        }
        return mv;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }
}
