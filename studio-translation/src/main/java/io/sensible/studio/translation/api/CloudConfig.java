package io.sensible.studio.translation.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A translation cloud configuration, as AEM keeps them: a page at
 * {@code /conf/<conf>/settings/cloudconfigs/translation/<name>} whose {@code jcr:content} names the connector
 * ({@code connector}, a {@code TranslationServiceFactory} name) and holds its settings. Secrets are never stored
 * here: a setting such as {@code apiKey} holds a reference, {@code $[secret:NAME]} or {@code $[env:NAME]}, that the
 * connector resolves on the instance.
 */
public final class CloudConfig {

    /** Where the configurations of a context-aware configuration live, below {@code /conf/<conf>}. */
    public static final String BUCKET = "settings/cloudconfigs/translation";

    private final String path;
    private final String title;
    private final String connector;
    private final Map<String, Object> properties;

    public CloudConfig(String path, String title, String connector, Map<String, Object> properties) {
        this.path = path;
        this.title = title == null || title.isEmpty() ? (path == null ? "" : path.substring(path.lastIndexOf('/') + 1)) : title;
        this.connector = connector;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** The page path; null for settings not saved yet (testing a connection). */
    public String getPath() {
        return path;
    }

    public String getTitle() {
        return title;
    }

    /** The connector's factory name, e.g. {@code studio-ai}. */
    public String getConnector() {
        return connector;
    }

    /** The {@code /conf/<conf>} the configuration belongs to; null when not saved. */
    public String getConf() {
        if (path == null) return null;
        int i = path.indexOf("/" + BUCKET + "/");
        return i < 0 ? null : path.substring(0, i);
    }

    /** All settings of {@code jcr:content} (values as stored: String, Long, Boolean, String[] ...). */
    public Map<String, Object> getProperties() {
        return properties;
    }

    public String get(String name) {
        Object v = properties.get(name);
        if (v instanceof Object[]) return ((Object[]) v).length == 0 ? null : String.valueOf(((Object[]) v)[0]);
        return v == null ? null : String.valueOf(v);
    }

    public int getInt(String name, int fallback) {
        Object v = properties.get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null || String.valueOf(v).trim().isEmpty() ? fallback : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String[] getArray(String name) {
        Object v = properties.get(name);
        if (v == null) return new String[0];
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            String[] out = new String[a.length];
            for (int i = 0; i < a.length; i++) out[i] = String.valueOf(a[i]);
            return out;
        }
        if (v instanceof java.util.Collection) return ((java.util.Collection<?>) v).stream().map(String::valueOf).toArray(String[]::new);
        return new String[]{String.valueOf(v)};
    }
}
