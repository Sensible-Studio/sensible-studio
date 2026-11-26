/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.scanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OSGi manifest header parser: {@code a.b;c.d;version="[1,2)";resolution:=optional, e.f}.
 * Several packages may share one clause; quotes protect commas and semicolons.
 */
final class Header {

    final String name;
    private final Map<String, String> attrs;
    private final Map<String, String> directives;

    private Header(String name, Map<String, String> attrs, Map<String, String> directives) {
        this.name = name;
        this.attrs = attrs;
        this.directives = directives;
    }

    String attr(String key, String def) {
        return attrs.getOrDefault(key, def);
    }

    String directive(String key) {
        return directives.get(key);
    }

    static List<Header> parse(String value) {
        List<Header> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        for (String clause : split(value, ',')) {
            List<String> names = new ArrayList<>();
            Map<String, String> attrs = new HashMap<>();
            Map<String, String> directives = new HashMap<>();
            for (String part : split(clause, ';')) {
                String p = part.trim();
                int dir = p.indexOf(":=");
                int eq = p.indexOf('=');
                if (dir > 0 && dir < eq + 1) {
                    directives.put(p.substring(0, dir).trim(), unquote(p.substring(dir + 2)));
                } else if (eq > 0) {
                    String key = p.substring(0, eq).trim();
                    int colon = key.indexOf(':'); // typed attribute, e.g. version:Version
                    attrs.put(colon > 0 ? key.substring(0, colon) : key, unquote(p.substring(eq + 1)));
                } else if (!p.isEmpty()) {
                    names.add(p);
                }
            }
            for (String n : names) {
                result.add(new Header(n, attrs, directives));
            }
        }
        return result;
    }

    private static List<String> split(String value, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : value.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == sep && !quoted) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String unquote(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }
}
