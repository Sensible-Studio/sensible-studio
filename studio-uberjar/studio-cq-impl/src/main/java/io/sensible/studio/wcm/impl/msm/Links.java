/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import org.apache.sling.api.resource.ResourceResolver;

/**
 * Rewrites links from a blueprint to its live copy inside property values: the path itself, child paths, renditions
 * ({@code .html}, selectors, fragments) and links inside rich text. A link is only rewritten when the linked page
 * exists in the live copy.
 */
final class Links {

    private Links() {
    }

    static String rewrite(ResourceResolver resolver, String value, String from, String to) {
        if (value == null || value.indexOf(from) < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int i = value.indexOf(from); i >= 0; i = value.indexOf(from, i + from.length())) {
            if (!startsWholePath(value, i)) {
                continue;
            }
            int end = endOfPath(value, i + from.length());
            String rest = value.substring(i + from.length(), end);
            if (!rest.isEmpty() && rest.charAt(0) != '/' && rest.charAt(0) != '.') {
                continue; // a longer name: /content/site/en-gb is no link to /content/site/en
            }
            String relPage = pagePart(rest);
            if (resolver.getResource(to + relPage) == null) {
                continue;
            }
            sb.append(value, last, i).append(to);
            last = i + from.length();
        }
        return last == 0 ? value : sb.append(value.substring(last)).toString();
    }

    private static boolean startsWholePath(String value, int i) {
        if (i == 0) {
            return true;
        }
        char c = value.charAt(i - 1);
        return !(Character.isLetterOrDigit(c) || c == '/' || c == '-' || c == '_' || c == '.');
    }

    private static int endOfPath(String value, int from) {
        int i = from;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>' || c == '?' || c == '#'
                    || c == ')' || c == ',' || c == ';') {
                break;
            }
            i++;
        }
        return i;
    }

    /** {@code /a/b.html} and {@code /a/b/jcr:content/x} become {@code /a/b}. */
    static String pagePart(String rest) {
        String r = rest;
        int c = r.indexOf("/jcr:content");
        if (c >= 0) {
            r = r.substring(0, c);
        }
        int slash = r.lastIndexOf('/');
        int dot = r.indexOf('.', Math.max(slash, 0));
        if (dot >= 0) {
            r = r.substring(0, dot);
        }
        return r;
    }
}
