/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;

/**
 * Restore Version and Restore Tree of the Sites timeline, and the delete that makes them possible.
 *
 * <p>A page version is a JCR version of its {@code jcr:content} (see {@code PageRevisions} in studio-cq-impl). A deleted
 * node keeps its version history, but nothing in JCR says where it was. So the console deletes pages through
 * {@link #delete}: every page of the subtree gets a version first, and a record
 * {@code /var/studio/deleted/<page path>} remembers the page's version history (identifier), title, who and when.
 * Restore Version lists the versioned pages inside a page (existing and deleted ones) and restores the chosen
 * versions; a deleted page is created again from its version. Restore Tree restores every page of a tree to the
 * latest version it had at a date.</p>
 */
final class VersionRestore {

    static final String DELETED = "/var/studio/deleted";
    static final String HISTORY = "versionHistory";

    private static final String CONTENT = "jcr:content";
    private static final String COMMENT = "cq:versionComment";
    private static final String CREATOR = "cq:versionCreator";

    private final ResourceResolver resolver;
    /**
     * Deletion records, the versions of deleted pages and the pages brought back are handled with the bundle's service
     * resolver, so that authors need no rights on {@code /var} or the version storage. What the user may do is checked
     * with {@link #resolver} (the user's) first; without a service resolver everything runs as the user.
     */
    private final ResourceResolver store;

    VersionRestore(ResourceResolver resolver) {
        this(resolver, null);
    }

    VersionRestore(ResourceResolver resolver, ResourceResolver store) {
        this.resolver = resolver;
        this.store = store == null ? resolver : store;
    }

    // ------------------------------------------------------------------------------------------ JCR access

    Session session() {
        Session s = resolver.adaptTo(Session.class);
        if (s == null) {
            throw new IllegalStateException("Page versions need a JCR repository");
        }
        return s;
    }

    private Session storeSession() {
        Session s = store.adaptTo(Session.class);
        if (s == null) {
            throw new IllegalStateException("Page versions need a JCR repository");
        }
        return s;
    }

    private PageManager pageManager() {
        return pageManager(resolver);
    }

    private static PageManager pageManager(ResourceResolver r) {
        PageManager pm = r.adaptTo(PageManager.class);
        if (pm == null) {
            throw new IllegalStateException("No page manager");
        }
        return pm;
    }

    /** The version history of an existing page, or null when it has no versions. */
    VersionHistory historyOf(String pagePath) throws RepositoryException {
        Session s = session();
        String content = pagePath + "/" + CONTENT;
        if (!s.nodeExists(content) || !s.getNode(content).isNodeType("mix:versionable")) {
            return null;
        }
        return s.getWorkspace().getVersionManager().getVersionHistory(content);
    }

    /** The version history a deletion record points to (null when it is gone). */
    VersionHistory historyOf(Resource record) throws RepositoryException {
        String id = record.getValueMap().get(HISTORY, String.class);
        if (id == null) {
            return null;
        }
        try {
            Node n = storeSession().getNodeByIdentifier(id);
            return n instanceof VersionHistory ? (VersionHistory) n : null;
        } catch (javax.jcr.ItemNotFoundException e) {
            return null;
        }
    }

