/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search;

import java.util.Map;
import java.util.TreeMap;

/**
 * Converts flat query parameters ({@code path=/content}, {@code 1_property=foo},
 * {@code 1_property.value=bar}, {@code p.limit=10}) into a {@link PredicateGroup}.
 */
public final class PredicateConverter {

    private PredicateConverter() {
    }

    public static PredicateGroup createPredicates(Map<?, ?> params) {
        PredicateGroup root = new PredicateGroup("root");
        Map<String, Predicate> byName = new TreeMap<>();
        for (Map.Entry<?, ?> e : params.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object raw = e.getValue();
            String value = raw instanceof String[] ? ((String[]) raw)[0] : String.valueOf(raw);
            if (key.startsWith("p.")) {
                root.set(key, value);
                continue;
            }
            int dot = key.indexOf('.');
            String head = dot < 0 ? key : key.substring(0, dot);
            String type = head.replaceFirst("^[0-9]+_", "");
            Predicate p = byName.computeIfAbsent(head, h -> new Predicate(h, type));
            if (dot < 0) {
                p.set(type, value);
            } else {
                p.set(key.substring(dot + 1), value);
            }
        }
        byName.values().forEach(root::add);
        return root;
    }
}
