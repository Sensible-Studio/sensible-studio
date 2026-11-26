/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
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
import org.apache.sling.api.resource.ValueMap;

/**
 * Collections: named sets of assets that stay where they are (a collection holds references,
 * not copies), as in AEM. A smart collection holds a search instead and shows what matches now.
 *
 * <pre>
 * /content/dam/collections/&lt;name&gt;   nt:unstructured, sling:resourceType = dam/collection
 *     jcr:title, jcr:description, studio:owner, studio:public (Boolean), studio:members (paths, in order)
 *     studio:smartScope + studio:smartQuery (JSON of DamSearch filters) for smart collections
 * </pre>
 * The nodes are not folders, so the folder listings do not show them. A private collection is
 * visible to its owner only (and to admin); write access to /content/dam is still needed to change one.
 */
final class DamCollections {

    static final String ROOT = DamItems.ROOT + "/collections";
    static final String RT = "dam/collection";
    static final int MAX_MEMBERS = 10_000;

    private DamCollections() {
    }

    static boolean isCollection(Resource r) {
        return r != null && RT.equals(r.getValueMap().get("sling:resourceType", String.class)) && r.getPath().startsWith(ROOT + "/");
    }

    static boolean visible(ResourceResolver resolver, Resource c) {
        ValueMap v = c.getValueMap();
        String user = resolver.getUserID();
        return v.get("studio:public", false) || user != null && (user.equals(v.get("studio:owner", String.class)) || DamAccess.isAdmin(resolver));
    }

    static boolean mine(ResourceResolver resolver, Resource c) {
        String user = resolver.getUserID();
        return user != null && (user.equals(c.getValueMap().get("studio:owner", String.class)) || DamAccess.isAdmin(resolver));
    }

    static Resource get(ResourceResolver resolver, String path, boolean forWriting) throws DamApiServlet.ApiException {
        Resource c = path == null ? null : resolver.getResource(path);
        if (!isCollection(c) || !visible(resolver, c)) {
            throw new DamApiServlet.ApiException(404, "no collection " + path);
        }
        if (forWriting && !mine(resolver, c) && !c.getValueMap().get("studio:public", false)) {
            throw new DamApiServlet.ApiException(403, "only the owner can change this collection");
        }
        return c;
    }

