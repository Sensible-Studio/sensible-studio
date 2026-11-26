/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Metadata Schemas: the form of the asset Properties page, per folder, as AEM's
 * Tools &gt; Assets &gt; Metadata Schemas. A schema is tabs of fields, each bound to a
 * metadata property; the folder's schema (or the nearest ancestor's) applies to its assets.
 *
 * <pre>
 * /conf/global/settings/dam/adminui-extension/metadataschema/&lt;name&gt;
 *     jcr:title, studio:definition = {"tabs":[{"title":"Basic","fields":[{"label":"Title","property":"dc:title","type":"text"}]}]}
 * &lt;folder&gt;/jcr:content   metadataSchema = &lt;schema path&gt;
 * </pre>
 * Field types: text, textarea, multi (several values), number, date, checkbox, select
 * (with {@code options}: [{value, text}]); {@code required} and {@code description} are optional.
 */
final class DamSchemas {

    static final String ROOT = "/conf/global/settings/dam/adminui-extension/metadataschema";
    static final String PN_SCHEMA = "metadataSchema";
    static final String PN_DEFINITION = "studio:definition";
    static final Set<String> TYPES = new HashSet<>(Arrays.asList("text", "textarea", "multi", "number", "date", "checkbox", "select"));
    static final int MAX_TABS = 20;
    static final int MAX_FIELDS = 200;

    private DamSchemas() {
    }

    static List<Map<String, Object>> list(ResourceResolver resolver) {
        Resource root = resolver.getResource(ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (root != null) {
            for (Resource s : root.getChildren()) {
                if (s.getValueMap().containsKey(PN_DEFINITION)) {
                    out.add(describe(s));
                }
            }
        }
        return out;
    }

    static Map<String, Object> describe(Resource schema) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", schema.getPath());
        m.put("name", schema.getName());
        m.put("title", schema.getValueMap().get("jcr:title", schema.getName()));
        try {
            m.put("tabs", ((Map<?, ?>) Json.parse(schema.getValueMap().get(PN_DEFINITION, "{}"))).get("tabs"));
        } catch (IllegalArgumentException | ClassCastException e) {
            m.put("tabs", Collections.emptyList());
        }
        return m;
    }

    /** The schema of an item's folder (or the nearest ancestor with one), or null. */
    static Resource schemaFor(Resource item) {
        Resource start = DamItems.isAsset(item) ? item.getParent() : item;
        for (Resource f = start; f != null && DamItems.inDam(f.getPath()); f = f.getParent()) {
            Resource c = f.getChild("jcr:content");
            String p = c == null ? null : c.getValueMap().get(PN_SCHEMA, String.class);
            if (p != null && !p.isEmpty()) {
                return p.startsWith(ROOT + "/") ? item.getResourceResolver().getResource(p) : null;
            }
        }
        return null;
    }

