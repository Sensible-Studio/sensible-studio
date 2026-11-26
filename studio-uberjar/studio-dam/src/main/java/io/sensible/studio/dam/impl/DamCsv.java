/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Metadata export and import as CSV, in the format of AEM's Metadata Export / Import tools:
 *
 * <pre>
 * assetPath,dc:title {{String}},dc:subject {{String: multi}},dam:size {{Long}}
 * /content/dam/wknd/surf.jpg,Surf in Bali,surf|bali,443802
 * </pre>
 * Multiple values are separated by {@code |}. The type in {@code {{ }}} is String (default),
 * Long, Double, Boolean or Date (ISO 8601). Import writes to {@code jcr:content/metadata}.
 */
final class DamCsv {

    static final String MULTI_SEPARATOR = "|";
    static final int MAX_ROWS = 50_000;
    static final int MAX_ASSETS = 100_000;
    /** Properties an import may write: prefixed names, nothing from the repository's own namespaces. */
    static final Pattern PROPERTY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*:[A-Za-z0-9_.-]+$");
    private static final Pattern HEADER = Pattern.compile("^\\s*([^{]+?)\\s*(?:\\{\\{\\s*(\\w+)\\s*(:\\s*multi\\s*)?\\}\\})?\\s*$");
    private static final Set<String> TYPES = new TreeSet<>(java.util.Arrays.asList("String", "Long", "Double", "Boolean", "Date"));

    private DamCsv() {
    }

    static boolean writable(String name) {
        return PROPERTY.matcher(name).matches() && !name.startsWith("jcr:") && !name.startsWith("rep:") && !name.startsWith("sling:")
                && !name.startsWith("oak:") && !name.startsWith("mix:") && !name.startsWith("nt:");
    }

    // --------------------------------------------------------------------------------------- export

