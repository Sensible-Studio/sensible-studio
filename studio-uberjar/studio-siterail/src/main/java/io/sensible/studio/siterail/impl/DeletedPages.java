/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.DefaultNodeStateDiff;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Pages deleted between two states of the repository, read from the state before: what {@code DeletedPagesObserver}
 * keeps. Only below {@code /content}, not the DAM or the tags; only pages and folders holding pages (a component taken
 * out of a page is an edit, not a deletion); not pages that were moved (the same state appears elsewhere in the same
 * change) and not pages the Sites console or a Sling POST delete already recorded (they have a version).
 */
final class DeletedPages {

    private static final String CONTENT = "jcr:content";
    private static final Set<String> FOLDERS = Set.of("sling:Folder", "sling:OrderedFolder", "nt:folder", "nt:unstructured");
    /** How deep a deleted folder is searched for pages. */
    private static final int FOLDER_DEPTH = 4;

    private DeletedPages() {
    }

    /** A deleted page or folder, with its state before the deletion. */
    static final class Deleted {
        final String path;
        final NodeState state;

        Deleted(String path, NodeState state) {
            this.path = path;
            this.state = state;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** What was deleted below {@code /content} from {@code before} to {@code after} and needs keeping. */
    static List<Deleted> between(NodeState before, NodeState after) {
        NodeState b = before.getChildNode("content");
        NodeState a = after.getChildNode("content");
        if (!b.exists() || b.equals(a)) {
            return Collections.emptyList();
        }
        List<Deleted> deleted = new ArrayList<>();
        List<Deleted> added = new ArrayList<>();
        a.compareAgainstBaseState(b, new Diff("/content", deleted, added));
        List<Deleted> out = new ArrayList<>();
        for (Deleted d : deleted) {
            if (!moved(d, added) && !recorded(after, d)) {
                out.add(d);
            }
        }
        return out;
    }

    private static final class Diff extends DefaultNodeStateDiff {
        private final String path;
        private final List<Deleted> deleted;
        private final List<Deleted> added;

        Diff(String path, List<Deleted> deleted, List<Deleted> added) {
            this.path = path;
            this.deleted = deleted;
            this.added = added;
        }

        @Override
        public boolean childNodeAdded(String name, NodeState after) {
            if (relevant(path, name, after)) {
                added.add(new Deleted(path + "/" + name, after));
            }
            return true;
        }

        @Override
        public boolean childNodeDeleted(String name, NodeState before) {
            if (relevant(path, name, before)) {
                deleted.add(new Deleted(path + "/" + name, before));
            }
            return true;
        }

        @Override
        public boolean childNodeChanged(String name, NodeState before, NodeState after) {
            // into pages and folders only: what changes inside page content is an edit
            if (!skipped(path, name) && !CONTENT.equals(name) && (isPage(after) || isFolder(after))) {
                after.compareAgainstBaseState(before, new Diff(path + "/" + name, deleted, added));
            }
            return true;
        }
    }

    private static boolean skipped(String parent, String name) {
        return name.startsWith(":") || name.startsWith("rep:") || "/content".equals(parent) && ("dam".equals(name) || "cq:tags".equals(name));
    }

    private static boolean relevant(String parent, String name, NodeState state) {
        return !skipped(parent, name) && !CONTENT.equals(name) && (isPage(state) || isFolder(state) && hasPages(state, FOLDER_DEPTH));
    }

    static boolean isPage(NodeState s) {
        return "cq:Page".equals(type(s));
    }

    private static boolean isFolder(NodeState s) {
        return FOLDERS.contains(type(s));
    }

    private static String type(NodeState s) {
        PropertyState t = s.getProperty("jcr:primaryType");
        return t == null ? null : t.getValue(Type.NAME);
    }

    private static boolean hasPages(NodeState folder, int depth) {
        for (String n : folder.getChildNodeNames()) {
            NodeState c = folder.getChildNode(n);
            if (isPage(c) || depth > 0 && isFolder(c) && hasPages(c, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A move shows as a deletion and an addition (a rename too). Oak marks the moved node with the hidden property
     * {@code :source-path}; otherwise the same state, hidden properties left aside, is taken as the same node.
     */
    private static boolean moved(Deleted d, List<Deleted> added) {
        for (Deleted a : added) {
            PropertyState source = a.state.getProperty(":source-path");
            if (source != null && d.path.equals(source.getValue(Type.STRING)) || same(a.state, d.state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(NodeState x, NodeState y) {
        if (x.equals(y)) {
            return true;
        }
        for (PropertyState p : x.getProperties()) {
            if (!p.getName().startsWith(":") && !p.equals(y.getProperty(p.getName()))) {
                return false;
            }
        }
        for (PropertyState q : y.getProperties()) {
            if (!q.getName().startsWith(":") && !x.hasProperty(q.getName())) {
                return false;
            }
        }
        for (String n : x.getChildNodeNames()) {
            if (!n.startsWith(":") && !x.getChildNode(n).equals(y.getChildNode(n))) {
                return false;
            }
        }
        for (String n : y.getChildNodeNames()) {
            if (!n.startsWith(":") && !x.hasChildNode(n)) {
                return false;
            }
        }
        return true;
    }

    /** Whether every page in what was deleted has a deletion record (the console and Sling POST deletes make them). */
    private static boolean recorded(NodeState root, Deleted d) {
        List<String> pages = new ArrayList<>();
        pagePaths(d.path, d.state, pages, FOLDER_DEPTH + 8);
        if (pages.isEmpty()) {
            return true;
        }
        for (String p : pages) {
            NodeState r = at(root, VersionRestore.DELETED + p);
            if (!r.exists() || !r.hasProperty("pagePath")) {
                return false;
            }
        }
        return true;
    }

    /** A deleted page whose content was versioned: where it was, its version history, its title. */
    static final class Versioned {
        final String path;
        final String history;
        final String title;

        Versioned(String path, String history, String title) {
            this.path = path;
            this.history = history;
            this.title = title;
        }
    }

    /** The pages in what was deleted whose content had a version history (read from the state before). */
    static List<Versioned> versioned(Deleted d) {
        List<String> pages = new ArrayList<>();
        pagePaths(d.path, d.state, pages, FOLDER_DEPTH + 8);
        List<Versioned> out = new ArrayList<>();
        for (String p : pages) {
            NodeState content = p.equals(d.path) ? d.state.getChildNode(CONTENT)
                    : at(d.state, p.substring(d.path.length())).getChildNode(CONTENT);
            PropertyState h = content.getProperty("jcr:versionHistory");
            if (h != null) {
                PropertyState t = content.getProperty("jcr:title");
                out.add(new Versioned(p, h.getValue(Type.STRING), t == null ? p.substring(p.lastIndexOf('/') + 1) : t.getValue(Type.STRING)));
            }
        }
        return out;
    }

    private static void pagePaths(String path, NodeState s, List<String> out, int depth) {
        if (isPage(s)) {
            out.add(path);
        }
        if (depth <= 0) {
            return;
        }
        for (String n : s.getChildNodeNames()) {
            if (!CONTENT.equals(n) && !n.startsWith(":")) {
                NodeState c = s.getChildNode(n);
                if (isPage(c) || isFolder(c)) {
                    pagePaths(path + "/" + n, c, out, depth - 1);
                }
            }
        }
    }

    static NodeState at(NodeState root, String path) {
        NodeState s = root;
        for (String segment : path.substring(1).split("/")) {
            s = s.getChildNode(segment);
        }
        return s;
    }
}
