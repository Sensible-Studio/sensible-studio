package io.sensible.studio.cfm.impl.headless;

import io.sensible.studio.cfm.impl.ContentFragmentImpl;
import io.sensible.studio.cfm.impl.FragmentModel;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-request state of a GraphQL execution: the caller's resolver (so repository permissions apply), the models
 * of the endpoint, the base URLs for {@code _authorUrl}/{@code _publishUrl}, and caches so a fragment referenced
 * many times is read once.
 */
final class HeadlessContext {

    static final String KEY = HeadlessContext.class.getName();
    static final String DAM_ROOT = "/content/dam";
    private static final int MAX_SCAN = 50000;

    final ResourceResolver resolver;
    final Map<String, GqlModel> models;
    private final String authorBase;
    private final String publishBase;
    private Map<String, List<String>> byModel;
    private final Map<String, Optional<FragmentView>> views = new HashMap<>();
    private final Map<String, Optional<AssetRef>> refs = new HashMap<>();

    HeadlessContext(ResourceResolver resolver, Map<String, GqlModel> models, String authorBase, String publishBase) {
        this.resolver = resolver;
        this.models = models;
        this.authorBase = trim(authorBase);
        this.publishBase = trim(publishBase == null || publishBase.isEmpty() ? authorBase : publishBase);
    }

    String authorUrl(String path) {
        return authorBase + path;
    }

    String publishUrl(String path) {
        return publishBase + path;
    }

    /** A fragment of a model of this endpoint, in {@code variation} when it has one; null if not readable. */
    FragmentView fragment(String path, String variation, String requiredModel) {
        if (path == null || path.isEmpty()) return null;
        String key = path + "|" + variation;
        Optional<FragmentView> cached = views.get(key);
        if (cached == null) {
            cached = Optional.ofNullable(load(path, variation));
            views.put(key, cached);
        }
        FragmentView v = cached.orElse(null);
        if (v != null && requiredModel != null && !requiredModel.equals(v.model.path)) return null;
        return v;
    }

    private FragmentView load(String path, String variation) {
        Resource asset = resolver.getResource(path);
        if (asset != null && "jcr:content".equals(asset.getName())) asset = asset.getParent();
        if (!ContentFragmentImpl.isFragment(asset)) return null;
        String modelPath = FragmentModel.valueMap(asset.getChild("jcr:content/data")).get("cq:model", String.class);
        GqlModel model = modelPath == null ? null : models.get(modelPath);
        return model == null ? null : new FragmentView(asset, model, variation);
    }

    /** A content reference: an asset or page; null when it does not exist or cannot be read. */
    AssetRef reference(String path) {
        if (path == null || path.isEmpty()) return null;
        return refs.computeIfAbsent(path, p -> Optional.ofNullable(AssetRef.of(resolver.getResource(p)))).orElse(null);
    }

    /** Paths of the fragments of a model, in path order. */
    List<String> fragmentsOf(String modelPath) {
        if (byModel == null) byModel = index();
        List<String> paths = byModel.get(modelPath);
        return paths == null ? Collections.emptyList() : paths;
    }

    /**
     * All fragments below {@code /content/dam}, grouped by model. Uses a repository query when the resolver
     * supports one, else walks the DAM tree.
     */
    private Map<String, List<String>> index() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();
        boolean queried = false;
        try {
            Iterator<Resource> it = resolver.findResources(
                    "SELECT * FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, '" + DAM_ROOT + "') "
                            + "AND a.[jcr:content/contentFragment] = true", "JCR-SQL2");
            while (it.hasNext()) {
                found.add(it.next().getPath());
                queried = true;
            }
        } catch (RuntimeException e) {
            found.clear();
            queried = false;
        }
        if (!queried) {
            Resource root = resolver.getResource(DAM_ROOT);
            if (root != null) walk(root, found, new int[]{0});
        }
        Collections.sort(found);
        for (String p : found) {
            String model = FragmentModel.valueMap(resolver.getResource(p + "/jcr:content/data")).get("cq:model", String.class);
            if (model != null) out.computeIfAbsent(model, k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    private void walk(Resource node, List<String> found, int[] scanned) {
        for (Resource child : node.getChildren()) {
            if (++scanned[0] > MAX_SCAN) return;
            String name = child.getName();
            if (name.contains(":")) continue;
            if (ContentFragmentImpl.isFragment(child)) {
                found.add(child.getPath());
                continue;
            }
            String type = child.getValueMap().get("jcr:primaryType", "");
            if (type.endsWith("Folder") || "nt:folder".equals(type)) walk(child, found, scanned);
        }
    }

    private static String trim(String base) {
        if (base == null) return "";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
