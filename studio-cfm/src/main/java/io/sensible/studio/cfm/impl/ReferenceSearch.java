package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where content fragments are used and what they use.
 *
 * <ul>
 *   <li>{@link #usedIn}: every page, experience fragment, content fragment or other node under {@code /content}
 *   with a property that names the path: as its whole value (a Content Fragment component's {@code fragmentPath},
 *   a fragment reference element) or inside text (a link in rich text). Grouped by the page or asset that holds
 *   the property.</li>
 *   <li>{@link #referencesOf}: the assets, fragments and pages the fragment's elements point to, in every
 *   variation.</li>
 * </ul>
 * The search walks the repository (no index needed), bounded by a node and a time budget; the answer says when it
 * stopped early.
 */
final class ReferenceSearch {

    static final int MAX_NODES = 300_000;
    static final long MAX_MILLIS = 8_000;
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "renditions", "related", "cq:tags", "cq:graphql", "rep:policy", "jcr:system", "oak:index"));
    private static final Pattern LINK = Pattern.compile("(?:href|src)=[\"'](/content/[^\"'?#]+)");

    private final ResourceResolver resolver;
    private final int maxNodes;
    private final long deadline;
    private int scanned;
    private boolean truncated;

    ReferenceSearch(ResourceResolver resolver, int maxNodes, long maxMillis) {
        this.resolver = resolver;
        this.maxNodes = maxNodes;
        this.deadline = System.currentTimeMillis() + maxMillis;
    }

    static Map<String, Object> usedIn(ResourceResolver resolver, String path) {
        return new ReferenceSearch(resolver, MAX_NODES, MAX_MILLIS).usedIn(path, "/content");
    }

    /** Containers (pages, assets) holding a property that refers to {@code path}, searched below {@code root}. */
    Map<String, Object> usedIn(String path, String root) {
        Pattern ref = Pattern.compile(Pattern.quote(path) + "(?![A-Za-z0-9_\\-])");
        Map<String, Map<String, Object>> holders = new LinkedHashMap<>();
        Resource start = resolver.getResource(root);
        if (start != null) walk(start, path, ref, holders);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("usedIn", new ArrayList<>(holders.values()));
        out.put("scanned", scanned);
        out.put("truncated", truncated);
        return out;
    }

    private void walk(Resource node, String path, Pattern ref, Map<String, Map<String, Object>> holders) {
        if (truncated) return;
        if (++scanned > maxNodes || (scanned % 512 == 0 && System.currentTimeMillis() > deadline)) {
            truncated = true;
            return;
        }
        String own = node.getPath();
        boolean self = own.equals(path) || own.startsWith(path + "/");
        if (!self) {
            for (Map.Entry<String, Object> e : node.getValueMap().entrySet()) {
                if (matches(e.getValue(), ref)) record(node, e.getKey(), holders);
            }
        }
        for (Resource child : node.getChildren()) {
            String name = child.getName();
            if (SKIP.contains(name) || name.startsWith("rep:")) continue;
            walk(child, path, ref, holders);
        }
    }

    private static boolean matches(Object value, Pattern ref) {
        if (value instanceof String) return ref.matcher((String) value).find();
        if (value instanceof String[]) {
            for (String s : (String[]) value) if (s != null && ref.matcher(s).find()) return true;
        }
        return false;
    }

    private void record(Resource node, String property, Map<String, Map<String, Object>> holders) {
        Resource container = container(node);
        String key = container.getPath();
        Map<String, Object> h = holders.get(key);
        if (h == null) {
            h = describe(container);
            h.put("via", new ArrayList<Map<String, Object>>());
            holders.put(key, h);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> via = (List<Map<String, Object>>) h.get("via");
        Map<String, Object> v = new LinkedHashMap<>();
        String relative = node.getPath().equals(key) ? "" : node.getPath().substring(key.length() + 1);
        v.put("node", relative);
        v.put("property", property);
        v.put("resourceType", node.getValueMap().get("sling:resourceType", String.class));
        via.add(v);
    }

    /** The page or asset a node belongs to (or the node itself). */
    private static Resource container(Resource node) {
        for (Resource r = node; r != null; r = r.getParent()) {
            String type = r.getValueMap().get("jcr:primaryType", "");
            if ("cq:Page".equals(type) || "dam:Asset".equals(type)) return r;
        }
        return node;
    }

    /** {@code type} (page, experience-fragment, fragment, asset, other), {@code path}, {@code title}. */
    Map<String, Object> describe(Resource r) {
        Map<String, Object> m = new LinkedHashMap<>();
        String type = r.getValueMap().get("jcr:primaryType", "");
        ValueMap content = FragmentModel.valueMap(r.getChild("jcr:content"));
        String kind;
        if ("cq:Page".equals(type)) {
            kind = r.getPath().startsWith("/content/experience-fragments/") ? "experience-fragment" : "page";
            m.put("title", content.get("jcr:title", r.getName()));
        } else if (ContentFragmentImpl.isFragment(r)) {
            kind = "fragment";
            m.put("title", content.get("jcr:title", r.getName()));
            String model = FragmentModel.valueMap(r.getChild("jcr:content/data")).get("cq:model", String.class);
            Resource modelContent = model == null ? null : resolver.getResource(model + "/jcr:content");
            m.put("model", modelContent == null ? null : modelContent.getValueMap().get("jcr:title", String.class));
        } else if ("dam:Asset".equals(type)) {
            kind = "asset";
            String title = FragmentModel.valueMap(r.getChild("jcr:content/metadata")).get("dc:title", String.class);
            m.put("title", title != null ? title : r.getName());
        } else {
            kind = "other";
            m.put("title", r.getName());
        }
        m.put("type", kind);
        m.put("path", r.getPath());
        return m;
    }

    /** What the fragment's elements reference, in all variations (each target once, with the elements naming it). */
    static List<Map<String, Object>> referencesOf(ResourceResolver resolver, String fragmentPath) {
        Resource asset = resolver.getResource(fragmentPath);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ContentFragmentImpl.isFragment(asset)) return out;
        ContentFragmentImpl cf = ContentFragmentImpl.of(asset);
        Resource data = asset.getChild("jcr:content/data");
        if (data == null) return out;
        Map<String, Map<String, Object>> byPath = new LinkedHashMap<>();
        ReferenceSearch describer = new ReferenceSearch(resolver, 0, 0);
        for (Resource variation : data.getChildren()) {
            ValueMap values = variation.getValueMap();
            for (FragmentModel.ElementDef def : cf.getModel().getElements()) {
                Object raw = values.get(def.name);
                List<String> targets = new ArrayList<>();
                String kind = ModelJson.kindOf(def.metaType);
                if ("content-reference".equals(kind) || "fragment-reference".equals(kind)) {
                    for (Object o : FragmentStore.toJsonList(raw)) if (o != null && o.toString().startsWith("/")) targets.add(o.toString());
                } else if (raw instanceof String && ((String) raw).contains("/content/")) {
                    Matcher m = LINK.matcher((String) raw);
                    while (m.find()) {
                        String p = m.group(1);
                        targets.add(p.endsWith(".html") ? p.substring(0, p.length() - 5) : p);
                    }
                }
                for (String t : targets) {
                    Map<String, Object> entry = byPath.get(t);
                    if (entry == null) {
                        Resource target = resolver.getResource(t);
                        entry = target == null ? missing(t) : describer.describe(target);
                        entry.put("elements", new ArrayList<String>());
                        byPath.put(t, entry);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> elements = (List<String>) entry.get("elements");
                    if (!elements.contains(def.name)) elements.add(def.name);
                }
            }
        }
        out.addAll(byPath.values());
        return out;
    }

    private static Map<String, Object> missing(String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", path.substring(path.lastIndexOf('/') + 1));
        m.put("type", "missing");
        m.put("path", path);
        return m;
    }
}
