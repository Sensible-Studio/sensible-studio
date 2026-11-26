/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Turns a submission into a record (a content fragment) of the model the form names, in the folder it names,
 * in the storage format the Records console and editor read (the one {@code studio-cfm} writes):
 * {@code dam:Asset} → {@code jcr:content} ({@code contentFragment=true}, title, {@code lastFragmentSave}) →
 * {@code data} ({@code cq:model}) → {@code master} with one property per element, typed by the model.
 * <p>
 * Form fields go to the element of the same name unless the form maps them otherwise ({@code recordMapping}:
 * field → element). Text from visitors is always stored as {@code text/plain}, never as HTML, whatever the
 * element's default type: a visitor's text is not markup.
 */
final class RecordWriter {

    static final String MODEL_ITEMS = "jcr:content/model/cq:dialog/content/items";
    static final String DAM_ROOT = "/content/dam";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_-]+)\\}");

    /** One element of a model, as far as writing a value needs it. */
    static final class Element {
        final String name;
        final String metaType;
        final String valueType;

        Element(String name, String metaType, String valueType) {
            this.name = name;
            this.metaType = metaType;
            this.valueType = valueType == null ? "string" : valueType;
        }

        boolean multiple() {
            return valueType.endsWith("[]");
        }

        String base() {
            String t = multiple() ? valueType.substring(0, valueType.length() - 2) : valueType;
            int slash = t.indexOf('/');
            return slash > 0 ? t.substring(0, slash) : t;
        }

        boolean text() {
            return "string".equals(base()) && (metaType == null || metaType.startsWith("text"));
        }
    }

    /** What happened: the record's path, and the fields that could not be written (with why). */
    static final class Result {
        final String path;
        final List<String> skipped;

        Result(String path, List<String> skipped) {
            this.path = path;
            this.skipped = skipped;
        }
    }

    private RecordWriter() {
    }

    /** The model's elements by name, in model order; null when there is no model at {@code modelPath}. */
    static Map<String, Element> elements(ResourceResolver resolver, String modelPath) {
        Resource model = modelPath == null ? null : resolver.getResource(modelPath);
        if (model == null) {
            return null;
        }
        Map<String, Element> out = new LinkedHashMap<>();
        Resource items = model.getChild(MODEL_ITEMS);
        if (items != null) {
            for (Resource item : items.getChildren()) {
                ValueMap p = item.getValueMap();
                String name = p.get("name", String.class);
                if (name == null || name.isEmpty()) {
                    continue; // tab placeholders
                }
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                out.put(name, new Element(name, p.get("metaType", String.class), p.get("valueType", "string")));
            }
        }
        return out;
    }

    static boolean disabled(ResourceResolver resolver, String modelPath) {
        Resource content = resolver.getResource(modelPath + "/jcr:content");
        return content != null && "disabled".equals(content.getValueMap().get("cq:status", ""));
    }

    /**
     * Creates the record.
     *
     * @param mapping form field → element; fields not in it go to the element of their own name
     */
    static Result write(ResourceResolver resolver, FormModel form, String modelPath, String folder, String titlePattern,
            Map<String, String> mapping, Map<String, Object> values, Calendar when) throws PersistenceException {
        if (folder == null || !(folder.equals(DAM_ROOT) || folder.startsWith(DAM_ROOT + "/"))) {
            throw new IllegalArgumentException("Content fragments are kept under " + DAM_ROOT + ", not " + folder);
        }
        Map<String, Element> elements = elements(resolver, modelPath);
        if (elements == null) {
            throw new IllegalArgumentException("No model at " + modelPath);
        }
        Resource parent = ensureFolder(resolver, folder);

        Map<String, Object> master = new LinkedHashMap<>();
        master.put("jcr:primaryType", "nt:unstructured");
        List<String> skipped = new ArrayList<>();
        for (FormModel.Field f : form.fields) {
            if ("fileupload".equals(f.type)) {
                continue; // files stay with the submission
            }
            String target = mapping.containsKey(f.name) ? mapping.get(f.name) : f.name;
            if (target == null || target.isEmpty()) {
                continue; // mapped to nothing: left out on purpose
            }
            Element e = elements.get(target);
            if (e == null) {
                continue; // the model has no such element
            }
            Object raw = values.get(f.name);
            if (raw == null || String.valueOf(raw).trim().isEmpty()) {
                continue;
            }
            try {
                Object v = convert(e, String.valueOf(raw), f.type);
                if (v == null) {
                    continue;
                }
                master.put(e.name, v);
                if (e.text()) {
                    master.put(e.name + "@ContentType", "text/plain");
                }
                master.put(e.name + "@LastModified", when);
            } catch (IllegalArgumentException ex) {
                skipped.add(f.name + " (" + ex.getMessage() + ")");
            }
        }

        String title = title(titlePattern, form, values, when);
        String name = uniqueName(parent, slug(title));
        Resource asset = resolver.create(parent, name, props("jcr:primaryType", "dam:Asset"));
        Map<String, Object> content = props("jcr:primaryType", "dam:AssetContent");
        content.put("jcr:title", title);
        content.put("contentFragment", Boolean.TRUE);
        content.put("lastFragmentSave", when);
        content.put("jcr:lastModified", when);
        content.put("jcr:lastModifiedBy", resolver.getUserID());
        Resource jcrContent = resolver.create(asset, "jcr:content", content);
        Map<String, Object> data = props("jcr:primaryType", "nt:unstructured");
        data.put("cq:model", modelPath);
        Resource dataRes = resolver.create(jcrContent, "data", data);
        resolver.create(dataRes, "master", master);
        Map<String, Object> metadata = props("jcr:primaryType", "nt:unstructured");
        metadata.put("dc:source", form.path);
        resolver.create(jcrContent, "metadata", metadata);
        resolver.create(jcrContent, "related", props("jcr:primaryType", "nt:unstructured"));
        resolver.commit();
        return new Result(asset.getPath(), skipped);
    }

    /**
     * A form value as the element's type: numbers, booleans, dates, several values for a multiple element
     * (a checkbox group's "a, b" becomes two), text as is.
     */
    static Object convert(Element e, String value, String fieldType) {
        List<String> parts = new ArrayList<>();
        if (e.multiple()) {
            for (String p : ("checkboxgroup".equals(fieldType) ? value.split(",\\s*") : new String[] {value})) {
                if (!p.trim().isEmpty()) {
                    parts.add(p.trim());
                }
            }
        } else {
            parts.add(value.trim());
        }
        if (parts.isEmpty()) {
            return null;
        }
        switch (e.base()) {
            case "long": {
                Long[] out = new Long[parts.size()];
                for (int i = 0; i < out.length; i++) {
                    try {
                        out[i] = Long.parseLong(parts.get(i));
                    } catch (NumberFormatException ex) {
                        double d;
                        try {
                            d = Double.parseDouble(parts.get(i));
                        } catch (NumberFormatException ex2) {
                            throw new IllegalArgumentException("not a whole number");
                        }
                        if (d != Math.rint(d)) {
                            throw new IllegalArgumentException("not a whole number");
                        }
                        out[i] = (long) d;
                    }
                }
                return e.multiple() ? out : out[0];
            }
            case "double": {
                Double[] out = new Double[parts.size()];
                for (int i = 0; i < out.length; i++) {
                    try {
                        out[i] = Double.parseDouble(parts.get(i));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("not a number");
                    }
                }
                return e.multiple() ? out : out[0];
            }
            case "boolean": {
                Boolean[] out = new Boolean[parts.size()];
                for (int i = 0; i < out.length; i++) {
                    String s = parts.get(i).toLowerCase(Locale.ROOT);
                    out[i] = !(s.isEmpty() || "false".equals(s) || "off".equals(s) || "no".equals(s) || "0".equals(s));
                }
                return e.multiple() ? out : out[0];
            }
            case "calendar": {
                Calendar[] out = new Calendar[parts.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = calendar(parts.get(i));
                }
                return e.multiple() ? out : out[0];
            }
            default:
                return e.multiple() ? parts.toArray(new String[0]) : parts.get(0);
        }
    }

    static Calendar calendar(String s) {
        ZonedDateTime z;
        try {
            z = ZonedDateTime.parse(s);
        } catch (DateTimeParseException a) {
            try {
                z = LocalDateTime.parse(s).atZone(ZoneId.systemDefault());
            } catch (DateTimeParseException b) {
                try {
                    z = LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault());
                } catch (DateTimeParseException c) {
                    throw new IllegalArgumentException("not a date");
                }
            }
        }
        return GregorianCalendar.from(z);
    }

    /** The record's title: the form's pattern with {field} placeholders, or "<form title> <date time>". */
    static String title(String pattern, FormModel form, Map<String, Object> values, Calendar when) {
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
                ZonedDateTime.ofInstant(when.toInstant(), when.getTimeZone().toZoneId()));
        if (pattern == null || pattern.trim().isEmpty()) {
            return form.title + " " + stamp;
        }
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = "date".equals(key) ? stamp : "form".equals(key) ? form.title : values.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        String t = sb.toString().replaceAll("\\s+", " ").trim();
        if (t.length() > 200) {
            t = t.substring(0, 200).trim();
        }
        return t.isEmpty() ? form.title + " " + stamp : t;
    }

    static String slug(String title) {
        String s = java.text.Normalizer.normalize(title == null ? "" : title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "record" : s;
    }

    private static String uniqueName(Resource parent, String base) {
        if (parent.getChild(base) == null) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            if (parent.getChild(base + "-" + i) == null) {
                return base + "-" + i;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Resource ensureFolder(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) {
            return r;
        }
        int slash = path.lastIndexOf('/');
        Resource parent = ensureFolder(resolver, path.substring(0, slash));
        return resolver.create(parent, path.substring(slash + 1), props("jcr:primaryType", "sling:Folder"));
    }

    private static Map<String, Object> props(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
