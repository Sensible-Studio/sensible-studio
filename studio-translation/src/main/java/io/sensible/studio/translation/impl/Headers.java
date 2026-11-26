package io.sensible.studio.translation.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extra HTTP headers of a model API ({@code "Name: value"} lines), for providers that authenticate or route
 * differently ({@code api-key} for Azure OpenAI, organisation or project headers ...). Values may hold secret
 * references ({@code $[secret:NAME]}, also inside a value such as {@code Bearer $[secret:TOKEN]}); headers that
 * carry credentials must use them.
 */
final class Headers {

    /** Headers the HTTP client sets itself. */
    private static final Set<String> RESTRICTED = new HashSet<>(Arrays.asList(
            "connection", "content-length", "expect", "host", "upgrade", "content-type"));
    /** Headers that authenticate the request. */
    private static final Set<String> AUTH = new HashSet<>(Arrays.asList("authorization", "api-key", "x-api-key", "x-goog-api-key"));

    private Headers() {
    }

    /** A header line's name and value; null when it is not {@code Name: value}. */
    static String[] split(String line) {
        if (line == null) return null;
        int i = line.indexOf(':');
        if (i <= 0) return null;
        return new String[]{line.substring(0, i).trim(), line.substring(i + 1).trim()};
    }

    /** What is wrong with a header line, or null. {@code literalSecretsAllowed}: outside the repository (OSGi). */
    static String problem(String line, boolean literalSecretsAllowed) {
        String[] nv = split(line);
        if (nv == null || nv[0].isEmpty()) return "Write a header as Name: value.";
        if (!nv[0].matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) return "\"" + nv[0] + "\" is not a header name.";
        if (RESTRICTED.contains(nv[0].toLowerCase(Locale.ROOT))) return nv[0] + " is set by Sensible Studio itself.";
        if (nv[1].isEmpty()) return "The header " + nv[0] + " has no value.";
        if (!literalSecretsAllowed && carriesCredentials(nv[0]) && !Secrets.hasReference(nv[1])) {
            return "The header " + nv[0] + " carries a credential: refer to a secret, e.g. " + nv[0] + ": $[secret:NAME].";
        }
        return null;
    }

    static boolean isAuth(String name) {
        return name != null && AUTH.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Names that suggest a credential (keys, tokens, secrets, passwords, authorisation). */
    static boolean carriesCredentials(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return isAuth(n) || n.contains("key") || n.contains("token") || n.contains("secret") || n.contains("password") || n.contains("auth");
    }

    static boolean hasAuth(String[] lines) {
        if (lines == null) return false;
        for (String l : lines) {
            String[] nv = split(l);
            if (nv != null && isAuth(nv[0])) return true;
        }
        return false;
    }

    /** The headers with their references resolved; unresolved references are added to {@code missing}. */
    static Map<String, String> resolve(String[] lines, Secrets secrets, List<String> missing) {
        Map<String, String> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (String l : lines) {
            String[] nv = split(l);
            if (nv == null) continue;
            out.put(nv[0], secrets.expand(nv[1], missing));
        }
        return out;
    }

    /** The references a set of header lines uses. */
    static List<String> references(String[] lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String l : lines) {
            java.util.regex.Matcher m = Secrets.EMBEDDED.matcher(l);
            while (m.find()) out.add(m.group());
        }
        return out;
    }
}
