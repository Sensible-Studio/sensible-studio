/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * ContextHub's configuration, as AEM's Tools &gt; Sites &gt; ContextHub and in AEM's own layout, so a site's
 * configuration from AEM (WKND's) is read and kept: {@code <conf>/settings/cloudsettings/default/contexthub}
 * ({@code baseconfiguration}) with one child per store ({@code basestore}: {@code storeType}, {@code enabled},
 * {@code jcr:title}, {@code configjson}) and per UI mode ({@code uimode}, whose children are its modules).
 *
 * <ul>
 * <li>Stores Sensible Studio runs: {@code granite.profile}, {@code contexthub.geolocation},
 *     {@code contexthub.surferinfo}, {@code contexthub.generic} (values it starts with: {@code configjson.initialValues})
 *     and {@code contexthub.generic-jsonp} (the answer of an address read in the visitor's browser:
 *     {@code configjson.url}, {@code refresh} in minutes, {@code select}, or AEM's {@code service} host/port/path).
 *     Other store types (AEM's segmentation, emulators, page data…) are listed and kept as they are.</li>
 * <li>The fields of the ContextHub bar: a UI mode of its own, {@code studio-bar}, whose modules
 *     ({@code moduleType=studio.field}) say {@code {title, store, property, type, options}} in their
 *     {@code configjson}. AEM's own modes are kept as they are; the bar in the page shows their modules.</li>
 * </ul>
 *
 * Without a configuration of its own a site uses {@code /conf/global}'s, else the default (the three stores built
 * in and the bar's usual fields). Saving changes only what Sensible Studio manages; Reset takes away what it added
 * ({@code studio:managed} stores, the {@code studio-bar} mode, values, a store turned off) or, when Sensible Studio
 * made the whole configuration ({@code studio:created}), removes it.
 */
public final class ContextHubConfig {

    public static final String SUBPATH = "/settings/cloudsettings/default/contexthub";
    static final String CONFIG_RT = "/libs/granite/contexthub/cloudsettings/components/baseconfiguration";
    static final String STORE_RT = "/libs/granite/contexthub/cloudsettings/components/basestore";
    static final String MODE_RT = "/libs/granite/contexthub/cloudsettings/components/uimode";
    static final String BAR = "studio-bar";
    static final String FIELD_TYPE = "studio.field";
    static final String CREATED = "studio:created";
    static final String MANAGED = "studio:managed";
    static final String ENABLED_WAS = "studio:enabledWas";
    static final String CONFIG_WAS = "studio:configjsonWas";

    static final List<String> BUILT_IN = Arrays.asList("profile", "geolocation", "surferinfo");
    static final List<String> FIELD_TYPES = Arrays.asList("text", "number", "select");
    static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9_-]{0,39}");
    static final Pattern PROPERTY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
    static final Pattern URL = Pattern.compile("(https?://[^\\s\"'<>]+|/[^\\s\"'<>]*)");
    static final int MAX_STORES = 20;
    static final int MAX_MODULES = 30;
    static final int MAX_INITIAL = 4000;

    private ContextHubConfig() {
    }

    /** AEM's store type → the kind the console and the client know; null: a store Sensible Studio does not run. */
    static String kindOf(String storeType) {
        if (storeType == null) {
            return null;
        }
        switch (storeType) {
            case "granite.profile":
            case "contexthub.profile":
                return "profile";
            case "contexthub.geolocation":
                return "geolocation";
            case "contexthub.surferinfo":
                return "surferinfo";
            case "contexthub.generic":
                return "generic";
            case "contexthub.generic-jsonp":
                return "json";
            default:
                return null;
        }
    }

    static String storeTypeOf(String kind) {
        switch (kind) {
            case "profile":
                return "granite.profile";
            case "geolocation":
                return "contexthub.geolocation";
            case "surferinfo":
                return "contexthub.surferinfo";
            case "json":
                return "contexthub.generic-jsonp";
            default:
                return "contexthub.generic";
        }
    }

    /** The fields of the bar when nothing is configured (the ones the bar always had). */
    static List<Map<String, Object>> defaultModules() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(module("First name", "profile", "givenName", "text"));
        out.add(module("Age", "profile", "age", "number"));
        out.add(module("Gender", "profile", "gender", "select", "female", "male", "other"));
        out.add(module("Interests", "profile", "interests", "text"));
        out.add(module("Loyalty", "profile", "loyalty", "select", "none", "member", "gold", "platinum"));
        out.add(module("Country", "geolocation", "country", "text"));
        out.add(module("City", "geolocation", "city", "text"));
        out.add(module("Device", "surferinfo", "device", "select", "desktop", "tablet", "mobile"));
        out.add(module("Month", "surferinfo", "month", "number"));
        return out;
    }

    private static Map<String, Object> module(String title, String store, String property, String type, String... options) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("store", store);
        m.put("property", property);
        m.put("type", type);
        if (options.length > 0) {
            m.put("options", new ArrayList<>(Arrays.asList(options)));
        }
        return m;
    }

    private static String builtInTitle(String kind) {
        return "profile".equals(kind) ? "Profile" : "geolocation".equals(kind) ? "Location" : "Browser and time";
    }

    /** Where a configuration's ContextHub settings are kept. */
    public static String pathOf(String conf) {
        return conf + SUBPATH;
    }

    /**
     * The ContextHub configuration that applies to a page or a configuration: its own, else {@code /conf/global}'s,
     * else the default. {@code conf} says whose it is, {@code from} where it was read (null: the default).
     */
    public static Map<String, Object> effective(ResourceResolver rr, String pathOrConf) {
        String conf = pathOrConf != null && pathOrConf.startsWith("/conf/") ? confRoot(pathOrConf) : Segments.confOf(rr, pathOrConf);
        Resource own = rr.getResource(pathOf(conf));
        Resource used = own != null ? own : rr.getResource(pathOf(Segments.GLOBAL));
        Map<String, Object> out = used == null ? defaults() : read(used);
        out.put("conf", conf);
        out.put("from", used == null ? null : used.getPath());
        out.put("custom", own != null);
        out.put("madeHere", own != null && own.getValueMap().get(CREATED, false));
        return out;
    }

    /** {@code /conf/wknd/settings/...} → {@code /conf/wknd}. */
    static String confRoot(String path) {
        String[] parts = path.split("/");
        return parts.length >= 3 ? "/" + parts[1] + "/" + parts[2] : path;
    }

    static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> stores = new ArrayList<>();
        for (String s : BUILT_IN) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s);
            m.put("title", builtInTitle(s));
            m.put("type", s);
            m.put("enabled", true);
            m.put("initial", new LinkedHashMap<String, Object>());
            stores.add(m);
        }
        out.put("stores", stores);
        out.put("modules", defaultModules());
        out.put("modes", new ArrayList<>());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> configJson(ValueMap v) {
        try {
            Object o = Json.parse(v.get("configjson", "{}"));
            return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    static boolean isStore(Resource r) {
        ValueMap v = r.getValueMap();
        return v.get("storeType", String.class) != null || STORE_RT.equals(v.get("sling:resourceType", String.class));
    }

    static boolean isMode(Resource r) {
        return MODE_RT.equals(r.getValueMap().get("sling:resourceType", String.class));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Resource config) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> stores = new ArrayList<>();
        List<Object> modes = new ArrayList<>();
        List<Object> modules = null;
        for (Resource c : config.getChildren()) {
            ValueMap v = c.getValueMap();
            if (isStore(c)) {
                String storeType = v.get("storeType", String.class);
                String kind = kindOf(storeType);
                Map<String, Object> cj = configJson(v);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.getName());
                m.put("title", v.get("jcr:title", c.getName()));
                m.put("type", kind == null ? "other" : kind);
                m.put("storeType", storeType);
                m.put("enabled", v.get("enabled", true));
                m.put("managed", v.get(MANAGED, false));
                m.put("initial", cj.get("initialValues") instanceof Map ? cj.get("initialValues") : new LinkedHashMap<String, Object>());
                if ("json".equals(kind)) {
                    m.put("url", cj.get("url") != null ? String.valueOf(cj.get("url")) : serviceUrl(cj.get("service")));
                    m.put("refresh", cj.get("refresh") instanceof Number ? ((Number) cj.get("refresh")).longValue() : 60L);
                    m.put("select", cj.get("select") == null ? "" : String.valueOf(cj.get("select")));
                }
                stores.add(m);
            } else if (isMode(c)) {
                if (BAR.equals(c.getName())) {
                    modules = new ArrayList<>();
                    for (Resource f : c.getChildren()) {
                        if (!FIELD_TYPE.equals(f.getValueMap().get("moduleType", String.class))) {
                            continue;
                        }
                        Map<String, Object> cj = configJson(f.getValueMap());
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("title", String.valueOf(cj.getOrDefault("title", "")));
                        m.put("store", String.valueOf(cj.getOrDefault("store", "")));
                        m.put("property", String.valueOf(cj.getOrDefault("property", "")));
                        m.put("type", String.valueOf(cj.getOrDefault("type", "text")));
                        if (cj.get("options") instanceof List && !((List<?>) cj.get("options")).isEmpty()) {
                            List<String> options = new ArrayList<>();
                            for (Object x : (List<?>) cj.get("options")) {
                                options.add(String.valueOf(x));
                            }
                            m.put("options", options);
                        }
                        modules.add(m);
                    }
                } else {
                    // AEM's own UI modes: listed, kept as they are
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", c.getName());
                    m.put("title", v.get("jcr:title", c.getName()));
                    List<String> types = new ArrayList<>();
                    // what the ContextHub bar in the page needs to show each module: its type, its title and,
                    // for AEM's generic module (contexthub.base), the stores and the template it shows them with
                    List<Object> items = new ArrayList<>();
                    for (Resource f : c.getChildren()) {
                        ValueMap fv = f.getValueMap();
                        String t = fv.get("moduleType", String.class);
                        if (t == null) {
                            continue;
                        }
                        types.add(t);
                        Map<String, Object> cj = configJson(fv);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", f.getName());
                        item.put("title", cj.get("title") instanceof String ? cj.get("title") : fv.get("jcr:title", f.getName()));
                        item.put("type", t);
                        item.put("enabled", fv.get("enabled", true));
                        if (cj.get("storeMapping") instanceof Map) {
                            item.put("storeMapping", cj.get("storeMapping"));
                        }
                        if (cj.get("template") instanceof String) {
                            item.put("template", cj.get("template"));
                        }
                        items.add(item);
                    }
                    m.put("modules", types);
                    m.put("items", items);
                    modes.add(m);
                }
            }
        }
        if (modules == null) {
            // the usual fields, of the stores this configuration has
            Set<String> names = new HashSet<>();
            for (Object o : stores) {
                names.add(String.valueOf(((Map<?, ?>) o).get("name")));
            }
            modules = new ArrayList<>();
            for (Map<String, Object> m : defaultModules()) {
                if (names.contains(String.valueOf(m.get("store")))) {
                    modules.add(m);
                }
            }
        }
        out.put("stores", stores);
        out.put("modules", modules);
        out.put("modes", modes);
        ValueMap v = config.getValueMap();
        Calendar modified = v.get("jcr:lastModified", Calendar.class);
        out.put("modified", modified == null ? null : modified.toInstant().toString());
        out.put("modifiedBy", v.get("jcr:lastModifiedBy", String.class));
        return out;
    }

    /** AEM's JSONP store service ({@code {secure, host, port, path}}) as an address. */
    @SuppressWarnings("unchecked")
    static String serviceUrl(Object service) {
        if (!(service instanceof Map)) {
            return "";
        }
        Map<String, Object> s = (Map<String, Object>) service;
        Object host = s.get("host");
        if (host == null) {
            return "";
        }
        boolean secure = !"false".equals(String.valueOf(s.get("secure")));
        Object port = s.get("port");
        String p = port == null || "80".equals(String.valueOf(port)) || "443".equals(String.valueOf(port)) ? "" : ":" + port;
        Object path = s.get("path");
        return (secure ? "https://" : "http://") + host + p + (path == null ? "" : String.valueOf(path));
    }

    /** What the visitor's browser needs: the stores it runs and the bar's fields. */
    public static Map<String, Object> forClient(Map<String, Object> effective) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> stores = new ArrayList<>();
        for (Object o : (List<?>) effective.get("stores")) {
            Map<?, ?> s = (Map<?, ?>) o;
            if ("other".equals(s.get("type"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.get("name"));
            m.put("type", s.get("type"));
            m.put("enabled", s.get("enabled"));
            Object initial = s.get("initial");
            if (initial instanceof Map && !((Map<?, ?>) initial).isEmpty()) {
                m.put("initial", initial);
            }
            if ("json".equals(s.get("type"))) {
                m.put("url", s.get("url"));
                m.put("refresh", s.get("refresh"));
                m.put("select", s.get("select"));
            }
            stores.add(m);
        }
        out.put("stores", stores);
        out.put("modules", effective.get("modules"));
        return out;
    }

    /**
     * Saves a configuration's ContextHub settings from what the console sends:
     * {@code {"stores":[{name,title,type,enabled,initial,url,refresh,select}], "modules":[{title,store,property,type,options}]}}.
     * Stores of other types are sent back with {@code type=other}: only whether they are on changes.
     */
    @SuppressWarnings("unchecked")
    public static void save(Session session, String conf, Object json) throws RepositoryException {
        if (conf == null || !conf.startsWith("/conf/") || conf.split("/").length != 3) {
            throw new IllegalArgumentException("A configuration is /conf/<name>");
        }
        if (!session.nodeExists(conf)) {
            throw new IllegalArgumentException("No configuration " + conf);
        }
        if (!(json instanceof Map)) {
            throw new IllegalArgumentException("The configuration is a JSON object");
        }
        Map<String, Object> in = (Map<String, Object>) json;
        List<Object> stores = in.get("stores") instanceof List ? (List<Object>) in.get("stores") : new ArrayList<>();
        List<Object> modules = in.get("modules") instanceof List ? (List<Object>) in.get("modules") : new ArrayList<>();
        if (stores.size() > MAX_STORES) {
            throw new IllegalArgumentException("At most " + MAX_STORES + " stores");
        }
        if (modules.size() > MAX_MODULES) {
            throw new IllegalArgumentException("At most " + MAX_MODULES + " fields in the bar");
        }
        String path = pathOf(conf);
        Node existing = session.nodeExists(path) ? session.getNode(path) : null;

        // check everything first: nothing is written when something is wrong
        Set<String> names = new HashSet<>();
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Object o : stores) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("Each store is a JSON object");
            }
            Map<String, Object> s = (Map<String, Object>) o;
            String name = str(s.get("name"));
            String kind = str(s.get("type"));
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("A store's name is a word of letters, digits, - and _ starting with a small letter: " + name);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Two stores named " + name);
            }
            Node node = existing != null && existing.hasNode(name) ? existing.getNode(name) : null;
            if (node != null && !node.hasProperty("storeType")) {
                throw new IllegalArgumentException("The name " + name + " is taken by something else in the configuration");
            }
            String nodeKind = node == null ? null : kindOf(node.getProperty("storeType").getString());
            if (node != null && nodeKind == null) {
                kind = "other";
            } else if (node != null) {
                kind = nodeKind;
            } else if (BUILT_IN.contains(name)) {
                kind = name;
            } else if (!"generic".equals(kind) && !"json".equals(kind)) {
                throw new IllegalArgumentException("Store " + name + ": a store of your own keeps values or reads them from a JSON address");
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            c.put("kind", kind);
            c.put("title", str(s.get("title")));
            c.put("enabled", !Boolean.FALSE.equals(s.get("enabled")));
            if (!"other".equals(kind)) {
                Object initial = s.get("initial");
                if (initial != null && !(initial instanceof Map)) {
                    throw new IllegalArgumentException("Store " + name + ": the values it starts with are a JSON object");
                }
                if (initial != null && Json.write(initial).length() > MAX_INITIAL) {
                    throw new IllegalArgumentException("Store " + name + ": at most " + MAX_INITIAL + " characters of values");
                }
                c.put("initial", initial == null ? new LinkedHashMap<String, Object>() : initial);
            }
            if ("json".equals(kind)) {
                String url = str(s.get("url"));
                if (url == null || !URL.matcher(url).matches()) {
                    throw new IllegalArgumentException("Store " + name + ": the address is https://… or a path on this site");
                }
                long refresh = s.get("refresh") instanceof Number ? ((Number) s.get("refresh")).longValue() : 60L;
                if (refresh < 1 || refresh > 10_080) {
                    throw new IllegalArgumentException("Store " + name + ": read again every 1 to 10080 minutes");
                }
                String select = str(s.get("select"));
                if (select != null && !PROPERTY.matcher(select).matches()) {
                    throw new IllegalArgumentException("Store " + name + ": the part of the answer to keep is a dotted name, such as current or data.weather");
                }
                c.put("url", url);
                c.put("refresh", refresh);
                c.put("select", select);
            }
            clean.add(c);
        }
        if (names.contains(BAR)) {
            throw new IllegalArgumentException("The name " + BAR + " is the bar's own");
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Object o : modules) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("Each field is a JSON object");
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String title = str(m.get("title"));
            String store = str(m.get("store"));
            String property = str(m.get("property"));
            String type = str(m.get("type"));
            if (title == null) {
                throw new IllegalArgumentException("Each field of the bar has a label");
            }
            if (store == null || !names.contains(store)) {
                throw new IllegalArgumentException(title + ": no store " + store);
            }
            if (property == null || !PROPERTY.matcher(property).matches()) {
                throw new IllegalArgumentException(title + ": a property is a name such as age or address.city");
            }
            if (type == null) {
                type = "text";
            }
            if (!FIELD_TYPES.contains(type)) {
                throw new IllegalArgumentException(title + ": a field is text, a number or a choice");
            }
            List<String> options = new ArrayList<>();
            if (m.get("options") instanceof List) {
                for (Object x : (List<?>) m.get("options")) {
                    String t = str(x);
                    if (t != null && !options.contains(t)) {
                        options.add(t);
                    }
                }
            }
            if ("select".equals(type) && options.isEmpty()) {
                throw new IllegalArgumentException(title + ": a choice needs its options");
            }
            if (options.size() > 50) {
                throw new IllegalArgumentException(title + ": at most 50 options");
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("title", title.length() > 60 ? title.substring(0, 60) : title);
            c.put("store", store);
            c.put("property", property);
            c.put("type", type);
            if (!options.isEmpty()) {
                c.put("options", options);
            }
            fields.add(c);
        }

        Node root = existing != null ? existing : create(session, path);
        for (Map<String, Object> c : clean) {
            String name = String.valueOf(c.get("name"));
            String kind = String.valueOf(c.get("kind"));
            Node n;
            if (root.hasNode(name)) {
                n = root.getNode(name);
            } else {
                n = root.addNode(name, "nt:unstructured");
                n.setProperty("sling:resourceType", STORE_RT);
                n.setProperty("storeType", storeTypeOf(kind));
                if (!BUILT_IN.contains(kind)) {
                    n.setProperty(MANAGED, true);
                }
            }
            boolean on = (Boolean) c.get("enabled");
            boolean was = !n.hasProperty("enabled") || n.getProperty("enabled").getBoolean();
            if (on != was && !n.hasProperty(ENABLED_WAS) && !n.hasProperty(MANAGED)) {
                n.setProperty(ENABLED_WAS, was);
            }
            n.setProperty("enabled", on);
            if (c.get("title") != null) {
                n.setProperty("jcr:title", String.valueOf(c.get("title")));
            } else if (!n.hasProperty("jcr:title")) {
                n.setProperty("jcr:title", BUILT_IN.contains(kind) ? builtInTitle(kind) : name);
            }
            if ("other".equals(kind)) {
                continue;
            }
            Map<String, Object> before = configJson(valueMap(n));
            Map<String, Object> cj = new LinkedHashMap<>(before);
            Map<String, Object> initial = (Map<String, Object>) c.get("initial");
            if (initial == null || initial.isEmpty()) {
                cj.remove("initialValues");
            } else {
                cj.put("initialValues", initial);
            }
            if ("json".equals(kind)) {
                cj.put("url", c.get("url"));
                cj.put("refresh", c.get("refresh"));
                if (c.get("select") == null) {
                    cj.remove("select");
                } else {
                    cj.put("select", c.get("select"));
                }
            }
            // an unchanged configuration stays as it was written (AEM's own, formatted)
            if (cj.equals(before)) {
                continue;
            }
            // the first change of a store Sensible Studio did not add: its configuration as written is kept for Reset
            if (!n.hasProperty(MANAGED) && !n.hasProperty(CONFIG_WAS)) {
                n.setProperty(CONFIG_WAS, n.hasProperty("configjson") ? n.getProperty("configjson").getString() : "");
            }
            if (cj.isEmpty()) {
                if (n.hasProperty("configjson")) {
                    n.getProperty("configjson").remove();
                }
            } else {
                n.setProperty("configjson", Json.write(cj));
            }
        }
        // stores Sensible Studio added and the console no longer lists go
        for (NodeIterator it = root.getNodes(); it.hasNext();) {
            Node n = it.nextNode();
            if (n.hasProperty(MANAGED) && n.hasProperty("storeType") && !names.contains(n.getName())) {
                n.remove();
            }
        }
        if (root.hasNode(BAR)) {
            root.getNode(BAR).remove();
        }
        Node bar = root.addNode(BAR, "nt:unstructured");
        bar.setProperty("sling:resourceType", MODE_RT);
        bar.setProperty("jcr:title", "Sensible Studio bar");
        bar.setProperty("icon", "coral-Icon--properties");
        bar.setProperty(MANAGED, true);
        int i = 0;
        for (Map<String, Object> f : fields) {
            Node m = bar.addNode("field" + i++, "nt:unstructured");
            m.setProperty("moduleType", FIELD_TYPE);
            m.setProperty("enabled", true);
            m.setProperty("jcr:title", String.valueOf(f.get("title")));
            m.setProperty("configjson", Json.write(f));
        }
        root.setProperty("jcr:lastModified", Calendar.getInstance());
        root.setProperty("jcr:lastModifiedBy", session.getUserID());
    }

    /**
     * Takes away what Sensible Studio added: the whole configuration when it made it, otherwise its stores, the bar's
     * fields, the values stores start with, and a store it turned off is on again.
     */
    public static void reset(Session session, String conf) throws RepositoryException {
        String p = pathOf(conf);
        if (!session.nodeExists(p)) {
            return;
        }
        Node root = session.getNode(p);
        if (root.hasProperty(CREATED) && root.getProperty(CREATED).getBoolean()) {
            root.remove();
            return;
        }
        for (NodeIterator it = root.getNodes(); it.hasNext();) {
            Node n = it.nextNode();
            if (n.hasProperty(MANAGED)) {
                n.remove();
                continue;
            }
            if (n.hasProperty(ENABLED_WAS)) {
                n.setProperty("enabled", n.getProperty(ENABLED_WAS).getBoolean());
                n.getProperty(ENABLED_WAS).remove();
            }
            if (n.hasProperty(CONFIG_WAS)) {
                String was = n.getProperty(CONFIG_WAS).getString();
                if (was.isEmpty()) {
                    if (n.hasProperty("configjson")) {
                        n.getProperty("configjson").remove();
                    }
                } else {
                    n.setProperty("configjson", was);
                }
                n.getProperty(CONFIG_WAS).remove();
            } else if (n.hasProperty("configjson")) {
                Map<String, Object> cj = configJson(valueMap(n));
                if (cj.remove("initialValues") != null) {
                    if (cj.isEmpty()) {
                        n.getProperty("configjson").remove();
                    } else {
                        n.setProperty("configjson", Json.write(cj));
                    }
                }
            }
        }
        for (String prop : new String[] {"jcr:lastModified", "jcr:lastModifiedBy"}) {
            if (root.hasProperty(prop)) {
                root.getProperty(prop).remove();
            }
        }
    }

    /** The store properties the configuration knows: the bar's fields and the values stores start with. */
    @SuppressWarnings("unchecked")
    public static List<String> properties(Map<String, Object> effective) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) effective.get("modules")) {
            Map<?, ?> m = (Map<?, ?>) o;
            String p = m.get("store") + "/" + m.get("property");
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        for (Object o : (List<?>) effective.get("stores")) {
            Map<String, Object> s = (Map<String, Object>) o;
            if (s.get("initial") instanceof Map) {
                for (String k : ((Map<String, Object>) s.get("initial")).keySet()) {
                    String p = s.get("name") + "/" + k;
                    if (!out.contains(p)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    private static Node create(Session session, String path) throws RepositoryException {
        boolean existed = session.nodeExists(path);
        Node n = folders(session, path);
        if (!existed) {
            n.setProperty("jcr:title", "ContextHub Configuration");
            n.setProperty("sling:resourceType", CONFIG_RT);
            n.setProperty(CREATED, true);
        }
        return n;
    }

    /** The node, made with its missing ancestors; not from the root, which only an administrator's session sees. */
    private static Node folders(Session session, String path) throws RepositoryException {
        if (session.nodeExists(path)) {
            return session.getNode(path);
        }
        int cut = path.lastIndexOf('/');
        if (cut <= 0) {
            throw new javax.jcr.PathNotFoundException(path);
        }
        return folders(session, path.substring(0, cut)).addNode(path.substring(cut + 1), "sling:OrderedFolder");
    }

    /** A node's string properties, enough for {@link #configJson}. */
    private static ValueMap valueMap(Node n) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (n.hasProperty("configjson")) {
            m.put("configjson", n.getProperty("configjson").getString());
        }
        return new org.apache.sling.api.wrappers.ValueMapDecorator(m);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
