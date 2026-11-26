/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Rewrites references to moved or renamed assets and folders, like AEM's move wizard does for
 * the pages the author ticks: every string property below those pages that names an old path
 * (on its own, as a prefix of a longer path such as a rendition URL, or inside rich text) gets the
 * new path.
 */
final class DamReferences {

    /** Nodes visited per call at most; a page is a few hundred. */
    static final int MAX_NODES = 100_000;

    private DamReferences() {
    }

    /** Thrown when a page cannot be changed (no permission, outside the allowed trees). */
    static final class AdjustException extends Exception {
        private static final long serialVersionUID = 1L;

        AdjustException(String message) {
            super(message);
        }
    }

    /**
     * Rewrites {@code moves} (old path → new path) in the subtrees of {@code roots}.
     *
     * @return number of properties changed
     */
    static int rewrite(ResourceResolver resolver, List<String> roots, Map<String, String> moves) throws AdjustException {
        if (moves.isEmpty() || roots.isEmpty()) {
            return 0;
        }
        Map<Pattern, String> patterns = new LinkedHashMap<>();
        for (Map.Entry<String, String> m : moves.entrySet()) {
            patterns.put(pattern(m.getKey()), Matcher.quoteReplacement(m.getValue()));
        }
        int changed = 0;
        int visited = 0;
        for (String root : roots) {
            Resource start = resolver.getResource(root);
            if (start == null) {
                continue;
            }
            if ("cq:Page".equals(start.getResourceType()) && start.getChild("jcr:content") != null) {
                start = start.getChild("jcr:content"); // the page itself, not the pages below it
            }
            Deque<Resource> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Resource r = queue.poll();
                if (++visited > MAX_NODES) {
                    throw new AdjustException("too many nodes below " + root);
                }
                changed += rewriteProperties(r, patterns);
                for (Resource child : r.getChildren()) {
                    queue.add(child);
                }
            }
        }
        return changed;
    }

    /**
     * An old path where it ends: followed by nothing, a path separator or anything that cannot
     * continue a node name (quote, space, query, anchor, bracket...). "/a.jpg" matches in
     * "/a.jpg", "/a.jpg/_jcr_content/renditions/x" and {@code src="/a.jpg"}, not in "/a.jpg2".
     */
    static Pattern pattern(String oldPath) {
        return Pattern.compile(Pattern.quote(oldPath) + "(?![A-Za-z0-9_\\-])");
    }

    private static int rewriteProperties(Resource r, Map<Pattern, String> patterns) throws AdjustException {
        ValueMap vm = r.getValueMap();
        Map<String, Object> updates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("jcr:") && !"jcr:description".equals(key)) {
                continue; // system properties (types, uuids, dates); never paths an author set
            }
            Object v = e.getValue();
            if (v instanceof String) {
                String next = replace((String) v, patterns);
                if (!next.equals(v)) {
                    updates.put(key, next);
                }
            } else if (v instanceof String[]) {
                String[] values = (String[]) v;
                String[] next = new String[values.length];
                boolean diff = false;
                for (int i = 0; i < values.length; i++) {
                    next[i] = replace(values[i], patterns);
                    diff |= !next[i].equals(values[i]);
                }
                if (diff) {
                    updates.put(key, next);
                }
            }
        }
        if (updates.isEmpty()) {
            return 0;
        }
        ModifiableValueMap mvm = r.adaptTo(ModifiableValueMap.class);
        if (mvm == null) {
            throw new AdjustException("no permission to adjust references in " + r.getPath());
        }
        mvm.putAll(updates);
        return updates.size();
    }

    static String replace(String value, Map<Pattern, String> patterns) {
        if (value == null || value.indexOf('/') < 0) {
            return value;
        }
        String out = value;
        for (Map.Entry<Pattern, String> p : patterns.entrySet()) {
            out = p.getKey().matcher(out).replaceAll(p.getValue());
        }
        return out;
    }

    /** Resources holding references (or pages) the author chose to adjust: inside /content or /conf, not inside the DAM paths moved. */
    static List<String> checkedRoots(List<String> requested, Iterable<String> movedFrom) throws AdjustException {
        List<String> roots = new ArrayList<>();
        for (String raw : requested) {
            String p = raw == null ? "" : raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.contains("..") || !(p.startsWith("/content/") || p.startsWith("/conf/"))) {
                throw new AdjustException("cannot adjust references in " + p);
            }
            for (String from : movedFrom) {
                if (p.equals(from) || p.startsWith(from + "/")) {
                    throw new AdjustException(p + " is being moved itself");
                }
            }
            if (!roots.contains(p)) {
                roots.add(p);
            }
        }
        return roots;
    }
}
