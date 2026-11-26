package io.sensible.studio.translation.impl;

import io.sensible.studio.translation.api.CloudConfig;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes translation cloud configurations, AEM's layout:
 * <pre>
 * /conf/&lt;conf&gt;/settings/cloudconfigs/translation/&lt;name&gt;   cq:Page
 *   jcr:content   jcr:title, connector (studio-ai), api, endpoint, model, apiKey ($[secret:NAME]),
 *                 maxTokens, maxChars, timeoutSeconds, instructions, doNotTranslate[]
 * </pre>
 * Content finds its configurations through {@code cq:conf} on itself or an ancestor (or their {@code jcr:content}),
 * then {@code /conf/global}.
 */
final class CloudConfigs {

    static final String GLOBAL = "/conf/global";
    static final String RESOURCE_TYPE = "studio/translation/cloudconfig";

    /** The settings a configuration may hold, with their kind. */
    static final Map<String, String> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put("api", "string");
        SETTINGS.put("endpoint", "string");
        SETTINGS.put("model", "string");
        SETTINGS.put("apiKey", "string");
        SETTINGS.put("maxTokens", "long");
        SETTINGS.put("maxChars", "long");
        SETTINGS.put("timeoutSeconds", "long");
        SETTINGS.put("instructions", "string");
        SETTINGS.put("doNotTranslate", "string[]");
        SETTINGS.put("preset", "string");
        SETTINGS.put("headers", "string[]");
    }

    private CloudConfigs() {
    }

    static CloudConfig read(Resource page) {
        if (page == null) return null;
        Resource content = page.getChild("jcr:content");
        if (content == null) return null;
        ValueMap vm = content.getValueMap();
        if (!vm.containsKey("connector") && !RESOURCE_TYPE.equals(vm.get("sling:resourceType", String.class))) return null;
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            if (!e.getKey().startsWith("jcr:") && !e.getKey().startsWith("sling:") && !e.getKey().startsWith("cq:")) props.put(e.getKey(), e.getValue());
        }
        Calendar modified = vm.get("cq:lastModified", Calendar.class);
        if (modified != null) props.put("lastModified", modified);
        String by = vm.get("cq:lastModifiedBy", String.class);
        if (by != null) props.put("lastModifiedBy", by);
        return new CloudConfig(page.getPath(), vm.get("jcr:title", String.class), vm.get("connector", AiTranslationServiceFactory.NAME), props);
    }

    /** The configurations of one conf ({@code /conf/<conf>}), by name. */
    static List<CloudConfig> list(ResourceResolver resolver, String conf) {
        List<CloudConfig> out = new ArrayList<>();
        Resource bucket = resolver.getResource(conf + "/" + CloudConfig.BUCKET);
        if (bucket == null) return out;
        List<Resource> children = new ArrayList<>();
        bucket.getChildren().forEach(children::add);
        children.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Resource r : children) {
            CloudConfig c = read(r);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** The confs that apply to content: its {@code cq:conf}, then {@code /conf/global}. */
    static List<String> confsFor(ResourceResolver resolver, String contentPath) {
        Set<String> out = new LinkedHashSet<>();
        String conf = confOf(resolver, contentPath);
        if (conf != null) out.add(conf);
        out.add(GLOBAL);
        return new ArrayList<>(out);
    }

    static String confOf(ResourceResolver resolver, String path) {
        if (path == null || !path.startsWith("/")) return null;
        if (path.startsWith("/conf/")) {
            String[] parts = path.split("/");
            return parts.length > 2 ? "/conf/" + parts[2] : null;
        }
        for (String p = path; p != null && !p.isEmpty() && !"/".equals(p); p = ResourceUtil.getParent(p)) {
            Resource r = resolver.getResource(p);
            if (r == null) continue;
            String c = r.getValueMap().get("cq:conf", String.class);
            Resource content = r.getChild("jcr:content");
            if (c == null && content != null) c = content.getValueMap().get("cq:conf", String.class);
            if (c != null && !c.isEmpty()) return c;
        }
        return null;
    }

    /** The configurations for content, its site's first. */
    static List<CloudConfig> forContent(ResourceResolver resolver, String contentPath) {
        List<CloudConfig> out = new ArrayList<>();
        for (String conf : confsFor(resolver, contentPath)) out.addAll(list(resolver, conf));
        return out;
    }

    /** The confs under {@code /conf} (where configurations can be created). */
    static List<String> confs(ResourceResolver resolver) {
        List<String> out = new ArrayList<>();
        Resource root = resolver.getResource("/conf");
        if (root == null) return out;
        for (Resource r : root.getChildren()) {
            if (r.getName().contains(":")) continue;
            out.add(r.getPath());
        }
        out.sort((a, b) -> GLOBAL.equals(a) ? -1 : GLOBAL.equals(b) ? 1 : a.compareTo(b));
        return out;
    }

    /** Creates or updates a configuration. Settings not given are removed. */
    static CloudConfig save(ResourceResolver resolver, String conf, String name, String title, String connector, Map<String, Object> settings)
            throws PersistenceException {
        Resource bucket = ensure(resolver, conf + "/" + CloudConfig.BUCKET);
        Resource page = bucket.getChild(name);
        if (page == null) {
            Map<String, Object> p = new HashMap<>();
            p.put("jcr:primaryType", "cq:Page");
            page = resolver.create(bucket, name, p);
        }
        Resource content = page.getChild("jcr:content");
        if (content == null) {
            Map<String, Object> p = new HashMap<>();
            p.put("jcr:primaryType", "cq:PageContent");
            content = resolver.create(page, "jcr:content", p);
        }
        ModifiableValueMap mvm = content.adaptTo(ModifiableValueMap.class);
        if (mvm == null) throw new PersistenceException("Cannot write " + content.getPath());
        mvm.put("jcr:title", title);
        mvm.put("sling:resourceType", RESOURCE_TYPE);
        mvm.put("connector", connector);
        mvm.put("cq:lastModified", Calendar.getInstance());
        mvm.put("cq:lastModifiedBy", resolver.getUserID());
        for (Map.Entry<String, String> s : SETTINGS.entrySet()) {
            Object v = settings.get(s.getKey());
            if (v == null) mvm.remove(s.getKey());
            else mvm.put(s.getKey(), v);
        }
        resolver.commit();
        return read(resolver.getResource(page.getPath()));
    }

    static void delete(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) {
            resolver.delete(r);
            resolver.commit();
        }
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) return r;
        Resource parent = ensure(resolver, ResourceUtil.getParent(path));
        Map<String, Object> p = new HashMap<>();
        p.put("jcr:primaryType", "sling:Folder");
        return resolver.create(parent, ResourceUtil.getName(path), p);
    }
}
