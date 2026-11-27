/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.WCMException;

/**
 * What the side rails of the Sites console show: the timeline of a page, the site of a path and the
 * page filter. Reads the repository with the given (user's) resolver.
 */
final class SiteRail {

    /** Page comments: {@code /var/studio/comments/<page path>/<comment>} with message, author, created. */
    static final String COMMENTS = "/var/studio/comments";
    /** Where Sensible Studio keeps workflow instances ({@code <server>/<day>/<instance>}). */
    static final String INSTANCES = "/var/workflow/instances";
    /** Tree walks stop after this many resources, so a filter on a huge tree stays bounded. */
    static final int MAX_VISITS = 20000;
    static final int MAX_RESULTS = 500;

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}([_-][a-zA-Z]{2,4})?");
    private static final String LIVE_RELATIONSHIP = "cq:LiveRelationship";

    private final ResourceResolver resolver;
    /**
     * Where comments are kept and workflows are read: the bundle's service resolver, so that authors do not need
     * rights on {@code /var}. Every call first checks with {@link #resolver} (the user's) that the page is readable.
     */
    private final ResourceResolver store;

    SiteRail(ResourceResolver resolver) {
        this(resolver, null);
    }

    SiteRail(ResourceResolver resolver, ResourceResolver store) {
        this.resolver = resolver;
        this.store = store == null ? resolver : store;
    }

    // ------------------------------------------------------------------------------------------ timeline

    String lockOwner(String path) {
        return first(content(requirePage(path)), "cq:lockOwner", "studio:lockOwner");
    }

    /** The events of a page, newest first. */
    List<Map<String, Object>> timeline(String path) throws WCMException {
        Resource page = requirePage(path);
        List<Map<String, Object>> out = new ArrayList<>();
        ValueMap pv = page.getValueMap();
        ValueMap cv = content(page);

        event(out, "activity", "created", "Page created", null,
                pv.get("jcr:createdBy", cv.get("jcr:createdBy", String.class)),
                pv.get("jcr:created", cv.get("jcr:created", Calendar.class)));
        event(out, "activity", "modified", "Page modified", null,
                cv.get("cq:lastModifiedBy", String.class), cv.get("cq:lastModified", Calendar.class));
        event(out, "activity", "rolledout", "Synchronized from its source", null,
                cv.get("cq:lastRolledoutBy", String.class), cv.get("cq:lastRolledout", Calendar.class));
        String action = cv.get("cq:lastReplicationAction", String.class);
        if (action != null) {
            event(out, "activity", "replicated", "Activate".equalsIgnoreCase(action) ? "Published" : "Unpublished", null,
                    cv.get("cq:lastReplicatedBy", String.class), cv.get("cq:lastReplicated", Calendar.class));
        }

        PageManager pm = resolver.adaptTo(PageManager.class);
        Collection<Revision> revisions = pm == null ? null : pm.getRevisions(path, null);
        if (revisions != null) {
            for (Revision r : revisions) {
                String label = r.getLabel() == null || r.getLabel().isEmpty() ? "Version " + r.getVersionName() : r.getLabel();
                Map<String, Object> e = event(out, "version", r.getId(), label, r.getComment(),
                        r.getProperties() == null ? null : r.getProperties().get("cq:versionCreator", String.class), r.getCreated());
                if (e != null) {
                    e.put("version", r.getVersionName());
                }
            }
        }

        workflows(out, path);
        launches(out, path);
        annotations(out, page);

        Resource comments = store.getResource(COMMENTS + path);
        if (comments != null) {
            for (Resource c : comments.getChildren()) {
                ValueMap v = c.getValueMap();
                if (v.get("message") == null) {
                    continue;
                }
                Map<String, Object> e = event(out, "comment", c.getPath(), "Comment", v.get("message", String.class),
                        v.get("author", String.class), v.get("created", Calendar.class));
                if (e != null) {
                    e.put("mine", String.valueOf(resolver.getUserID()).equals(v.get("author", String.class)));
                }
            }
        }

        // ISO-8601 UTC strings sort like the times they stand for
        out.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return out;
    }

    /** Notes left on components in the page editor's Annotate mode: {@code <component>/cq:annotations/<name>}. */
    static final String ANNOTATIONS = "cq:annotations";
    /** A page's content is searched this deep, at most {@link #MAX_ANNOTATION_VISITS} resources. */
    private static final int ANNOTATION_DEPTH = 30;
    private static final int MAX_ANNOTATION_VISITS = 5000;

    /**
     * The annotations on the page's components, when each was made and by whom (read as the user). An annotation
     * without a creation time (made by a plain Sling POST) shows with its last modification.
     */
    private void annotations(List<Map<String, Object>> out, Resource page) {
        Resource content = page.getChild("jcr:content");
        if (content == null) {
            return;
        }
        List<Resource> found = new ArrayList<>();
        findAnnotations(content, found, ANNOTATION_DEPTH, new int[] {MAX_ANNOTATION_VISITS});
        String prefix = content.getPath() + "/";
        for (Resource holder : found) {
            Resource component = holder.getParent();
            String name = component == null ? null : component.getPath().startsWith(prefix)
                    ? component.getPath().substring(prefix.length()) : component.getName();
            for (Resource a : holder.getChildren()) {
                ValueMap v = a.getValueMap();
                Calendar created = v.get("jcr:created", v.get("jcr:lastModified", Calendar.class));
                Map<String, Object> e = event(out, "annotation", a.getPath(), "Annotation", v.get("text", String.class),
                        v.get("jcr:createdBy", v.get("jcr:lastModifiedBy", String.class)), created);
                if (e != null) {
                    e.put("component", name);
                    String color = v.get("color", String.class);
                    if (color != null && !color.isEmpty()) {
                        e.put("color", color);
                    }
                }
            }
        }
    }

    private static void findAnnotations(Resource r, List<Resource> out, int depth, int[] budget) {
        for (Resource c : r.getChildren()) {
            if (--budget[0] < 0) {
                return;
            }
            if (ANNOTATIONS.equals(c.getName())) {
                out.add(c);
            } else if (depth > 0 && !c.getName().startsWith("rep:")) {
                findAnnotations(c, out, depth - 1, budget);
            }
        }
    }

    static final String LAUNCHES = "/content/launches";
    static final String LAUNCH_TYPE = "wcm/launches/components/launch";

    /**
     * The launches that hold a copy of the page (studio-launches: {@code /content/launches/<date>/<name>}, the page at its
     * own path below {@code <launch>/content}): when each was created and promoted. Read as the user, so only the
     * launches they can see.
     */
    private void launches(List<Map<String, Object>> out, String path) {
        Resource root = resolver.getResource(LAUNCHES);
        if (root == null) {
            return;
        }
        List<Resource> launches = new ArrayList<>();
        launchPages(root, launches, 5);
        for (Resource launch : launches) {
            String inLaunch = launch.getPath() + "/content" + path;
            Resource copy = resolver.getResource(inLaunch);
            // the ancestors of a launch's pages are shells there (launchShell): only a real copy of the page counts
            Resource copyContent = copy == null || !isPage(copy) ? null : copy.getChild("jcr:content");
            if (copyContent == null || copyContent.getValueMap().get("launchShell", false)) {
                continue;
            }
            ValueMap v = content(launch);
            String title = v.get("jcr:title", launch.getName());
            Calendar created = v.get("created", v.get("jcr:created", Calendar.class));
            String createdBy = v.get("createdBy", v.get("jcr:createdBy", String.class));
            for (Map<String, Object> e : java.util.Arrays.asList(
                    event(out, "launch", launch.getPath() + ":created", "Launch created: " + title, null, createdBy, created),
                    event(out, "launch", launch.getPath() + ":promoted", "Launch promoted: " + title, null,
                            v.get("promotedBy", String.class), v.get("promoted", Calendar.class)))) {
                if (e != null) {
                    e.put("launch", launch.getPath());
                    e.put("pageInLaunch", inLaunch);
                    e.put("liveDate", iso(v.get("liveDate", Calendar.class)));
                    e.put("promoted", v.get("promoted") != null);
                }
            }
        }
    }

    private static void launchPages(Resource r, List<Resource> out, int depth) {
        for (Resource c : r.getChildren()) {
            Resource content = c.getChild("jcr:content");
            if (isPage(c) && content != null && content.isResourceType(LAUNCH_TYPE)) {
                out.add(c);
            } else if (depth > 0 && (isFolder(c) || isPage(c)) && !"jcr:content".equals(c.getName())) {
                launchPages(c, out, depth - 1);
            }
        }
    }

    /** Workflow instances on the page: started, completed participant steps, completed or terminated. */
    private void workflows(List<Map<String, Object>> out, String path) {
        Resource root = store.getResource(INSTANCES);
        if (root == null) {
            return;
        }
        for (Resource server : root.getChildren()) {
            for (Resource day : server.getChildren()) {
                for (Resource i : day.getChildren()) {
                    Resource data = i.getChild("data");
                    String payload = data == null ? null : data.getValueMap().get("payload", String.class);
                    if (payload == null || !(payload.equals(path) || payload.equals(path + "/jcr:content"))) {
                        continue;
                    }
                    ValueMap iv = i.getValueMap();
                    Resource meta = i.getChild("metaData");
                    String title = meta == null ? null : meta.getValueMap().get("workflowTitle", String.class);
                    String model = iv.get("modelTitle", iv.get("modelId", ""));
                    String name = title == null || title.isEmpty() ? model : title + " (" + model + ")";
                    Map<String, Object> started = event(out, "workflow", i.getPath(), "Workflow started: " + name,
                            meta == null ? null : meta.getValueMap().get("startComment", String.class),
                            iv.get("initiator", String.class), iv.get("startTime", Calendar.class));
                    if (started != null) {
                        started.put("status", iv.get("status", String.class));
                        started.put("instance", i.getPath());
                    }
                    Resource items = i.getChild("workItems");
                    if (items != null) {
                        for (Resource item : items.getChildren()) {
                            ValueMap v = item.getValueMap();
                            String type = v.get("type", "");
                            boolean participant = "PARTICIPANT".equals(type) || "DYNAMIC_PARTICIPANT".equals(type);
                            if (!participant || !"COMPLETED".equals(v.get("status", ""))) {
                                continue;
                            }
                            event(out, "workflow", item.getPath(), "Step completed: " + v.get("nodeTitle", ""),
                                    v.get("comment", String.class), v.get("completedBy", String.class), v.get("endTime", Calendar.class));
                        }
                    }
                    String status = iv.get("status", "");
                    if ("COMPLETED".equals(status) || "ABORTED".equals(status)) {
                        event(out, "workflow", i.getPath() + "#end",
                                ("COMPLETED".equals(status) ? "Workflow completed: " : "Workflow terminated: ") + name, null,
                                iv.get("terminatedBy", String.class), iv.get("endTime", Calendar.class));
                    }
                }
            }
        }
    }

    /** Adds an event; one without a time is left out (returns null). */
    private static Map<String, Object> event(List<Map<String, Object>> out, String kind, String id, String title, String text,
            String user, Calendar time) {
        if (time == null) {
            return null;
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        e.put("id", id);
        e.put("title", title);
        if (text != null && !text.isEmpty()) {
            e.put("text", text);
        }
        e.put("user", user);
        e.put("time", iso(time));
        out.add(e);
        return e;
    }

    Map<String, Object> addComment(String path, String message) throws PersistenceException {
        requirePage(path);
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Write a comment first");
        }
        Resource folder = folders(COMMENTS + path);
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("message", message.trim());
        props.put("author", resolver.getUserID());
        props.put("created", Calendar.getInstance());
        String name = "c" + System.currentTimeMillis();
        while (folder.getChild(name) != null) {
            name = name + "_";
        }
        Resource c = store.create(folder, name, props);
        store.commit();
        return Collections.singletonMap("id", c.getPath());
    }

    /** Only the author of a comment (or admin) deletes it. */
    void deleteComment(String id) throws PersistenceException {
        Resource c = id == null || !id.startsWith(COMMENTS + "/") ? null : store.getResource(id);
        if (c == null || c.getValueMap().get("message") == null) {
            throw new IllegalArgumentException("No comment " + id);
        }
        String author = c.getValueMap().get("author", String.class);
        if (!String.valueOf(resolver.getUserID()).equals(author) && !"admin".equals(resolver.getUserID())) {
            throw new SecurityException("Only the author of a comment may delete it");
        }
        String page = c.getPath().substring(COMMENTS.length(), c.getPath().lastIndexOf('/'));
        if (resolver.getResource(page) == null) {
            throw new IllegalArgumentException("No comment " + id); // not a page this user can see
        }
        store.delete(c);
        store.commit();
    }

    Map<String, Object> saveVersion(String path, String label, String comment) throws WCMException {
        requirePage(path);
        PageManager pm = resolver.adaptTo(PageManager.class);
        Page page = pm == null ? null : pm.getPage(path);
        if (page == null) {
            throw new IllegalArgumentException("Not a page: " + path);
        }
        Revision r = pm.createRevision(page, empty(label), empty(comment));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("version", r.getVersionName());
        return out;
    }

    // ------------------------------------------------------------------------------------------ site

    /** The site of a path: the first level below /content. */
    Map<String, Object> site(String path) {
        if (path == null || !path.startsWith("/content/") || path.startsWith("/content/dam") || path.startsWith("/content/cq:")) {
            throw new IllegalArgumentException("Not in a site: " + path);
        }
        String rootPath = "/content/" + path.substring("/content/".length()).split("/")[0];
        Resource root = resolver.getResource(rootPath);
        if (root == null) {
            throw new IllegalArgumentException("No site at " + rootPath);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", rootPath);
        out.put("title", title(root));
        out.put("isPage", isPage(root));
        Resource at = resolver.getResource(path);
        String conf = conf(at == null ? root : at, rootPath);
        out.put("conf", conf);

        List<Map<String, Object>> languages = new ArrayList<>();
        languages(root, 0, languages);
        out.put("languages", languages);

        Stats stats = new Stats();
        count(root, stats);
        out.put("pages", stats.pages);
        out.put("liveCopyPages", stats.liveCopies);
        out.put("lockedPages", stats.locked);
        if (stats.latest != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", stats.latestPath);
            m.put("title", title(resolver.getResource(stats.latestPath)));
            m.put("time", iso(stats.latest));
            out.put("lastModified", m);
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        Resource tpl = conf == null ? null : resolver.getResource(conf + "/settings/wcm/templates");
        if (tpl != null) {
            for (Resource t : tpl.getChildren()) {
                Resource tc = t.getChild("jcr:content");
                if (tc == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", t.getPath());
                m.put("title", tc.getValueMap().get("jcr:title", t.getName()));
                m.put("status", tc.getValueMap().get("status", String.class));
                templates.add(m);
            }
        }
        out.put("templates", templates);
        return out;
    }

    /** Language roots: pages named like a language (en, de_ch, pt-BR) up to three levels down. */
    private void languages(Resource r, int depth, List<Map<String, Object>> out) {
        if (depth > 2) {
            return;
        }
        for (Resource c : r.getChildren()) {
            if (!isPage(c)) {
                continue;
            }
            if (LANGUAGE.matcher(c.getName()).matches() && !hasLanguageChildren(c)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", c.getPath());
                m.put("title", title(c));
                m.put("language", c.getName());
                out.add(m);
            } else {
                languages(c, depth + 1, out);
            }
        }
    }

    /** A country page (us, ch) has language pages below it: it is not a language root itself. */
    private static boolean hasLanguageChildren(Resource r) {
        for (Resource c : r.getChildren()) {
            if (isPage(c) && LANGUAGE.matcher(c.getName()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static final class Stats {
        int pages;
        int liveCopies;
        int locked;
        int visits;
        Calendar latest;
        String latestPath;
    }

    private void count(Resource r, Stats s) {
        if (++s.visits > MAX_VISITS) {
            return;
        }
        if (isPage(r)) {
            s.pages++;
            Resource c = r.getChild("jcr:content");
            if (c != null) {
                ValueMap v = c.getValueMap();
                if (isLiveCopy(v)) {
                    s.liveCopies++;
                }
                if (first(v, "cq:lockOwner", "studio:lockOwner") != null) {
                    s.locked++;
                }
                Calendar m = v.get("cq:lastModified", Calendar.class);
                if (m != null && (s.latest == null || m.after(s.latest))) {
                    s.latest = m;
                    s.latestPath = r.getPath();
                }
            }
        }
        for (Resource c : r.getChildren()) {
            if (!"jcr:content".equals(c.getName()) && (isPage(c) || isFolder(c))) {
                count(c, s);
            }
        }
    }

    /** The context-aware configuration of a path: the nearest cq:conf (or sling:configRef) up to the site root. */
    String conf(Resource r, String stop) {
        for (Resource p = r; p != null && p.getPath().startsWith(stop); p = p.getParent()) {
            for (Resource holder : new Resource[] {p.getChild("jcr:content"), p}) {
                String c = holder == null ? null : first(holder.getValueMap(), "cq:conf", "sling:configRef");
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ filter

    static final class Criteria {
        String root = "/content";
        String text;
        String template;
        Set<String> tags = new LinkedHashSet<>();
        int modifiedDays;
        /** "livecopy", "notlivecopy" or empty for any */
        String liveCopy;
        boolean locked;
        /** options filters of the search form: the page property has one of the values; properties are ANDed */
        Map<String, Set<String>> meta = new LinkedHashMap<>();
        /** text filters of the search form: the page property contains the text, any case */
        Map<String, String> metaText = new LinkedHashMap<>();
    }

    /** The pages below the root (not the root itself) that match all criteria, newest change first. */
    Map<String, Object> search(Criteria c) {
        Resource root = resolver.getResource(c.root);
        if (root == null) {
            throw new IllegalArgumentException("Nothing at " + c.root);
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        int[] visits = {0};
        long since = c.modifiedDays > 0 ? System.currentTimeMillis() - c.modifiedDays * 86_400_000L : Long.MIN_VALUE;
        String text = c.text == null || c.text.trim().isEmpty() ? null : c.text.trim().toLowerCase(Locale.ROOT);
        walk(root, c, text, since, hits, visits);
        hits.sort((a, b) -> String.valueOf(b.get("lastModified")).compareTo(String.valueOf(a.get("lastModified"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", hits.size());
        out.put("truncated", hits.size() > MAX_RESULTS || visits[0] > MAX_VISITS);
        out.put("items", hits.size() > MAX_RESULTS ? hits.subList(0, MAX_RESULTS) : hits);
        return out;
    }

    private void walk(Resource r, Criteria c, String text, long since, List<Map<String, Object>> hits, int[] visits) {
        if (++visits[0] > MAX_VISITS) {
            return;
        }
        if (isPage(r) && !r.getPath().equals(c.root) && matches(r, c, text, since)) {
            hits.add(item(r));
        }
        for (Resource child : r.getChildren()) {
            if (!"jcr:content".equals(child.getName()) && (isPage(child) || isFolder(child))) {
                walk(child, c, text, since, hits, visits);
            }
        }
    }

    private boolean matches(Resource page, Criteria c, String text, long since) {
        ValueMap v = content(page);
        if (text != null) {
            // every word must appear in the name, title, description, page title or navigation title
            String hay = (page.getName() + " " + v.get("jcr:title", "") + " " + v.get("jcr:description", "") + " "
                    + v.get("pageTitle", "") + " " + v.get("navTitle", "")).toLowerCase(Locale.ROOT);
            for (String word : text.split("\\s+")) {
                if (!hay.contains(word)) {
                    return false;
                }
            }
        }
        if (c.template != null && !c.template.isEmpty() && !c.template.equals(v.get("cq:template", String.class))) {
            return false;
        }
        if (!c.tags.isEmpty()) {
            String[] tags = v.get("cq:tags", String[].class);
            if (tags == null || Collections.disjoint(Arrays.asList(tags), c.tags)) {
                return false;
            }
        }
        if (since != Long.MIN_VALUE) {
            Calendar m = v.get("cq:lastModified", v.get("jcr:created", Calendar.class));
            if (m == null || m.getTimeInMillis() < since) {
                return false;
            }
        }
        if (c.liveCopy != null && !c.liveCopy.isEmpty() && "livecopy".equals(c.liveCopy) != isLiveCopy(v)) {
            return false;
        }
        if (c.locked && first(v, "cq:lockOwner", "studio:lockOwner") == null) {
            return false;
        }
        for (Map.Entry<String, Set<String>> e : c.meta.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            if (Collections.disjoint(SiteSearchForms.strings(v.get(e.getKey())), e.getValue())) {
                return false;
            }
        }
        for (Map.Entry<String, String> e : c.metaText.entrySet()) {
            String want = e.getValue().trim().toLowerCase(Locale.ROOT);
            if (want.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (String have : SiteSearchForms.strings(v.get(e.getKey()))) {
                if (have.toLowerCase(Locale.ROOT).contains(want)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** A hit, shaped like an item of {@code /bin/studio/sites.json} so the console lists it the same way. */
    private Map<String, Object> item(Resource page) {
        ValueMap v = content(page);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", page.getPath());
        m.put("name", page.getName());
        m.put("title", title(page));
        m.put("type", "page");
        boolean children = false;
        for (Resource c : page.getChildren()) {
            if (isPage(c)) {
                children = true;
                break;
            }
        }
        m.put("hasChildren", children);
        String template = v.get("cq:template", String.class);
        m.put("template", template);
        if (template != null) {
            Resource t = resolver.getResource(template + "/jcr:content");
            m.put("templateTitle", t == null ? null : t.getValueMap().get("jcr:title", String.class));
        }
        m.put("resourceType", v.get("sling:resourceType", String.class));
        m.put("lastModified", iso(v.get("cq:lastModified", v.get("jcr:created", Calendar.class))));
        m.put("lastModifiedBy", v.get("cq:lastModifiedBy", String.class));
        m.put("description", v.get("jcr:description", String.class));
        String lock = first(v, "cq:lockOwner", "studio:lockOwner");
        m.put("locked", lock != null);
        m.put("lockOwner", lock);
        String[] tags = v.get("cq:tags", String[].class);
        m.put("tags", tags == null ? Collections.emptyList() : Arrays.asList(tags));
        if (isLiveCopy(v)) {
            m.put("liveCopy", "inherited");
        }
        return m;
    }

    /** The templates the pages below a path use (title order), for the Template filter. */
    List<Map<String, Object>> templatesUsed(String rootPath) {
        Resource root = resolver.getResource(rootPath);
        if (root == null) {
            throw new IllegalArgumentException("Nothing at " + rootPath);
        }
        Map<String, Map<String, Object>> used = new LinkedHashMap<>();
        collectTemplates(root, used, new int[] {0});
        List<Map<String, Object>> out = new ArrayList<>(used.values());
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    private void collectTemplates(Resource r, Map<String, Map<String, Object>> used, int[] visits) {
        if (++visits[0] > MAX_VISITS) {
            return;
        }
        Resource content = isPage(r) ? r.getChild("jcr:content") : null;
        String t = content == null ? null : content.getValueMap().get("cq:template", String.class);
        if (t != null && !used.containsKey(t)) {
            Resource tc = resolver.getResource(t + "/jcr:content");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", t);
            m.put("title", tc == null ? t.substring(t.lastIndexOf('/') + 1) : tc.getValueMap().get("jcr:title", t));
            used.put(t, m);
        }
        for (Resource c : r.getChildren()) {
            if (!"jcr:content".equals(c.getName()) && (isPage(c) || isFolder(c))) {
                collectTemplates(c, used, visits);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Resource requirePage(String path) {
        Resource r = path == null ? null : resolver.getResource(path);
        if (r == null || !isPage(r)) {
            throw new IllegalArgumentException("Not a page: " + path);
        }
        return r;
    }

    static boolean isPage(Resource r) {
        return r != null && "cq:Page".equals(r.getValueMap().get("jcr:primaryType", String.class));
    }

    static boolean isFolder(Resource r) {
        String t = r.getValueMap().get("jcr:primaryType", String.class);
        return "sling:Folder".equals(t) || "sling:OrderedFolder".equals(t) || "nt:folder".equals(t);
    }

    private static boolean isLiveCopy(ValueMap content) {
        String[] mixins = content.get("jcr:mixinTypes", String[].class);
        return mixins != null && Arrays.asList(mixins).contains(LIVE_RELATIONSHIP);
    }

    private static ValueMap content(Resource page) {
        Resource c = page.getChild("jcr:content");
        return c == null ? ValueMap.EMPTY : c.getValueMap();
    }

    static String titleOf(Resource r) {
        return title(r);
    }

    private static String title(Resource r) {
        if (r == null) {
            return null;
        }
        Resource c = r.getChild("jcr:content");
        String t = c == null ? null : c.getValueMap().get("jcr:title", String.class);
        if (t == null) {
            t = r.getValueMap().get("jcr:title", String.class);
        }
        return t == null ? r.getName() : t;
    }

    private static String first(ValueMap v, String... names) {
        for (String n : names) {
            String s = v.get(n, String.class);
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static String empty(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** Creates the missing sling:Folder parents of a path. */
    private Resource folders(String path) throws PersistenceException {
        Resource r = store.getResource(path);
        if (r != null) {
            return r;
        }
        int slash = path.lastIndexOf('/');
        Resource parent = folders(slash <= 0 ? "/" : path.substring(0, slash));
        return store.create(parent, path.substring(slash + 1), Collections.singletonMap("jcr:primaryType", "sling:Folder"));
    }

    static String iso(Calendar c) {
        if (c == null) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }
}