    static List<Map<String, Object>> list(ResourceResolver resolver) {
        Resource root = resolver.getResource(ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (root != null) {
            for (Resource c : root.getChildren()) {
                if (isCollection(c) && visible(resolver, c)) {
                    out.add(describe(resolver, c));
                }
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    static Map<String, Object> describe(ResourceResolver resolver, Resource c) {
        ValueMap v = c.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "collection");
        m.put("path", c.getPath());
        m.put("name", c.getName());
        m.put("title", v.get("jcr:title", c.getName()));
        m.put("description", v.get("jcr:description", String.class));
        m.put("owner", v.get("studio:owner", String.class));
        m.put("public", v.get("studio:public", false));
        m.put("modified", v.get("jcr:lastModified", Calendar.class));
        String query = v.get("studio:smartQuery", String.class);
        if (query != null) {
            m.put("smart", true);
            m.put("scope", v.get("studio:smartScope", DamItems.ROOT));
            m.put("query", Json.parse(query));
        } else {
            String[] members = v.get("studio:members", new String[0]);
            m.put("count", members.length);
            List<String> previews = new ArrayList<>();
            for (String p : members) {
                Resource thumb = previews.size() < 3 ? resolver.getResource(p + "/jcr:content/renditions/" + DamItems.THUMBNAIL) : null;
                if (thumb != null) {
                    previews.add(DamItems.url(thumb));
                }
            }
            m.put("previews", previews);
        }
        return m;
    }

    /** Members that still exist (or, for a smart collection, what its search finds now). */
    static List<Map<String, Object>> members(ResourceResolver resolver, Resource c) {
        ValueMap v = c.getValueMap();
        String query = v.get("studio:smartQuery", String.class);
        if (query != null) {
            Resource scope = resolver.getResource(v.get("studio:smartScope", DamItems.ROOT));
            if (scope == null) {
                return Collections.emptyList();
            }
            return DamSearch.search(scope, DamSearch.fromJson(Json.parse(query)), System.currentTimeMillis()).hits;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String p : v.get("studio:members", new String[0])) {
            Resource r = resolver.getResource(p);
            if (DamItems.isAsset(r) || DamItems.isFolder(r)) {
                out.add(DamItems.item(r));
            }
        }
        return out;
    }

    static Map<String, Object> create(ResourceResolver resolver, String title, String description, boolean isPublic,
            String smartScope, String smartQuery) throws DamApiServlet.ApiException, PersistenceException {
        String t = title == null ? "" : title.trim();
        String name = DamApiServlet.nodeName(t);
        if (name == null) {
            throw new DamApiServlet.ApiException(400, "a title is required");
        }
        Resource root = ensureRoot(resolver);
        String unique = name;
        for (int i = 1; root.getChild(unique) != null; i++) {
            unique = name + "-" + i;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("sling:resourceType", RT);
        props.put("jcr:title", t);
        if (description != null && !description.trim().isEmpty()) {
            props.put("jcr:description", description.trim());
        }
        props.put("studio:owner", resolver.getUserID());
        props.put("studio:public", isPublic);
        props.put("jcr:lastModified", Calendar.getInstance());
        if (smartQuery != null) {
            if (!DamItems.inDam(smartScope)) {
                throw new DamApiServlet.ApiException(400, "the search scope must be in " + DamItems.ROOT);
            }
            try {
                DamSearch.fromJson(Json.parse(smartQuery)); // valid?
            } catch (IllegalArgumentException | ClassCastException e) {
                throw new DamApiServlet.ApiException(400, "invalid search: " + e.getMessage());
            }
            props.put("studio:smartScope", smartScope);
            props.put("studio:smartQuery", smartQuery);
        } else {
            props.put("studio:members", new String[0]);
        }
        return describe(resolver, resolver.create(root, unique, props));
    }

    static void update(Resource c, String title, String description, Boolean isPublic) throws DamApiServlet.ApiException {
        ModifiableValueMap m = c.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + c.getPath());
        }
        if (title != null && !title.trim().isEmpty()) {
            m.put("jcr:title", title.trim());
        }
        if (description != null) {
            if (description.trim().isEmpty()) {
                m.remove("jcr:description");
            } else {
                m.put("jcr:description", description.trim());
            }
        }
        if (isPublic != null) {
            m.put("studio:public", isPublic);
        }
        m.put("jcr:lastModified", Calendar.getInstance());
    }

    /** Adds (or with {@code remove} takes out) assets and folders; returns how many changed. */
    static int change(ResourceResolver resolver, Resource c, List<String> paths, boolean remove) throws DamApiServlet.ApiException {
        ModifiableValueMap m = c.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + c.getPath());
        }
        if (m.containsKey("studio:smartQuery")) {
            throw new DamApiServlet.ApiException(400, "a smart collection shows its search; members cannot be added");
        }
        Set<String> members = new LinkedHashSet<>(Arrays.asList(m.get("studio:members", new String[0])));
        int before = members.size();
        for (String p : paths) {
            if (remove) {
                members.remove(p);
                continue;
            }
            Resource r = DamItems.inDam(p) ? resolver.getResource(p) : null;
            if (!(DamItems.isAsset(r) || DamItems.isFolder(r)) || p.startsWith(ROOT + "/")) {
                throw new DamApiServlet.ApiException(404, "not an asset or folder: " + p);
            }
            members.add(r.getPath());
        }
        if (members.size() > MAX_MEMBERS) {
            throw new DamApiServlet.ApiException(413, "a collection holds at most " + MAX_MEMBERS + " items");
        }
        m.put("studio:members", members.toArray(new String[0]));
        m.put("jcr:lastModified", Calendar.getInstance());
        return Math.abs(members.size() - before);
    }

    private static Resource ensureRoot(ResourceResolver resolver) throws PersistenceException {
        Resource root = resolver.getResource(ROOT);
        if (root != null) {
            return root;
        }
        Resource dam = resolver.getResource(DamItems.ROOT);
        // nt:unstructured, not a folder: the Assets listings leave it out
        return resolver.create(dam, "collections", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
    }
}
