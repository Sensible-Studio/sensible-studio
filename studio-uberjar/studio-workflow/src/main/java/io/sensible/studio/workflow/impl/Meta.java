/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.adobe.granite.workflow.metadata.MetaDataMap;

/** {@link MetaDataMap} over a plain map, converting on read like a Sling value map. */
final class Meta extends LinkedHashMap<String, Object> implements MetaDataMap {

    private static final long serialVersionUID = 1L;

    Meta() {
    }

    Meta(Map<String, ?> values) {
        if (values != null) {
            putAll(values);
        }
    }

    /** The properties of {@code resource} (without {@code jcr:} system properties). */
    static Meta of(Resource resource) {
        Meta m = new Meta();
        if (resource != null) {
            for (Map.Entry<String, Object> e : resource.getValueMap().entrySet()) {
                if (!e.getKey().startsWith("jcr:primaryType") && !e.getKey().startsWith("jcr:mixinTypes")) {
                    m.put(e.getKey(), e.getValue());
                }
            }
        }
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name, Class<T> type) {
        return convert(get(name), type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name, T defaultValue) {
        if (defaultValue == null) {
            return (T) get(name);
        }
        T v = convert(get(name), (Class<T>) defaultValue.getClass());
        return v == null ? defaultValue : v;
    }

    @SuppressWarnings("unchecked")
    static <T> T convert(Object v, Class<T> type) {
        if (v == null || type == null) {
            return null;
        }
        if (type.isInstance(v)) {
            return (T) v;
        }
        if (v.getClass().isArray() && !type.isArray()) {
            Object[] arr = v instanceof Object[] ? (Object[]) v : null;
            return arr == null || arr.length == 0 ? null : convert(arr[0], type);
        }
        String s = v instanceof Calendar ? String.valueOf(((Calendar) v).getTimeInMillis()) : String.valueOf(v);
        try {
            if (type == String.class) {
                return (T) (v instanceof Object[] ? Arrays.toString((Object[]) v) : s);
            }
            if (type == Boolean.class) {
                return (T) Boolean.valueOf(s);
            }
            if (type == Integer.class) {
                return (T) Integer.valueOf(s.trim());
            }
            if (type == Long.class) {
                return (T) Long.valueOf(s.trim());
            }
            if (type == Double.class) {
                return (T) Double.valueOf(s.trim());
            }
            if (type == Date.class && v instanceof Calendar) {
                return (T) ((Calendar) v).getTime();
            }
            if (type == String[].class) {
                return (T) (v instanceof Object[] ? Arrays.stream((Object[]) v).map(String::valueOf).toArray(String[]::new)
                        : new String[] {s});
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** Values that can be stored as JCR properties (strings, numbers, booleans, calendars, string arrays). */
    static Map<String, Object> storable(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Long || v instanceof Boolean || v instanceof Double
                    || v instanceof Calendar || v instanceof String[]) {
                out.put(e.getKey(), v);
            } else if (v instanceof Integer) {
                out.put(e.getKey(), ((Integer) v).longValue());
            } else if (v instanceof Date) {
                Calendar c = Calendar.getInstance();
                c.setTime((Date) v);
                out.put(e.getKey(), c);
            } else if (v != null) {
                out.put(e.getKey(), String.valueOf(v));
            }
        }
        return out;
    }

    static String str(ValueMap vm, String name) {
        Object v = vm.get(name);
        return v == null ? null : convert(v, String.class);
    }
}
