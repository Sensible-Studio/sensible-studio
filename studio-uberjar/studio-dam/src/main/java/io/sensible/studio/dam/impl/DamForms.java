/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * The two other forms of AEM's Tools &gt; Assets, made the way {@link DamSchemas} makes metadata schemas
 * (tabs of fields in {@code studio:definition}):
 * <ul>
 *   <li><b>Folder Metadata Schemas</b>: the fields a folder's Properties add; the folder's schema (or its nearest
 *       ancestor's) applies, the values live in {@code <folder>/jcr:content/metadata};</li>
 *   <li><b>Metadata Profiles</b>: default values ({@code studio:values}) the files of a folder (and its subfolders)
 *       get when they are uploaded. A profile fills what the file did not bring: a title or keywords in its
 *       XMP, or an author's edit, stay.</li>
 * </ul>
 * <pre>
 * /conf/global/settings/dam/adminui-extension/foldermetadataschema/&lt;name&gt;   jcr:title, studio:definition
 * /conf/global/settings/dam/adminui-extension/metadataprofile/&lt;name&gt;        jcr:title, studio:definition, studio:values
 * &lt;folder&gt;/jcr:content   folderMetadataSchema = &lt;path&gt;, metadataProfile = &lt;path&gt;
 * </pre>
 */
final class DamForms {

    enum Kind {
        FOLDER_SCHEMA("folderSchema", "/conf/global/settings/dam/adminui-extension/foldermetadataschema", "folderMetadataSchema", false,
                "folder metadata schema"),
        PROFILE("profile", "/conf/global/settings/dam/adminui-extension/metadataprofile", "metadataProfile", true, "metadata profile");

        final String id;
        final String root;
        /** The folder property naming the form. */
        final String property;
        /** The form carries default values. */
        final boolean values;
        final String noun;

        Kind(String id, String root, String property, boolean values, String noun) {
            this.id = id;
            this.root = root;
            this.property = property;
            this.values = values;
            this.noun = noun;
        }

        static Kind of(String id) throws DamApiServlet.ApiException {
            for (Kind k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
            throw new DamApiServlet.ApiException(400, "kind is folderSchema or profile");
        }
    }

    static final String PN_VALUES = "studio:values";
    /** Files a profile is applied to at most in one request. */
    static final int MAX_APPLY = 5000;

    private DamForms() {
    }

    // ------------------------------------------------------------------ the forms

    static List<Map<String, Object>> list(ResourceResolver resolver, Kind kind) {
        Resource root = resolver.getResource(kind.root);
        List<Map<String, Object>> out = new ArrayList<>();
        if (root != null) {
            for (Resource s : root.getChildren()) {
                if (s.getValueMap().containsKey(DamSchemas.PN_DEFINITION)) {
                    out.add(describe(s, kind));
                }
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    static Map<String, Object> describe(Resource form, Kind kind) {
        Map<String, Object> m = DamSchemas.describe(form);
        if (kind.values) {
            m.put("values", values(form));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> values(Resource form) {
        try {
            Object v = Json.parse(form.getValueMap().get(PN_VALUES, "{}"));
            return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
        } catch (IllegalArgumentException e) {
            return Collections.emptyMap();
        }
    }

    /** The form of an item's folder (a folder's own, or the nearest ancestor's), or null. */
    static Resource formFor(Resource item, Kind kind) {
        if (item == null) {
            return null;
        }
        Resource start = DamItems.isAsset(item) ? item.getParent() : item;
        for (Resource f = start; f != null && DamItems.inDam(f.getPath()); f = f.getParent()) {
            Resource c = f.getChild("jcr:content");
            String p = c == null ? null : c.getValueMap().get(kind.property, String.class);
            if (p != null && !p.isEmpty()) {
                return p.startsWith(kind.root + "/") ? item.getResourceResolver().getResource(p) : null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> save(ResourceResolver resolver, Kind kind, String name, String title, Object definition)
            throws DamApiServlet.ApiException, PersistenceException {
        if (name == null || !DamProfiles.NAME.matcher(name).matches()) {
            throw new DamApiServlet.ApiException(400, "names use a-z, 0-9, '.', '_' and '-'");
        }
        Map<String, Object> def = DamSchemas.clean(definition);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:title", title == null || title.trim().isEmpty() ? name : title.trim());
        props.put(DamSchemas.PN_DEFINITION, JsonOut.write(def));
        if (kind.values) {
            Object raw = definition instanceof Map ? ((Map<String, Object>) definition).get("values") : null;
            props.put(PN_VALUES, JsonOut.write(cleanValues(raw, (List<Object>) def.get("tabs"))));
        }
        Resource root = ensure(resolver, kind.root);
        Resource s = root.getChild(name);
        if (s == null) {
            props.put("jcr:primaryType", "nt:unstructured");
            s = resolver.create(root, name, props);
        } else {
            ModifiableValueMap m = s.adaptTo(ModifiableValueMap.class);
            if (m == null) {
                throw new DamApiServlet.ApiException(403, "cannot change " + s.getPath());
            }
            m.putAll(props);
        }
        return describe(s, kind);
    }

    /**
     * A profile's default values, checked against its fields: a value per field property, of the field's type
     * (text, a list for several values, a number, true/false, {@code {"date": "2026-11-23"}}, an option of a
     * dropdown). Empty values are left out.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> cleanValues(Object raw, List<Object> tabs) throws DamApiServlet.ApiException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map)) {
            throw new DamApiServlet.ApiException(400, "values must be a JSON object");
        }
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Object t : tabs) {
            for (Object f : (List<Object>) ((Map<String, Object>) t).get("fields")) {
                Map<String, Object> field = (Map<String, Object>) f;
                fields.put(String.valueOf(field.get("property")), field);
            }
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            Map<String, Object> field = fields.get(e.getKey());
            if (field == null) {
                throw new DamApiServlet.ApiException(400, e.getKey() + " is not a field of the profile");
            }
            Object v = e.getValue();
            if (v == null || "".equals(v) || v instanceof List && ((List<Object>) v).isEmpty()) {
                continue;
            }
            String label = String.valueOf(field.get("label"));
            switch (String.valueOf(field.get("type"))) {
                case "multi": {
                    List<String> list = new ArrayList<>();
                    for (Object o : v instanceof List ? (List<Object>) v : Collections.singletonList(v)) {
                        String s = String.valueOf(o).trim();
                        if (!s.isEmpty() && !list.contains(s)) {
                            list.add(s);
                        }
                    }
                    if (!list.isEmpty()) {
                        out.put(e.getKey(), list);
                    }
                    break;
                }
                case "number":
                    if (v instanceof Number) {
                        out.put(e.getKey(), v);
                    } else {
                        try {
                            out.put(e.getKey(), Double.valueOf(String.valueOf(v).trim()));
                        } catch (NumberFormatException x) {
                            throw new DamApiServlet.ApiException(400, label + ": not a number");
                        }
                    }
                    break;
                case "checkbox":
                    out.put(e.getKey(), Boolean.TRUE.equals(v) || "true".equals(v));
                    break;
                case "date": {
                    Object d = v instanceof Map ? ((Map<String, Object>) v).get("date") : v;
                    String s = String.valueOf(d).trim();
                    DamSchemas.date(s);
                    out.put(e.getKey(), Collections.singletonMap("date", s));
                    break;
                }
                case "select": {
                    String s = String.valueOf(v);
                    boolean known = false;
                    for (Object o : (List<Object>) field.get("options")) {
                        known |= s.equals(String.valueOf(((Map<String, Object>) o).get("value")));
                    }
                    if (!known) {
                        throw new DamApiServlet.ApiException(400, label + ": \"" + s + "\" is not one of its options");
                    }
                    out.put(e.getKey(), s);
                    break;
                }
                default: {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty()) {
                        out.put(e.getKey(), s);
                    }
                }
            }
        }
        return out;
    }

    static void delete(ResourceResolver resolver, Kind kind, String name) throws DamApiServlet.ApiException, PersistenceException {
        Resource s = name == null || name.contains("/") ? null : resolver.getResource(kind.root + "/" + name);
        if (s == null) {
            throw new DamApiServlet.ApiException(404, "no " + kind.noun + " " + name);
        }
        resolver.delete(s);
    }

    static void assign(Resource folder, Kind kind, String path) throws DamApiServlet.ApiException, PersistenceException {
        if (path != null && !path.isEmpty() && (!path.startsWith(kind.root + "/") || folder.getResourceResolver().getResource(path) == null)) {
            throw new DamApiServlet.ApiException(404, "no " + kind.noun + " " + path);
        }
        ModifiableValueMap m = content(folder).adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + folder.getPath());
        }
        if (path == null || path.isEmpty()) {
            m.remove(kind.property);
        } else {
            m.put(kind.property, path);
        }
    }

    // ------------------------------------------------------------------ metadata profiles at work

    /**
     * Gives a file the default values of its folder's profile for the properties it has no value for.
     * Returns how many properties were written.
     */
    static int fill(Resource asset) throws DamApiServlet.ApiException, PersistenceException {
        if (!DamItems.isAsset(asset)) {
            return 0;
        }
        Resource profile = formFor(asset, Kind.PROFILE);
        Map<String, Object> defaults = profile == null ? Collections.emptyMap() : values(profile);
        if (defaults.isEmpty()) {
            return 0;
        }
        Resource content = asset.getChild("jcr:content");
        if (content == null) {
            return 0;
        }
        Resource md = content.getChild("metadata");
        if (md == null) {
            md = asset.getResourceResolver().create(content, "metadata", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap m = md.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            return 0;
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            if (empty(m.get(e.getKey()))) {
                missing.put(e.getKey(), e.getValue());
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }
        DamSchemas.write(m, missing);
        return missing.size();
    }

    private static boolean empty(Object v) {
        return v == null || "".equals(v) || v instanceof Object[] && ((Object[]) v).length == 0;
    }

    /** {@link #fill} for the files already in a folder and its subfolders. */
    static Map<String, Object> applyProfile(ResourceResolver resolver, Resource folder) throws DamApiServlet.ApiException, PersistenceException {
        int visited = 0;
        int changed = 0;
        boolean truncated = false;
        Deque<Resource> todo = new ArrayDeque<>();
        todo.add(folder);
        while (!todo.isEmpty()) {
            Resource f = todo.poll();
            for (Resource child : f.getChildren()) {
                if (child.getName().contains(":")) {
                    continue;
                }
                if (DamItems.isAsset(child)) {
                    if (visited >= MAX_APPLY) {
                        truncated = true;
                        break;
                    }
                    visited++;
                    if (fill(child) > 0) {
                        changed++;
                        if (changed % 200 == 0) {
                            resolver.commit();
                        }
                    }
                } else if (DamItems.isFolder(child)) {
                    todo.add(child);
                }
            }
            if (truncated) {
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", visited);
        out.put("changed", changed);
        out.put("truncated", truncated);
        return out;
    }

    // ------------------------------------------------------------------ folder metadata

    /** The folder's metadata schema (its own or inherited) and the folder's values for its fields. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> folderMetadata(Resource folder) {
        Map<String, Object> out = new LinkedHashMap<>();
        Resource schema = formFor(folder, Kind.FOLDER_SCHEMA);
        out.put("schema", schema == null ? null : describe(schema, Kind.FOLDER_SCHEMA));
        Map<String, Object> values = new LinkedHashMap<>();
        Resource md = folder.getChild("jcr:content/metadata");
        if (schema != null && md != null) {
            ValueMap vm = md.getValueMap();
            Object tabs = DamSchemas.describe(schema).get("tabs");
            for (Object t : tabs instanceof List ? (List<Object>) tabs : Collections.emptyList()) {
                for (Object f : (List<Object>) ((Map<String, Object>) t).get("fields")) {
                    String p = String.valueOf(((Map<String, Object>) f).get("property"));
                    Object v = vm.get(p);
                    if (v != null) {
                        values.put(p, json(v));
                    }
                }
            }
        }
        out.put("values", values);
        return out;
    }

    private static Object json(Object v) {
        if (v instanceof Calendar) {
            Calendar c = (Calendar) v;
            return c instanceof GregorianCalendar ? ((GregorianCalendar) c).toZonedDateTime().toOffsetDateTime().toString()
                    : c.toInstant().toString();
        }
        if (v instanceof Object[]) {
            List<Object> list = new ArrayList<>();
            for (Object o : (Object[]) v) {
                list.add(json(o));
            }
            return list;
        }
        return v instanceof Number || v instanceof Boolean ? v : String.valueOf(v);
    }

    /** Writes a folder's metadata, only the fields of its folder metadata schema. */
    @SuppressWarnings("unchecked")
    static List<String> writeFolderMetadata(ResourceResolver resolver, Resource folder, Object fields)
            throws DamApiServlet.ApiException, PersistenceException {
        Resource schema = formFor(folder, Kind.FOLDER_SCHEMA);
        if (schema == null) {
            throw new DamApiServlet.ApiException(400, "the folder has no folder metadata schema");
        }
        if (!(fields instanceof Map)) {
            throw new DamApiServlet.ApiException(400, "fields must be a JSON object");
        }
        List<String> allowed = new ArrayList<>();
        Object tabs = DamSchemas.describe(schema).get("tabs");
        for (Object t : tabs instanceof List ? (List<Object>) tabs : Collections.emptyList()) {
            for (Object f : (List<Object>) ((Map<String, Object>) t).get("fields")) {
                allowed.add(String.valueOf(((Map<String, Object>) f).get("property")));
            }
        }
        for (String p : ((Map<String, Object>) fields).keySet()) {
            if (!allowed.contains(p)) {
                throw new DamApiServlet.ApiException(400, p + " is not a field of " + schema.getValueMap().get("jcr:title", schema.getName()));
            }
        }
        Resource content = content(folder);
        Resource md = content.getChild("metadata");
        if (md == null) {
            md = resolver.create(content, "metadata", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap m = md.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + folder.getPath());
        }
        return DamSchemas.write(m, fields);
    }

    // ------------------------------------------------------------------ io

    private static Resource content(Resource folder) throws PersistenceException {
        Resource c = folder.getChild("jcr:content");
        return c != null ? c
                : folder.getResourceResolver().create(folder, "jcr:content", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
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
