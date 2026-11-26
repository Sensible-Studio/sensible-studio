/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.clientlibs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Immutable snapshot of all {@code cq:ClientLibraryFolder} resources below the search paths.
 * Built by walking the tree once; rebuilt whenever {@code /apps} or {@code /libs} change.
 */
final class ClientLibraryIndex {

    static final String NT_CLIENTLIB = "cq:ClientLibraryFolder";
    private static final int MAX_DEPTH = 20;

    /** By folder path, in discovery order. */
    final Map<String, ClientLibraryImpl> byPath;

    /** By category, in discovery order. */
    final Map<String, List<ClientLibraryImpl>> byCategory;

    private ClientLibraryIndex(Map<String, ClientLibraryImpl> byPath) {
        this.byPath = Collections.unmodifiableMap(byPath);
        Map<String, List<ClientLibraryImpl>> cats = new LinkedHashMap<>();
        for (ClientLibraryImpl lib : byPath.values()) {
            for (String c : lib.getCategories()) {
                cats.computeIfAbsent(c, k -> new ArrayList<>()).add(lib);
            }
        }
        this.byCategory = Collections.unmodifiableMap(cats);
    }

    static ClientLibraryIndex build(ResourceResolver resolver, String[] roots) {
        Map<String, ClientLibraryImpl> found = new LinkedHashMap<>();
        for (String root : roots) {
            Resource r = resolver.getResource(root);
            if (r != null) {
                walk(r, found, 0);
            }
        }
        return new ClientLibraryIndex(found);
    }

    private static void walk(Resource r, Map<String, ClientLibraryImpl> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        ValueMap props = r.getValueMap();
        if (NT_CLIENTLIB.equals(props.get("jcr:primaryType", String.class))) {
            out.put(r.getPath(), new ClientLibraryImpl(r.getPath(),
                    props.get("categories", new String[0]),
                    props.get("embed", new String[0]),
                    props.get("dependencies", new String[0]),
                    props.get("allowProxy", false)));
        }
        String type = props.get("jcr:primaryType", "");
        if ("nt:file".equals(type) || "nt:resource".equals(type)) {
            return;
        }
        for (Resource child : r.getChildren()) {
            String n = child.getName();
            if (!n.startsWith("jcr:") && !n.startsWith("rep:")) {
                walk(child, out, depth + 1);
            }
        }
    }

    /** @return libraries of a category, never {@code null} */
    List<ClientLibraryImpl> category(String category) {
        List<ClientLibraryImpl> l = byCategory.get(category);
        return l == null ? Collections.emptyList() : l;
    }

    /**
     * @return the library owning {@code path} (the folder itself or an ancestor), or {@code null}
     */
    ClientLibraryImpl owning(String path) {
        for (String p = path; p != null && p.length() > 1; p = parent(p)) {
            ClientLibraryImpl lib = byPath.get(p);
            if (lib != null) {
                return lib;
            }
        }
        return null;
    }

    private static String parent(String p) {
        int i = p.lastIndexOf('/');
        return i <= 0 ? null : p.substring(0, i);
    }
}
