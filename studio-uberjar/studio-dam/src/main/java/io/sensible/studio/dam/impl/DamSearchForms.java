/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Search Forms, as AEM's Tools &gt; General &gt; Search Forms: the filters the Media search shows, in order. Without a
 * form of its own the search shows the default one (file type, tags, modified, file size, orientation, color); an admin
 * can reorder, rename and remove those and add filters on a metadata property: a list of values to tick
 * ({@code options}) or a text the property contains ({@code text}). Reset goes back to the default.
 *
 * <pre>
 * /conf/global/settings/cq/search/facets/assets               jcr:lastModified, jcr:lastModifiedBy
 *     items/&lt;n&gt;   type, label, description, property, root (tags), optionValues[], optionLabels[]
 * </pre>
 */
final class DamSearchForms {

    static final String STORE = "/conf/global/settings/cq/search/facets/assets";

    /** The filters the search knows; the first five are the default form, each at most once. */
    static final List<String> BUILT_IN = Arrays.asList("types", "tags", "modified", "size", "orientation", "colors");
    static final List<String> TYPES = Arrays.asList("types", "tags", "modified", "size", "orientation", "colors", "options", "text");

    static final int MAX_FACETS = 30;
    static final int MAX_OPTIONS = 50;
    /** Files read for the values of a property, at most. */
    static final int MAX_SCAN = 5000;

    /** A metadata property: {@code dc:creator}, {@code dam:status}, {@code xmpRights:Owner}. */
    static final Pattern PROPERTY = Pattern.compile("[A-Za-z][\\w.-]{0,63}(:[A-Za-z][\\w.-]{0,63})?");
    private static final Pattern TAG_ROOT = Pattern.compile("[\\w.-]+:([\\w.-]+(/[\\w.-]+)*)?");

    private DamSearchForms() {
    }

