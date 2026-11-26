/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import com.day.cq.wcm.api.NameConstants;

/**
 * The Components console's server side (AEM's Tools › General › Components), at {@code /bin/studio/components.json}:
 * <ul>
 *   <li>{@code GET} (no {@code op}): every component of the search path ({@code /apps}, then {@code /libs}), hidden
 *       groups included, as the console lists them: title, group, resource type, icon, container, super type;</li>
 *   <li>{@code GET op=component&path=<component>}: its properties, what it is made of (dialog, design dialog,
 *       edit config, template, scripts) and its {@code README.md};</li>
 *   <li>{@code GET op=policies&path=<component>}: the policies of its resource type in every configuration, with
 *       the templates that use each one;</li>
 *   <li>{@code GET op=usage&path=<component>}: the pages under {@code /content} that hold it (Live Usage).</li>
 * </ul>
 * Everything runs with the requesting user's resolver.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/components",
                "sling.servlet.methods=GET"
        })
public class ComponentsConsoleServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    static final int MAX_DEPTH = 12;
    static final int MAX_README = 64 * 1024;
    /** Component nodes a usage search reads at most; the pages they are on come from these. */
    static final int MAX_HITS = 5000;
    static final int MAX_PAGES = 500;
    private static final String NN_DIALOG = "cq:dialog";
    private static final String NN_DESIGN_DIALOG = "cq:design_dialog";
    private static final String NN_EDIT_CONFIG = "cq:editConfig";
    private static final String NN_CHILD_EDIT_CONFIG = "cq:childEditConfig";
    private static final String NN_TEMPLATE = "cq:template";
    private static final String PN_SUPER_TYPE = "sling:resourceSuperType";
    private static final String PN_RESOURCE_TYPE = "sling:resourceType";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        if (op == null || op.isEmpty() || "list".equals(op)) {
            send(response, 200, Collections.singletonMap("components", list(resolver)));
            return;
        }
        Resource component = path == null ? null : resolver.getResource(path);
        if (!ComponentManagerImpl.isComponent(component)) {
            send(response, 404, Collections.singletonMap("error", "No component at " + path));
            return;
        }
        ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
        if ("component".equals(op)) {
            send(response, 200, detail(resolver, cm, component));
        } else if ("policies".equals(op)) {
            send(response, 200, Collections.singletonMap("policies", policies(resolver, cm.toResourceType(component.getPath()))));
        } else if ("usage".equals(op)) {
            send(response, 200, usage(resolver, component.getPath(), cm.toResourceType(component.getPath())));
        } else {
            send(response, 404, Collections.singletonMap("error", "Unknown op"));
        }
    }

    // ------------------------------------------------------------------ the list

    static List<Map<String, Object>> list(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
        Set<String> seen = new HashSet<>();
        for (String root : resolver.getSearchPath()) {
            Resource r = resolver.getResource(root.endsWith("/") ? root.substring(0, root.length() - 1) : root);
            if (r != null) {
                collect(r, cm, out, seen, 0);
            }
        }
        out.sort((a, b) -> {
            int t = String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title")));
            return t != 0 ? t : String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path")));
        });
        return out;
    }

    private static void collect(Resource parent, ComponentManagerImpl cm, List<Map<String, Object>> out, Set<String> seen, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Resource child : parent.getChildren()) {
            if (child.getName().contains(":")) {
                continue;
            }
            if (ComponentManagerImpl.isComponent(child)) {
                // an overlay in /apps hides the /libs component of the same type, as Sling resolves it
                if (seen.add(cm.toResourceType(child.getPath()))) {
                    out.add(summary(cm, child));
                }
            }
            collect(child, cm, out, seen, depth + 1);
        }
    }

    static Map<String, Object> summary(ComponentManagerImpl cm, Resource c) {
        ValueMap vm = c.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        String title = vm.get(NameConstants.PN_TITLE, c.getName());
        m.put("path", c.getPath());
        m.put("resourceType", cm.toResourceType(c.getPath()));
        m.put("title", title);
        m.put("group", vm.get("componentGroup", String.class));
        m.put("container", vm.get("cq:isContainer", false));
        m.put("superType", vm.get(PN_SUPER_TYPE, String.class));
        m.put("abbreviation", abbreviation(vm.get("abbreviation", String.class), title));
        m.put("icon", vm.get("cq:icon", String.class));
        m.put("iconImage", iconImage(c));
        return m;
    }

    /** AEM's abbreviation: the property, or the title's first two letters (the first upper case). */
    static String abbreviation(String own, String title) {
        if (own != null && !own.trim().isEmpty()) {
            return own.trim();
        }
        String t = title == null ? "" : title.replaceAll("[^\\p{L}\\p{N}]", "");
        if (t.isEmpty()) {
            return "?";
        }
        String two = t.length() > 1 ? t.substring(0, 2) : t;
        return Character.toUpperCase(two.charAt(0)) + two.substring(1).toLowerCase();
    }

    private static String iconImage(Resource c) {
        for (String name : new String[] {"icon.svg", "icon.png", "cq:icon.svg", "cq:icon.png"}) {
            if (c.getChild(name) != null) {
                return c.getPath() + "/" + name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ one component

    static Map<String, Object> detail(ResourceResolver resolver, ComponentManagerImpl cm, Resource c) {
        ValueMap vm = c.getValueMap();
        Map<String, Object> m = summary(cm, c);
        m.put("description", vm.get(NameConstants.PN_DESCRIPTION, String.class));
        String superType = vm.get(PN_SUPER_TYPE, String.class);
        Resource sup = superType == null ? null : cm.resolveType(superType);
        m.put("superTypePath", ComponentManagerImpl.isComponent(sup) ? sup.getPath() : null);
        m.put("created", iso(vm.get("jcr:created", Calendar.class)));
        m.put("createdBy", vm.get("jcr:createdBy", String.class));
        Calendar modified = vm.get("jcr:lastModified", Calendar.class);
        m.put("lastModified", iso(modified != null ? modified : vm.get(NameConstants.PN_PAGE_LAST_MOD, Calendar.class)));
        m.put("lastModifiedBy", vm.get("jcr:lastModifiedBy", vm.get("cq:lastModifiedBy", String.class)));
        m.put("dialog", childPath(c, NN_DIALOG));
        m.put("designDialog", childPath(c, NN_DESIGN_DIALOG));
        m.put("editConfig", childPath(c, NN_EDIT_CONFIG));
        m.put("childEditConfig", childPath(c, NN_CHILD_EDIT_CONFIG));
        m.put("template", childPath(c, NN_TEMPLATE));
        List<String> scripts = new ArrayList<>();
        for (Resource child : c.getChildren()) {
            String n = child.getName();
            if (n.endsWith(".html") || n.endsWith(".jsp") || n.endsWith(".js") || n.endsWith(".esp")) {
                scripts.add(n);
            }
        }
        Collections.sort(scripts);
        m.put("scripts", scripts);
        m.put("readme", readme(c));
        // the chain of super types, for "what it extends"
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        for (Resource s = sup; ComponentManagerImpl.isComponent(s) && guard.add(s.getPath()) && chain.size() < 10; ) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", s.getPath());
            e.put("title", s.getValueMap().get(NameConstants.PN_TITLE, s.getName()));
            chain.add(e);
            String next = s.getValueMap().get(PN_SUPER_TYPE, String.class);
            s = next == null ? null : cm.resolveType(next);
        }
        m.put("superTypes", chain);
        return m;
    }

    private static String childPath(Resource c, String name) {
        Resource r = c.getChild(name);
        return r == null ? null : r.getPath();
    }

    private static String readme(Resource c) {
        Resource r = c.getChild("README.md");
        if (r == null) {
            return null;
        }
        InputStream in = r.adaptTo(InputStream.class);
        if (in == null) {
            Resource content = r.getChild(NameConstants.NN_CONTENT);
            in = content == null ? null : content.adaptTo(InputStream.class);
        }
        if (in == null) {
            return null;
        }
        try (InputStream body = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = body.read(buf)) > 0 && out.size() < MAX_README; ) {
                out.write(buf, 0, Math.min(n, MAX_README - out.size()));
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String iso(Calendar c) {
        return c == null ? null : c.toInstant().toString();
    }

    // ------------------------------------------------------------------ policies

    /** The policies of {@code type} in every configuration under {@code /conf}, with the templates that use each. */
    static List<Map<String, Object>> policies(ResourceResolver resolver, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource confs = resolver.getResource("/conf");
        if (confs == null) {
            return out;
        }
        List<Resource> roots = new ArrayList<>();
        for (Resource conf : confs.getChildren()) {
            roots.add(conf);
            for (Resource nested : conf.getChildren()) {
                if (nested.getChild("settings") != null && !"settings".equals(nested.getName())) {
                    roots.add(nested);
                }
            }
        }
        for (Resource conf : roots) {
            Resource folder = resolver.getResource(conf.getPath() + "/settings/wcm/policies/" + type);
            if (folder == null) {
                continue;
            }
            String confTitle = conf.getValueMap().get(NameConstants.PN_TITLE, conf.getChild(NameConstants.NN_CONTENT) == null
                    ? conf.getName() : conf.getChild(NameConstants.NN_CONTENT).getValueMap().get(NameConstants.PN_TITLE, conf.getName()));
            for (Resource p : folder.getChildren()) {
                ValueMap vm = p.getValueMap();
                // a policy has the policy resource type or a title; anything else is a folder of another type
                if (p.getName().contains(":")
                        || vm.get(NameConstants.PN_TITLE) == null && !"wcm/core/components/policy/policy".equals(vm.get(PN_RESOURCE_TYPE, String.class))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", p.getPath());
                m.put("title", vm.get(NameConstants.PN_TITLE, p.getName()));
                m.put("description", vm.get(NameConstants.PN_DESCRIPTION, String.class));
                m.put("conf", conf.getPath());
                m.put("confTitle", confTitle);
                Calendar modified = vm.get("jcr:lastModified", Calendar.class);
                m.put("lastModified", iso(modified != null ? modified : vm.get(NameConstants.PN_PAGE_LAST_MOD, Calendar.class)));
                m.put("lastModifiedBy", vm.get("jcr:lastModifiedBy", vm.get("cq:lastModifiedBy", String.class)));
                m.put("templates", templatesUsing(resolver, conf.getPath(), p.getPath()));
                out.add(m);
            }
        }
        return out;
    }

    /** The templates of {@code conf} whose policy mappings name {@code policy} (relative, as AEM stores them, or absolute). */
    static List<Map<String, Object>> templatesUsing(ResourceResolver resolver, String conf, String policy) {
        String prefix = conf + "/settings/wcm/policies/";
        String ref = policy.startsWith(prefix) ? policy.substring(prefix.length()) : policy;
        List<Map<String, Object>> out = new ArrayList<>();
        Resource templates = resolver.getResource(conf + "/settings/wcm/templates");
        if (templates == null) {
            return out;
        }
        for (Resource t : templates.getChildren()) {
            Resource mappings = t.getChild(TemplateImpl.NN_POLICIES + "/" + NameConstants.NN_CONTENT);
            if (mappings != null && refers(mappings, ref, policy, 0)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", t.getPath());
                Resource c = t.getChild(NameConstants.NN_CONTENT);
                m.put("title", c == null ? t.getName() : c.getValueMap().get(NameConstants.PN_TITLE, t.getName()));
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
        if (depth > 32) {
            return false;
        }
        for (Resource c : r.getChildren()) {
            if (refers(c, ref, abs, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ live usage

    static Map<String, Object> usage(ResourceResolver resolver, String path, String type) {
        String q = "SELECT [jcr:path] FROM [nt:base] AS n WHERE (n.[sling:resourceType] = '" + literal(type)
                + "' OR n.[sling:resourceType] = '" + literal(path) + "') AND ISDESCENDANTNODE(n, '/content')";
        return pagesOf(resolver.findResources(q, "JCR-SQL2"));
    }

    private static String literal(String s) {
        return s.replace("'", "''");
    }

    /** The pages the hits are on (the closest {@code cq:Page} above each), how often, sorted by path. */
    static Map<String, Object> pagesOf(Iterator<Resource> hits) {
        Map<String, Map<String, Object>> pages = new TreeMap<>();
        int read = 0;
        boolean truncated = false;
        while (hits.hasNext()) {
            if (read >= MAX_HITS) {
                truncated = true;
                break;
            }
            Resource hit = hits.next();
            read++;
            Resource page = hit;
            while (page != null && !Pages.isPage(page)) {
                page = page.getParent();
            }
            if (page == null) {
                continue;
            }
            Map<String, Object> m = pages.get(page.getPath());
            if (m == null) {
                if (pages.size() >= MAX_PAGES) {
                    truncated = true;
                    continue;
                }
                m = new LinkedHashMap<>();
                Resource content = page.getChild(NameConstants.NN_CONTENT);
                ValueMap vm = content == null ? ValueMap.EMPTY : content.getValueMap();
                m.put("path", page.getPath());
                m.put("title", vm.get(NameConstants.PN_TITLE, page.getName()));
                m.put("count", 0);
                pages.put(page.getPath(), m);
            }
            m.put("count", (Integer) m.get("count") + 1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", new ArrayList<>(pages.values()));
        out.put("uses", read);
        out.put("truncated", truncated);
        return out;
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
