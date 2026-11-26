/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.LiveRelationship;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutConfigManager;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

import io.sensible.studio.wcm.impl.EditorJson;

/**
 * MSM for the Sensible Studio author UI, with the permissions of the signed-in user.
 * <ul>
 *   <li>{@code GET /bin/studio/msm.json?op=status&path=} live copy and blueprint status of a page or component;</li>
 *   <li>{@code GET ?op=overview&path=} the live copies of a blueprint page and the state of their pages;</li>
 *   <li>{@code GET ?op=configs} rollout configurations; {@code GET ?op=liveCopies} all live copies;</li>
 *   <li>{@code POST op=rollout&path=&deep=&target=…} roll a blueprint page out (configurations with the rollout
 *       trigger); {@code op=sync&path=&deep=&reset=} synchronise a live copy page or component;</li>
 *   <li>{@code POST op=cancel|reenable&path=} a component, {@code op=suspend|resume&path=&deep=} a page,
 *       {@code op=cancelProperty|reenableProperty&path=&name=…}, {@code op=detach&path=};</li>
 *   <li>{@code POST op=create&source=&parent=&name=&title=&deep=&rolloutConfig=…&exclude=…} a new live copy;</li>
 *   <li>{@code POST op=liveCopyConfig&path=&inherit=&rolloutConfig=…&deep=&writeExcluded=&excludedPath=…} and
 *       {@code op=blueprintConfig&path=&rolloutConfig=…}: the Live Copy and Blueprint tabs of the page properties
 *       ({@link PageMsmConfig}); {@code status} reports them as {@code pageConfig} and {@code blueprintConfig};</li>
 *   <li>{@code GET op=revisions&path=} versions of a page, {@code POST op=restore&path=&revision=} restore one;</li>
 *   <li>{@code GET op=blueprints}, {@code GET op=blueprint&path=} (its languages and chapters; {@code &sitePath=}
 *       instead of {@code path}: those of a site without a blueprint configuration yet),
 *       {@code POST op=createSite&blueprint=&parent=&title=&name=&owner=&language=…&chapter=…&liveCopy=&rolloutConfig=…}
 *       ({@code newBlueprintSite=&newBlueprintTitle=} instead of {@code blueprint}: creates the configuration too),
 *       {@code POST op=createBlueprint&name=&title=&description=&sitePath=}; {@code op=updateBlueprint&blueprint=&title=&description=&sitePath=}
 *       and {@code op=deleteBlueprint&blueprint=} (Tools › Sites › Blueprints).</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/msm",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class MsmServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    transient LiveRelationshipManagerImpl relationships;

    @Reference
    transient RolloutManagerImpl rollouts;

    @Reference
    transient RolloutConfigManager configs;

    // ------------------------------------------------------------------ GET

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        ResourceResolver resolver = request.getResourceResolver();
        Object result = null;
        if ("configs".equals(op)) {
            result = Collections.singletonMap("configs", configs(configs.getRolloutConfigs(resolver)));
        } else if ("blueprints".equals(op)) {
            // with the sites made from each: the live copies of its source
            List<Map<String, Object>> blueprints = BlueprintSites.blueprints(resolver);
            List<LiveCopyIndex.Entry> copies = relationships.liveCopies(resolver);
            for (Map<String, Object> bp : blueprints) {
                bp.put("sites", sitesOf(String.valueOf(bp.get("sitePath")), copies));
            }
            result = Collections.singletonMap("blueprints", blueprints);
        } else if ("blueprint".equals(op) && (path != null || request.getParameter("sitePath") != null)) {
            try {
                result = path != null ? BlueprintSites.structure(resolver, path)
                        : BlueprintSites.structureOfSite(resolver, request.getParameter("sitePath"));
            } catch (WCMException e) {
                send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", e.getMessage()));
                return;
            }
        } else if ("liveCopies".equals(op)) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (LiveCopyIndex.Entry e : relationships.liveCopies(resolver)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", e.root);
                m.put("blueprintPath", e.master);
                out.add(m);
            }
            result = Collections.singletonMap("liveCopies", out);
        } else if (Msm.contentPath(path) != null && path.startsWith("/content/")) {
            if ("status".equals(op)) {
                result = status(resolver, path);
            } else if ("overview".equals(op)) {
                result = overview(resolver, path);
            } else if ("revisions".equals(op) && Msm.isPage(resolver.getResource(path))) {
                try {
                    result = Collections.singletonMap("revisions", revisions(resolver, path));
                } catch (WCMException e) {
                    send(response, HttpServletResponse.SC_CONFLICT, Collections.singletonMap("error", e.getMessage()));
                    return;
                }
            }
        }
        if (result == null) {
            send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "Unknown op or path"));
            return;
        }
        send(response, HttpServletResponse.SC_OK, result);
    }

    Map<String, Object> status(ResourceResolver resolver, String path) {
        Resource r = resolver.getResource(path);
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        Relationships rels = relationships.relationships(resolver);
        LiveRelationshipImpl rel = rels.relationshipOf(path);
        out.put("inLiveCopy", rel != null);
        if (rel != null) {
            out.put("liveCopy", liveCopy(rel.liveCopy()));
            out.put("relationship", relationship(rel));
            out.put("pageExclusions", rollouts.pageExclusions());
            if (Msm.isPage(r)) {
                out.put("pageConfig", PageMsmConfig.liveCopy(resolver, path, rel.liveCopy()));
            }
        }
        if (Msm.isPage(r)) {
            out.put("blueprintConfig", PageMsmConfig.blueprint(resolver, path));
        }
        List<LiveRelationshipImpl> copies = relationships.relationshipsOf(resolver, path);
        out.put("isSource", !copies.isEmpty());
        if (!copies.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (LiveRelationshipImpl c : copies) {
                Map<String, Object> m = relationship(c);
                m.put("liveCopy", liveCopy(c.liveCopy()));
                list.add(m);
            }
            out.put("liveCopies", list);
        }
        return out;
    }

    Map<String, Object> overview(ResourceResolver resolver, String path) {
        Resource page = resolver.getResource(path);
        if (!Msm.isPage(page)) {
            return null;
        }
        List<Map<String, Object>> copies = new ArrayList<>();
        for (LiveRelationshipImpl rel : relationships.relationshipsOf(resolver, path)) {
            Map<String, Object> m = liveCopy(rel.liveCopy());
            m.put("targetPath", rel.getTargetPath());
            List<Map<String, Object>> pages = new ArrayList<>();
            Relationships rels = relationships.relationships(resolver);
            collectPages(page, rel.liveCopy(), rels, pages, 0);
            m.put("pages", pages);
            copies.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("liveCopies", copies);
        return out;
    }

    private static void collectPages(Resource source, LiveCopyImpl lc, Relationships rels, List<Map<String, Object>> out,
            int level) {
        if (out.size() >= 500) {
            return;
        }
        String target = lc.targetOf(source.getPath());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourcePath", source.getPath());
        m.put("targetPath", target);
        m.put("level", level);
        Resource content = source.getChild(Msm.JCR_CONTENT);
        m.put("title", content == null ? source.getName() : content.getValueMap().get("jcr:title", source.getName()));
        if (!lc.covers(target)) {
            m.put("status", "excluded");
        } else {
            LiveStatusImpl st = rels.status(lc, source.getPath(), target);
            m.put("status", st.summary());
            m.put("lastRolledOut", iso(st.lastRolledOut));
        }
        out.add(m);
        if (lc.isDeep()) {
            for (Resource child : source.getChildren()) {
                if (Msm.isPage(child)) {
                    collectPages(child, lc, rels, out, level + 1);
                }
            }
        }
    }

    private static Map<String, Object> relationship(LiveRelationshipImpl rel) {
        LiveStatusImpl st = rel.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourcePath", rel.getSourcePath());
        m.put("targetPath", rel.getTargetPath());
        m.put("status", st.summary());
        m.put("inherited", st.related);
        m.put("cancelled", st.cancelled);
        m.put("cancelledForChildren", st.cancelledForChildren);
        m.put("cancelledByAncestor", st.cancelledByAncestor);
        m.put("cancelledProperties", new ArrayList<>(st.canceledProperties));
        m.put("sourceExisting", st.sourceExisting);
        m.put("targetExisting", st.targetExisting);
        m.put("page", st.page);
        m.put("lastRolledOut", iso(st.lastRolledOut));
        m.put("lastRolledOutBy", st.lastRolledOutBy);
        return m;
    }

    private static Map<String, Object> liveCopy(LiveCopyImpl lc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", lc.getPath());
        m.put("blueprintPath", lc.getBlueprintPath());
        m.put("deep", lc.isDeep());
        m.put("root", lc.isRoot());
        m.put("exclusions", new ArrayList<>(lc.getExclusions()));
        m.put("rolloutConfigs", configs(lc.getRolloutConfigs()));
        return m;
    }

    private static List<Map<String, Object>> configs(List<RolloutConfig> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RolloutConfig c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.getPath());
            m.put("name", c.getName());
            m.put("title", c.getTitle());
            m.put("description", c.getDescription());
            m.put("trigger", c.getTrigger().getName());
            m.put("actions", c.getActionNames());
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> revisions(ResourceResolver resolver, String page) throws WCMException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.day.cq.wcm.api.Revision r : new io.sensible.studio.wcm.impl.PageRevisions(resolver).list(page, null)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getVersionName());
            m.put("label", r.getLabel());
            m.put("comment", r.getComment());
            m.put("created", iso(r.getCreated()));
            m.put("createdBy", r.getProperties().get(io.sensible.studio.wcm.impl.PageRevisions.CREATOR, String.class));
            m.put("title", r.getTitle());
            out.add(m);
        }
        return out;
    }

    static String iso(Calendar c) {
        if (c == null) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    // ------------------------------------------------------------------ POST

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        ResourceResolver resolver = request.getResourceResolver();
        try {
            Map<String, Object> out = post(resolver, op, path, request);
            if (out == null) {
                send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "Unknown op or path"));
                return;
            }
            resolver.commit();
            if (path != null && resolver.getResource(path) != null) {
                out.put("status", status(resolver, path));
            }
            send(response, HttpServletResponse.SC_OK, out);
        } catch (WCMException | org.apache.sling.api.resource.PersistenceException e) {
            resolver.revert();
            send(response, HttpServletResponse.SC_CONFLICT, Collections.singletonMap("error", e.getMessage()));
        }
    }

    /**
     * The sites made from a blueprint's source: live copies of the site root itself (Create Site), and the sites holding
     * live copies of its languages (/content/wknd/us for /content/wknd/us/en of /content/wknd/language-masters/en).
     */
    static List<String> sitesOf(String sitePath, List<LiveCopyIndex.Entry> copies) {
        java.util.Set<String> sites = new java.util.TreeSet<>();
        for (LiveCopyIndex.Entry e : copies) {
            if (e.master.equals(sitePath)) {
                sites.add(e.root);
            } else if (e.master.startsWith(sitePath + "/") && e.master.indexOf('/', sitePath.length() + 1) < 0 && e.root.lastIndexOf('/') > 0) {
                sites.add(e.root.substring(0, e.root.lastIndexOf('/')));
            }
        }
        return new ArrayList<>(sites);
    }

    Map<String, Object> post(ResourceResolver resolver, String op, String path, SlingHttpServletRequest request)
            throws WCMException {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean deep = Boolean.parseBoolean(request.getParameter("deep"));
        boolean reset = Boolean.parseBoolean(request.getParameter("reset"));
        if ("createSite".equals(op)) {
            BlueprintSites.SiteRequest req = new BlueprintSites.SiteRequest();
            req.blueprint = request.getParameter("blueprint");
            req.parent = request.getParameter("parent");
            req.name = request.getParameter("name");
            req.title = request.getParameter("title");
            req.owner = request.getParameter("owner");
            req.liveCopy = !"false".equals(request.getParameter("liveCopy"));
            req.rolloutConfigs = request.getParameterValues("rolloutConfig");
            req.newBlueprintSite = request.getParameter("newBlueprintSite");
            req.newBlueprintTitle = request.getParameter("newBlueprintTitle");
            if (request.getParameterValues("language") != null) {
                req.languages.addAll(java.util.Arrays.asList(request.getParameterValues("language")));
            }
            if (request.getParameterValues("chapter") != null) {
                req.chapters.addAll(java.util.Arrays.asList(request.getParameterValues("chapter")));
            }
            RolloutResult r = BlueprintSites.createSite(resolver, rollouts, req);
            out.put("path", r.created.isEmpty() ? null : r.created.get(0));
            out.put("result", r.toMap());
            return out;
        }
        if ("createBlueprint".equals(op)) {
            out.put("path", BlueprintSites.createBlueprint(resolver, request.getParameter("name"),
                    request.getParameter("title"), request.getParameter("description"), request.getParameter("sitePath")));
            return out;
        }
        if ("updateBlueprint".equals(op)) {
            out.put("blueprint", BlueprintSites.updateBlueprint(resolver, request.getParameter("blueprint"), request.getParameter("title"),
                    request.getParameter("description"), request.getParameter("sitePath")));
            return out;
        }
        if ("deleteBlueprint".equals(op)) {
            BlueprintSites.deleteBlueprint(resolver, request.getParameter("blueprint"));
            out.put("deleted", request.getParameter("blueprint"));
            return out;
        }
        if ("create".equals(op)) {
            String source = request.getParameter("source");
            String parent = request.getParameter("parent");
            String name = request.getParameter("name");
            if (source == null || parent == null || name == null || name.trim().isEmpty()) {
                throw new WCMException("source, parent and name are required");
            }
            RolloutResult r = rollouts.createLiveCopy(resolver, source, parent, name.trim(), request.getParameter("title"),
                    !"false".equals(request.getParameter("deep")), request.getParameterValues("rolloutConfig"),
                    request.getParameterValues("exclude"));
            out.put("path", ("/".equals(parent) ? "" : parent) + "/" + name.trim());
            out.put("result", r.toMap());
            return out;
        }
        if (path != null && Msm.contentPath(path) == null) {
            throw new WCMException("Not a content path: " + path);
        }
        // a live copy page that does not exist yet can be synchronised (created from its blueprint)
        if (path == null || (!"sync".equals(op) && resolver.getResource(path) == null)) {
            return null;
        }
        switch (op == null ? "" : op) {
            case "rollout": {
                RolloutManagerImpl.Options o = RolloutManagerImpl.Options.of(deep, Trigger.ROLLOUT);
                String[] targets = request.getParameterValues("target");
                if (targets != null && targets.length > 0) {
                    o.targets = new java.util.LinkedHashSet<>(java.util.Arrays.asList(targets));
                }
                o.reset = reset;
                out.put("result", rollouts.rollout(resolver, path, o).toMap());
                return out;
            }
            case "sync":
                out.put("result", rollouts.synchronize(resolver, path, deep, reset).toMap());
                return out;
            case "cancel":
            case "suspend":
                relationships.cancelRelationship(resolver, relation(resolver, path),
                        "cancel".equals(op) ? !"false".equals(request.getParameter("deep")) : deep, false);
                return out;
            case "reenable":
            case "resume":
                relationships.reenableRelationship(resolver, relation(resolver, path), false);
                if (!"false".equals(request.getParameter("sync"))) {
                    out.put("result", rollouts.synchronize(resolver, path, false, false).toMap());
                }
                return out;
            case "cancelProperty":
                relationships.cancelPropertyRelationship(resolver, relation(resolver, path), names(request), false);
                return out;
            case "reenableProperty":
                relationships.reenablePropertyRelationship(resolver, relation(resolver, path), names(request), false);
                if (!"false".equals(request.getParameter("sync"))) {
                    out.put("result", rollouts.synchronize(resolver, path, false, false).toMap());
                }
                return out;
            case "restore": {
                String revision = request.getParameter("revision");
                if (revision == null || !Msm.isPage(resolver.getResource(path))) {
                    throw new WCMException("A page and a revision are required");
                }
                new io.sensible.studio.wcm.impl.PageRevisions(resolver).restore(path, revision);
                resolver.refresh();
                out.put("revisions", revisions(resolver, path));
                return out;
            }
            case "liveCopyConfig": {
                String deepParam = request.getParameter("deep");
                PageMsmConfig.writeLiveCopy(resolver, configs, path, deepParam == null ? null : Boolean.valueOf(deepParam),
                        "true".equals(request.getParameter("inherit")) ? null : values(request, "rolloutConfig"),
                        "true".equals(request.getParameter("writeExcluded")) ? values(request, "excludedPath") : null);
                return out;
            }
            case "blueprintConfig":
                PageMsmConfig.writeBlueprint(resolver, configs, path, values(request, "rolloutConfig"));
                return out;
            case "detach":
                relationships.endRelationship(resolver, relation(resolver, path), false);
                return out;
            default:
                return null;
        }
    }

    private static String[] names(SlingHttpServletRequest request) throws WCMException {
        String[] names = request.getParameterValues("name");
        if (names == null || names.length == 0) {
            throw new WCMException("name is required");
        }
        return names;
    }

    private LiveRelationship relation(ResourceResolver resolver, String path) throws WCMException {
        LiveRelationship rel = relationships.getRelationship(resolver, path);
        if (rel == null) {
            throw new WCMException(path + " is not in a live copy");
        }
        return rel;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }

    private static String[] values(SlingHttpServletRequest request, String name) {
        String[] v = request.getParameterValues(name);
        return v == null ? new String[0] : v;
    }
}