    static final class Facet {
        String type;
        String label;
        String description;
        String property;
        String root;
        final List<String[]> options = new ArrayList<>(); // {value, label}

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("label", label);
            if (description != null) {
                m.put("description", description);
            }
            if (property != null) {
                m.put("property", property);
            }
            if (root != null) {
                m.put("root", root);
            }
            if ("options".equals(type)) {
                List<Object> os = new ArrayList<>();
                for (String[] o : options) {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("value", o[0]);
                    om.put("label", o[1]);
                    os.add(om);
                }
                m.put("options", os);
            }
            return m;
        }
    }

    static String defaultLabel(String type) {
        switch (type) {
            case "types":
                return "File type";
            case "tags":
                return "Tags";
            case "modified":
                return "Modified";
            case "size":
                return "File size";
            case "orientation":
                return "Orientation";
            case "colors":
                return "Color";
            default:
                return "";
        }
    }

    static List<Facet> defaults() {
        List<Facet> out = new ArrayList<>();
        for (String t : BUILT_IN) {
            Facet f = new Facet();
            f.type = t;
            f.label = defaultLabel(t);
            out.add(f);
        }
        return out;
    }

    /** The form the search shows: the stored one, or the default. */
    static Map<String, Object> read(ResourceResolver resolver) {
        Resource form = resolver.getResource(STORE);
        Resource items = form == null ? null : form.getChild("items");
        List<Facet> facets = new ArrayList<>();
        if (items != null) {
            List<Resource> rs = new ArrayList<>();
            items.getChildren().forEach(rs::add);
            rs.sort((a, b) -> Integer.compare(a.getValueMap().get("order", 0), b.getValueMap().get("order", 0)));
            for (Resource r : rs) {
                ValueMap v = r.getValueMap();
                Facet f = new Facet();
                f.type = v.get("type", String.class);
                if (!TYPES.contains(f.type)) {
                    continue;
                }
                f.label = v.get("label", defaultLabel(f.type));
                f.description = v.get("description", String.class);
                f.property = v.get("property", String.class);
                f.root = v.get("root", String.class);
                String[] values = v.get("optionValues", new String[0]);
                String[] labels = v.get("optionLabels", new String[0]);
                for (int i = 0; i < values.length; i++) {
                    f.options.add(new String[] {values[i], i < labels.length && !labels[i].isEmpty() ? labels[i] : values[i]});
                }
                facets.add(f);
            }
        }
        boolean custom = items != null;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (Facet f : custom ? facets : defaults()) {
            list.add(f.toJson());
        }
        out.put("facets", list);
        out.put("custom", custom);
        if (custom) {
            ValueMap v = form.getValueMap();
            Calendar modified = v.get("jcr:lastModified", Calendar.class);
            if (modified != null) {
                out.put("modified", modified.toInstant().toString());
            }
            String by = v.get("jcr:lastModifiedBy", String.class);
            if (by != null) {
                out.put("modifiedBy", by);
            }
        }
        return out;
    }

    /** Checks the facets the editor sends (a JSON array) and stores them as the form. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> save(ResourceResolver resolver, Object json) throws DamApiServlet.ApiException, PersistenceException {
        if (!(json instanceof List)) {
            throw new DamApiServlet.ApiException(400, "facets must be a JSON array");
        }
        List<Object> in = (List<Object>) json;
        if (in.size() > MAX_FACETS) {
            throw new DamApiServlet.ApiException(400, "at most " + MAX_FACETS + " filters");
        }
        List<Facet> facets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : in) {
            if (!(o instanceof Map)) {
                throw new DamApiServlet.ApiException(400, "each filter is a JSON object");
            }
            Facet f = facet((Map<String, Object>) o);
            String key = BUILT_IN.contains(f.type) ? f.type : f.type + " " + f.property;
            if (!seen.add(key)) {
                throw new DamApiServlet.ApiException(400, BUILT_IN.contains(f.type)
                        ? "the " + defaultLabel(f.type).toLowerCase() + " filter is there twice"
                        : "two " + f.type + " filters on " + f.property);
            }
            facets.add(f);
        }
        Resource form = resolver.getResource(STORE);
        if (form == null) {
            Resource parent = ensure(resolver, STORE.substring(0, STORE.lastIndexOf('/')));
            form = resolver.create(parent, STORE.substring(STORE.lastIndexOf('/') + 1),
                    Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        Resource old = form.getChild("items");
        if (old != null) {
            resolver.delete(old);
        }
        Resource items = resolver.create(form, "items", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        for (int i = 0; i < facets.size(); i++) {
            Facet f = facets.get(i);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("jcr:primaryType", "nt:unstructured");
            p.put("order", i);
            p.put("type", f.type);
            p.put("label", f.label);
            if (f.description != null) {
                p.put("description", f.description);
            }
            if (f.property != null) {
                p.put("property", f.property);
            }
            if (f.root != null) {
                p.put("root", f.root);
            }
            if (!f.options.isEmpty()) {
                p.put("optionValues", f.options.stream().map(x -> x[0]).toArray(String[]::new));
                p.put("optionLabels", f.options.stream().map(x -> x[1]).toArray(String[]::new));
            }
            resolver.create(items, "item" + i, p);
        }
        ModifiableValueMap m = form.adaptTo(ModifiableValueMap.class);
        if (m != null) {
            m.put("jcr:lastModified", Calendar.getInstance());
            if (resolver.getUserID() != null) {
                m.put("jcr:lastModifiedBy", resolver.getUserID());
            }
        }
        return read(resolver);
    }

    /** Back to the default form. */
    static Map<String, Object> reset(ResourceResolver resolver) throws PersistenceException {
        Resource form = resolver.getResource(STORE);
        if (form != null) {
            resolver.delete(form);
        }
        return read(resolver);
    }

    private static Facet facet(Map<String, Object> m) throws DamApiServlet.ApiException {
        Facet f = new Facet();
        f.type = str(m.get("type"));
        if (!TYPES.contains(f.type)) {
            throw new DamApiServlet.ApiException(400, "a filter is one of " + String.join(", ", TYPES));
        }
        String label = str(m.get("label"));
        f.label = label == null ? defaultLabel(f.type) : label.length() > 80 ? label.substring(0, 80) : label;
        String description = str(m.get("description"));
        f.description = description == null ? null : description.length() > 300 ? description.substring(0, 300) : description;
        if ("tags".equals(f.type)) {
            f.root = str(m.get("root"));
            if (f.root != null && !TAG_ROOT.matcher(f.root).matches()) {
                throw new DamApiServlet.ApiException(400, "the tags to choose from start at a tag ID, such as wknd: or wknd:activity");
            }
        }
        if ("options".equals(f.type) || "text".equals(f.type)) {
            f.property = str(m.get("property"));
            if (f.property == null || !PROPERTY.matcher(f.property).matches()) {
                throw new DamApiServlet.ApiException(400, "\"" + f.label + "\" needs a metadata property, such as dc:creator");
            }
            if (f.label.isEmpty()) {
                f.label = f.property;
            }
        }
        if ("options".equals(f.type)) {
            Object os = m.get("options");
            if (os instanceof List) {
                Set<String> values = new HashSet<>();
                for (Object o : (List<?>) os) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    String value = str(((Map<?, ?>) o).get("value"));
                    String label2 = str(((Map<?, ?>) o).get("label"));
                    if (value != null && values.add(value)) {
                        f.options.add(new String[] {value, label2 == null ? value : label2});
                    }
                }
            }
            if (f.options.isEmpty()) {
                throw new DamApiServlet.ApiException(400, "\"" + f.label + "\" needs at least one value to choose");
            }
            if (f.options.size() > MAX_OPTIONS) {
                throw new DamApiServlet.ApiException(400, "\"" + f.label + "\" has more than " + MAX_OPTIONS + " values");
            }
        }
        return f;
    }

    /**
     * The values a metadata property has on the files below a folder, the most used first (for choosing the values
     * of an {@code options} filter).
     */
    static Map<String, Object> values(Resource scope, String property, int max) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(scope);
        int scanned = 0;
        boolean truncated = false;
        outer:
        while (!queue.isEmpty()) {
            for (Resource c : queue.poll().getChildren()) {
                if (DamItems.isAsset(c)) {
                    if (++scanned > MAX_SCAN) {
                        truncated = true;
                        break outer;
                    }
                    Resource md = c.getChild("jcr:content/metadata");
                    if (md != null) {
                        for (String v : strings(md.getValueMap().get(property))) {
                            if (!v.isEmpty() && v.length() <= 200) {
                                counts.merge(v, 1, Integer::sum);
                            }
                        }
                    }
                } else if (DamItems.isFolder(c)) {
                    queue.add(c);
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareToIgnoreCase(b.getKey()));
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted.subList(0, Math.min(max, sorted.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", e.getKey());
            m.put("count", e.getValue());
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("property", property);
        result.put("values", out);
        result.put("more", sorted.size() > max);
        result.put("truncated", truncated);
        return result;
    }

    /** A property value as strings: one per item of a multi-value property. */
    static List<String> strings(Object v) {
        if (v == null) {
            return Collections.emptyList();
        }
        if (v instanceof Object[]) {
            List<String> out = new ArrayList<>();
            for (Object o : (Object[]) v) {
                if (o != null) {
                    out.add(o instanceof Calendar ? ((Calendar) o).toInstant().toString() : o.toString());
                }
            }
            return out;
        }
        return Collections.singletonList(v instanceof Calendar ? ((Calendar) v).toInstant().toString() : v.toString());
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : path.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }
}