    private static List<Map<String, Object>> versions(VersionHistory history) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
            Version v = it.nextVersion();
            if ("jcr:rootVersion".equals(v.getName())) {
                continue;
            }
            Node frozen = v.getFrozenNode();
            String[] labels = history.getVersionLabels(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getIdentifier());
            m.put("name", v.getName());
            m.put("label", labels.length > 0 ? labels[0] : null);
            m.put("comment", frozen.hasProperty(COMMENT) ? frozen.getProperty(COMMENT).getString() : null);
            m.put("createdBy", frozen.hasProperty(CREATOR) ? frozen.getProperty(CREATOR).getString() : null);
            m.put("title", frozen.hasProperty("jcr:title") ? frozen.getProperty("jcr:title").getString() : null);
            m.put("created", SiteRail.iso(v.getCreated()));
            m.put(" time", v.getCreated().getTimeInMillis());
            out.add(m);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get(" time")).reversed());
        return out;
    }

    /** The latest version created at or before {@code at} (versions are newest first). */
    private static Map<String, Object> versionAt(List<Map<String, Object>> versions, long at) {
        for (Map<String, Object> v : versions) {
            if ((Long) v.get(" time") <= at) {
                return v;
            }
        }
        return null;
    }

    private static Map<String, Object> clean(Map<String, Object> version) {
        if (version == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>(version);
        m.remove(" time");
        return m;
    }

    private static List<Map<String, Object>> clean(List<Map<String, Object>> versions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : versions) {
            out.add(clean(v));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ delete

    /**
     * Deletes pages or folders. Every page inside gets a version first and a deletion record, so Restore Version
     * can bring it back. A page locked by someone else is refused before anything changes.
     *
     * @return how many pages were versioned
     */
    int delete(List<String> paths) throws WCMException, PersistenceException {
        List<Resource> targets = prepareDelete(paths);
        for (Resource r : targets) {
            Resource again = resolver.getResource(r.getPath());
            if (again != null) {
                resolver.delete(again);
            }
        }
        resolver.commit();
        return versioned;
    }

    /** Pages versioned by the last {@link #prepareDelete}. */
    private int versioned;

    /**
     * What a delete does before it deletes: refuses locked pages and what the user may not remove, then versions every
     * page inside and records it. Also used for deletes that do not come through {@link #delete} (a Sling POST
     * {@code :operation=delete}, see {@code RestorableDeleteFilter}).
     *
     * @return the resources to delete
     */
    List<Resource> prepareDelete(List<String> paths) throws WCMException, PersistenceException {
        List<Resource> targets = new ArrayList<>();
        for (String p : paths) {
            Resource r = p == null || !p.startsWith("/content/") ? null : resolver.getResource(p);
            if (r == null) {
                throw new IllegalArgumentException("Nothing to delete at " + p);
            }
            targets.add(r);
        }
        List<Resource> pages = new ArrayList<>();
        for (Resource r : targets) {
            pages(r, pages);
        }
        String user = resolver.getUserID();
        for (Resource page : pages) {
            String owner = lockOwner(page);
            if (owner != null && !owner.equals(user)) {
                throw new SecurityException(page.getPath() + " is locked by " + owner);
            }
        }
        // the user must be allowed to remove what goes: asked before anything is written
        Session session = session();
        try {
            for (Resource r : targets) {
                if (!session.hasPermission(r.getPath(), Session.ACTION_REMOVE)) {
                    throw new SecurityException("Not allowed to delete " + r.getPath());
                }
            }
        } catch (RepositoryException e) {
            throw new WCMException("Could not check the permissions on " + paths, e);
        }
        PageManager pm = pageManager();
        versioned = 0;
        for (Resource page : pages) {
            if (page.getChild(CONTENT) == null) {
                continue;
            }
            versionBeforeDelete(pm, page.getPath(), "Deleted by " + user);
            versioned++;
            try {
                VersionHistory h = storeHistoryOf(page.getPath());
                record(page.getPath(), h == null ? null : h.getIdentifier(), SiteRail.titleOf(page), user);
            } catch (RepositoryException e) {
                throw new WCMException("Could not note the deletion of " + page.getPath(), e);
            }
        }
        store.commit();
        return targets;
    }

    /** A version as the user (whose name it then carries), or as the service when the user may not version. */
    private void versionBeforeDelete(PageManager pm, String path, String comment) throws WCMException {
        Page p = pm.getPage(path);
        if (p == null) {
            return;
        }
        try {
            pm.createRevision(p, null, comment);
        } catch (WCMException e) {
            if (store == resolver) {
                throw e;
            }
            PageManager service = pageManager(store);
            Page sp = service.getPage(path);
            if (sp == null) {
                throw e;
            }
            service.createRevision(sp, null, comment);
        }
    }

    /** The version history of an existing page, read by the service (null without versions). */
    private VersionHistory storeHistoryOf(String pagePath) throws RepositoryException {
        Session s = storeSession();
        String content = pagePath + "/" + CONTENT;
        if (!s.nodeExists(content) || !s.getNode(content).isNodeType("mix:versionable")) {
            return null;
        }
        return s.getWorkspace().getVersionManager().getVersionHistory(content);
    }

    private static void pages(Resource r, List<Resource> out) {
        if (SiteRail.isPage(r)) {
            out.add(r);
        }
        for (Resource c : r.getChildren()) {
            if (!CONTENT.equals(c.getName()) && (SiteRail.isPage(c) || SiteRail.isFolder(c))) {
                pages(c, out);
            }
        }
    }

    private static String lockOwner(Resource page) {
        Resource c = page.getChild(CONTENT);
        if (c == null) {
            return null;
        }
        ValueMap v = c.getValueMap();
        String o = v.get("cq:lockOwner", String.class);
        return o != null && !o.isEmpty() ? o : v.get("studio:lockOwner", String.class);
    }

    /**
     * Records a page deleted outside the console from the repository state before the deletion
     * ({@code DeletedPagesObserver}): its version history, who deleted it and when — more than the version storage
     * walk can tell, and without waiting for the index. A record that already says who deleted the page stays.
     *
     * @return whether a record was written
     */
    boolean recordFromState(String pagePath, String history, String title, String user) throws PersistenceException {
        Resource existing = store.getResource(DELETED + pagePath);
        if (existing != null && existing.getValueMap().get("deletedBy") != null) {
            return false;
        }
        record(pagePath, history, title, user);
        org.apache.sling.api.resource.ModifiableValueMap m = store.getResource(DELETED + pagePath)
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        m.remove("found");
        store.commit();
        return true;
    }

    private void record(String pagePath, String history, String title, String user) throws PersistenceException {
        Resource r = folders(DELETED + pagePath);
        org.apache.sling.api.resource.ModifiableValueMap m = r.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        m.put("pagePath", pagePath);
        if (history != null) {
            m.put(HISTORY, history);
        }
        m.put("title", title);
        m.put("deletedBy", user);
        m.put("deleted", Calendar.getInstance());
    }

    private Resource folders(String path) throws PersistenceException {
        Resource r = store.getResource(path);
        if (r != null) {
            return r;
        }
        int slash = path.lastIndexOf('/');
        Resource parent = folders(slash <= 0 ? "/" : path.substring(0, slash));
        return store.create(parent, path.substring(slash + 1), Collections.singletonMap("jcr:primaryType", "sling:Folder"));
    }

    /**
     * Records the pages below a path that were deleted without a record (outside the console, or before records
     * existed): their content's version history says where they were. Moved pages and pages without versions are left
     * out. Runs as the service.
     *
     * @return how many records were made
     */
    int recordDeleted(String prefix) throws RepositoryException, PersistenceException {
        int made = 0;
        Session s = storeSession();
        for (Map.Entry<String, VersionHistory> e : VersionHistories.deletedBelow(s, prefix).entrySet()) {
            String page = e.getKey();
            Resource existing = store.getResource(DELETED + page);
            if (existing != null && e.getValue().getIdentifier().equals(existing.getValueMap().get(HISTORY, String.class))) {
                continue;
            }
            Map<String, Object> latest = versions(e.getValue()).stream().findFirst().orElse(null);
            String title = latest == null || latest.get("title") == null ? page.substring(page.lastIndexOf('/') + 1) : String.valueOf(latest.get("title"));
            Resource r = folders(DELETED + page);
            org.apache.sling.api.resource.ModifiableValueMap m = r.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
            m.put("pagePath", page);
            m.put(HISTORY, e.getValue().getIdentifier());
            m.put("title", title);
            m.put("found", Calendar.getInstance()); // when it was noticed; who deleted it and when is not known
            made++;
        }
        if (made > 0) {
            store.commit();
        }
        return made;
    }

    /**
     * Forgets a deleted page for good: its versions are removed and its record goes, so nothing can bring it back. Only
     * for admin; refused while the page (or its versioned content, moved) still exists.
     */
    void forgetDeleted(String pagePath) throws RepositoryException, PersistenceException {
        if (!"admin".equals(resolver.getUserID())) {
            throw new SecurityException("Only admin may forget a deleted page");
        }
        if (pagePath == null || !pagePath.startsWith("/content/") || store.getResource(pagePath) != null) {
            throw new IllegalArgumentException("Not a deleted page: " + pagePath);
        }
        Resource record = store.getResource(DELETED + pagePath);
        VersionHistory history = record == null ? null : historyOf(record);
        if (history == null) {
            history = VersionHistories.deletedBelow(storeSession(), pagePath.substring(0, pagePath.lastIndexOf('/'))).get(pagePath);
        }
        if (history != null) {
            if (VersionHistories.livePath(storeSession(), history) != null) {
                throw new IllegalArgumentException(pagePath + " was moved, not deleted");
            }
            List<String> names = new ArrayList<>();
            for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
                Version v = it.nextVersion();
                if (!"jcr:rootVersion".equals(v.getName())) {
                    names.add(v.getName());
                }
            }
            for (String label : history.getVersionLabels()) {
                history.removeVersionLabel(label);
            }
            for (String name : names) {
                history.removeVersion(name);
            }
        }
        if (record != null) {
            forget(pagePath);
            store.commit();
        }
        KeptPages.forget(storeSession(), pagePath);
    }

    /** Forgets a deletion record once its page is back (records of pages below it stay). */
    private void forget(String pagePath) throws PersistenceException {
        Resource r = store.getResource(DELETED + pagePath);
        if (r == null) {
            return;
        }
        if (r.hasChildren()) {
            org.apache.sling.api.resource.ModifiableValueMap m = r.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
            for (String k : new String[] {"pagePath", HISTORY, "title", "deletedBy", "deleted", "found"}) {
                m.remove(k);
            }
        } else {
            Resource parent = r.getParent();
            store.delete(r);
            // the folders that held only this record go too
            while (parent != null && parent.getPath().startsWith(DELETED + "/") && !parent.hasChildren()
                    && parent.getValueMap().get("pagePath") == null) {
                Resource up = parent.getParent();
                store.delete(parent);
                parent = up;
            }
        }
    }

    /** Deletion records below a path, keyed by page path (pages that exist again are left out). */
    private Map<String, Resource> deletedBelow(String path) {
        Map<String, Resource> out = new LinkedHashMap<>();
        Resource root = store.getResource(DELETED + path);
        if (root != null) {
            for (Resource c : root.getChildren()) {
                collectRecords(c, out);
            }
        }
        return out;
    }

    private void collectRecords(Resource r, Map<String, Resource> out) {
        String page = r.getValueMap().get("pagePath", String.class);
        if (page != null && r.getValueMap().get(HISTORY) != null && store.getResource(page) == null && visible(page)
                && movedTo(r) == null) {
            out.put(page, r);
        }
        for (Resource c : r.getChildren()) {
            collectRecords(c, out);
        }
    }

    /**
     * Where the page of a deletion record is now, when it was moved rather than deleted (its versioned content still
     * exists under another path), else null.
     */
    private String movedTo(Resource record) {
        try {
            VersionHistory h = historyOf(record);
            return h == null ? null : VersionHistories.livePath(storeSession(), h);
        } catch (RepositoryException e) {
            return null;
        }
    }

    /** Whether the user can see where a deleted page was: its nearest ancestor that exists is readable to them. */
    private boolean visible(String pagePath) {
        for (String p = pagePath.substring(0, pagePath.lastIndexOf('/')); p.lastIndexOf('/') > 0; p = p.substring(0, p.lastIndexOf('/'))) {
            if (store.getResource(p) != null) {
                return resolver.getResource(p) != null;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ restore version

    /** The pages inside a page that have versions: existing ones and deleted ones, in path order. */
    List<Map<String, Object>> versionsInside(String path) throws RepositoryException {
        Resource root = requireItem(path);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Resource> pages = new ArrayList<>();
        for (Resource c : root.getChildren()) {
            if (!CONTENT.equals(c.getName()) && (SiteRail.isPage(c) || SiteRail.isFolder(c))) {
                pages(c, pages);
            }
        }
        for (Resource p : pages) {
            List<Map<String, Object>> vs = versions(historyOf(p.getPath()));
            if (!vs.isEmpty()) {
                out.add(entry(p.getPath(), SiteRail.titleOf(p), false, vs));
            }
        }
        for (Map.Entry<String, Resource> d : deletedBelow(path).entrySet()) {
            List<Map<String, Object>> vs = versions(historyOf(d.getValue()));
            if (!vs.isEmpty()) {
                Map<String, Object> e = entry(d.getKey(), d.getValue().getValueMap().get("title", d.getKey()), true, vs);
                e.put("deletedBy", d.getValue().getValueMap().get("deletedBy", String.class));
                e.put("deletedAt", SiteRail.iso(d.getValue().getValueMap().get("deleted", Calendar.class)));
                e.put("foundAt", SiteRail.iso(d.getValue().getValueMap().get("found", Calendar.class)));
                out.add(e);
            }
        }
        addKept(path, out);
        out.sort((a, b) -> String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path"))));
        return out;
    }

    /**
     * Pages deleted outside the console that were kept as they were ({@link KeptPages}): their state at the deletion
     * comes first among the versions of a page, or is the only one of a page that never had a version.
     */
    @SuppressWarnings("unchecked")
    private void addKept(String path, List<Map<String, Object>> out) throws RepositoryException {
        for (KeptPages.Kept k : KeptPages.below(storeSession(), path).values()) {
            if (!visible(k.page)) {
                continue;
            }
            Map<String, Object> e = out.stream().filter(x -> k.page.equals(x.get("path"))).findFirst().orElse(null);
            if (e == null) {
                e = entry(k.page, k.title, true, new ArrayList<>(List.of(k.version())));
                out.add(e);
            } else {
                ((List<Map<String, Object>>) e.get("versions")).add(0, k.version());
            }
            e.put("deletedBy", k.deletedBy);
            e.put("deletedAt", SiteRail.iso(k.deleted));
            e.put("kept", true);
        }
    }

    private static Map<String, Object> entry(String path, String title, boolean deleted, List<Map<String, Object>> versions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("name", path.substring(path.lastIndexOf('/') + 1));
        m.put("title", title);
        m.put("deleted", deleted);
        m.put("versions", clean(versions));
        return m;
    }

    /**
     * Restores the given versions (page path → version identifier or name), parents before children. A deleted page
     * is created again from its version. Each page succeeds or fails on its own.
     */
    List<Map<String, Object>> restoreVersions(Map<String, String> choices) {
        List<String> order = new ArrayList<>(choices.keySet());
        order.sort(Comparator.comparingInt((String p) -> p.split("/").length).thenComparing(Comparator.naturalOrder()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String page : order) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", page);
            try {
                r.put("status", restoreOne(page, choices.get(page)));
            } catch (Exception e) {
                revert();
                r.put("status", "failed");
                r.put("error", e.getMessage());
            }
            out.add(r);
        }
        return out;
    }

    /** @return "restored" (an existing page) or "recreated" (a deleted one) */
    String restoreOne(String page, String version) throws WCMException, RepositoryException, PersistenceException {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("Choose a version of " + page);
        }
        Resource existing = resolver.getResource(page);
        if (existing != null) {
            if (!SiteRail.isPage(existing)) {
                throw new IllegalArgumentException("Not a page: " + page);
            }
            pageManager().restore(page, version);
            resolver.commit();
            return "restored";
        }
        if (version.startsWith(KeptPages.PREFIX)) {
            Node kept = visible(page) ? KeptPages.node(storeSession(), page, version) : null;
            if (kept == null) {
                throw new IllegalArgumentException("Nothing kept of " + page + " as " + version);
            }
            restoreKept(page, kept);
            forget(page);
            store.commit();
            return "recreated";
        }
        Resource record = store.getResource(DELETED + page);
        VersionHistory history = record == null || !visible(page) ? null : historyOf(record);
        Version v = history == null ? null : find(history, version);
        if (v == null) {
            throw new IllegalArgumentException("No version " + version + " of " + page);
        }
        recreate(page, v);
        forget(page);
        store.commit();
        return "recreated";
    }

    /** A version of a page, existing or deleted (by identifier or name), or null. */
    Version versionOf(String pagePath, String version) throws RepositoryException {
        if (version == null || version.isEmpty()) {
            return null;
        }
        VersionHistory history = historyOf(pagePath);
        if (history == null) {
            Resource record = store.getResource(DELETED + pagePath);
            history = record == null || !visible(pagePath) ? null : historyOf(record);
        }
        return history == null ? null : find(history, version);
    }

    static Version find(VersionHistory history, String version) throws RepositoryException {
        for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
            Version v = it.nextVersion();
            if (!"jcr:rootVersion".equals(v.getName()) && (v.getIdentifier().equals(version) || v.getName().equals(version))) {
                return v;
            }
        }
        return null;
    }

    /**
     * Creates a deleted page again: the {@code cq:Page} node, then its content restored from the version (the same
     * node, so the page keeps its version history). A repository that cannot restore to a new place gets a copy of
     * the frozen content instead, with a new history.
     */
    private void recreate(String page, Version v) throws RepositoryException, PersistenceException {
        // restoring a version whose node lives elsewhere would remove that node: a moved page is not brought back
        String moved = VersionHistories.livePath(storeSession(), v.getContainingHistory());
        if (moved != null) {
            throw new IllegalArgumentException(page + " was moved, not deleted: it is at " + moved.substring(0, moved.lastIndexOf('/')));
        }
        mayCreate(page);
        int slash = page.lastIndexOf('/');
        Resource parent = store.getResource(page.substring(0, slash));
        store.create(parent, page.substring(slash + 1), Collections.singletonMap("jcr:primaryType", "cq:Page"));
        store.commit();
        Session s = storeSession();
        String content = page + "/" + CONTENT;
        try {
            // the version comes back as the same node, with its history: the page keeps its versions
            VersionManager vm = s.getWorkspace().getVersionManager();
            vm.restore(content, v, true);
            vm.checkout(content);
        } catch (javax.jcr.UnsupportedRepositoryOperationException | javax.jcr.ItemExistsException e) {
            s.refresh(false);
            copyFrozen(v.getFrozenNode(), s.getNode(page), CONTENT);
        }
        s.save();
    }

    /**
     * Brings a kept page back. When it had versions, its content comes back from the last one first — the same node,
     * so the page keeps its version history — and then gets the kept content; otherwise the kept copy is copied back.
     */
    private void restoreKept(String page, Node kept) throws RepositoryException, PersistenceException {
        restoreKeptContent(page, kept);
        // the state it came back in becomes a version, as the user (or the service, when the user may not version)
        resolver.refresh();
        try {
            versionBeforeDelete(pageManager(), page, RESTORED_COMMENT);
        } catch (WCMException e) {
            throw new RepositoryException("Could not version " + page + " as it came back", e);
        }
    }

    /** The comment of the version made when a page comes back from its kept copy. */
    static final String RESTORED_COMMENT = "Restored as it was when it was deleted";

    private void restoreKeptContent(String page, Node kept) throws RepositoryException, PersistenceException {
        Version last = lastVersionOfDeleted(page);
        if (last == null || !kept.hasNode(CONTENT)) {
            mayCreate(page);
            KeptPages.restore(storeSession(), kept, page);
            return;
        }
        recreate(page, last);
        Session s = storeSession();
        Node pageNode = s.getNode(page);
        KeptPages.overwrite(pageNode.getNode(CONTENT), kept.getNode(CONTENT));
        // whatever else the page held (not its content, not the pages below it)
        for (NodeIterator it = kept.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (!CONTENT.equals(c.getName()) && !c.isNodeType("cq:Page") && !pageNode.hasNode(c.getName())) {
                KeptPages.copy(c, pageNode, c.getName());
            }
        }
        s.save();
        KeptPages.cleanUp(s, kept);
    }

    /** The newest version of a deleted page (from its record, or found in the version storage), or null. */
    private Version lastVersionOfDeleted(String page) throws RepositoryException {
        Resource record = store.getResource(DELETED + page);
        VersionHistory h = record == null ? null : historyOf(record);
        if (h == null) {
            h = VersionHistories.deletedBelow(storeSession(), page.substring(0, page.lastIndexOf('/'))).get(page);
        }
        if (h == null || VersionHistories.livePath(storeSession(), h) != null) {
            return null;
        }
        Version last = null;
        for (VersionIterator it = h.getAllVersions(); it.hasNext();) {
            Version v = it.nextVersion();
            if (!"jcr:rootVersion".equals(v.getName()) && (last == null || v.getCreated().after(last.getCreated()))) {
                last = v;
            }
        }
        return last;
    }

    /** Whether the page can come back where it was: its parent exists, and the user may create it there. */
    private void mayCreate(String page) throws RepositoryException {
        int slash = page.lastIndexOf('/');
        if (resolver.getResource(page.substring(0, slash)) == null) {
            throw new IllegalArgumentException("Restore " + page.substring(0, slash) + " first: " + page + " was inside it");
        }
        // the user must be allowed to create the page there; the service brings it back
        if (!session().hasPermission(page, Session.ACTION_ADD_NODE)) {
            throw new SecurityException("Not allowed to create " + page);
        }
    }

    /** Copies a frozen node (and its subtree) to a new child of {@code parent}. */
    static void copyFrozen(Node frozen, Node parent, String name) throws RepositoryException {
        String type = frozen.getProperty("jcr:frozenPrimaryType").getString();
        Node n = parent.addNode(name, type);
        if (frozen.hasProperty("jcr:frozenMixinTypes")) {
            for (Value mixin : frozen.getProperty("jcr:frozenMixinTypes").getValues()) {
                String m = mixin.getString();
                // a new history starts with the next version; a uuid is assigned by the repository
                if (!"mix:versionable".equals(m) && !"mix:referenceable".equals(m)) {
                    n.addMixin(m);
                }
            }
        }
        for (PropertyIterator it = frozen.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            String pn = p.getName();
            if (pn.startsWith("jcr:frozen") || pn.equals("jcr:uuid") || pn.equals("jcr:primaryType")
                    || pn.equals("jcr:mixinTypes")) {
                continue;
            }
            try {
                if (p.isMultiple()) {
                    n.setProperty(pn, p.getValues(), p.getType());
                } else {
                    n.setProperty(pn, p.getValue(), p.getType());
                }
            } catch (javax.jcr.nodetype.ConstraintViolationException e) {
                // protected on the live node (e.g. jcr:created of mix:created): the repository sets it
            }
        }
        for (NodeIterator it = frozen.getNodes(); it.hasNext();) {
            Node child = it.nextNode();
            if (child.isNodeType("nt:frozenNode")) {
                copyFrozen(child, n, child.getName());
            }
        }
    }

    private void revert() {
        resolver.revert();
        store.revert();
        try {
            session().refresh(false);
            storeSession().refresh(false);
        } catch (RuntimeException | RepositoryException ignored) {
            // nothing more to undo
        }
    }

    // ------------------------------------------------------------------------------------------ restore tree

    /**
     * The tree below (and with) a page as it would be restored to the given time: for each page, existing or deleted,
     * the latest version it had then (or none).
     */
    Map<String, Object> treeAt(String path, long at) throws RepositoryException {
        Resource root = requireItem(path);
        Map<String, KeptPages.Kept> kept = new LinkedHashMap<>();
        for (Map.Entry<String, KeptPages.Kept> k : KeptPages.below(storeSession(), path).entrySet()) {
            if (visible(k.getKey())) {
                kept.put(k.getKey(), k.getValue());
            }
        }
        return node(path, root, deletedBelow(path), kept, at);
    }

    private Map<String, Object> node(String path, Resource existing, Map<String, Resource> deleted, Map<String, KeptPages.Kept> kept,
            long at) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("name", path.substring(path.lastIndexOf('/') + 1));
        boolean isPage = existing == null || SiteRail.isPage(existing);
        Resource record = deleted.get(path);
        List<Map<String, Object>> vs = !isPage ? Collections.emptyList()
                : versions(existing != null ? historyOf(path) : record == null ? null : historyOf(record));
        KeptPages.Kept k = existing == null ? kept.get(path) : null;
        if (k != null) {
            // the kept copy is how the page was since its last change: a version of that time
            vs = new ArrayList<>(vs);
            vs.add(k.timedVersion());
            vs.sort(Comparator.comparingLong((Map<String, Object> v) -> (Long) v.get(" time")).reversed());
        }
        Map<String, Object> version = versionAt(vs, at);
        String title = existing != null ? SiteRail.titleOf(existing)
                : version != null && version.get("title") != null ? String.valueOf(version.get("title"))
                : k != null ? k.title
                : record == null ? m.get("name").toString() : record.getValueMap().get("title", path);
        m.put("title", title);
        m.put("page", isPage);
        m.put("exists", existing != null);
        m.put("version", clean(version));
        if (existing == null && version == null && !vs.isEmpty()) {
            // a deleted page that did not exist yet (or had no version) then: if pages below it come back, it comes back
            // first, as it was when it was deleted
            m.put("fallback", clean(vs.get(0)));
        }

        // children: the existing ones and the deleted ones, by name
        Set<String> names = new LinkedHashSet<>();
        Map<String, Resource> live = new HashMap<>();
        if (existing != null) {
            for (Resource c : existing.getChildren()) {
                if (!CONTENT.equals(c.getName()) && (SiteRail.isPage(c) || SiteRail.isFolder(c))) {
                    names.add(c.getName());
                    live.put(c.getName(), c);
                }
            }
        }
        String prefix = path + "/";
        // deleted pages, also deeper ones whose parent left no record: reached through their parent's name
        for (String d : deleted.keySet()) {
            if (d.startsWith(prefix)) {
                String first = d.substring(prefix.length()).split("/")[0];
                names.add(first);
            }
        }
        for (String d : kept.keySet()) {
            if (d.startsWith(prefix)) {
                names.add(d.substring(prefix.length()).split("/")[0]);
            }
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (String n : names) {
            Map<String, Object> child = node(prefix + n, live.get(n), deleted, kept, at);
            // a deleted page without a version then, and nothing restorable below it: nothing to show
            if (!(Boolean) child.get("exists") && child.get("version") == null && ((List<?>) child.get("children")).isEmpty()) {
                continue;
            }
            children.add(child);
        }
        m.put("children", children);
        return m;
    }

    /**
     * Restores a tree to a time: every page with a version then gets that version (deleted ones are created again);
     * without {@code preserve}, existing pages that had no version then — and nothing restorable below them — are
     * deleted (through {@link #delete}, so they can be restored themselves).
     */
    Map<String, Object> restoreTree(String path, long at, boolean preserve) throws RepositoryException, WCMException, PersistenceException {
        Map<String, Object> tree = treeAt(path, at);
        List<Map<String, Object>> restore = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        plan(tree, preserve, true, restore, remove);
        int restored = 0;
        int recreated = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Map<String, Object> n : restore) {
            String page = String.valueOf(n.get("path"));
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) n.get("version");
            try {
                if ("recreated".equals(restoreOne(page, String.valueOf(v.get("id"))))) {
                    recreated++;
                } else {
                    restored++;
                }
            } catch (Exception e) {
                revert();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", page);
                f.put("error", e.getMessage());
                failed.add(f);
            }
        }
        if (!remove.isEmpty()) {
            delete(remove);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", restored);
        out.put("recreated", recreated);
        out.put("removed", remove.size());
        out.put("failed", failed);
        return out;
    }

    /** @return whether the subtree has something to restore */
    @SuppressWarnings("unchecked")
    private static boolean plan(Map<String, Object> n, boolean preserve, boolean root, List<Map<String, Object>> restore,
            List<String> remove) {
        boolean here = n.get("version") != null;
        int at = restore.size();
        if (here) {
            restore.add(n);
        }
        boolean below = false;
        List<String> removeBelow = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) n.get("children")) {
            below |= plan(c, preserve, false, restore, removeBelow);
        }
        boolean exists = (Boolean) n.get("exists");
        boolean page = (Boolean) n.get("page");
        if (!here && !exists && below && n.get("fallback") != null) {
            Map<String, Object> container = new LinkedHashMap<>(n);
            container.put("version", n.get("fallback"));
            restore.add(at, container); // before the pages below it
        }
        if (!preserve && !root && exists && page && !here && !below) {
            remove.add(String.valueOf(n.get("path"))); // its pages below go with it (the tree itself stays)
        } else {
            remove.addAll(removeBelow);
        }
        return here || below;
    }

    private Resource requireItem(String path) {
        Resource r = path == null || !path.startsWith("/content/") ? null : resolver.getResource(path);
        if (r == null || !(SiteRail.isPage(r) || SiteRail.isFolder(r))) {
            throw new IllegalArgumentException("Not a page or folder: " + path);
        }
        return r;
    }
}
