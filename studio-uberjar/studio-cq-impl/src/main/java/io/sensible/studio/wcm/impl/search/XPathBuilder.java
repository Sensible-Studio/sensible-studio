/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.day.cq.search.Predicate;
import com.day.cq.search.PredicateGroup;

/**
 * Translates the common QueryBuilder predicates into one XPath statement:
 * <ul>
 *   <li>{@code path} (with {@code path.self=true} to include the root), {@code type},
 *       {@code nodename};</li>
 *   <li>{@code property} with {@code value}, {@code N_value}, {@code operation} (equals,
 *       unequals, like, exists, not) and {@code and};</li>
 *   <li>{@code fulltext} (with {@code fulltext.relPath});</li>
 *   <li>{@code orderby} ({@code @prop} or {@code path}) with {@code orderby.sort};</li>
 *   <li>{@code p.limit} (default 10, {@code -1} for all) and {@code p.offset}.</li>
 * </ul>
 * Numbered predicates ({@code 1_property}, {@code 2_property}) are AND-ed.
 */
final class XPathBuilder {

    static final long DEFAULT_LIMIT = 10;

    final String statement;
    final long limit;
    final long offset;
    /** Where the search looks (its path predicate), or null: everywhere. */
    final String path;

    private XPathBuilder(String statement, long limit, long offset, String path) {
        this.statement = statement;
        this.limit = limit;
        this.offset = offset;
        this.path = path;
    }

    static XPathBuilder build(PredicateGroup group) {
        String path = null;
        boolean self = false;
        String type = null;
        List<String> conditions = new ArrayList<>();
        String orderBy = null;
        boolean desc = false;
        long limit = parseLong(group.get("p.limit"), DEFAULT_LIMIT);
        long offset = parseLong(group.get("p.offset"), 0);

        for (int i = 0; i < group.size(); i++) {
            Predicate p = group.get(i);
            Map<String, String> params = p.getParameters();
            switch (p.getType()) {
                case "path":
                    path = params.get("path");
                    self = "true".equals(params.get("self"));
                    break;
                case "type":
                    type = params.get("type");
                    break;
                case "nodename":
                    conditions.add(nodename(params.get("nodename")));
                    break;
                case "property":
                    String c = property(params);
                    if (c != null) {
                        conditions.add(c);
                    }
                    break;
                case "fulltext":
                    String rel = params.getOrDefault("relPath", ".");
                    conditions.add("jcr:contains(" + rel + ", " + literal(params.get("fulltext")) + ")");
                    break;
                case "orderby":
                    orderBy = params.get("orderby");
                    desc = "desc".equalsIgnoreCase(params.get("sort"));
                    break;
                default:
                    break; // unknown predicates are ignored rather than failing the page
            }
        }

        StringBuilder q = new StringBuilder("/jcr:root");
        q.append(path == null || "/".equals(path) ? "" : escapePath(path));
        q.append(self ? "/descendant-or-self::" : "//");
        q.append("element(*, ").append(type == null || type.isEmpty() ? "nt:base" : type).append(")");
        if (!conditions.isEmpty()) {
            q.append("[").append(String.join(" and ", conditions)).append("]");
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            String key = "path".equals(orderBy) ? "@jcr:path" : orderBy.startsWith("@") ? orderBy : "@" + orderBy;
            q.append(" order by ").append(key).append(desc ? " descending" : " ascending");
        }
        return new XPathBuilder(q.toString(), limit, offset, path);
    }

    private static String property(Map<String, String> params) {
        String name = params.get("property");
        if (name == null) {
            return null;
        }
        String attr = attribute(name);
        String op = params.getOrDefault("operation", "equals");
        Map<Integer, String> values = new TreeMap<>();
        if (params.containsKey("value")) {
            values.put(0, params.get("value"));
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().matches("[0-9]+_value")) {
                values.put(Integer.parseInt(e.getKey().substring(0, e.getKey().indexOf('_'))), e.getValue());
            }
        }
        if ("exists".equals(op)) {
            return "false".equals(params.get("value")) ? "not(" + attr + ")" : attr;
        }
        if ("not".equals(op)) {
            return "not(" + attr + ")";
        }
        List<String> parts = new ArrayList<>();
        for (String v : values.values()) {
            switch (op) {
                case "unequals":
                    parts.add(attr + " != " + literal(v));
                    break;
                case "like":
                    parts.add("jcr:like(" + attr + ", " + literal(v) + ")");
                    break;
                default:
                    parts.add(attr + " = " + literal(v));
            }
        }
        if (parts.isEmpty()) {
            return attr;
        }
        String joiner = "true".equals(params.get("and")) ? " and " : " or ";
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(joiner, parts) + ")";
    }

    private static String nodename(String pattern) {
        if (pattern == null) {
            return "true()";
        }
        if (pattern.contains("*") || pattern.contains("?")) {
            return "jcr:like(fn:name(), " + literal(pattern.replace('*', '%').replace('?', '_')) + ")";
        }
        return "fn:name() = " + literal(pattern);
    }

    /** {@code jcr:content/cq:tags} becomes {@code jcr:content/@cq:tags}. */
    static String attribute(String relProperty) {
        int slash = relProperty.lastIndexOf('/');
        return slash < 0 ? "@" + relProperty : relProperty.substring(0, slash + 1) + "@" + relProperty.substring(slash + 1);
    }

    static String literal(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    /** Escapes path segments XPath cannot start with a digit or contain spaces in. */
    static String escapePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append('/');
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < seg.length(); i++) {
                char ch = seg.charAt(i);
                if ((i == 0 && Character.isDigit(ch)) || ch == ' ') {
                    s.append(String.format("_x%04X_", (int) ch));
                } else {
                    s.append(ch);
                }
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static long parseLong(String v, long def) {
        try {
            return v == null ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
