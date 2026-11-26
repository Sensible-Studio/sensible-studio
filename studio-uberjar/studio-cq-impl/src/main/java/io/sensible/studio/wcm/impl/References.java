/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.Page;

/**
 * Finds and rewrites references to a content path: string properties anywhere below the search
 * roots whose value is the path, a path below it ({@code /content/site/page/child}), a rendition of
 * it ({@code /content/site/page.html}) or contains one of those (links in rich text).
 *
 * <p>The repository is walked rather than queried: Sling Starter has no full-text index, and the
 * walk is cheap for the content sizes Sensible Studio targets.
 */
final class References {

    /** One property that mentions the path. */
    static final class Ref {
        final String resourcePath;
        final String property;
        final String pagePath;
        final String pageTitle;
        final String value;

        Ref(String resourcePath, String property, String pagePath, String pageTitle, String value) {
            this.resourcePath = resourcePath;
            this.property = property;
            this.pagePath = pagePath;
            this.pageTitle = pageTitle;
            this.value = value;
        }
    }

    static final String[] DEFAULT_ROOTS = {"/content", "/conf"};
    private static final int MAX_REFS = 2000;

    private References() {
    }

    /**
     * @param inside {@code true} to also report references from inside {@code path}'s own subtree
     *               (needed when moving, where they must be rewritten too)
     */
    static List<Ref> find(ResourceResolver resolver, String path, String[] roots, boolean inside) {
        List<Ref> out = new ArrayList<>();
        PageManagerImpl pm = new PageManagerImpl(resolver);
        for (String root : roots) {
            Resource r = resolver.getResource(root);
            if (r != null) {
                walk(r, path, inside, pm, out);
            }
        }
        return out;
    }

    private static void walk(Resource r, String path, boolean inside, PageManagerImpl pm, List<Ref> out) {
        if (out.size() >= MAX_REFS) {
            return;
        }
        String own = r.getPath();
        boolean isInside = own.equals(path) || own.startsWith(path + "/");
        if (inside || !isInside) {
            for (Map.Entry<String, Object> e : r.getValueMap().entrySet()) {
                String key = e.getKey();
                if (key.startsWith("jcr:") && !"jcr:description".equals(key) && !"jcr:title".equals(key)) {
                    continue; // jcr:primaryType, jcr:uuid, jcr:data ...
                }
                String hit = firstMatch(e.getValue(), path);
                if (hit != null) {
                    Page page = pm.getContainingPage(r);
                    out.add(new Ref(own, key, page == null ? null : page.getPath(),
                            page == null ? null : page.getTitle(), hit));
                }
            }
        }
        for (Resource child : r.getChildren()) {
            // binaries and DAM renditions never hold links
            if (!"renditions".equals(child.getName()) && !"rep:policy".equals(child.getName())) {
                walk(child, path, inside, pm, out);
            }
        }
    }

    private static String firstMatch(Object value, String path) {
        if (value instanceof String) {
            return mentions((String) value, path) ? (String) value : null;
        }
        if (value instanceof String[]) {
            for (String s : (String[]) value) {
                if (mentions(s, path)) {
                    return s;
                }
            }
        }
        return null;
    }

    /** @return {@code true} if {@code value} contains {@code path} as a whole path (not a prefix of a longer name) */
    static boolean mentions(String value, String path) {
        for (int i = value.indexOf(path); i >= 0; i = value.indexOf(path, i + 1)) {
            if (isBoundary(value, i, path.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBoundary(String value, int start, int length) {
        if (start > 0) {
            char before = value.charAt(start - 1);
            if (Character.isLetterOrDigit(before) || before == '/' || before == '-' || before == '_') {
                return false;
            }
        }
        int end = start + length;
        if (end == value.length()) {
            return true;
        }
        char after = value.charAt(end);
        return !(Character.isLetterOrDigit(after) || after == '-' || after == '_' || after == ':');
    }

    /** Replaces whole-path occurrences of {@code from} with {@code to}. */
    static String rewrite(String value, String from, String to) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int i = value.indexOf(from); i >= 0; i = value.indexOf(from, i + from.length())) {
            if (isBoundary(value, i, from.length())) {
                sb.append(value, last, i).append(to);
                last = i + from.length();
            }
        }
        return sb.append(value.substring(last)).toString();
    }

    /**
     * Rewrites {@code from} to {@code to} in the given references. References whose resource lived
     * inside {@code from} are looked up at their new place.
     *
     * @return number of properties changed
     */
    static int adjust(ResourceResolver resolver, Collection<Ref> refs, String from, String to) {
        int changed = 0;
        for (Ref ref : refs) {
            String at = ref.resourcePath.equals(from) || ref.resourcePath.startsWith(from + "/")
                    ? to + ref.resourcePath.substring(from.length()) : ref.resourcePath;
            Resource r = resolver.getResource(at);
            ModifiableValueMap mvm = r == null ? null : r.adaptTo(ModifiableValueMap.class);
            if (mvm == null) {
                continue;
            }
            Object v = mvm.get(ref.property);
            if (v instanceof String) {
                String nv = rewrite((String) v, from, to);
                if (!nv.equals(v)) {
                    mvm.put(ref.property, nv);
                    changed++;
                }
            } else if (v instanceof String[]) {
                String[] arr = ((String[]) v).clone();
                boolean any = false;
                for (int i = 0; i < arr.length; i++) {
                    String nv = rewrite(arr[i], from, to);
                    any |= !nv.equals(arr[i]);
                    arr[i] = nv;
                }
                if (any) {
                    mvm.put(ref.property, arr);
                    changed++;
                }
            }
        }
        return changed;
    }
}
