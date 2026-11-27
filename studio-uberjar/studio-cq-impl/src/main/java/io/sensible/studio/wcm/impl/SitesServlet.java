/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.WCMException;

import io.sensible.studio.wcm.impl.msm.MsmState;

/**
 * Data for the Sites console:
 * <ul>
 *   <li>{@code GET /bin/studio/sites.json?op=children&path=/content/site}: the item at {@code path}, its
 *       trail from {@code /content} and its child pages and folders;</li>
 *   <li>{@code GET ?op=unpublished&path=/content&limit=20[&state=modified|unpublished|never]}: the pages with
 *       changes the live site does not have, newest first, with their count and the count per state;</li>
 *   <li>{@code GET ?op=search&q=<text>&path=/content&limit=20}: pages (and blocks, forms) whose title,
 *       name or path contains the text, for the command palette; a bounded walk of the page tree (it never
 *       enters jcr:content or the DAM), best matches first;</li>
 *   <li>{@code GET ?op=templates&path=<parent>}: templates a page may be created from below
 *       {@code parent} (the site's {@code cq:conf}, enabled, matching {@code cq:allowedTemplates}
 *       and the template's own rules);</li>
 *   <li>{@code POST ?op=create&parent=&template=&title=&name=}: creates a page from a template.</li>
 * </ul>
 * Moving, copying, deleting and editing properties go through the Sling POST servlet.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/sites",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class SitesServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    /** Follows a moved page on the publish side; absent on an instance without replication. */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile Replicator replicator;

    static final String ROOT = "/content";
    /** Content trees with their own consoles; the Sites console does not show them at the root. */
    static final Set<String> OTHER_CONSOLES = new HashSet<>(Arrays.asList(
            "dam", "experience-fragments", "cq:tags", "forms", "projects", "screens", "communities",
            "usergenerated", "launches", "catalogs"));
    private static final Set<String> FOLDER_TYPES = new HashSet<>(Arrays.asList(
            "sling:Folder", "sling:OrderedFolder", "nt:folder"));

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        ResourceResolver resolver = request.getResourceResolver();
        Object result = null;
        if (path != null && (path.equals(ROOT) || path.startsWith(ROOT + "/"))) {
            if ("children".equals(op)) {
                result = children(resolver, path);
            } else if ("templates".equals(op)) {
                result = templates(resolver, path);
            } else if ("references".equals(op)) {
                result = references(resolver, path);
            } else if ("search".equals(op)) {
                result = search(resolver, path, request.getParameter("q"), request.getParameter("limit"));
            } else if ("unpublished".equals(op)) {
                result = unpublished(resolver, path, request.getParameter("limit"), request.getParameter("state"));
            }
        }
        if (result == null) {
            send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "Unknown op or path"));
            return;
        }
        send(response, HttpServletResponse.SC_OK, result);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        if ("move".equals(request.getParameter("op"))) {
            move(request, response);
            return;
        }
        String op = request.getParameter("op");
        ResourceResolver resolver = request.getResourceResolver();
        PageManagerImpl pm = new PageManagerImpl(resolver);
        String parent = request.getParameter("parent");
        String name = request.getParameter("name");
        if (parent == null || !(parent.equals(ROOT) || parent.startsWith(ROOT + "/"))) {
            send(response, HttpServletResponse.SC_BAD_REQUEST, Collections.singletonMap("error", "parent: a path below " + ROOT));
            return;
        }
        try {
            if ("createXf".equals(op)) {
                // an experience fragment: the fragment and its master variation
                Resource xf = XfConversion.createFragment(resolver, pm, parent, request.getParameter("title"), name,
                        request.getParameter("template"));
                resolver.commit();
                Map<String, Object> out = item(xf, pm);
                out.put("master", xf.getPath() + "/master");
                send(response, HttpServletResponse.SC_CREATED, out);
            } else if ("copy".equals(op)) {
                send(response, HttpServletResponse.SC_CREATED, item(copy(resolver, request.getParameter("path"), parent), pm));
            } else if ("createFolder".equals(op)) {
                send(response, HttpServletResponse.SC_CREATED, item(createFolder(resolver, parent, request.getParameter("title"), name), pm));
            } else if ("create".equals(op)) {
                Page page = pm.create(parent, name == null || name.trim().isEmpty() ? null : name.trim(),
                        request.getParameter("template"), request.getParameter("title"), true);
                Resource created = page.adaptTo(Resource.class);
                if (XfConversion.isFragment(created.getParent())) {
                    XfConversion.markVariation(created);
                    resolver.commit();
                }
                send(response, HttpServletResponse.SC_CREATED, item(created, pm));
            } else {
                send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "Unknown op"));
            }
        } catch (WCMException | IllegalArgumentException e) {
            resolver.revert();
            send(response, HttpServletResponse.SC_CONFLICT, Collections.singletonMap("error", e.getMessage()));
        } catch (PersistenceException e) {
            // The repository refused (most often: no right to write there). Left to travel on as an IOException,
            // Sling took it for a client that went away and the answer was an empty 200: a failure that read as done.
            resolver.revert();
            boolean denied = String.valueOf(e.getMessage()).contains("Access denied") || String.valueOf(e.getCause()).contains("AccessDenied");
            send(response, denied ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_CONFLICT,
                    Collections.singletonMap("error", denied ? "You are not allowed to do that here" : String.valueOf(e.getMessage())));
        }
    }

    /**
     * A copy of a page (with the pages below it) or folder under {@code parent}, named like the original or with the next
     * free number. The copy has not been published: it does not carry the original's publication state.
     */
    static Resource copy(ResourceResolver resolver, String path, String parent) throws PersistenceException {
        Resource src = path == null ? null : resolver.getResource(path);
        Resource target = resolver.getResource(parent);
        if (src == null || target == null || !(path.equals(ROOT) || path.startsWith(ROOT + "/"))) {
            throw new IllegalArgumentException("Nothing to copy at " + path);
        }
        if (parent.equals(path) || parent.startsWith(path + "/")) {
            throw new IllegalArgumentException("A page cannot be copied into itself");
        }
        String name = src.getName();
        for (int i = 1; target.getChild(name) != null; i++) {
            name = src.getName() + "_" + i;
        }
        javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
        if (session == null) {
            throw new PersistenceException("No repository session to copy with");
        }
        try {
            session.getWorkspace().copy(path, parent + "/" + name);
        } catch (javax.jcr.RepositoryException e) {
            throw new PersistenceException("Could not copy " + path + ": " + e.getMessage(), e);
        }
        resolver.refresh();
        Resource copy = resolver.getResource(parent + "/" + name);
        if (copy == null) {
            throw new PersistenceException("The copy of " + path + " is not there");
        }
        forgetPublication(copy);
        resolver.commit();
        return copy;
    }

    private static void forgetPublication(Resource r) {
        org.apache.sling.api.resource.ModifiableValueMap m = r.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        if (m != null) {
            for (String key : new ArrayList<>(m.keySet())) {
                if (key.startsWith("cq:lastReplicat")) {
                    m.remove(key);
                }
            }
        }
        for (Resource c : r.getChildren()) {
            if (Pages.isPage(c) || isFolder(c) || "jcr:content".equals(c.getName())) {
                forgetPublication(c);
            }
        }
    }

    /** Marks a folder made here: it is listed while it is still empty (other folders only once they hold pages). */
    static final String PN_LISTED = "studio:listed";

    /** A folder to keep pages or experience fragments in. */
    static Resource createFolder(ResourceResolver resolver, String parent, String title, String name) throws PersistenceException {
        Resource parentRes = resolver.getResource(parent);
        if (parentRes == null) {
            throw new IllegalArgumentException("Parent " + parent + " does not exist");
        }
        if (Pages.isPage(parentRes)) {
            throw new IllegalArgumentException("A folder goes into a folder, not into a page");
        }
        String t = title == null ? "" : title.trim();
        String n = Pages.toValidName(name == null || name.trim().isEmpty() ? t : name.trim());
        if (t.isEmpty() || n == null || n.isEmpty()) {
            throw new IllegalArgumentException("A title is required");
        }
        if (parentRes.getChild(n) != null) {
            throw new IllegalArgumentException("There is already something named " + n + " here");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(Pages.PN_PRIMARY_TYPE, "sling:OrderedFolder");
        props.put("jcr:title", t);
        props.put(PN_LISTED, Boolean.TRUE);
        Resource folder = resolver.create(parentRes, n, props);
        resolver.commit();
        return folder;
    }

    // ------------------------------------------------------------------ children

    Map<String, Object> children(ResourceResolver resolver, String path) {
        Resource res = resolver.getResource(path);
        if (res == null) {
            return null;
        }
        PageManagerImpl pm = new PageManagerImpl(resolver);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Resource child : res.getChildren()) {
            if (isListed(child) && !(ROOT.equals(path) && OTHER_CONSOLES.contains(child.getName()))) {
                items.add(item(child, pm));
            }
        }
        List<Map<String, Object>> trail = new ArrayList<>();
        for (Resource r = res; r != null && (r.getPath().equals(ROOT) || r.getPath().startsWith(ROOT + "/")); r = r.getParent()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("path", r.getPath());
            t.put("title", titleOf(r, pm));
            trail.add(0, t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item(res, pm));
        out.put("trail", trail);
        out.put("children", items);
        return out;
    }

    static boolean isListed(Resource r) {
        if (r.getName().contains(":")) {
            return false; // jcr:content, rep:policy, cq:tags, cq:graphql ...
        }
        if (Pages.isPage(r)) {
            return true;
        }
        // folders only when they hold pages (Sling's own /content/starter, /content/htl ... do not); experience
        // fragments sit four folders deep (/content/experience-fragments/<site>/<language-masters>/<lang>/<group>)
        return isFolder(r) && (containsPages(r, 6) || r.getValueMap().get(PN_LISTED, false));
    }

    private static boolean isFolder(Resource r) {
        return FOLDER_TYPES.contains(r.getValueMap().get(Pages.PN_PRIMARY_TYPE, String.class));
    }

    private static boolean containsPages(Resource folder, int depth) {
        for (Resource c : folder.getChildren()) {
            if (Pages.isPage(c) || depth > 1 && isFolder(c) && containsPages(c, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    Map<String, Object> item(Resource r, PageManagerImpl pm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", r.getPath());
        m.put("name", r.getName());
        m.put("title", titleOf(r, pm));
        boolean hasChildren = false;
        for (Resource c : r.getChildren()) {
            if (isListed(c)) {
                hasChildren = true;
                break;
            }
        }
        m.put("hasChildren", hasChildren);
        Page page = Pages.isPage(r) ? pm.getPage(r.getPath()) : null;
        m.put("type", page != null ? "page" : "folder");
        ValueMap folderProps = r.getValueMap();
        if (page == null) {
            m.put("lastModified", EditorServlet.iso(folderProps.get("jcr:lastModified", Calendar.class)));
            return m;
        }
        Resource content = page.getContentResource();
        ValueMap props = content == null ? ValueMap.EMPTY : content.getValueMap();
        Template t = page.getTemplate();
        m.put("template", props.get("cq:template", String.class));
        m.put("templateTitle", t == null ? null : t.getTitle());
        m.put("resourceType", content == null ? null : content.getResourceType());
        m.put("lastModified", EditorServlet.iso(page.getLastModified()));
        m.put("lastModifiedBy", page.getLastModifiedBy());
        m.put("created", EditorServlet.iso(props.get("jcr:created", Calendar.class)));
        m.put("language", page.getLanguage().getDisplayLanguage(java.util.Locale.ENGLISH));
        m.put("locked", page.isLocked());
        m.put("lockOwner", page.getLockOwner());
        m.put("hideInNav", page.isHideInNav());
        m.put("description", page.getDescription());
        m.put("thumbnail", thumbnail(content, t));
        // publishing: 'published' (on the publish instances), 'modified' (changed since), 'unpublished'
        // (taken down), or nothing (never published) — what the console's list shows as a dot
        Calendar replicated = props.get("cq:lastReplicated", Calendar.class);
        String replication = props.get("cq:lastReplicationAction", String.class);
        if (replicated != null && replication != null) {
            boolean activated = "Activate".equalsIgnoreCase(replication);
            Calendar modified = page.getLastModified();
            m.put("publication", !activated ? "unpublished" : modified != null && modified.after(replicated) ? "modified" : "published");
            m.put("lastPublished", EditorServlet.iso(replicated));
        }
        // the preview tier (AEM as a Cloud Service's Publish to Preview): its own status, the same words
        Calendar previewed = props.get("cq:lastReplicated_preview", Calendar.class);
        String previewAction = props.get("cq:lastReplicationAction_preview", String.class);
        if (previewed != null && previewAction != null) {
            boolean activated = "Activate".equalsIgnoreCase(previewAction);
            Calendar modified = page.getLastModified();
            m.put("preview", !activated ? "unpublished" : modified != null && modified.after(previewed) ? "modified" : "published");
            m.put("lastPreviewed", EditorServlet.iso(previewed));
        }
        String msm = MsmState.of(r);
        if (msm != null) {
            m.put("liveCopy", msm);
            if (MsmState.isLiveCopyRoot(r)) {
                m.put("liveCopyRoot", true);
            }
        }
        return m;
    }

    /**
     * The page's thumbnail (page properties > Images > Thumbnail: {@code image}, an uploaded file or an asset), else
     * its featured image, else the template's thumbnail.
     */
    static String thumbnail(Resource content, Template t) {
        if (content != null) {
            for (String child : new String[] {"image", "cq:featuredimage"}) {
                Resource img = content.getChild(child);
                String ref = img == null ? null : img.getValueMap().get("fileReference", String.class);
                if (ref != null && !ref.isEmpty()) {
                    return ref;
                }
                if (img != null && img.getChild("file") != null) {
                    return img.getPath() + "/file";
                }
            }
        }
        return t == null ? null : t.getThumbnailPath();
    }

    private static String titleOf(Resource r, PageManagerImpl pm) {
        Page page = Pages.isPage(r) ? pm.getPage(r.getPath()) : null;
        if (page != null && page.getTitle() != null) {
            return page.getTitle();
        }
        String folderTitle = r.getValueMap().get("jcr:title", String.class);
        if (folderTitle == null) {
            folderTitle = prop(r, "jcr:title", String.class);
        }
        return folderTitle != null ? folderTitle : (ROOT.equals(r.getPath()) ? "Sites" : r.getName());
    }

    // ------------------------------------------------------------------ search

    /** At most this many pages are looked at; a repository with more gets its best matches from the first ones. */
    static final int SEARCH_BUDGET = 5000;

    Map<String, Object> search(ResourceResolver resolver, String root, String q, String limitParam) {
        Resource res = resolver.getResource(root);
        if (res == null) {
            return null;
        }
        String text = q == null ? "" : q.trim().toLowerCase();
        int limit = 20;
        try {
            if (limitParam != null) {
                limit = Math.max(1, Math.min(100, Integer.parseInt(limitParam)));
            }
        } catch (NumberFormatException e) {
            // the default
        }
        PageManagerImpl pm = new PageManagerImpl(resolver);
        List<Map<String, Object>> hits = new ArrayList<>();
        if (!text.isEmpty()) {
            // breadth first: a short path (the site, its language roots) is found before a deep one
            java.util.ArrayDeque<Resource> queue = new java.util.ArrayDeque<>();
            queue.add(res);
            int seen = 0;
            while (!queue.isEmpty() && seen < SEARCH_BUDGET) {
                Resource r = queue.poll();
                for (Resource child : r.getChildren()) {
                    if (!isListed(child) || (ROOT.equals(r.getPath()) && "dam".equals(child.getName()))) {
                        continue;
                    }
                    seen++;
                    // a block's variations are pages under the block; the block is the hit, not each variation
                    if (Pages.isPage(child) && !(Pages.isPage(r) && child.getPath().startsWith("/content/experience-fragments/"))) {
                        String title = titleOf(child, pm);
                        int score = score(text, title, child.getName(), child.getPath());
                        if (score > 0) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("path", child.getPath());
                            m.put("name", child.getName());
                            m.put("title", title);
                            m.put("score", score);
                            hits.add(m);
                        }
                    }
                    queue.add(child);
                }
            }
            hits.sort((a, b) -> {
                int d = (Integer) b.get("score") - (Integer) a.get("score");
                return d != 0 ? d : ((String) a.get("path")).compareTo((String) b.get("path"));
            });
            if (hits.size() > limit) {
                hits = new ArrayList<>(hits.subList(0, limit));
            }
            for (Map<String, Object> h : hits) {
                h.remove("score");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", q == null ? "" : q);
        out.put("hits", hits);
        return out;
    }

    /**
     * The pages below {@code root} with changes the live site does not have: modified since they were last
     * published, unpublished since, or never published at all (and modified by someone) — newest change
     * first, at most {@code limit}, with the count of all of them (and per state; {@code state} keeps one). Home's "Unpublished changes".
     */
    Map<String, Object> unpublished(ResourceResolver resolver, String root, String limitParam, String state) {
        Resource res = resolver.getResource(root);
        if (res == null) {
            return null;
        }
        int limit = 20;
        try {
            if (limitParam != null) {
                limit = Math.max(1, Math.min(2000, Integer.parseInt(limitParam)));
            }
        } catch (NumberFormatException e) {
            // the default
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("modified", 0);
        counts.put("unpublished", 0);
        counts.put("never", 0);
        PageManagerImpl pm = new PageManagerImpl(resolver);
        List<Map<String, Object>> hits = new ArrayList<>();
        java.util.ArrayDeque<Resource> queue = new java.util.ArrayDeque<>();
        queue.add(res);
        int seen = 0;
        boolean complete = true;
        while (!queue.isEmpty()) {
            if (seen >= SEARCH_BUDGET) {
                complete = false;
                break;
            }
            Resource r = queue.poll();
            for (Resource child : r.getChildren()) {
                if (!isListed(child) || (ROOT.equals(r.getPath()) && "dam".equals(child.getName()))) {
                    continue;
                }
                seen++;
                if (Pages.isPage(child)) {
                    Map<String, Object> m = item(child, pm);
                    Object publication = m.get("publication");
                    Object modified = m.get("lastModified");
                    boolean changed = "modified".equals(publication) || "unpublished".equals(publication)
                            || (publication == null && modified != null);
                    String what = publication == null ? "never" : String.valueOf(publication);
                    if (changed) {
                        counts.merge(what, 1, Integer::sum);
                    }
                    if (changed && (state == null || state.isEmpty() || state.equals(what))) {
                        Map<String, Object> h = new LinkedHashMap<>();
                        h.put("path", m.get("path"));
                        h.put("name", m.get("name"));
                        h.put("title", m.get("title"));
                        h.put("type", m.get("type"));
                        h.put("lastModified", modified);
                        h.put("lastModifiedBy", m.get("lastModifiedBy"));
                        h.put("publication", what);
                        h.put("lastPublished", m.get("lastPublished"));
                        hits.add(h);
                    }
                }
                queue.add(child);
            }
        }
        // changes to what the live site already has first, then what it never had; newest first within each
        hits.sort((a, b) -> {
            int d = Integer.compare(stateOrder(a.get("publication")), stateOrder(b.get("publication")));
            return d != 0 ? d : String.valueOf(b.get("lastModified")).compareTo(String.valueOf(a.get("lastModified")));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", hits.size());
        out.put("counts", counts);
        out.put("complete", complete);
        out.put("items", hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits);
        return out;
    }

    private static int stateOrder(Object publication) {
        return "modified".equals(publication) ? 0 : "unpublished".equals(publication) ? 1 : 2;
    }

    /** 0 when the page does not match; higher for a title that starts with the text, then one that contains it, then the name, then the path. */
    static int score(String text, String title, String name, String path) {
        String t = title == null ? "" : title.toLowerCase();
        if (t.equals(text)) {
            return 100;
        }
        if (t.startsWith(text)) {
            return 80;
        }
        if (t.contains(text)) {
            return 60;
        }
        if (name.toLowerCase().contains(text)) {
            return 40;
        }
        if (path.toLowerCase().contains(text)) {
            return 20;
        }
        return 0;
    }

    // ------------------------------------------------------------------ references

    /** Who refers to {@code path} (or anything below it), from outside its own subtree. */
    Map<String, Object> references(ResourceResolver resolver, String path) {
        if (resolver.getResource(path) == null) {
            return null;
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (References.Ref r : References.find(resolver, path, References.DEFAULT_ROOTS, false)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.resourcePath);
            m.put("property", r.property);
            m.put("page", r.pagePath);
            m.put("pageTitle", r.pageTitle);
            m.put("value", r.value.length() > 200 ? r.value.substring(0, 200) + "…" : r.value);
            refs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("references", refs);
        return out;
    }

    /**
     * {@code POST op=move&path=&dest=<new parent>&name=<new name>&adjust=<page>...}: moves (and/or
     * renames) a page or folder, then rewrites references to it in the listed pages and inside the
     * moved subtree itself. Pages of the subtree that were published follow on the publish side, as AEM's
     * Move does with Republish: the old path is unpublished and those pages are published at the new one
     * ({@code republish=false} leaves the publish side alone). The move stands if that fails; the answer says why.
     */
    private void move(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String path = request.getParameter("path");
        String destParent = request.getParameter("dest");
        String name = request.getParameter("name");
        Resource source = path == null ? null : resolver.getResource(path);
        if (source == null || destParent == null || name == null || !path.startsWith(ROOT + "/")
                || !(destParent.equals(ROOT) || destParent.startsWith(ROOT + "/"))) {
            send(response, HttpServletResponse.SC_BAD_REQUEST, Collections.singletonMap("error", "path, dest and name are required"));
            return;
        }
        String target = destParent + "/" + name;
        if (PageManagerImpl.inside(destParent, path)) {
            send(response, HttpServletResponse.SC_CONFLICT, Collections.singletonMap("error", "Cannot move " + path + " into itself"));
            return;
        }
        if (!target.equals(path) && resolver.getResource(target) != null) {
            send(response, HttpServletResponse.SC_CONFLICT, Collections.singletonMap("error", target + " already exists"));
            return;
        }
        Set<String> adjustPages = new HashSet<>();
        String[] adjust = request.getParameterValues("adjust");
        if (adjust != null) {
            adjustPages.addAll(Arrays.asList(adjust));
        }
        try {
            List<References.Ref> refs = new ArrayList<>();
            for (References.Ref r : References.find(resolver, path, References.DEFAULT_ROOTS, true)) {
                if (PageManagerImpl.inside(r.resourcePath, path) || adjustPages.contains(r.pagePath)) {
                    refs.add(r);
                }
            }
            javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
            List<String> live = target.equals(path) || "false".equals(request.getParameter("republish"))
                    ? new ArrayList<>() : published(source, path, target);
            if (!target.equals(path)) {
                if (session == null) {
                    throw new javax.jcr.RepositoryException("Moving needs a JCR session");
                }
                session.move(path, target);
            }
            int changed = References.adjust(resolver, refs, path, target);
            resolver.commit();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", target);
            out.put("adjusted", changed);
            if (!live.isEmpty()) {
                Replicator r = replicator;
                try {
                    if (r == null) {
                        throw new ReplicationException("Publishing is not available on this instance");
                    }
                    r.replicate(session, ReplicationActionType.DEACTIVATE, path);
                    r.replicate(session, ReplicationActionType.ACTIVATE, live.toArray(new String[0]), new ReplicationOptions());
                    out.put("republished", live);
                } catch (ReplicationException e) {
                    out.put("republishError", e.getMessage());
                }
            }
            send(response, HttpServletResponse.SC_OK, out);
        } catch (javax.jcr.RepositoryException | org.apache.sling.api.resource.PersistenceException e) {
            resolver.revert();
            send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Collections.singletonMap("error", e.getMessage()));
        }
    }

    /** The pages of a subtree that are published now, as the paths they will have after the move; parents first. */
    static List<String> published(Resource root, String from, String to) {
        List<String> out = new ArrayList<>();
        collectPublished(root, from, to, out);
        return out;
    }

    private static void collectPublished(Resource r, String from, String to, List<String> out) {
        Resource content = r.getChild("jcr:content");
        if (content != null && "Activate".equalsIgnoreCase(content.getValueMap().get("cq:lastReplicationAction", String.class))) {
            out.add(to + r.getPath().substring(from.length()));
        }
        for (Resource c : r.getChildren()) {
            if (!"jcr:content".equals(c.getName()) && !c.getName().startsWith("rep:")) {
                collectPublished(c, from, to, out);
            }
        }
    }

    // ------------------------------------------------------------------ templates

    List<Map<String, Object>> templates(ResourceResolver resolver, String parentPath) {
        Resource parent = resolver.getResource(parentPath);
        if (parent == null) {
            return null;
        }
        String conf = inherited(parent, "cq:conf");
        String[] allowed = inheritedArray(parent, "cq:allowedTemplates");
        List<Map<String, Object>> out = new ArrayList<>();
        if (conf == null) {
            return out;
        }
        Resource root = resolver.getResource(conf + "/settings/wcm/templates");
        if (root == null) {
            return out;
        }
        for (Resource tr : root.getChildren()) {
            if (!Pages.isTemplate(tr)) {
                continue;
            }
            TemplateImpl t = new TemplateImpl(tr);
            String status = prop(tr, "status", String.class);
            if (status != null && !"enabled".equals(status)) {
                continue;
            }
            if (allowed != null && !matchesAny(allowed, t.getPath())) {
                continue;
            }
            if (!t.isAllowed(parentPath)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", t.getPath());
            m.put("title", t.getTitle() != null ? t.getTitle() : t.getName());
            m.put("description", t.getDescription());
            m.put("thumbnail", t.getThumbnailPath());
            m.put("ranking", t.getRanking());
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    /** First value of {@code name} on the resource's own or an ancestor's {@code jcr:content} (or the resource). */
    static String inherited(Resource r, String name) {
        for (Resource c = r; c != null; c = c.getParent()) {
            String v = prop(c, name, String.class);
            if (v == null) {
                v = c.getValueMap().get(name, String.class);
            }
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static String[] inheritedArray(Resource r, String name) {
        for (Resource c = r; c != null; c = c.getParent()) {
            String[] v = prop(c, name, String[].class);
            if (v == null) {
                v = c.getValueMap().get(name, String[].class);
            }
            if (v != null && v.length > 0) {
                return v;
            }
        }
        return null;
    }

    /** A property of the resource's {@code jcr:content} child. */
    static <T> T prop(Resource r, String name, Class<T> type) {
        Resource content = r.getChild("jcr:content");
        return content == null ? null : content.getValueMap().get(name, type);
    }

    static boolean matchesAny(String[] patterns, String path) {
        for (String p : patterns) {
            try {
                if (Pattern.matches(p, path)) {
                    return true;
                }
            } catch (PatternSyntaxException e) {
                if (p.equals(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }
}
