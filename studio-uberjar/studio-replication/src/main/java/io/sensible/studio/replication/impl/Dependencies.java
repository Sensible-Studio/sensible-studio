/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * What a page shows besides itself, for the cache in front of the publish: the media and the
 * experience fragments it names anywhere in its content (any property that holds such a path), those
 * its template's structure names (a header and a footer), and, a few levels down, what those fragments
 * and content fragments name in turn. A publication of one of them then takes out only the pages that
 * show it ({@code X-Studio-Depends}). Other pages (what navigation, lists, breadcrumbs and teasers show)
 * are not in the content: the rendering records them ({@code Shown}, {@link #combine}). Not listed:
 * {@code /conf} (policies, templates), which the cache treats as everybody's.
 */
final class Dependencies {

    /** Above this many, or this long a header, a page says nothing and the cache treats it as showing everything. */
    static final int MAX_PATHS = 300;
    /** Jetty keeps all of an answer's headers in 8 KB: this one leaves room for the others. */
    static final int MAX_HEADER = 6000;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_HOPS = 3;
    private static final String[] TRACKED = {"/content/dam/", "/content/experience-fragments/"};

    private Dependencies() {
    }

    /** The header value for a page, or null when it would say too much to be of use. */
    static String header(Resource page) {
        return header(of(page));
    }

    /**
     * Everything a page shows: what its content names ({@link #of}), the pages above it (their
     * properties are inherited: a brand, a navigation root, a language) and the pages its rendering
     * showed ({@code <page>/jcr:content}) or listed the children of ({@code <page>/*}). A page whose
     * parent's children were listed is not named again: a publication of it is one of a child.
     * Null when the content part is not known, or when it is too much.
     */
    static Set<String> combine(Resource page, Set<String> named, Set<String> rendered) {
        if (named == null || rendered == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>(named);
        for (Resource p = page.getParent(); p != null && p.getPath().startsWith("/content/")
                && "cq:Page".equals(p.getValueMap().get("jcr:primaryType", String.class)); p = p.getParent()) {
            out.add(p.getPath() + "/jcr:content");
        }
        String own = page.getPath() + "/jcr:content";
        for (String path : rendered) {
            if (path.equals(own)) {
                continue;
            }
            if (path.endsWith("/jcr:content")) {
                String shown = path.substring(0, path.length() - "/jcr:content".length());
                int slash = shown.lastIndexOf('/');
                if (slash > 0 && rendered.contains(shown.substring(0, slash) + "/*")) {
                    continue;
                }
            }
            out.add(path);
        }
        return out.size() > MAX_PATHS ? null : out;
    }

    /** The header value for these paths, or null when they say too much (or nothing is known). */
    static String header(Set<String> paths) {
        if (paths == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String p : paths) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(p.replace("%", "%25").replace(",", "%2C"));
            if (out.length() > MAX_HEADER) {
                return null;
            }
        }
        return out.toString();
    }

    /** What the page shows besides itself; null when it is more than {@link #MAX_PATHS}. */
    static Set<String> of(Resource page) {
        ResourceResolver rr = page.getResourceResolver();
        Set<String> found = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String[]> todo = new ArrayDeque<>();
        Resource content = page.getChild("jcr:content");
        if (content == null) {
            return found;
        }
        scan(content, 0, found, todo, 0);
        // the template's structure: the header and footer every page of it shows
        String template = content.getValueMap().get("cq:template", String.class);
        if (template != null && template.startsWith("/conf/")) {
            if (rr.getResource(template) == null) {
                return null; // a template that cannot be read: what it shows is not known
            }
            // the template itself (its structure, its initial content, its policy mapping)
            found.add(template);
            Resource structure = rr.getResource(template + "/structure/jcr:content");
            if (structure != null) {
                scan(structure, 0, found, todo, 0);
            }
        }
        while (!todo.isEmpty()) {
            String[] next = todo.pollFirst();
            String path = next[0];
            int hop = Integer.parseInt(next[1]);
            if (!visited.add(path) || hop > MAX_HOPS || found.size() > MAX_PATHS) {
                continue;
            }
            // an experience fragment variation is a page; a content fragment keeps its data in jcr:content/data
            Resource r = rr.getResource(path);
            Resource inner = r == null ? null : r.getChild("jcr:content");
            if (inner != null) {
                scan(inner, 0, found, todo, hop);
            }
        }
        return found.size() > MAX_PATHS ? null : found;
    }

    private static void scan(Resource r, int depth, Set<String> found, Deque<String[]> todo, int hop) {
        if (depth > MAX_DEPTH || found.size() > MAX_PATHS) {
            return;
        }
        for (Object v : r.getValueMap().values()) {
            if (v instanceof String) {
                take((String) v, found, todo, hop);
            } else if (v instanceof String[]) {
                for (String s : (String[]) v) {
                    take(s, found, todo, hop);
                }
            }
        }
        for (Resource c : r.getChildren()) {
            if (!"cq:Page".equals(c.getValueMap().get("jcr:primaryType", String.class))) {
                scan(c, depth + 1, found, todo, hop);
            }
        }
    }

    private static void take(String value, Set<String> found, Deque<String[]> todo, int hop) {
        if (value == null || value.length() > 1024) {
            return;
        }
        for (String root : TRACKED) {
            if (value.startsWith(root) && value.length() > root.length() && value.indexOf('\n') < 0 && value.indexOf(' ') < 0) {
                String path = strip(value);
                if (found.add(path)) {
                    todo.addLast(new String[] {path, String.valueOf(hop + 1)});
                }
                return;
            }
        }
    }

    /** A path as published: without a query, a fragment, or a rendering's extension after the asset's own. */
    static String strip(String value) {
        int end = value.length();
        for (char c : new char[] {'?', '#'}) {
            int at = value.indexOf(c);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String path = value.substring(0, end);
        // an experience fragment linked as a page (…/master.html): the page
        if (path.startsWith("/content/experience-fragments/") && path.endsWith(".html")) {
            path = path.substring(0, path.length() - 5);
        }
        return path;
    }
}