    /** Checks a definition from the console and returns it normalised (only known keys). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> clean(Object json) throws DamApiServlet.ApiException {
        if (!(json instanceof Map) || !(((Map<String, Object>) json).get("tabs") instanceof List)) {
            throw new DamApiServlet.ApiException(400, "a schema is {\"tabs\": [...]}");
        }
        List<Object> tabs = (List<Object>) ((Map<String, Object>) json).get("tabs");
        if (tabs.isEmpty() || tabs.size() > MAX_TABS) {
            throw new DamApiServlet.ApiException(400, "a schema has 1 to " + MAX_TABS + " tabs");
        }
        List<Object> outTabs = new ArrayList<>();
        int count = 0;
        Set<String> properties = new HashSet<>();
        for (Object t : tabs) {
            if (!(t instanceof Map)) {
                throw new DamApiServlet.ApiException(400, "tabs must be objects");
            }
            Map<String, Object> tab = (Map<String, Object>) t;
            String title = String.valueOf(tab.getOrDefault("title", "")).trim();
            if (title.isEmpty()) {
                throw new DamApiServlet.ApiException(400, "every tab needs a title");
            }
            List<Object> fields = tab.get("fields") instanceof List ? (List<Object>) tab.get("fields") : Collections.emptyList();
            List<Object> outFields = new ArrayList<>();
            for (Object f : fields) {
                if (!(f instanceof Map) || ++count > MAX_FIELDS) {
                    throw new DamApiServlet.ApiException(400, "fields must be objects, " + MAX_FIELDS + " at most");
                }
                Map<String, Object> field = (Map<String, Object>) f;
                String property = String.valueOf(field.get("property")).trim();
                String type = String.valueOf(field.getOrDefault("type", "text"));
                String label = String.valueOf(field.getOrDefault("label", property)).trim();
                if (!DamCsv.writable(property)) {
                    throw new DamApiServlet.ApiException(400, "\"" + property + "\" is not a metadata property (use names such as dc:title)");
                }
                if (!TYPES.contains(type)) {
                    throw new DamApiServlet.ApiException(400, "unknown field type " + type);
                }
                if (!properties.add(property)) {
                    throw new DamApiServlet.ApiException(400, property + " is used by two fields");
                }
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("label", label.isEmpty() ? property : label);
                o.put("property", property);
                o.put("type", type);
                if (Boolean.TRUE.equals(field.get("required"))) {
                    o.put("required", true);
                }
                if (field.get("description") != null && !String.valueOf(field.get("description")).trim().isEmpty()) {
                    o.put("description", String.valueOf(field.get("description")).trim());
                }
                if ("select".equals(type)) {
                    List<Object> options = new ArrayList<>();
                    if (field.get("options") instanceof List) {
                        for (Object opt : (List<Object>) field.get("options")) {
                            if (opt instanceof Map && ((Map<String, Object>) opt).get("value") != null) {
                                Map<String, Object> op = new LinkedHashMap<>();
                                String value = String.valueOf(((Map<String, Object>) opt).get("value"));
                                op.put("value", value);
                                Object text = ((Map<String, Object>) opt).get("text");
                                op.put("text", text == null || String.valueOf(text).isEmpty() ? value : String.valueOf(text));
                                options.add(op);
                            }
                        }
                    }
                    if (options.isEmpty()) {
                        throw new DamApiServlet.ApiException(400, label + ": a dropdown needs options");
                    }
                    o.put("options", options);
                }
                outFields.add(o);
            }
            Map<String, Object> ot = new LinkedHashMap<>();
            ot.put("title", title);
            ot.put("fields", outFields);
            outTabs.add(ot);
        }
        return Collections.singletonMap("tabs", outTabs);
    }

    static Map<String, Object> save(ResourceResolver resolver, String name, String title, Object definition)
            throws DamApiServlet.ApiException, PersistenceException {
        if (name == null || !DamProfiles.NAME.matcher(name).matches()) {
            throw new DamApiServlet.ApiException(400, "schema names use a-z, 0-9, '.', '_' and '-'");
        }
        Map<String, Object> def = clean(definition);
        Resource root = ensure(resolver, ROOT);
        Resource s = root.getChild(name);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:title", title == null || title.trim().isEmpty() ? name : title.trim());
        props.put(PN_DEFINITION, JsonOut.write(def));
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
        return describe(s);
    }

    static void delete(ResourceResolver resolver, String name) throws DamApiServlet.ApiException, PersistenceException {
        Resource s = name == null || name.contains("/") ? null : resolver.getResource(ROOT + "/" + name);
        if (s == null) {
            throw new DamApiServlet.ApiException(404, "no schema " + name);
        }
        resolver.delete(s);
    }

    static void assign(Resource folder, String schemaPath) throws DamApiServlet.ApiException, PersistenceException {
        if (schemaPath != null && !schemaPath.isEmpty()
                && (!schemaPath.startsWith(ROOT + "/") || folder.getResourceResolver().getResource(schemaPath) == null)) {
            throw new DamApiServlet.ApiException(404, "no schema " + schemaPath);
        }
        Resource c = folder.getChild("jcr:content");
        if (c == null) {
            c = folder.getResourceResolver().create(folder, "jcr:content", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap m = c.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + folder.getPath());
        }
        if (schemaPath == null || schemaPath.isEmpty()) {
            m.remove(PN_SCHEMA);
        } else {
            m.put(PN_SCHEMA, schemaPath);
        }
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : path.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }

    // ------------------------------------------------------------------------- typed metadata write

    /**
     * Writes metadata from a schema form: {@code fields} maps property names to a string, number,
     * boolean, list of strings, {@code {"date": "ISO 8601"}} or null (removes the property).
     */
    @SuppressWarnings("unchecked")
    static List<String> write(ModifiableValueMap md, Object json) throws DamApiServlet.ApiException {
        if (!(json instanceof Map)) {
            throw new DamApiServlet.ApiException(400, "fields must be a JSON object");
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) json).entrySet()) {
            String p = e.getKey();
            if (!DamCsv.writable(p)) {
                throw new DamApiServlet.ApiException(400, "\"" + p + "\" cannot be written");
            }
            Object v = e.getValue();
            if (v == null || "".equals(v) || v instanceof List && ((List<Object>) v).isEmpty()) {
                md.remove(p);
            } else if (v instanceof String) {
                md.put(p, ((String) v).trim());
            } else if (v instanceof Boolean) {
                md.put(p, v);
            } else if (v instanceof Number) {
                Number n = (Number) v;
                md.put(p, v instanceof Long || v instanceof Integer ? (Object) n.longValue() : (Object) n.doubleValue());
            } else if (v instanceof List) {
                List<String> vs = new ArrayList<>();
                for (Object o : (List<Object>) v) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty() && !vs.contains(s)) {
                        vs.add(s);
                    }
                }
                if (vs.isEmpty()) {
                    md.remove(p);
                } else {
                    md.put(p, vs.toArray(new String[0]));
                }
            } else if (v instanceof Map && ((Map<String, Object>) v).get("date") != null) {
                md.put(p, date(String.valueOf(((Map<String, Object>) v).get("date"))));
            } else {
                throw new DamApiServlet.ApiException(400, p + ": unsupported value");
            }
            changed.add(p);
        }
        return changed;
    }

    static Calendar date(String s) throws DamApiServlet.ApiException {
        try {
            OffsetDateTime t = s.length() == 10 ? OffsetDateTime.parse(s + "T00:00:00Z") : OffsetDateTime.parse(s);
            GregorianCalendar c = GregorianCalendar.from(t.toZonedDateTime());
            c.setTimeZone(TimeZone.getTimeZone(t.getOffset()));
            return c;
        } catch (DateTimeParseException e) {
            throw new DamApiServlet.ApiException(400, "not a date: " + s);
        }
    }
}
