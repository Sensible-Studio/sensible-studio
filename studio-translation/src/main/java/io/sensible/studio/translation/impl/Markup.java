package io.sensible.studio.translation.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a translation kept what must not change: the HTML tags in order, the link and image targets, and
 * placeholders such as {@code {0}}, {@code ${name}} or {@code {{name}}}.
 */
final class Markup {

    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>");
    private static final Pattern TARGET = Pattern.compile("\\b(href|src|data-[a-z-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}|\\{\\{[^}]+}}|\\{\\d+}");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    private Markup() {
    }

    /** Null when {@code translation} kept the structure of {@code source}; otherwise what differs. */
    static String damage(String source, String translation, boolean html) {
        if (translation == null) return "no translation";
        if (!placeholders(source).equals(placeholders(translation))) return "placeholders changed";
        if (!html) return null;
        List<String> a = skeleton(source);
        List<String> b = skeleton(translation);
        if (!a.equals(b)) return "markup changed (" + first(a, b) + ")";
        return null;
    }

    /** Whether a text has anything to translate (letters outside tags). */
    static boolean hasText(String s, boolean html) {
        String text = html ? s.replaceAll("<[^>]*>", " ") : s;
        return LETTER.matcher(text).find();
    }

    /** Tags in order, with the attributes whose values must not change. */
    static List<String> skeleton(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = TAG.matcher(html);
        while (m.find()) {
            StringBuilder sb = new StringBuilder(m.group(1)).append(m.group(2).toLowerCase());
            Matcher t = TARGET.matcher(m.group(3));
            while (t.find()) sb.append(' ').append(t.group(1).toLowerCase()).append('=').append(unquote(t.group(2)));
            out.add(sb.toString());
        }
        return out;
    }

    private static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) out.add(m.group());
        out.sort(String::compareTo);
        return out;
    }

    private static String unquote(String v) {
        return (v.startsWith("\"") || v.startsWith("'")) && v.length() >= 2 ? v.substring(1, v.length() - 1) : v;
    }

    private static String first(List<String> a, List<String> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "∅";
            String y = i < b.size() ? b.get(i) : "∅";
            if (!x.equals(y)) return "<" + x + "> became <" + y + ">";
        }
        return "?";
    }
}
