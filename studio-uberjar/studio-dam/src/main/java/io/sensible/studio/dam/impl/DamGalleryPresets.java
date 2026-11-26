/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Gallery presets (AEM's viewer presets for image sets): how a gallery looks, saved under a name
 * and chosen in the Responsive Image component or its policy. Kept at {@value #ROOT}/&lt;name&gt;:
 * {@code jcr:title}, {@code thumbs} (below, left, right, dots, none), {@code arrows} (true/false),
 * {@code ratio} (16:9, 3:2, 4:3, 1:1 or empty: each picture's own), {@code zoom} (true/false).
 * Visitors of the published site read them (repoinit allows it): they only describe a look.
 */
final class DamGalleryPresets {

    static final String ROOT = "/conf/global/settings/dam/gallerypresets";
    static final List<String> THUMBS = Collections.unmodifiableList(Arrays.asList("below", "left", "right", "dots", "none"));
    static final List<String> RATIOS = Collections.unmodifiableList(Arrays.asList("", "16:9", "3:2", "4:3", "1:1"));

    private DamGalleryPresets() {
    }

    static List<Map<String, Object>> list(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = resolver.getResource(ROOT);
        if (root != null) {
            for (Resource r : root.getChildren()) {
                if (!r.getName().startsWith("jcr:") && !r.getName().startsWith("rep:")) {
                    out.add(describe(r));
                }
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    static Map<String, Object> describe(Resource r) {
        ValueMap v = r.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.getName());
        m.put("path", r.getPath());
        m.put("title", v.get("jcr:title", r.getName()));
        m.put("thumbs", v.get("thumbs", "below"));
        m.put("arrows", "true".equals(v.get("arrows", "false")));
        m.put("ratio", v.get("ratio", ""));
        m.put("zoom", "true".equals(v.get("zoom", "false")));
        return m;
    }

    /** Creates or changes a preset; its name is made from the title when it is new. */
    static Map<String, Object> save(ResourceResolver resolver, String name, String title, String thumbs, String arrows, String ratio,
            String zoom) throws PersistenceException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 100) {
            throw new IllegalArgumentException("A preset needs a title (at most 100 characters)");
        }
        String th = thumbs == null || thumbs.isEmpty() ? "below" : thumbs;
        if (!THUMBS.contains(th)) {
            throw new IllegalArgumentException("thumbs is one of " + THUMBS);
        }
        String ra = ratio == null ? "" : ratio;
        if (!RATIOS.contains(ra)) {
            throw new IllegalArgumentException("ratio is one of 16:9, 3:2, 4:3, 1:1 or empty");
        }
        String n = name == null || name.trim().isEmpty() ? DamApiServlet.nodeName(t) : name.trim();
        if (n == null || !n.matches("^[a-z0-9][a-z0-9_-]{0,63}$")) {
            throw new IllegalArgumentException("A preset name uses a-z, 0-9, '_' and '-'");
        }
        Resource root = ensureRoot(resolver);
        Resource r = root.getChild(n);
        if (r == null) {
            r = resolver.create(root, n, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap mv = r.adaptTo(ModifiableValueMap.class);
        mv.put("jcr:title", t);
        mv.put("thumbs", th);
        mv.put("arrows", String.valueOf("true".equals(arrows)));
        mv.put("ratio", ra);
        mv.put("zoom", String.valueOf("true".equals(zoom)));
        return describe(r);
    }

    static void delete(ResourceResolver resolver, String name) throws PersistenceException {
        Resource r = name == null || !name.matches("^[a-z0-9][a-z0-9_-]{0,63}$") ? null : resolver.getResource(ROOT + "/" + name);
        if (r == null) {
            throw new IllegalArgumentException("No gallery preset " + name);
        }
        resolver.delete(r);
    }

    private static Resource ensureRoot(ResourceResolver resolver) throws PersistenceException {
        Resource existing = resolver.getResource(ROOT);
        if (existing != null) {
            return existing;
        }
        Resource r = resolver.getResource("/");
        for (String seg : ROOT.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }
}
