/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.api.policies.ContentPolicy;

/**
 * Data the Sensible Studio page editor cannot get from Sling's default servlets.
 * <ul>
 *   <li>{@code GET /bin/studio/editor.json?op=page&path=<page>}: page info, the editable containers
 *       of the page with the component types their policy allows, and those components' info;</li>
 *   <li>{@code GET /bin/studio/editor.json?op=assets&path=/content/dam&type=images&q=&offset=0&limit=20}:
 *       assets for the side panel;</li>
 *   <li>{@code POST /bin/studio/editor.json?op=lock|unlock&path=<page>}; {@code op=publish|unpublish&path=[&deep=true]}
 *       records the replication status ({@code cq:lastReplicated*}); {@code op=version&path=&label=&comment=}
 *       saves a version of the page; {@code op=convertToXf&path=…&parent=&title=&template=&resourceType=} turns
 *       components into an experience fragment ({@link XfConversion}).</li>
 * </ul>
 * Everything is read with the requesting user's resolver: the editor only sees what the author may see.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/editor",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class EditorServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ASSETS_SCANNED = 5000;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        String op = request.getParameter("op");
        ResourceResolver resolver = request.getResourceResolver();
        Object result;
        if ("page".equals(op)) {
            result = pageInfo(resolver, request.getParameter("path"));
        } else if ("assets".equals(op)) {
            result = assets(resolver, param(request, "path", "/content/dam"), param(request, "type", "images"),
                    param(request, "q", ""), intParam(request, "offset", 0), intParam(request, "limit", 20));
        } else if ("xfTemplates".equals(op)) {
            result = Collections.singletonMap("templates", XfConversion.templates(resolver, param(request, "parent", "/content/experience-fragments")));
        } else if ("styles".equals(op)) {
            result = styles(resolver, request.getParameter("path"));
        } else if ("pages".equals(op)) {
            result = pages(resolver, param(request, "root", "/content"), Math.min(intParam(request, "depth", 2), 6),
                    "true".equals(request.getParameter("xf")));
        } else {
            result = null;
        }
        if (result == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op or path");
            return;
        }
        send(response, result);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        String op = request.getParameter("op");
        ResourceResolver resolver = request.getResourceResolver();
        PageManagerImpl pm = new PageManagerImpl(resolver);
        if ("exportToXf".equals(op)) {
            // Generate Variations: the variations chosen become a new experience fragment
            try {
                send(response, XfExport.export(resolver, param(request, "parent", null), param(request, "title", null),
                        param(request, "template", null), request.getParameterValues("part")));
            } catch (WCMException | IllegalArgumentException e) {
                error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            } catch (PersistenceException e) {
                error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
            return;
        }
        if ("convertToXf".equals(op)) {
            try {
                send(response, XfConversion.convert(resolver, request.getParameterValues("path"), param(request, "parent", null),
                        param(request, "title", null), param(request, "template", null), param(request, "resourceType", null)));
            } catch (WCMException | IllegalArgumentException e) {
                error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            } catch (PersistenceException e) {
                error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
            return;
        }
        Page page = pm.getPage(request.getParameter("path"));
        if (page == null || !("lock".equals(op) || "unlock".equals(op) || "version".equals(op) || "publish".equals(op)
                || "unpublish".equals(op))) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op or page");
            return;
        }
        try {
            if ("version".equals(op)) {
                // a saved version of the page (Timewarp shows them; the Sites timeline lists them)
                com.day.cq.wcm.api.Revision r = pm.createRevision(page, param(request, "label", null), param(request, "comment", null));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("name", r.getVersionName());
                m.put("label", r.getLabel());
                m.put("comment", r.getComment());
                m.put("created", iso(r.getCreated()));
                send(response, m);
                return;
            }
            if ("publish".equals(op) || "unpublish".equals(op)) {
                // the replication status AEM keeps on the page; Sensible Studio has no publish instance to send it to yet
                markReplicated(page, "publish".equals(op) ? "Activate" : "Deactivate", resolver.getUserID());
                if ("true".equals(request.getParameter("deep"))) {
                    for (java.util.Iterator<Page> it = page.listChildren(null, true); it.hasNext();) {
                        markReplicated(it.next(), "publish".equals(op) ? "Activate" : "Deactivate", resolver.getUserID());
                    }
                }
                resolver.commit();
                send(response, page(page));
                return;
            }
            if ("lock".equals(op)) {
                page.lock();
            } else {
                page.unlock();
            }
            resolver.commit();
        } catch (WCMException e) {
            error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            return;
        } catch (PersistenceException e) {
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }
        send(response, page(page));
    }

    // ------------------------------------------------------------------ page

    Map<String, Object> pageInfo(ResourceResolver resolver, String path) {
        PageManagerImpl pm = new PageManagerImpl(resolver);
        Page page = path == null ? null : pm.getPage(path);
        if (page == null || page.getContentResource() == null) {
            return null;
        }
        ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
        ContentPolicyManagerImpl policies = new ContentPolicyManagerImpl(resolver, pm);
        Map<String, Object> containers = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> assetMappings = new LinkedHashMap<>();
        Map<String, List<String>> allowedByPolicy = new LinkedHashMap<>();
        List<com.day.cq.wcm.api.components.Component> appComponents = new ArrayList<>();
        boolean[] appScanned = {false};
        // the page as it renders: with its editable template's structure (locked parts, unlocked containers)
        Resource content = page.getContentResource();
        com.day.cq.wcm.api.TemplatedResource merged = TemplatedResourceImpl.of(content);
        collectContainers(merged != null ? merged : content, 0, cm, (container) -> {
            ContentPolicy policy = policies.getPolicy(container);
            String key = policy == null ? "" : policy.getPath();
            List<String> allowed = allowedByPolicy.computeIfAbsent(key, k -> {
                String[] entries = policy == null ? new String[0]
                        : policy.getProperties().get("components", new String[0]);
                return resolveAllowed(entries, resolver, cm, appComponents, appScanned, components);
            });
            containers.put(container.getPath(), allowed);
            List<Map<String, Object>> mappings = assetMappings(policy);
            if (!mappings.isEmpty()) {
                assetMappings.put(container.getPath(), mappings);
            }
        });
        List<String> styled = new ArrayList<>();
        collectStyled(page.getContentResource(), 0, policies, new java.util.HashMap<>(), styled);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page(page));
        out.put("containers", containers);
        out.put("components", components);
        out.put("assetMappings", assetMappings);
        out.put("styled", styled);
        java.util.regex.Matcher tm = TemplateEditorServlet.TEMPLATE.matcher(page.getPath());
        if (tm.matches() && tm.group(3) != null
                && (tm.group(3).equals("/" + TemplateImpl.NN_STRUCTURE) || tm.group(3).equals("/" + TemplateImpl.NN_INITIAL))) {
            // the template editor: the template, and which mode the page is for
            try {
                Map<String, Object> t = TemplateEditorServlet.Tpl.of(resolver, page.getPath()).describe();
                t.put("mode", tm.group(3).substring(1));
                out.put("templateEditor", t);
            } catch (IllegalArgumentException e) {
                // a page that only looks like a template part
            }
        }
        return out;
    }

    /** Paths of the components whose policy defines Style System groups (they get a Styles action). */
    private void collectStyled(Resource resource, int depth, ContentPolicyManagerImpl policies,
            Map<String, Boolean> byPolicy, List<String> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Resource child : resource.getChildren()) {
            String name = child.getName();
            if (name.startsWith("cq:") || name.startsWith("jcr:") || name.startsWith("rep:")) {
                continue;
            }
            ContentPolicy policy = policies.getPolicy(child);
            if (policy != null) {
                boolean has = byPolicy.computeIfAbsent(policy.getPath(), k -> {
                    Resource pr = policy.adaptTo(Resource.class);
                    Resource groups = pr == null ? null : pr.getChild("cq:styleGroups");
                    if (groups == null) {
                        return false;
                    }
                    for (Resource g : groups.getChildren()) {
                        Resource styles = g.getChild("cq:styles");
                        if (styles != null && styles.hasChildren()) {
                            return true;
                        }
                    }
                    return false;
                });
                if (has) {
                    out.add(child.getPath());
                }
            }
            collectStyled(child, depth + 1, policies, byPolicy, out);
        }
    }

    /**
     * The policy's {@code cq:authoring/assetToComponentMapping/*}: which component an asset dropped
     * into the container becomes ({@code assetMimetype} such as {@code image/*}, {@code resourceType},
     * {@code droptarget}).
     */
    static List<Map<String, Object>> assetMappings(ContentPolicy policy) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource pr = policy == null ? null : policy.adaptTo(Resource.class);
        Resource mapping = pr == null ? null : pr.getChild("cq:authoring/assetToComponentMapping");
        if (mapping != null) {
            for (Resource m : mapping.getChildren()) {
                ValueMap vm = m.getValueMap();
                String type = vm.get("resourceType", String.class);
                String mime = vm.get("assetMimetype", String.class);
                if (type != null && mime != null) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("mimeType", mime);
                    e.put("resourceType", type);
                    e.put("dropTarget", vm.get("droptarget", String.class));
                    out.add(e);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ styles

    /**
     * Style System choices of one component instance: the groups of its policy
     * ({@code cq:styleGroups/<group>}: {@code cq:styleGroupLabel}, {@code cq:styleGroupMultiple},
     * {@code cq:styles/<style>}: {@code cq:styleId}, {@code cq:styleLabel}, {@code cq:styleClasses})
     * and the ids applied to the instance ({@code cq:styleIds}).
     */
    Map<String, Object> styles(ResourceResolver resolver, String path) {
        Resource resource = path == null ? null : resolver.getResource(path);
        if (resource == null) {
            return null;
        }
        PageManagerImpl pm = new PageManagerImpl(resolver);
        ContentPolicy policy = new ContentPolicyManagerImpl(resolver, pm).getPolicy(resource);
        Resource pr = policy == null ? null : policy.adaptTo(Resource.class);
        Resource groupsRes = pr == null ? null : pr.getChild("cq:styleGroups");
        List<Map<String, Object>> groups = new ArrayList<>();
        if (groupsRes != null) {
            for (Resource group : groupsRes.getChildren()) {
                ValueMap gv = group.getValueMap();
                List<Map<String, Object>> styles = new ArrayList<>();
                Resource stylesRes = group.getChild("cq:styles");
                if (stylesRes != null) {
                    for (Resource style : stylesRes.getChildren()) {
                        ValueMap sv = style.getValueMap();
                        String id = sv.get("cq:styleId", String.class);
                        if (id == null) {
                            continue;
                        }
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("id", id);
                        s.put("label", sv.get("cq:styleLabel", id));
                        s.put("classes", sv.get("cq:styleClasses", ""));
                        styles.add(s);
                    }
                }
                if (styles.isEmpty()) {
                    continue;
                }
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("name", group.getName());
                g.put("label", gv.get("cq:styleGroupLabel", group.getName()));
                g.put("multiple", gv.get("cq:styleGroupMultiple", false));
                g.put("styles", styles);
                groups.add(g);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("policy", policy == null ? null : policy.getPath());
        out.put("groups", groups);
        out.put("applied", Arrays.asList(resource.getValueMap().get("cq:styleIds", new String[0])));
        return out;
    }

    interface ContainerVisitor {
        void visit(Resource container);
    }

    private void collectContainers(Resource resource, int depth, ComponentManagerImpl cm, ContainerVisitor visitor) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Resource child : resource.getChildren()) {
            String name = child.getName();
            if (name.startsWith("cq:") || name.startsWith("jcr:") || name.startsWith("rep:")) {
                continue;
            }
            if (EditMeta.of(child, cm.getComponentOfResource(child)).container) {
                visitor.visit(child);
            }
            collectContainers(child, depth + 1, cm, visitor);
        }
    }

    /**
     * Policy entries are component paths ({@code /apps/site/components/title}) or {@code group:Name}: every component of
     * that group in /apps or /libs.
     */
    private List<String> resolveAllowed(String[] entries, ResourceResolver resolver, ComponentManagerImpl cm,
            List<com.day.cq.wcm.api.components.Component> appComponents, boolean[] appScanned,
            Map<String, Object> infos) {
        Set<String> types = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.startsWith("group:")) {
                if (!appScanned[0]) {
                    appScanned[0] = true;
                    // a group holds the components of /apps and of /libs (Dynamic Media, foundation components), as
                    // the policy dialog lists them; /apps first, so an overlay counts once and with its own definition
                    for (String root : new String[] {"/apps", "/libs"}) {
                        Resource r = resolver.getResource(root);
                        if (r != null) {
                            collectComponents(r, appComponents, 0);
                        }
                    }
                }
                String group = entry.substring("group:".length());
                for (com.day.cq.wcm.api.components.Component c : appComponents) {
                    if (group.equals(c.getComponentGroup())) {
                        types.add(register(c, cm, infos));
                    }
                }
            } else {
                com.day.cq.wcm.api.components.Component c = cm.getComponent(entry);
                if (c != null) {
                    types.add(register(c, cm, infos));
                }
            }
        }
        return new ArrayList<>(types);
    }

    private static void collectComponents(Resource parent, List<com.day.cq.wcm.api.components.Component> out, int depth) {
        if (depth > 10) {
            return;
        }
        for (Resource child : parent.getChildren()) {
            if (ComponentManagerImpl.isComponent(child)) {
                out.add(new ComponentManagerImpl(child.getResourceResolver()).getComponent(child.getPath()));
            }
            if (!child.getName().contains(":")) {
                collectComponents(child, out, depth + 1);
            }
        }
    }

    private static String register(com.day.cq.wcm.api.components.Component c, ComponentManagerImpl cm,
            Map<String, Object> infos) {
        String type = cm.toResourceType(c.getPath());
        if (!infos.containsKey(type)) {
            infos.put(type, componentInfo(c, type));
        }
        return type;
    }

    static Map<String, Object> componentInfo(com.day.cq.wcm.api.components.Component c, String type) {
        Resource fake = new org.apache.sling.api.resource.SyntheticResource(null, "/", type);
        EditMeta meta = EditMeta.of(fake, c);
        ValueMap p = c.getProperties();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resourceType", type);
        m.put("path", c.getPath());
        m.put("title", meta.title);
        m.put("group", c.getComponentGroup());
        m.put("description", c.getDescription());
        m.put("abbreviation", p.get("abbreviation", String.class));
        m.put("dialog", meta.dialog);
        m.put("designDialog", meta.designDialog);
        m.put("container", meta.container);
        m.put("drop", meta.dropProperty);
        m.put("dropAccept", meta.dropAccept);
        m.putAll(EditConfig.describe(c));
        // Developer mode: the component's super types, nearest first
        List<Map<String, Object>> supers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (com.day.cq.wcm.api.components.Component sc = c.getSuperComponent(); sc != null && seen.add(sc.getPath()); sc = sc.getSuperComponent()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("resourceType", sc.getResourceType());
            sm.put("path", sc.getPath());
            sm.put("title", sc.getTitle());
            supers.add(sm);
        }
        m.put("superTypes", supers);
        return m;
    }

    static void markReplicated(Page page, String action, String user) {
        Resource content = page.getContentResource();
        org.apache.sling.api.resource.ModifiableValueMap m = content == null ? null
                : content.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        if (m != null) {
            m.put("cq:lastReplicated", Calendar.getInstance());
            m.put("cq:lastReplicatedBy", user);
            m.put("cq:lastReplicationAction", action);
        }
    }

    static Map<String, Object> page(Page page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", page.getPath());
        m.put("name", page.getName());
        m.put("title", page.getTitle());
        m.put("resourceType", page.getContentResource() == null ? null : page.getContentResource().getResourceType());
        Template t = page.getTemplate();
        if (t != null) {
            m.put("template", t.getPath());
            m.put("templateTitle", t.getTitle());
            m.put("editableTemplate", t.hasStructureSupport());
        }
        m.put("breakpoints", breakpoints(page, t));
        m.put("lastModified", iso(page.getLastModified()));
        m.put("lastModifiedBy", page.getLastModifiedBy());
        m.put("locked", page.isLocked());
        m.put("lockOwner", page.getLockOwner());
        m.put("canUnlock", page.canUnlock());
        m.put("canEdit", may(page, false));
        m.put("canPublish", may(page, true));
        m.put("language", page.getLanguage().toLanguageTag());
        ValueMap cv = page.getContentResource() == null ? ValueMap.EMPTY : page.getContentResource().getValueMap();
        m.put("lastReplicated", iso(cv.get("cq:lastReplicated", Calendar.class)));
        m.put("lastReplicatedBy", cv.get("cq:lastReplicatedBy", String.class));
        m.put("lastReplicationAction", cv.get("cq:lastReplicationAction", String.class));
        // published: activated after the last change
        Calendar rep = cv.get("cq:lastReplicated", Calendar.class);
        Calendar mod = page.getLastModified();
        m.put("published", rep != null && "Activate".equals(cv.get("cq:lastReplicationAction", String.class)) && (mod == null || !mod.after(rep)));
        return m;
    }

    /**
     * Layout breakpoints ({@code cq:responsive/breakpoints/*} with {@code title} and {@code width},
     * the largest viewport width the breakpoint applies to), from the page or its template structure.
     */
    static List<Map<String, Object>> breakpoints(Page page, Template t) {
        Resource bps = page.getContentResource("cq:responsive/breakpoints");
        if (bps == null && t != null && t.getStructurePath() != null && page.getContentResource() != null) {
            bps = page.getContentResource().getResourceResolver()
                    .getResource(t.getStructurePath() + "/jcr:content/cq:responsive/breakpoints");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (bps != null) {
            for (Resource bp : bps.getChildren()) {
                ValueMap vm = bp.getValueMap();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", bp.getName());
                m.put("title", vm.get("title", bp.getName()));
                m.put("width", vm.get("width", Long.class));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> m.get("width") == null ? Long.MAX_VALUE : (Long) m.get("width")));
        return out;
    }

    // ------------------------------------------------------------------ pages

    static final String XF_PAGE_TYPE = "cq/experience-fragments/components/xfpage";
    static final String XF_TYPE = "cq/experience-fragments/components/experiencefragment";
    private static final int MAX_PAGES = 500;

    /**
     * Pages below {@code root} (the root itself excluded) down to {@code depth} levels, depth first.
     * Without {@code xf}, experience fragments are skipped; with it only their variations are listed.
     */
    Map<String, Object> pages(ResourceResolver resolver, String root, int depth, boolean xf) {
        if (!root.startsWith("/content")) {
            return null;
        }
        Resource rootRes = resolver.getResource(root);
        if (rootRes == null) {
            return null;
        }
        PageManagerImpl pm = new PageManagerImpl(resolver);
        List<Map<String, Object>> out = new ArrayList<>();
        collectPages(rootRes, pm, new ComponentManagerImpl(resolver), 1, Math.max(1, depth), xf, out);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("root", root);
        m.put("pages", out);
        return m;
    }

    private void collectPages(Resource parent, PageManagerImpl pm, ComponentManagerImpl cm, int level, int depth, boolean xf,
            List<Map<String, Object>> out) {
        for (Resource child : parent.getChildren()) {
            if (out.size() >= MAX_PAGES) {
                return;
            }
            String name = child.getName();
            if (name.contains(":") || "dam".equals(name) && "/content".equals(parent.getPath())) {
                continue;
            }
            if (!xf && "experience-fragments".equals(name) && "/content".equals(parent.getPath())) {
                continue;
            }
            Page page = pm.getPage(child.getPath());
            boolean folder = page == null && !"cq:Page".equals(child.getValueMap().get(Pages.PN_PRIMARY_TYPE, String.class));
            if (page != null && page.getContentResource() != null) {
                String type = page.getContentResource().getResourceType();
                boolean fragment = XF_TYPE.equals(type);
                boolean variation = !fragment && inherits(type, XF_PAGE_TYPE, cm);
                if (xf ? fragment || variation : !fragment && !variation) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", page.getPath());
                    m.put("title", page.getTitle() != null ? page.getTitle() : page.getName());
                    m.put("level", level);
                    m.put("template", page.getContentResource().getValueMap().get("cq:template", String.class));
                    m.put("resourceType", type);
                    m.put("lastModified", iso(page.getLastModified()));
                    // an experience fragment groups its variations; only variations open in the editor
                    m.put("editable", !fragment);
                    out.add(m);
                }
            } else if (!folder) {
                continue;
            }
            if (level < depth) {
                collectPages(child, pm, cm, level + 1, depth, xf, out);
            }
        }
    }

    /** @return {@code true} if {@code type} is {@code ancestor} or has it as a super type */
    private static boolean inherits(String type, String ancestor, ComponentManagerImpl cm) {
        Set<String> seen = new LinkedHashSet<>();
        for (com.day.cq.wcm.api.components.Component c = cm.getComponent(type); c != null && seen.add(c.getPath());
                c = c.getSuperComponent()) {
            if (ancestor.equals(c.getResourceType()) || c.getPath().endsWith("/" + ancestor)) {
                return true;
            }
        }
        return ancestor.equals(type);
    }

    // ------------------------------------------------------------------ assets

    Map<String, Object> assets(ResourceResolver resolver, String root, String type, String q, int offset, int limit) {
        if (!root.startsWith("/content/dam") || root.contains("'") || root.contains("]")) {
            return null;
        }
        String query = "SELECT * FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, [" + root + "])";
        List<Map<String, Object>> found = new ArrayList<>();
        String needle = q.trim().toLowerCase(Locale.ROOT);
        Iterator<Resource> it = resolver.findResources(query, "JCR-SQL2");
        int scanned = 0;
        while (it.hasNext() && scanned++ < MAX_ASSETS_SCANNED) {
            Map<String, Object> a = asset(it.next());
            String mime = String.valueOf(a.get("mimeType"));
            if (!matchesType(type, mime)) {
                continue;
            }
            if (!needle.isEmpty() && !(String.valueOf(a.get("name")).toLowerCase(Locale.ROOT).contains(needle)
                    || String.valueOf(a.get("title")).toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            found.add(a);
        }
        found.sort(Comparator.comparing((Map<String, Object> a) -> String.valueOf(a.get("lastModified")))
                .reversed().thenComparing(a -> String.valueOf(a.get("name"))));
        int from = Math.min(Math.max(0, offset), found.size());
        int to = Math.min(found.size(), from + Math.max(1, Math.min(limit, 200)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", found.size());
        out.put("offset", from);
        out.put("assets", new ArrayList<>(found.subList(from, to)));
        return out;
    }

    static boolean matchesType(String type, String mime) {
        switch (type) {
            case "images":
                return mime.startsWith("image/");
            case "videos":
                return mime.startsWith("video/");
            case "documents":
                return mime.startsWith("application/") || mime.startsWith("text/");
            default:
                return true;
        }
    }

    static Map<String, Object> asset(Resource asset) {
        Resource content = asset.getChild("jcr:content");
        ValueMap meta = content == null ? ValueMap.EMPTY : valueMap(content.getChild("metadata"));
        Resource renditions = content == null ? null : content.getChild("renditions");
        Resource original = renditions == null ? null : renditions.getChild("original");
        Resource originalData = original == null ? null : original.getChild("jcr:content");
        String mime = meta.get("dc:format", String.class);
        if (mime == null && originalData != null) {
            mime = originalData.getValueMap().get("jcr:mimeType", String.class);
        }
        Long size = meta.get("dam:size", Long.class);
        if (size == null && originalData != null && originalData.getResourceMetadata().getContentLength() > 0) {
            size = originalData.getResourceMetadata().getContentLength();
        }
        String title = firstString(meta.get("dc:title"));
        String thumbnail = null;
        if (renditions != null) {
            for (String r : new String[] {"cq5dam.thumbnail.319.319.png", "cq5dam.thumbnail.140.100.png",
                    "cq5dam.web.1280.1280.jpeg"}) {
                if (renditions.getChild(r) != null) {
                    thumbnail = renditions.getPath() + "/" + r;
                    break;
                }
            }
        }
        if (thumbnail == null && mime != null && mime.startsWith("image/") && original != null) {
            thumbnail = original.getPath();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", asset.getPath());
        m.put("name", asset.getName());
        m.put("title", title != null ? title : asset.getName());
        m.put("mimeType", mime == null ? "application/octet-stream" : mime);
        m.put("size", size);
        m.put("width", meta.get("tiff:ImageWidth", Long.class));
        m.put("height", meta.get("tiff:ImageLength", Long.class));
        m.put("thumbnail", thumbnail);
        Calendar modified = content == null ? null : content.getValueMap().get("jcr:lastModified", Calendar.class);
        m.put("lastModified", iso(modified));
        return m;
    }

    private static ValueMap valueMap(Resource r) {
        return r == null ? ValueMap.EMPTY : r.getValueMap();
    }

    private static String firstString(Object v) {
        if (v instanceof String[]) {
            String[] a = (String[]) v;
            return a.length == 0 ? null : a[0];
        }
        return v == null ? null : String.valueOf(v);
    }

    // ------------------------------------------------------------------ helpers

    static String iso(Calendar c) {
        if (c == null) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    private static String param(SlingHttpServletRequest r, String name, String def) {
        String v = r.getParameter(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int intParam(SlingHttpServletRequest r, String name, int def) {
        try {
            return Integer.parseInt(r.getParameter(name));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void send(SlingHttpServletResponse response, Object body) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }

    private static void error(SlingHttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        send(response, Collections.singletonMap("error", message == null ? "" : message));
    }

    /**
     * Whether the signed-in user may change the page's content, or publish it. The editor opened for a reader
     * with every tool and a Publish button, and each of them ended in a refusal.
     */
    private static boolean may(Page page, boolean publish) {
        org.apache.sling.api.resource.Resource content = page.getContentResource();
        javax.jcr.Session session = content == null ? null : content.getResourceResolver().adaptTo(javax.jcr.Session.class);
        if (session == null) {
            return true;
        }
        String owner = page.getLockOwner();
        if (owner != null && !owner.equals(session.getUserID())) {
            return false;
        }
        try {
            boolean write = session.hasPermission(content.getPath() + "/jcr:title", javax.jcr.Session.ACTION_SET_PROPERTY)
                    && session.hasPermission(content.getPath() + "/root/new", javax.jcr.Session.ACTION_ADD_NODE);
            if (!publish) {
                return write;
            }
            javax.jcr.security.AccessControlManager acm = session.getAccessControlManager();
            try {
                return acm.hasPrivileges(page.getPath(), new javax.jcr.security.Privilege[] {acm.privilegeFromName("crx:replicate")}) || write;
            } catch (javax.jcr.security.AccessControlException e) {
                return write;
            }
        } catch (javax.jcr.RepositoryException | RuntimeException e) {
            // not known: the server still judges every change
            return true;
        }
    }
}
