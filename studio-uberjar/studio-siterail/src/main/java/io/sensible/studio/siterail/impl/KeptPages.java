/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Pages deleted outside the Sites console without a deletion record (CRX tools, WebDAV, the JCR API, packages), kept
 * as they were: {@code DeletedPagesObserver} reads them from the repository state before the deletion and keeps a
 * copy here, so Restore Version can bring them back even when they never had a version.
 *
 * <p>Storage: {@code /var/studio/kept/<day>/<entry>} ({@code path}, {@code deleted}, {@code deletedBy}) with a child
 * {@code node}: the deleted node as it was, pages inside it included. The copy leaves out what belongs to the place
 * it was in: identifiers, version history links, locks and access control. Written by the service user.</p>
 */
final class KeptPages {

    static final String ROOT = "/var/studio/kept";
    static final String NODE = "node";
    /** The version id of a kept page in Restore Version: {@code kept:<path of the kept page node>}. */
    static final String PREFIX = "kept:";
    /** A deletion larger than this is kept page by page; configurable on the observer. */
    static final int MAX_NODES = 20_000;
    /** How much one deletion may keep page by page, in all; configurable on the observer. */
    static final int MAX_TOTAL_NODES = 200_000;

    private static final String CONTENT = "jcr:content";
    private static final Set<String> SKIP_PROPERTIES = Set.of("jcr:primaryType", "jcr:mixinTypes", "jcr:uuid",
            "jcr:versionHistory", "jcr:baseVersion", "jcr:predecessors", "jcr:isCheckedOut", "jcr:mergeFailed",
            "jcr:activity", "jcr:configuration", "jcr:lockOwner", "jcr:lockIsDeep");
    private static final Set<String> SKIP_MIXINS = Set.of("mix:versionable", "mix:simpleVersionable",
            "mix:referenceable", "mix:lockable", "mix:shareable", "rep:AccessControllable", "rep:RepoAccessControllable");
    private static final Set<String> SKIP_NODES = Set.of("rep:policy", "rep:repoPolicy");

    private KeptPages() {
    }

    /** A kept page, as Restore Version lists it. */
    static final class Kept {
        final String page;
        final String node;
        final String title;
        final Calendar deleted;
        final String deletedBy;
        /** When the page was last changed before it was deleted (its content's cq:lastModified or jcr:lastModified). */
        final Calendar modified;

        Kept(String page, String node, String title, Calendar deleted, String deletedBy, Calendar modified) {
            this.page = page;
            this.node = node;
            this.title = title;
            this.deleted = deleted;
            this.deletedBy = deletedBy;
            this.modified = modified;
        }

        /**
         * When the kept state was the page's state from: its last change. Restore Tree takes the copy as a version of
         * that time — for any later time until the deletion it is exactly how the page was.
         */
        long since() {
            return modified != null ? modified.getTimeInMillis() : deleted != null ? deleted.getTimeInMillis() : 0;
        }

        /** As a version entry with its time, for Restore Tree. */
        Map<String, Object> timedVersion() {
            Map<String, Object> m = version();
            m.put(" time", since());
            return m;
        }

