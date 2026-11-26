/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Search Forms for the Pages console, as AEM's "Sites Admin Search Rail" in Tools &gt; General &gt; Search Forms: the
 * filters the Filter rail shows, in order. Without a form of its own the rail shows the default one (path, template,
 * tags, modified, linked copy, locked); an admin can reorder, rename and remove those and add filters on a property of
 * the pages ({@code jcr:content}): a list of values to tick ({@code options}) or a text the property contains
 * ({@code text}). Reset goes back to the default. The full-text field is always there.
 *
 * <pre>
 * /conf/global/settings/cq/search/facets/sites               jcr:lastModified, jcr:lastModifiedBy
 *     items/&lt;n&gt;   order, type, label, description, property, root (tags), optionValues[], optionLabels[]
 * </pre>
 */
final class SiteSearchForms {

    static final String STORE = "/conf/global/settings/cq/search/facets/sites";

    /** The filters the rail knows built in, the default form in this order; each at most once. */
    static final List<String> BUILT_IN = Arrays.asList("path", "template", "tags", "modified", "livecopy", "locked");
    static final List<String> TYPES = Arrays.asList("path", "template", "tags", "modified", "livecopy", "locked", "options", "text");

    static final int MAX_FACETS = 30;
    static final int MAX_OPTIONS = 50;
    /** Pages read for the values of a property, at most. */
    static final int MAX_SCAN = 5000;

    /** A property of a page's jcr:content: {@code cq:productId}, {@code pageTitle}, {@code wknd:region}. */
    static final Pattern PROPERTY = Pattern.compile("[A-Za-z][\\w.-]{0,63}(:[A-Za-z][\\w.-]{0,63})?");
    private static final Pattern TAG_ROOT = Pattern.compile("[\\w.-]+:([\\w.-]+(/[\\w.-]+)*)?");

