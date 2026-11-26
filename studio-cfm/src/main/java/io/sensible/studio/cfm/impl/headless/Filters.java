package io.sensible.studio.cfm.impl.headless;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code filter} and {@code sort} arguments of list queries, with AEM's semantics: fields of a filter are
 * combined with {@code _logOp} (default AND), the {@code _expressions} of one field likewise; an expression
 * compares with {@code _operator} (default EQUALS), optionally {@code _ignoreCase}; multi-value fields match
 * per {@code _apply} (default AT_LEAST_ONCE); a fragment reference field takes the filter of the referenced model.
 */
final class Filters {

    private Filters() {
    }

    // ------------------------------------------------------------------------------------------- filter

    @SuppressWarnings("unchecked")
    static boolean matches(FragmentView v, Map<String, Object> filter, HeadlessContext ctx) {
        if (filter == null || filter.isEmpty()) return true;
        boolean or = "OR".equals(filter.get("_logOp"));
        boolean any = false;
        boolean all = true;
        boolean seen = false;
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            String key = e.getKey();
            if ("_logOp".equals(key) || e.getValue() == null) continue;
            Map<String, Object> fieldFilter = (Map<String, Object>) e.getValue();
            boolean m;
            switch (key) {
                case "_path": m = field(Collections.singletonList(v.path()), fieldFilter, false, "string"); break;
                case "_variation": m = field(Collections.singletonList(v.variation), fieldFilter, false, "string"); break;
                case "_tags": m = field(new ArrayList<>(v.tags()), fieldFilter, true, "string"); break;
                case "_locale": m = field(Collections.singletonList(v.locale()), fieldFilter, false, "string"); break;
                default: {
                    GqlModel.Field f = v.model.field(key);
                    if (f == null) continue;
                    m = element(v, f, fieldFilter, ctx);
                }
            }
            seen = true;
            any |= m;
            all &= m;
        }
        return !seen || (or ? any : all);
    }

    private static boolean element(FragmentView v, GqlModel.Field f, Map<String, Object> fieldFilter, HeadlessContext ctx) {
        if ("fragment-reference".equals(f.kind)) {
            // nested: a model filter applied to the referenced fragment(s)
            for (Object path : FragmentView.items(v.raw(f.name))) {
                FragmentView ref = ctx.fragment(path.toString(), v.variation, null);
                if (ref != null && matches(ref, fieldFilter, ctx)) return true;
            }
            return false;
        }
        if ("content-reference".equals(f.kind)) {
            List<Object> paths = new ArrayList<>();
            for (Object p : FragmentView.items(v.raw(f.name))) paths.add(p.toString());
            return field(paths, fieldFilter, f.multiple, "string");
        }
        return field(v.comparable(f, ctx), fieldFilter, f.multiple || "tags".equals(f.kind), typeOf(f));
    }

    static String typeOf(GqlModel.Field f) {
        switch (f.kind) {
            case "number": return "number";
            case "boolean": return "boolean";
            case "date": return "time".equals(f.dateType) ? "time" : "date".equals(f.dateType) ? "date" : "calendar";
            default: return "string";
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean field(List<Object> values, Map<String, Object> fieldFilter, boolean multi, String type) {
        Object exprs = fieldFilter.get("_expressions");
        if (!(exprs instanceof List) || ((List<?>) exprs).isEmpty()) return true;
        boolean or = "OR".equals(fieldFilter.get("_logOp"));
        boolean any = false;
        boolean all = true;
        for (Object o : (List<Object>) exprs) {
            if (!(o instanceof Map)) continue;
            boolean m = expression(values, (Map<String, Object>) o, multi, type);
            any |= m;
            all &= m;
        }
        return or ? any : all;
    }

    private static boolean expression(List<Object> values, Map<String, Object> expr, boolean multi, String type) {
        String op = expr.get("_operator") == null ? "EQUALS" : expr.get("_operator").toString();
        boolean ignoreCase = Boolean.TRUE.equals(expr.get("_ignoreCase"));
        Object expected = expr.get("value");
        if (!multi) {
            Object actual = values.isEmpty() ? null : values.get(0);
            return compare(actual, expected, op, ignoreCase, type);
        }
        String apply = expr.get("_apply") == null ? "AT_LEAST_ONCE" : expr.get("_apply").toString();
        switch (apply) {
            case "ALL":
                if (values.isEmpty()) return false;
                // fall through
            case "ALL_OR_EMPTY":
                for (Object a : values) if (!compare(a, expected, op, ignoreCase, type)) return false;
                return true;
            case "NONE":
                for (Object a : values) if (compare(a, expected, op, ignoreCase, type)) return false;
                return true;
            default:
                for (Object a : values) if (compare(a, expected, op, ignoreCase, type)) return true;
                return values.isEmpty() && expected == null && "EQUALS".equals(op);
        }
    }

    static boolean compare(Object actual, Object expected, String op, boolean ignoreCase, String type) {
        if (expected == null) {
            boolean empty = actual == null || actual.toString().isEmpty();
            return "EQUALS_NOT".equals(op) != empty;
        }
        if (actual == null) return op.endsWith("_NOT") || "CONTAINS_NOT".equals(op);
        switch (type) {
            case "number": {
                Double a = FragmentView.number(actual);
                Double e = FragmentView.number(expected);
                if (a == null || e == null) return false;
                return ordered(Double.compare(a, e), op);
            }
            case "boolean":
                boolean eq = Boolean.parseBoolean(actual.toString()) == Boolean.parseBoolean(expected.toString());
                return "EQUALS_NOT".equals(op) != eq;
            case "calendar":
            case "date":
            case "time": {
                Comparable<Object> a = temporal(actual, type);
                Comparable<Object> e = temporal(expected, type);
                if (a == null || e == null) return false;
                return ordered(a.compareTo(e), op);
            }
            default: {
                String a = actual.toString();
                String e = expected.toString();
                if (ignoreCase) {
                    a = a.toLowerCase(Locale.ROOT);
                    e = e.toLowerCase(Locale.ROOT);
                }
                switch (op) {
                    case "EQUALS": return a.equals(e);
                    case "EQUALS_NOT": return !a.equals(e);
                    case "CONTAINS": return a.contains(e);
                    case "CONTAINS_NOT": return !a.contains(e);
                    case "STARTS_WITH": return a.startsWith(e);
                    case "LIKE": return like(e).matcher(a).matches();
                    default: return ordered(a.compareTo(e), op);
                }
            }
        }
    }

    private static boolean ordered(int c, String op) {
        switch (op) {
            case "EQUALS":
            case "AT": return c == 0;
            case "EQUALS_NOT":
            case "NOT_AT": return c != 0;
            case "LOWER":
            case "BEFORE": return c < 0;
            case "LOWER_EQUAL":
            case "AT_OR_BEFORE": return c <= 0;
            case "GREATER":
            case "AFTER": return c > 0;
            case "GREATER_EQUAL":
            case "AT_OR_AFTER": return c >= 0;
            default: return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable<Object> temporal(Object o, String type) {
        if (o instanceof Instant) {
            Instant i = (Instant) o;
            if ("date".equals(type)) return (Comparable) i.atOffset(java.time.ZoneOffset.UTC).toLocalDate();
            if ("time".equals(type)) return (Comparable) i.atOffset(java.time.ZoneOffset.UTC).toLocalTime();
            return (Comparable) i;
        }
        String s = o.toString().trim();
        try {
            switch (type) {
                case "date":
                    return (Comparable) LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
                case "time":
                    return (Comparable) LocalTime.parse(s.contains("T") ? s.substring(s.indexOf('T') + 1, Math.min(s.length(), s.indexOf('T') + 9)) : s);
                default:
                    if (s.length() == 10) return (Comparable) LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                    return (Comparable) OffsetDateTime.parse(s).toInstant();
            }
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Pattern like(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '%') sb.append(".*");
            else if (c == '_') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    // ------------------------------------------------------------------------------------------- sort

    /** {@code "price DESC, title"}: comma separated element names (or {@code _path}), each ASC (default) or DESC. */
    static Comparator<FragmentView> sort(String spec, HeadlessContext ctx) {
        Comparator<FragmentView> byPath = Comparator.comparing(FragmentView::path);
        if (spec == null || spec.trim().isEmpty()) return byPath;
        Comparator<FragmentView> c = null;
        for (String part : spec.split(",")) {
            String[] tokens = part.trim().split("\\s+");
            if (tokens.length == 0 || tokens[0].isEmpty()) continue;
            boolean desc = tokens.length > 1 && "DESC".equalsIgnoreCase(tokens[1]);
            String[] path = tokens[0].split("\\.");
            Comparator<FragmentView> one = (a, b) -> compareKeys(key(a, path, 0, ctx), key(b, path, 0, ctx));
            if (desc) one = one.reversed();
            c = c == null ? one : c.thenComparing(one);
        }
        return c == null ? byPath : c.thenComparing(byPath);
    }

    private static Object key(FragmentView v, String[] path, int i, HeadlessContext ctx) {
        if (v == null) return null;
        String name = path[i];
        if ("_path".equals(name)) return v.path();
        if ("_variation".equals(name)) return v.variation;
        GqlModel.Field f = v.model.field(name);
        if (f == null) return null;
        if ("fragment-reference".equals(f.kind)) {
            if (i + 1 >= path.length) return null;
            List<Object> refs = FragmentView.items(v.raw(f.name));
            return refs.isEmpty() ? null : key(ctx.fragment(refs.get(0).toString(), v.variation, null), path, i + 1, ctx);
        }
        List<Object> values = v.comparable(f, ctx);
        return values.isEmpty() ? null : values.get(0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1; // empty values last
        if (a instanceof Number && b instanceof Number) return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        if (a instanceof String && b instanceof String) {
            int c = ((String) a).compareToIgnoreCase((String) b);
            return c != 0 ? c : ((String) a).compareTo((String) b);
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable) return ((Comparable) a).compareTo(b);
        return a.toString().compareTo(b.toString());
    }
}
