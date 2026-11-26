/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Collection;
import java.util.Map;

/** Small JSON writer: maps, collections, strings, numbers, booleans, dates (ISO-8601), arrays. */
final class JsonOut {

    private JsonOut() {
    }

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        append(b, value);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    static void append(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            b.append(Json.quote((String) v));
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            b.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (v instanceof BigDecimal) {
            b.append(((BigDecimal) v).toPlainString());
        } else if (v instanceof Number) {
            b.append(v);
        } else if (v instanceof Calendar) {
            b.append(Json.quote(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    ((Calendar) v).toInstant().atZone(((Calendar) v).getTimeZone().toZoneId()))));
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(Json.quote(e.getKey())).append(':');
                append(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Collection) {
            b.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) v) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                append(b, o);
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            append(b, java.util.Arrays.asList((Object[]) v));
        } else {
            b.append(Json.quote(String.valueOf(v)));
        }
    }
}