    private SiteSearchForms() {
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
            m.put("description", description);
            m.put("property", property);
            m.put("root", root);
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
            case "path":
                return "Path";
            case "template":
                return "Template";
            case "tags":
                return "Tags";
            case "modified":
                return "Modified";
            case "livecopy":
                return "Linked copy";
            case "locked":
                return "Locked pages only";
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

    /** The form the rail shows: the stored one, or the default; {@code canEdit} says whether the user may change it. */
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
            out.put("modifiedBy", v.get("jcr:lastModifiedBy", String.class));
        }
        out.put("canEdit", isAdmin(resolver));
        return out;
    }

    /** Checks the facets the editor sends (a JSON array) and stores them as the form; admin only. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> save(ResourceResolver resolver, String json) throws PersistenceException {
        admin(resolver);
        Object parsed = JsonReader.parse(json == null ? "" : json);
        if (!(parsed instanceof List)) {
            throw new IllegalArgumentException("facets must be a JSON array");
        }
        List<Object> in = (List<Object>) parsed;
        if (in.size() > MAX_FACETS) {
            throw new IllegalArgumentException("At most " + MAX_FACETS + " filters");
        }
        List<Facet> facets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : in) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("Each filter is a JSON object");
            }
            Facet f = facet((Map<String, Object>) o);
            String key = BUILT_IN.contains(f.type) ? f.type : f.type + " " + f.property;
            if (!seen.add(key)) {
                throw new IllegalArgumentException(BUILT_IN.contains(f.type)
                        ? "The " + defaultLabel(f.type).toLowerCase() + " filter is there twice"
                        : "Two " + f.type + " filters on " + f.property);
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
        resolver.commit();
        return read(resolver);
    }

    /** Back to the default form; admin only. */
    static Map<String, Object> reset(ResourceResolver resolver) throws PersistenceException {
        admin(resolver);
        Resource form = resolver.getResource(STORE);
        if (form != null) {
            resolver.delete(form);
            resolver.commit();
        }
        return read(resolver);
    }

    private static Facet facet(Map<String, Object> m) {
        Facet f = new Facet();
        f.type = str(m.get("type"));
        if (!TYPES.contains(f.type)) {
            throw new IllegalArgumentException("A filter is one of " + String.join(", ", TYPES));
        }
        String label = str(m.get("label"));
        f.label = label == null ? defaultLabel(f.type) : label.length() > 80 ? label.substring(0, 80) : label;
        String description = str(m.get("description"));
        f.description = description == null ? null : description.length() > 300 ? description.substring(0, 300) : description;
        if ("tags".equals(f.type)) {
            f.root = str(m.get("root"));
            if (f.root != null && !TAG_ROOT.matcher(f.root).matches()) {
                throw new IllegalArgumentException("The tags to choose from start at a tag ID, such as wknd: or wknd:activity");
            }
        }
        if ("options".equals(f.type) || "text".equals(f.type)) {
            f.property = str(m.get("property"));
            if (f.property == null || !PROPERTY.matcher(f.property).matches()) {
                throw new IllegalArgumentException("\"" + f.label + "\" needs a page property, such as pageTitle or cq:productId");
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
                throw new IllegalArgumentException("\"" + f.label + "\" needs at least one value to choose");
            }
            if (f.options.size() > MAX_OPTIONS) {
                throw new IllegalArgumentException("\"" + f.label + "\" has more than " + MAX_OPTIONS + " values");
            }
        }
        return f;
    }

    /** The values a property has on the pages below a path, the most used first (for an {@code options} filter). */
    static Map<String, Object> values(ResourceResolver resolver, String path, String property, int max) {
        if (property == null || !PROPERTY.matcher(property).matches()) {
            throw new IllegalArgumentException("Not a page property: " + property);
        }
        Resource scope = resolver.getResource(path == null || path.isEmpty() ? "/content" : path);
        if (scope == null) {
            throw new IllegalArgumentException("Nothing at " + path);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(scope);
        int scanned = 0;
        boolean truncated = false;
        outer:
        while (!queue.isEmpty()) {
            for (Resource c : queue.poll().getChildren()) {
                if ("jcr:content".equals(c.getName())) {
                    continue;
                }
                boolean page = SiteRail.isPage(c);
                if (page) {
                    if (++scanned > MAX_SCAN) {
                        truncated = true;
                        break outer;
                    }
                    Resource content = c.getChild("jcr:content");
                    if (content != null) {
                        for (String v : strings(content.getValueMap().get(property))) {
                            if (!v.isEmpty() && v.length() <= 200) {
                                counts.merge(v, 1, Integer::sum);
                            }
                        }
                    }
                }
                if (page || SiteRail.isFolder(c)) {
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

    /** admin, or a user with every right on /conf (who could write the form there anyway). */
    static boolean isAdmin(ResourceResolver resolver) {
        String user = resolver == null ? null : resolver.getUserID();
        if ("admin".equals(user)) {
            return true;
        }
        if (user == null || "anonymous".equals(user)) {
            return false;
        }
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return false;
        }
        try {
            AccessControlManager acm = session.getAccessControlManager();
            return session.nodeExists("/conf") && acm.hasPrivileges("/conf", new Privilege[] {acm.privilegeFromName(Privilege.JCR_ALL)});
        } catch (RepositoryException | RuntimeException e) {
            return false;
        }
    }

    private static void admin(ResourceResolver resolver) {
        if (!isAdmin(resolver)) {
            throw new SecurityException("Only admin can change the search form");
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        // from the nearest existing ancestor: a user may not read "/"
        String existing = path;
        while (resolver.getResource(existing) == null) {
            int slash = existing.lastIndexOf('/');
            if (slash <= 0) {
                throw new IllegalArgumentException("Cannot create " + path);
            }
            existing = existing.substring(0, slash);
        }
        Resource r = resolver.getResource(existing);
        if (existing.length() < path.length()) {
            for (String seg : path.substring(existing.length() + 1).split("/")) {
                Resource c = r.getChild(seg);
                r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
            }
        }
        return r;
    }
}
