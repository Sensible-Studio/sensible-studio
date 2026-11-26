package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.DataType;
import com.adobe.cq.dam.cfm.FragmentData;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The value of an element (in one variation) with its type, converted on request like AEM does:
 * a {@code long} element can be read as String, a single value as an array and vice versa.
 */
final class FragmentDataImpl implements FragmentData {

    private final Object raw;
    private final DataType dataType;
    private final String contentType;

    FragmentDataImpl(Object raw, DataType dataType, String contentType) {
        this.raw = raw;
        this.dataType = dataType;
        this.contentType = contentType;
    }

    @Override
    public DataType getDataType() {
        return dataType;
    }

    /** The value in its natural Java type: String, Long, Double, Boolean, Calendar, or an array of those. */
    @Override
    public Object getValue() {
        if (raw == null) return null;
        Class<?> target = naturalType();
        if (dataType.isMultiValue()) {
            Object arr = Array.newInstance(target, 0);
            return convert(raw, arr.getClass());
        }
        return convert(raw, target);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue(Class<T> type) {
        if (raw == null || type == null) return null;
        return (T) convert(raw, type);
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    private Class<?> naturalType() {
        switch (dataType.getTypeString()) {
            case "long":
                return Long.class;
            case "double":
                return Double.class;
            case "boolean":
                return Boolean.class;
            case "calendar":
                return Calendar.class;
            default:
                return String.class;
        }
    }

    // ------------------------------------------------------------------------------------------- conversion

    static Object convert(Object value, Class<?> type) {
        if (value == null) return null;
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            List<Object> items = toList(value);
            Object out = Array.newInstance(component, items.size());
            int i = 0;
            for (Object item : items) {
                Object converted = convertSingle(item, component);
                if (converted == null && component.isPrimitive()) continue;
                Array.set(out, i++, converted);
            }
            if (i < items.size()) {
                Object trimmed = Array.newInstance(component, i);
                System.arraycopy(out, 0, trimmed, 0, i);
                return trimmed;
            }
            return out;
        }
        List<Object> items = toList(value);
        if (type == String.class && items.size() > 1) {
            return String.join(", ", items.stream().map(String::valueOf).toArray(String[]::new));
        }
        return items.isEmpty() ? null : convertSingle(items.get(0), type);
    }

    private static List<Object> toList(Object value) {
        List<Object> out = new ArrayList<>();
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) out.add(Array.get(value, i));
        } else {
            out.add(value);
        }
        return out;
    }

    private static Object convertSingle(Object v, Class<?> type) {
        if (v == null) return null;
        if (type.isInstance(v)) return v;
        try {
            if (type == String.class) return stringOf(v);
            if (type == Long.class || type == long.class) return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(v.toString().trim());
            if (type == Integer.class || type == int.class) return v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            if (type == Double.class || type == double.class) return v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(v.toString().trim());
            if (type == Float.class || type == float.class) return v instanceof Number ? ((Number) v).floatValue() : Float.parseFloat(v.toString().trim());
            if (type == BigDecimal.class) return new BigDecimal(v.toString().trim());
            if (type == Boolean.class || type == boolean.class) return v instanceof Boolean ? v : Boolean.parseBoolean(v.toString().trim());
            if (type == Calendar.class) return calendarOf(v);
            if (type == Date.class) {
                Calendar c = calendarOf(v);
                return c == null ? null : c.getTime();
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
        return null;
    }

    private static String stringOf(Object v) {
        if (v instanceof Calendar) return ((Calendar) v).toInstant().atOffset(ZoneOffset.UTC).toString();
        if (v instanceof Date) return ((Date) v).toInstant().atOffset(ZoneOffset.UTC).toString();
        return v.toString();
    }

    /** Calendars come typed from the repository, or as ISO 8601 strings (e.g. written without a type hint). */
    static Calendar calendarOf(Object v) {
        if (v instanceof Calendar) return (Calendar) v;
        if (v instanceof Date) {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTime((Date) v);
            return c;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.length() == 10) {
            LocalDate d = LocalDate.parse(s);
            return GregorianCalendar.from(d.atStartOfDay(ZoneOffset.UTC));
        }
        return GregorianCalendar.from(OffsetDateTime.parse(s).toZonedDateTime());
    }
}