    /** Assets of the folder (and below with {@code recursive}). */
    static List<Resource> assets(Resource folder, boolean recursive) {
        List<Resource> out = new ArrayList<>();
        if (DamItems.isAsset(folder)) {
            out.add(folder);
            return out;
        }
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(folder);
        while (!queue.isEmpty() && out.size() < MAX_ASSETS) {
            for (Resource c : queue.poll().getChildren()) {
                if (DamItems.isAsset(c)) {
                    out.add(c);
                } else if (recursive && DamItems.isFolder(c)) {
                    queue.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The CSV of {@code assets}. With no {@code properties} every metadata property found is a
     * column (in name order); values the repository manages itself are left out.
     */
    static String export(List<Resource> assets, List<String> properties) {
        Map<String, String> columns = new LinkedHashMap<>(); // name -> type header
        List<ValueMap> rows = new ArrayList<>();
        for (Resource a : assets) {
            Resource md = a.getChild("jcr:content/metadata");
            ValueMap v = md == null ? ValueMap.EMPTY : md.getValueMap();
            rows.add(v);
        }
        Set<String> names = new TreeSet<>();
        if (properties.isEmpty()) {
            for (ValueMap v : rows) {
                for (String k : v.keySet()) {
                    if (writable(k)) {
                        names.add(k);
                    }
                }
            }
        }
        for (String p : properties.isEmpty() ? names : properties) {
            String type = "String";
            boolean multi = false;
            for (ValueMap v : rows) {
                Object o = v.get(p);
                if (o == null) {
                    continue;
                }
                multi |= o.getClass().isArray();
                Object one = o.getClass().isArray() ? (java.lang.reflect.Array.getLength(o) > 0 ? java.lang.reflect.Array.get(o, 0) : null) : o;
                type = typeOf(one, type);
            }
            columns.put(p, p + " {{" + type + (multi ? ": multi" : "") + "}}");
        }
        StringBuilder csv = new StringBuilder();
        csv.append("assetPath");
        for (String h : columns.values()) {
            csv.append(',').append(cell(h));
        }
        csv.append("\r\n");
        for (int i = 0; i < assets.size(); i++) {
            csv.append(cell(assets.get(i).getPath()));
            ValueMap v = rows.get(i);
            for (String p : columns.keySet()) {
                csv.append(',').append(cell(text(v.get(p))));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private static String typeOf(Object o, String fallback) {
        if (o instanceof Long || o instanceof Integer) {
            return "Long";
        }
        if (o instanceof Double || o instanceof Float || o instanceof BigDecimal) {
            return "Double";
        }
        if (o instanceof Boolean) {
            return "Boolean";
        }
        if (o instanceof Calendar) {
            return "Date";
        }
        return o == null ? fallback : "String";
    }

    private static String text(Object o) {
        if (o == null) {
            return "";
        }
        if (o.getClass().isArray()) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < java.lang.reflect.Array.getLength(o); i++) {
                if (i > 0) {
                    b.append(MULTI_SEPARATOR);
                }
                b.append(text(java.lang.reflect.Array.get(o, i)));
            }
            return b.toString();
        }
        if (o instanceof Calendar) {
            Calendar c = (Calendar) o;
            return c.toInstant().atZone(c.getTimeZone().toZoneId()).toOffsetDateTime().toString();
        }
        return String.valueOf(o);
    }

    /** RFC 4180 quoting; cells starting with = + - @ get a leading quote so spreadsheets do not run them. */
    static String cell(String s) {
        String v = s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("^-?\\d+(\\.\\d+)?$")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // --------------------------------------------------------------------------------------- import

    /** RFC 4180: quoted cells may hold commas, quotes ("") and line breaks. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString());
        if (!(row.size() == 1 && row.get(0).isEmpty())) {
            rows.add(row);
        }
        return rows;
    }

    static final class Column {
        final String name;
        final String type;
        final boolean multi;

        Column(String name, String type, boolean multi) {
            this.name = name;
            this.type = type;
            this.multi = multi;
        }
    }

    static Column column(String header) throws DamApiServlet.ApiException {
        Matcher m = HEADER.matcher(header);
        if (!m.matches()) {
            throw new DamApiServlet.ApiException(400, "cannot read the column " + header);
        }
        String name = m.group(1).trim();
        String type = m.group(2) == null ? "String" : m.group(2);
        if (!TYPES.contains(type)) {
            throw new DamApiServlet.ApiException(400, "unknown type " + type + " in column " + name);
        }
        if (!writable(name)) {
            throw new DamApiServlet.ApiException(400, "the column " + name + " cannot be imported (use prefixed names such as dc:title)");
        }
        return new Column(name, type, m.group(3) != null);
    }

    /** Result of an import: assets changed, rows that could not be used (with the reason). */
    static Map<String, Object> importCsv(ResourceResolver resolver, String text) throws DamApiServlet.ApiException {
        List<List<String>> rows = parse(text);
        if (rows.isEmpty() || !"assetPath".equals(rows.get(0).get(0).trim())) {
            throw new DamApiServlet.ApiException(400, "the first column must be assetPath");
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new DamApiServlet.ApiException(413, "more than " + MAX_ROWS + " rows");
        }
        List<Column> columns = new ArrayList<>();
        for (String h : rows.get(0).subList(1, rows.get(0).size())) {
            columns.add(column(h));
        }
        List<String> updated = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String path = row.get(0).trim();
            Resource asset = DamItems.inDam(path) ? resolver.getResource(path) : null;
            String problem = null;
            if (!DamItems.isAsset(asset)) {
                problem = "not an asset";
            } else {
                try {
                    DamLocks.checkWritable(resolver, asset);
                } catch (DamApiServlet.ApiException e) {
                    problem = e.getMessage();
                }
            }
            ModifiableValueMap md = null;
            if (problem == null) {
                Resource m = asset.getChild("jcr:content/metadata");
                md = m == null ? null : m.adaptTo(ModifiableValueMap.class);
                if (md == null) {
                    problem = "no metadata node or no permission";
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int c = 0; problem == null && c < columns.size(); c++) {
                String raw = c + 1 < row.size() ? row.get(c + 1) : "";
                Column col = columns.get(c);
                try {
                    values.put(col.name, raw.isEmpty() ? null : value(col, raw));
                } catch (IllegalArgumentException e) {
                    problem = col.name + ": " + e.getMessage();
                }
            }
            if (problem != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("line", i + 1);
                s.put("path", path);
                s.put("reason", problem);
                skipped.add(s);
                continue;
            }
            for (Map.Entry<String, Object> e : values.entrySet()) {
                // An empty cell leaves the property as it is, as AEM's import does.
                if (e.getValue() != null) {
                    md.put(e.getKey(), e.getValue());
                }
            }
            ModifiableValueMap content = asset.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
            if (content != null) {
                content.put("jcr:lastModified", Calendar.getInstance());
                content.put("jcr:lastModifiedBy", resolver.getUserID());
            }
            updated.add(path);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated.size());
        out.put("skipped", skipped);
        return out;
    }

    static Object value(Column col, String raw) {
        if (!col.multi) {
            return one(col.type, raw.trim());
        }
        String[] parts = raw.split(Pattern.quote(MULTI_SEPARATOR), -1);
        List<Object> vs = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                vs.add(one(col.type, p.trim()));
            }
        }
        switch (col.type) {
            case "Long":
                return vs.toArray(new Long[0]);
            case "Double":
                return vs.toArray(new Double[0]);
            case "Boolean":
                return vs.toArray(new Boolean[0]);
            case "Date":
                return vs.toArray(new Calendar[0]);
            default:
                return vs.toArray(new String[0]);
        }
    }

    private static Object one(String type, String v) {
        String s = v.startsWith("'") && v.length() > 1 && "=+-@".indexOf(v.charAt(1)) >= 0 ? v.substring(1) : v;
        try {
            switch (type) {
                case "Long":
                    return Long.parseLong(s);
                case "Double":
                    return Double.parseDouble(s);
                case "Boolean":
                    if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) {
                        throw new IllegalArgumentException("not true or false: " + s);
                    }
                    return Boolean.parseBoolean(s);
                case "Date":
                    OffsetDateTime t = s.length() == 10 ? OffsetDateTime.parse(s + "T00:00:00Z") : OffsetDateTime.parse(s);
                    GregorianCalendar c = GregorianCalendar.from(t.toZonedDateTime());
                    c.setTimeZone(TimeZone.getTimeZone(t.getOffset()));
                    return c;
                default:
                    return s;
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("not a " + type + ": " + s);
        }
    }
}