        /** The kept page as a version entry of Restore Version (newest of all: it is how the page was last). */
        Map<String, Object> version() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", PREFIX + node);
            m.put("name", "As deleted");
            m.put("comment", "Kept as it was when it was deleted");
            m.put("createdBy", deletedBy);
            m.put("title", title);
            m.put("created", SiteRail.iso(deleted));
            return m;
        }
    }

    // ------------------------------------------------------------------------------------------ keep

    /**
     * Keeps a deleted node (a page, or a folder with pages) as it was before its deletion.
     *
     * @return the path of the entry
     */
    static String keep(Session s, String path, NodeState deleted, String user, Calendar when) throws RepositoryException {
        return keep(s, path, deleted, user, when, MAX_NODES);
    }

    /**
     * Keeps a deletion: as a whole when it has at most {@code maxNodes} nodes, otherwise page by page — each page on its
     * own, without the pages below it — until {@code maxTotal} nodes are kept. Pages left out are added to
     * {@code skipped}.
     *
     * @return the entries made
     */
    static List<String> keepAll(Session s, String path, NodeState deleted, String user, Calendar when, int maxNodes, int maxTotal,
            List<String> skipped) throws RepositoryException {
        if (count(deleted, maxNodes + 1, false) <= maxNodes) {
            return Collections.singletonList(keep(s, path, deleted, user, when, maxNodes));
        }
        List<String> out = new ArrayList<>();
        int left = maxTotal;
        for (Map.Entry<String, NodeState> page : pageStates(path, deleted).entrySet()) {
            int n = count(page.getValue(), maxNodes + 1, true);
            if (n > maxNodes || n > left) {
                skipped.add(page.getKey());
                continue;
            }
            out.add(keep(s, page.getKey(), page.getValue(), user, when, maxNodes, true));
            left -= n;
        }
        return out;
    }

    /** The pages in a deleted subtree, parents first, with their states. */
    private static Map<String, NodeState> pageStates(String path, NodeState state) {
        Map<String, NodeState> out = new LinkedHashMap<>();
        collectPages(path, state, out);
        return out;
    }

    private static void collectPages(String path, NodeState state, Map<String, NodeState> out) {
        PropertyState type = state.getProperty("jcr:primaryType");
        if (type != null && "cq:Page".equals(type.getValue(Type.NAME))) {
            out.put(path, state);
        }
        for (String n : state.getChildNodeNames()) {
            if (!CONTENT.equals(n) && !n.startsWith(":")) {
                collectPages(path + "/" + n, state.getChildNode(n), out);
            }
        }
    }

    /** How many nodes a state has, counting no further than {@code limit} (pages below left out on request). */
    static int count(NodeState state, int limit, boolean withoutPagesBelow) {
        int[] n = {0};
        count(state, limit, withoutPagesBelow, n);
        return n[0];
    }

    private static void count(NodeState state, int limit, boolean withoutPagesBelow, int[] n) {
        if (++n[0] >= limit) {
            return;
        }
        for (String c : state.getChildNodeNames()) {
            NodeState child = state.getChildNode(c);
            if (c.startsWith(":") || SKIP_NODES.contains(c) || withoutPagesBelow && isPage(child)) {
                continue;
            }
            count(child, limit, withoutPagesBelow, n);
            if (n[0] >= limit) {
                return;
            }
        }
    }

    private static boolean isPage(NodeState state) {
        PropertyState type = state.getProperty("jcr:primaryType");
        return type != null && "cq:Page".equals(type.getValue(Type.NAME));
    }

    /** Keeps a deleted node, unless it has more than {@code maxNodes} nodes. */
    static String keep(Session s, String path, NodeState deleted, String user, Calendar when, int maxNodes) throws RepositoryException {
        return keep(s, path, deleted, user, when, maxNodes, false);
    }

    private static String keep(Session s, String path, NodeState deleted, String user, Calendar when, int maxNodes,
            boolean withoutPagesBelow) throws RepositoryException {
        Node day = folders(s, ROOT + "/" + new SimpleDateFormat("yyyy-MM-dd").format(when.getTime()));
        String name = "k" + when.getTimeInMillis();
        for (int i = 1; day.hasNode(name); i++) {
            name = "k" + when.getTimeInMillis() + "_" + i;
        }
        Node entry = day.addNode(name, "nt:unstructured");
        entry.setProperty("path", path);
        entry.setProperty("deleted", when);
        if (user != null) {
            entry.setProperty("deletedBy", user);
        }
        int[] budget = {maxNodes};
        add(s, entry, NODE, deleted, budget, withoutPagesBelow);
        if (budget[0] < 0) {
            s.refresh(false);
            throw new IllegalArgumentException(path + " is too large to keep (more than " + maxNodes + " nodes)");
        }
        s.save();
        return entry.getPath();
    }

    private static Node folders(Session s, String path) throws RepositoryException {
        if (s.nodeExists(path)) {
            return s.getNode(path);
        }
        int slash = path.lastIndexOf('/');
        Node parent = folders(s, slash == 0 ? "/" : path.substring(0, slash));
        return parent.addNode(path.substring(slash + 1), "sling:Folder");
    }

    /** Adds a node state, and its subtree, as a new child of {@code parent}. */
    private static void add(Session s, Node parent, String name, NodeState state, int[] budget, boolean withoutPagesBelow)
            throws RepositoryException {
        if (--budget[0] < 0) {
            return;
        }
        PropertyState type = state.getProperty("jcr:primaryType");
        Node n = parent.addNode(name, type == null ? "nt:unstructured" : type.getValue(Type.NAME));
        PropertyState mixins = state.getProperty("jcr:mixinTypes");
        if (mixins != null) {
            for (String m : mixins.getValue(Type.NAMES)) {
                if (!SKIP_MIXINS.contains(m)) {
                    try {
                        n.addMixin(m);
                    } catch (RepositoryException e) {
                        // a mixin that cannot be added here: the node is kept without it
                    }
                }
            }
        }
        ValueFactory vf = s.getValueFactory();
        for (PropertyState p : state.getProperties()) {
            String pn = p.getName();
            if (pn.startsWith(":") || SKIP_PROPERTIES.contains(pn)) {
                continue;
            }
            try {
                set(n, p, vf);
            } catch (ConstraintViolationException | ValueFormatException e) {
                // protected here (jcr:created of mix:created, say): the repository sets its own
            }
        }
        for (String child : childOrder(state)) {
            NodeState c = state.getChildNode(child);
            if (!child.startsWith(":") && !SKIP_NODES.contains(child) && !(withoutPagesBelow && isPage(c))) {
                add(s, n, child, c, budget, withoutPagesBelow);
            }
        }
    }

    private static void set(Node n, PropertyState p, ValueFactory vf) throws RepositoryException {
        int tag = p.getType().tag();
        // a reference would have to point at an existing node: kept as a weak one
        int target = tag == PropertyType.REFERENCE ? PropertyType.WEAKREFERENCE : tag;
        List<Value> values = new ArrayList<>();
        if (tag == PropertyType.BINARY) {
            for (Blob b : p.getValue(Type.BINARIES)) {
                values.add(vf.createValue(vf.createBinary(b.getNewStream())));
            }
        } else {
            for (String v : p.getValue(Type.STRINGS)) {
                values.add(vf.createValue(v, target));
            }
        }
        if (p.isArray()) {
            n.setProperty(p.getName(), values.toArray(new Value[0]), target);
        } else if (!values.isEmpty()) {
            n.setProperty(p.getName(), values.get(0));
        }
    }

    /** Child names in their order (an orderable node keeps it in a hidden property). */
    static List<String> childOrder(NodeState state) {
        Set<String> names = new LinkedHashSet<>();
        PropertyState order = state.getProperty(":childOrder");
        if (order != null) {
            for (String n : order.getValue(Type.NAMES)) {
                if (state.hasChildNode(n)) {
                    names.add(n);
                }
            }
        }
        for (String n : state.getChildNodeNames()) {
            names.add(n);
        }
        return new ArrayList<>(names);
    }

    // ------------------------------------------------------------------------------------------ find

    /**
     * The kept pages below a path that do not exist again, by page path; a page kept more than once comes from its
     * latest keeping.
     */
    static Map<String, Kept> below(Session s, String prefix) throws RepositoryException {
        Map<String, Kept> out = new LinkedHashMap<>();
        if (!s.nodeExists(ROOT)) {
            return out;
        }
        String below = prefix.endsWith("/") ? prefix : prefix + "/";
        for (Node entry : entries(s)) {
            String top = entry.getProperty("path").getString();
            if (!entry.hasNode(NODE) || !(top + "/").startsWith(below) && !below.startsWith(top + "/")) {
                continue;
            }
            Calendar deleted = entry.hasProperty("deleted") ? entry.getProperty("deleted").getDate() : null;
            String by = entry.hasProperty("deletedBy") ? entry.getProperty("deletedBy").getString() : null;
            List<Node> pages = new ArrayList<>();
            pages(entry.getNode(NODE), pages);
            String base = entry.getNode(NODE).getPath();
            for (Node p : pages) {
                String page = top + p.getPath().substring(base.length());
                if (page.startsWith(below) && !s.nodeExists(page)) {
                    out.put(page, new Kept(page, p.getPath(), titleOf(p, page), deleted, by, modifiedOf(p)));
                }
            }
        }
        return out;
    }

    /** The entries, oldest first (so later keepings of a page win). */
    private static List<Node> entries(Session s) throws RepositoryException {
        List<Node> out = new ArrayList<>();
        for (NodeIterator days = s.getNode(ROOT).getNodes(); days.hasNext();) {
            Node day = days.nextNode();
            for (NodeIterator it = day.getNodes(); it.hasNext();) {
                Node e = it.nextNode();
                if (e.hasProperty("path")) {
                    out.add(e);
                }
            }
        }
        out.sort((a, b) -> {
            try {
                return Long.compare(time(a), time(b));
            } catch (RepositoryException e) {
                return 0;
            }
        });
        return out;
    }

    private static long time(Node entry) throws RepositoryException {
        return entry.hasProperty("deleted") ? entry.getProperty("deleted").getDate().getTimeInMillis() : 0;
    }

    /** The pages in a kept subtree (not inside page content). */
    private static void pages(Node n, List<Node> out) throws RepositoryException {
        if (n.isNodeType("cq:Page")) {
            out.add(n);
        }
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (!CONTENT.equals(c.getName())) {
                pages(c, out);
            }
        }
    }

    private static Calendar modifiedOf(Node page) throws RepositoryException {
        if (page.hasNode(CONTENT)) {
            Node c = page.getNode(CONTENT);
            for (String p : new String[] {"cq:lastModified", "jcr:lastModified"}) {
                if (c.hasProperty(p) && c.getProperty(p).getType() == PropertyType.DATE) {
                    return c.getProperty(p).getDate();
                }
            }
        }
        return null;
    }

    private static String titleOf(Node page, String path) throws RepositoryException {
        if (page.hasNode(CONTENT) && page.getNode(CONTENT).hasProperty("jcr:title")) {
            return page.getNode(CONTENT).getProperty("jcr:title").getString();
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The kept page node a version id of Restore Version names, when it is the given page's. */
    static Node node(Session s, String page, String version) throws RepositoryException {
        if (version == null || !version.startsWith(PREFIX)) {
            return null;
        }
        String node = version.substring(PREFIX.length());
        if (!node.startsWith(ROOT + "/") || !s.nodeExists(node)) {
            return null;
        }
        Kept k = below(s, page.substring(0, page.lastIndexOf('/'))).get(page);
        return k != null && k.node.equals(node) ? s.getNode(node) : null;
    }

    // ------------------------------------------------------------------------------------------ restore, forget

    /**
     * Brings a kept page back: the page with its content and whatever else it held, but not the pages below it — they
     * come back on their own, as with versions. An entry whose pages all exist again is removed.
     */
    static void restore(Session s, Node kept, String page) throws RepositoryException {
        int slash = page.lastIndexOf('/');
        copy(kept, s.getNode(page.substring(0, slash)), page.substring(slash + 1));
        s.save();
        cleanUp(s, kept);
    }

    /** Once all pages of an entry exist again, the entry goes. */
    static void cleanUp(Session s, Node kept) throws RepositoryException {
        Node entry = entryOf(kept);
        if (entry != null && allBack(s, entry)) {
            remove(entry);
            s.save();
        }
    }

    /**
     * Puts the kept content of a page onto an existing content node — the one restored from the page's last version,
     * so the page keeps its version history: properties, mixins and child nodes become the kept ones.
     */
    static void overwrite(Node content, Node from) throws RepositoryException {
        for (NodeType m : from.getMixinNodeTypes()) {
            if (!content.isNodeType(m.getName())) {
                content.addMixin(m.getName());
            }
        }
        for (PropertyIterator it = content.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (!p.getDefinition().isProtected() && !from.hasProperty(p.getName())) {
                p.remove();
            }
        }
        for (PropertyIterator it = from.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (p.getDefinition().isProtected()) {
                continue;
            }
            try {
                if (p.isMultiple()) {
                    content.setProperty(p.getName(), p.getValues(), p.getType());
                } else {
                    content.setProperty(p.getName(), p.getValue(), p.getType());
                }
            } catch (ConstraintViolationException e) {
                // protected on the content node: it keeps its own
            }
        }
        for (NodeIterator it = content.getNodes(); it.hasNext();) {
            it.nextNode().remove();
        }
        for (NodeIterator it = from.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            copy(c, content, c.getName());
        }
    }

    /**
     * Removes what was kept before a day ({@code yyyy-MM-dd}, exclusive).
     *
     * @return how many entries went
     */
    static int purge(Session s, Calendar before) throws RepositoryException {
        if (!s.nodeExists(ROOT)) {
            return 0;
        }
        String cutoff = new SimpleDateFormat("yyyy-MM-dd").format(before.getTime());
        int removed = 0;
        for (NodeIterator days = s.getNode(ROOT).getNodes(); days.hasNext();) {
            Node day = days.nextNode();
            if (day.getName().matches("\\d{4}-\\d{2}-\\d{2}") && day.getName().compareTo(cutoff) < 0) {
                for (NodeIterator it = day.getNodes(); it.hasNext(); it.nextNode()) {
                    removed++;
                }
                day.remove();
            }
        }
        if (removed > 0 || s.hasPendingChanges()) {
            s.save();
        }
        return removed;
    }

    /** Removes an entry, and its day folder when that is left empty. */
    private static void remove(Node entry) throws RepositoryException {
        Node day = entry.getParent();
        entry.remove();
        if (!day.getNodes().hasNext() && day.getPath().startsWith(ROOT + "/")) {
            day.remove();
        }
    }

    private static boolean allBack(Session s, Node entry) throws RepositoryException {
        List<Node> pages = new ArrayList<>();
        pages(entry.getNode(NODE), pages);
        String top = entry.getProperty("path").getString();
        String base = entry.getNode(NODE).getPath();
        for (Node p : pages) {
            if (!s.nodeExists(top + p.getPath().substring(base.length()))) {
                return false;
            }
        }
        return true;
    }

    private static Node entryOf(Node kept) throws RepositoryException {
        for (Node n = kept; n.getDepth() > 0; n = n.getParent()) {
            if (NODE.equals(n.getName()) && n.getParent().hasProperty("path")) {
                return n.getParent();
            }
        }
        return null;
    }

    /** Copies a node (not the pages below it) to a new child of {@code parent}. */
    static void copy(Node from, Node parent, String name) throws RepositoryException {
        Node n = parent.addNode(name, from.getPrimaryNodeType().getName());
        for (NodeType m : from.getMixinNodeTypes()) {
            n.addMixin(m.getName());
        }
        for (PropertyIterator it = from.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (p.getDefinition().isProtected()) {
                continue;
            }
            try {
                if (p.isMultiple()) {
                    n.setProperty(p.getName(), p.getValues(), p.getType());
                } else {
                    n.setProperty(p.getName(), p.getValue(), p.getType());
                }
            } catch (ConstraintViolationException e) {
                // protected on the new node: the repository sets it
            }
        }
        for (NodeIterator it = from.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (!c.isNodeType("cq:Page")) {
                copy(c, n, c.getName());
            }
        }
    }

    /**
     * Forgets the kept copy of a page for good (with the copies of pages below it); entries left without pages go.
     *
     * @return whether anything was kept for it
     */
    static boolean forget(Session s, String page) throws RepositoryException {
        boolean found = false;
        if (!s.nodeExists(ROOT)) {
            return false;
        }
        for (Node entry : entries(s)) {
            String top = entry.getProperty("path").getString();
            if (!entry.hasNode(NODE) || !(page + "/").startsWith(top + "/")) {
                continue;
            }
            String rel = page.substring(top.length());
            String node = entry.getNode(NODE).getPath() + rel;
            if (s.nodeExists(node)) {
                s.getNode(node).remove();
                found = true;
            }
            List<Node> left = new ArrayList<>();
            if (entry.hasNode(NODE)) {
                pages(entry.getNode(NODE), left);
            }
            if (left.isEmpty()) {
                remove(entry);
                found = true;
            }
        }
        if (found) {
            s.save();
        }
        return found;
    }

    /** For tests and the listing: the entries' paths. */
    static List<String> entryPaths(Session s) throws RepositoryException {
        if (!s.nodeExists(ROOT)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Node e : entries(s)) {
            out.add(e.getPath());
        }
        return out;
    }
}
