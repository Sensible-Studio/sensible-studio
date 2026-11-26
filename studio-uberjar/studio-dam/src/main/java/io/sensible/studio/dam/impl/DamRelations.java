/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Relate / Unrelate: assets that belong together (a logo in several formats, the photos of one
 * shoot). Relations go both ways and are kept in {@code jcr:content/related/others} of each asset.
 * Paths of assets that were deleted since are left out when reading.
 */
final class DamRelations {

    static final String NODE = "related";
    static final String PN_OTHERS = "others";

    private DamRelations() {
    }

    static List<String> others(Resource asset) {
        Resource r = asset.getChild("jcr:content/" + NODE);
        return r == null ? Collections.emptyList() : Arrays.asList(r.getValueMap().get(PN_OTHERS, new String[0]));
    }

    /** The related assets that still exist, for the details page. */
    static List<Map<String, Object>> describe(ResourceResolver resolver, Resource asset) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String p : others(asset)) {
            Resource o = resolver.getResource(p);
            if (DamItems.isAsset(o)) {
                Map<String, Object> m = new LinkedHashMap<>();
                Map<String, Object> item = DamItems.asset(o);
                for (String k : new String[] {"path", "name", "title", "mimeType", "thumbnail"}) {
                    m.put(k, item.get(k));
                }
                out.add(m);
            }
        }
        return out;
    }

    /** Relates every asset of {@code paths} with every other one. Returns the number of new links. */
    static int relate(ResourceResolver resolver, List<Resource> assets) throws DamApiServlet.ApiException, PersistenceException {
        int added = 0;
        for (Resource a : assets) {
            Set<String> set = new LinkedHashSet<>(others(a));
            int before = set.size();
            for (Resource b : assets) {
                if (!b.getPath().equals(a.getPath())) {
                    set.add(b.getPath());
                }
            }
            if (set.size() != before) {
                added += set.size() - before;
                write(resolver, a, set);
            }
        }
        return added / 2;
    }

    /** Removes the links between {@code asset} and each of {@code others}, in both directions. */
    static void unrelate(ResourceResolver resolver, Resource asset, List<Resource> others)
            throws DamApiServlet.ApiException, PersistenceException {
        Set<String> mine = new LinkedHashSet<>(others(asset));
        for (Resource o : others) {
            mine.remove(o.getPath());
            Set<String> theirs = new LinkedHashSet<>(others(o));
            if (theirs.remove(asset.getPath())) {
                write(resolver, o, theirs);
            }
        }
        write(resolver, asset, mine);
    }

    private static void write(ResourceResolver resolver, Resource asset, Set<String> paths)
            throws DamApiServlet.ApiException, PersistenceException {
        Resource content = asset.getChild("jcr:content");
        if (content == null) {
            throw new DamApiServlet.ApiException(400, asset.getPath() + " has no jcr:content");
        }
        Resource node = content.getChild(NODE);
        if (node == null) {
            if (paths.isEmpty()) {
                return;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("jcr:primaryType", "nt:unstructured");
            node = resolver.create(content, NODE, props);
        }
        ModifiableValueMap m = node.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + asset.getPath());
        }
        if (paths.isEmpty()) {
            m.remove(PN_OTHERS);
        } else {
            m.put(PN_OTHERS, paths.toArray(new String[0]));
        }
    }
}
