/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.text;

import java.util.ArrayList;
import java.util.List;

/**
 * String and path helpers.
 */
public final class Text {

    private Text() {
    }

    /**
     * Splits {@code str} at {@code ch}, dropping empty parts.
     *
     * @return the non-empty parts
     */
    public static String[] explode(String str, int ch) {
        return explode(str, ch, false);
    }

    /**
     * Splits {@code str} at {@code ch}.
     *
     * @param respectEmpty {@code true} to keep empty parts
     * @return the parts
     */
    public static String[] explode(String str, int ch, boolean respectEmpty) {
        if (str == null || str.isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= str.length(); i++) {
            if (i == str.length() || str.charAt(i) == ch) {
                String part = str.substring(start, i);
                if (respectEmpty || !part.isEmpty()) {
                    parts.add(part);
                }
                start = i + 1;
            }
        }
        return parts.toArray(new String[0]);
    }

    /**
     * @return the parts joined with {@code delim}; {@code null} for a {@code null} array
     */
    public static String implode(String[] arr, String delim) {
        if (arr == null) {
            return null;
        }
        return String.join(delim == null ? "" : delim, arr);
    }

    /**
     * @return the last path segment, e.g. {@code c} for {@code /a/b/c}
     */
    public static String getName(String path) {
        if (path == null) {
            return null;
        }
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /**
     * @return the path {@code level} segments up, or {@code null} if that goes past the root
     */
    public static String getRelativeParent(String path, int level) {
        if (path == null) {
            return null;
        }
        String p = path;
        for (int i = 0; i < level; i++) {
            int idx = p.lastIndexOf('/');
            if (idx < 0) {
                return null;
            }
            p = idx == 0 ? "/" : p.substring(0, idx);
            if ("/".equals(p) && i < level - 1) {
                return null;
            }
        }
        return p;
    }

    /**
     * Escapes {@code & < > " '} for use in XML and HTML text and attributes.
     */
    public static String escapeXml(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(str.length() + 16);
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
