/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.caconfig.management.ConfigurationCollectionData;
import org.apache.sling.caconfig.management.ConfigurationData;
import org.apache.sling.caconfig.management.ConfigurationManager;
import org.apache.sling.caconfig.management.ValueInfo;
import org.apache.sling.caconfig.resource.ConfigurationResourceResolver;
import org.apache.sling.caconfig.spi.ConfigurationCollectionPersistData;
import org.apache.sling.caconfig.spi.ConfigurationPersistData;
import org.apache.sling.caconfig.spi.metadata.ConfigurationMetadata;
import org.apache.sling.caconfig.spi.metadata.PropertyMetadata;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Site configurations: Sling's context-aware configurations ({@code /conf/<site>/sling:configs/<name>}),
 * read and written through Sling's own {@link ConfigurationManager}, so inheritance, defaults, overrides and
 * the persistence strategy are Sling's. {@code /bin/studio/siteconfig.json}:
 * <ul>
 * <li>GET {@code op=list&path=<content path>}: the context the path belongs to (the nearest ancestor with a
 *     {@code sling:configRef}) and every configuration the bundles of this instance define, with whether this
 *     context has values of its own for it;</li>
 * <li>GET {@code op=get&path=&name=}: one configuration — its properties with type, default, the value set
 *     here, the effective value and where that comes from; a collection answers its items;</li>
 * <li>POST {@code op=save&path=&name=} with {@code p.<property>=<value>} (repeated for a list; a property
 *     left out is not set here, so it is inherited or the default); a collection: {@code items=<n>},
 *     {@code i.<k>.name}, {@code i.<k>.p.<property>};</li>
 * <li>POST {@code op=delete&path=&name=}: what this context set is removed, the inherited values show again.</li>
 * </ul>
 * POSTs need {@code X-Studio-CSRF: 1} and write with the session of the person asking.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/siteconfig",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class SiteConfigServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient ConfigurationManager manager;

    @Reference
    private transient ConfigurationResourceResolver contexts;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        Resource resource = resource(request, response);
        if (resource == null) {
            return;
        }
        String op = request.getParameter("op");
        String name = request.getParameter("name");
        try {
            if ("get".equals(op) && name != null) {
                send(response, 200, configuration(resource, name));
            } else {
                send(response, 200, list(resource));
            }
        } catch (RuntimeException e) {
            send(response, 500, error(e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!"1".equals(request.getHeader("X-Studio-CSRF"))) {
            send(response, 403, error("missing X-Studio-CSRF header"));
            return;
        }
        Resource resource = resource(request, response);
        if (resource == null) {
            return;
        }
        String op = request.getParameter("op");
        String name = request.getParameter("name");
        if (name == null || name.isEmpty()) {
            send(response, 400, error("name is missing"));
            return;
        }
        if (contexts.getContextPath(resource) == null) {
            send(response, 409, error("No site configuration applies to " + resource.getPath() + ": none of its ancestors names one (sling:configRef)"));
            return;
        }
        try {
            ConfigurationMetadata metadata = manager.getConfigurationMetadata(name);
            if ("delete".equals(op)) {
                manager.deleteConfiguration(resource, name);
            } else if ("save".equals(op)) {
                if (metadata != null && metadata.isCollection()) {
                    int n = Integer.parseInt(request.getParameter("items") == null ? "0" : request.getParameter("items"));
                    List<ConfigurationPersistData> items = new ArrayList<>();
                    for (int k = 0; k < n; k++) {
                        String itemName = request.getParameter("i." + k + ".name");
                        ConfigurationPersistData item = new ConfigurationPersistData(values(request, "i." + k + ".p.", metadata));
                        items.add(itemName == null || itemName.isEmpty() ? item : item.collectionItemName(itemName));
                    }
                    manager.persistConfigurationCollection(resource, name, new ConfigurationCollectionPersistData(items));
                } else {
                    Map<String, Object> values = new LinkedHashMap<>(values(request, "p.", metadata));
                    keepWhatPublishingWrote(resource, name, values);
                    // When it was changed, for "Changed since it was published": without it an edited
                    // configuration read "On the live site as it is here" while the live site had the earlier one.
                    values.put("cq:lastModified", Calendar.getInstance());
                    values.put("cq:lastModifiedBy", request.getResourceResolver().getUserID());
                    manager.persistConfiguration(resource, name, new ConfigurationPersistData(values));
                }
            } else {
                send(response, 400, error("op is save or delete"));
                return;
            }
            request.getResourceResolver().commit();
            send(response, 200, configuration(resource, name));
        } catch (NumberFormatException e) {
            send(response, 422, error("Not a number: " + e.getMessage()));
        } catch (RuntimeException e) {
            request.getResourceResolver().revert();
            send(response, 500, error(e.getMessage()));
        }
    }

    /**
     * Saving replaces every property of the configuration, and with them went cq:lastReplicated and its two
     * companions: a published configuration, once edited, read "Not published" and could not be unpublished,
     * while the live site went on serving the earlier values.
     */
    private void keepWhatPublishingWrote(Resource resource, String name, Map<String, Object> values) {
        ConfigurationData before = manager.getConfiguration(resource, name);
        if (before == null || before.isInherited() || before.getResourcePath() == null) {
            return;
        }
        Resource stored = resource.getResourceResolver().getResource(before.getResourcePath());
        if (stored == null) {
            return;
        }
        for (Map.Entry<String, Object> e : stored.getValueMap().entrySet()) {
            if (e.getKey().startsWith("cq:lastReplicat") || e.getKey().startsWith("cq:lastPublished")) {
                values.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    private Resource resource(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getResourceResolver().getUserID();
        if (user == null || "anonymous".equals(user)) {
            send(response, 401, error("sign in"));
            return null;
        }
        String path = request.getParameter("path");
        Resource resource = path == null ? null : request.getResourceResolver().getResource(path);
        if (resource == null) {
            send(response, 404, error("No such path: " + path));
        }
        return resource;
    }

    Map<String, Object> list(Resource resource) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", resource.getPath());
        out.put("contextPath", contexts.getContextPath(resource));
        out.put("contextPaths", new ArrayList<>(contexts.getAllContextPaths(resource)));
        List<Map<String, Object>> configs = new ArrayList<>();
        for (String name : manager.getConfigurationNames()) {
            ConfigurationMetadata metadata = manager.getConfigurationMetadata(name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", metadata == null || metadata.getLabel() == null ? name : metadata.getLabel());
            m.put("description", metadata == null ? null : metadata.getDescription());
            boolean collection = metadata != null && metadata.isCollection();
            m.put("collection", collection);
            m.put("defined", metadata != null);
            String source = null;
            boolean inherited = false;
            int items = 0;
            if (collection) {
                ConfigurationCollectionData data = manager.getConfigurationCollection(resource, name);
                items = data == null ? 0 : data.getItems().size();
                source = data == null ? null : data.getResourcePath();
            } else {
                ConfigurationData data = manager.getConfiguration(resource, name);
                source = data == null ? null : data.getResourcePath();
                inherited = data != null && data.isInherited();
            }
            m.put("items", items);
            m.put("resourcePath", source);
            m.put("set", source != null && (collection ? items > 0 : !inherited));
            m.put("inherited", inherited);
            configs.add(m);
        }
        out.put("configs", configs);
        return out;
    }

    Map<String, Object> configuration(Resource resource, String name) {
        ConfigurationMetadata metadata = manager.getConfigurationMetadata(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("label", metadata == null || metadata.getLabel() == null ? name : metadata.getLabel());
        out.put("description", metadata == null ? null : metadata.getDescription());
        out.put("path", resource.getPath());
        out.put("contextPath", contexts.getContextPath(resource));
        boolean collection = metadata != null && metadata.isCollection();
        out.put("collection", collection);
        if (collection) {
            ConfigurationCollectionData data = manager.getConfigurationCollection(resource, name);
            List<Map<String, Object>> items = new ArrayList<>();
            if (data != null) {
                for (ConfigurationData item : data.getItems()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", item.getCollectionItemName());
                    m.put("resourcePath", item.getResourcePath());
                    m.put("inherited", item.isInherited());
                    m.put("properties", properties(item));
                    items.add(m);
                }
            }
            out.put("resourcePath", data == null ? null : data.getResourcePath());
            out.put("items", items);
            // an empty item: the properties a new one has
            ConfigurationData blank = manager.newCollectionItem(resource, name);
            out.put("properties", blank == null ? new ArrayList<>() : properties(blank));
        } else {
            ConfigurationData data = manager.getConfiguration(resource, name);
            out.put("resourcePath", data == null ? null : data.getResourcePath());
            out.put("inherited", data != null && data.isInherited());
            out.put("overridden", data != null && data.isOverridden());
            out.put("properties", data == null ? new ArrayList<>() : properties(data));
        }
        return out;
    }

    private static List<Map<String, Object>> properties(ConfigurationData data) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String property : data.getPropertyNames()) {
            ValueInfo<?> info = data.getValueInfo(property);
            // What the repository keeps about the node (jcr:, sling:, and cq:lastReplicated… once it is published)
            // is not a setting: published configurations showed three fields more, one of them a GregorianCalendar.
            if (info == null || property.indexOf(':') >= 0) {
                continue;
            }
            PropertyMetadata<?> pm = info.getPropertyMetadata();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", property);
            m.put("label", pm == null || pm.getLabel() == null ? property : pm.getLabel());
            m.put("description", pm == null ? null : pm.getDescription());
            Class<?> type = pm == null ? (info.getEffectiveValue() == null ? String.class : info.getEffectiveValue().getClass()) : pm.getType();
            m.put("type", typeName(type));
            m.put("multiple", type.isArray());
            m.put("nested", pm != null && pm.isNestedConfiguration());
            m.put("default", pm == null ? null : json(pm.getDefaultValue()));
            m.put("value", json(info.getValue()));
            m.put("effectiveValue", json(info.getEffectiveValue()));
            m.put("isDefault", info.isDefault());
            m.put("inherited", info.isInherited());
            m.put("overridden", info.isOverridden());
            m.put("sourcePath", info.getConfigSourcePath());
            m.put("order", pm == null ? 0 : pm.getOrder());
            out.add(m);
        }
        out.sort((a, b) -> Integer.compare((Integer) a.get("order"), (Integer) b.get("order")));
        return out;
    }

    /** string | boolean | integer | number, of the property or of the elements of its list. */
    static String typeName(Class<?> type) {
        Class<?> t = type.isArray() ? type.getComponentType() : type;
        if (t == Boolean.class || t == boolean.class) {
            return "boolean";
        }
        if (t == Integer.class || t == int.class || t == Long.class || t == long.class) {
            return "integer";
        }
        if (t == Double.class || t == double.class || t == Float.class || t == float.class) {
            return "number";
        }
        return "string";
    }

    /** A value as JSON takes it: an array becomes a list. */
    static Object json(Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                list.add(json(Array.get(value, i)));
            }
            return list;
        }
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<?>) value);
        }
        return value instanceof Number || value instanceof Boolean ? value : String.valueOf(value);
    }

    /** The posted values under a prefix, typed as the configuration's metadata says (strings without metadata). */
    static Map<String, Object> values(SlingHttpServletRequest request, String prefix, ConfigurationMetadata metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String property = e.getKey().substring(prefix.length());
            PropertyMetadata<?> pm = metadata == null ? null : metadata.getPropertyMetadata().get(property);
            Class<?> type = pm == null ? (e.getValue().length > 1 ? String[].class : String.class) : pm.getType();
            Object value = convert(e.getValue(), type);
            if (value != null) {
                out.put(property, value);
            }
        }
        return out;
    }

    /** Posted strings as a value of the type; null for nothing (an empty field). */
    static Object convert(String[] posted, Class<?> type) {
        if (type.isArray()) {
            List<Object> list = new ArrayList<>();
            for (String s : posted) {
                Object one = one(s, type.getComponentType());
                if (one != null) {
                    list.add(one);
                }
            }
            Class<?> boxed = box(type.getComponentType());
            Object array = Array.newInstance(boxed, list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, list.get(i));
            }
            return list.isEmpty() ? null : array;
        }
        return posted.length == 0 ? null : one(posted[0], type);
    }

    private static Class<?> box(Class<?> t) {
        return t == int.class ? Integer.class : t == long.class ? Long.class : t == double.class ? Double.class : t == boolean.class ? Boolean.class : t == float.class ? Double.class : t;
    }

    private static Object one(String s, Class<?> type) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Class<?> t = box(type);
        if (t == Boolean.class) {
            return Boolean.valueOf(s);
        }
        if (t == Integer.class) {
            return Integer.valueOf(s.trim());
        }
        if (t == Long.class) {
            return Long.valueOf(s.trim());
        }
        if (t == Double.class || t == Float.class) {
            return Double.valueOf(s.trim());
        }
        return s;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null ? "failed" : message);
        return m;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }
}
