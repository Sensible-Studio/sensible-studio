/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * The Filter rail's saved searches: per user, under {@code /var/studio/searches/<user>/<search>} ({@code name},
 * {@code criteria} as the console's JSON, {@code created}, {@code modified}).
 *
 * <p>Authors have no rights on {@code /var}: the records are written with {@code store} (the service resolver), for
 * the user the request is signed in as, and each user only ever reaches their own folder. Without a service user the
 * user's own resolver is used.</p>
 */
final class SavedSearches {

    static final String ROOT = "/var/studio/searches";
    static final int MAX_SEARCHES = 50;
    static final int MAX_NAME = 100;
    static final int MAX_CRITERIA = 4000;

    private final ResourceResolver resolver;
    private final ResourceResolver store;

    SavedSearches(ResourceResolver resolver, ResourceResolver store) {
        this.resolver = resolver;
        this.store = store == null ? resolver : store;
    }

    /** The user's saved searches, by name. */
    List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource folder = store.getResource(folderPath());
        if (folder == null) {
            return out;
        }
        for (Resource s : folder.getChildren()) {
            ValueMap v = s.getValueMap();
            String name = v.get("name", String.class);
            if (name == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("criteria", v.get("criteria", "{}"));
            m.put("modified", SiteRail.iso(v.get("modified", Calendar.class)));
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    /** Saves the criteria under a name; a search of the same name is replaced. */
    Map<String, Object> save(String name, String criteria) throws PersistenceException {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Name the search");
        }
        if (n.length() > MAX_NAME) {
            throw new IllegalArgumentException("A search name has at most " + MAX_NAME + " characters");
        }
        String c = criteria == null ? "" : criteria.trim();
        if (!c.startsWith("{") || !c.endsWith("}") || c.length() > MAX_CRITERIA) {
            throw new IllegalArgumentException("Not search criteria");
        }
        Resource folder = folder();
        Resource existing = folder.getChild(nodeName(n));
        Calendar now = Calendar.getInstance();
        if (existing != null) {
            ModifiableValueMap v = existing.adaptTo(ModifiableValueMap.class);
            v.put("name", n);
            v.put("criteria", c);
            v.put("modified", now);
        } else {
            int count = 0;
            for (Resource ignored : folder.getChildren()) {
                count++;
            }
            if (count >= MAX_SEARCHES) {
                throw new IllegalArgumentException("At most " + MAX_SEARCHES + " saved searches; delete one first");
            }
            Map<String, Object> props = new HashMap<>();
            props.put("jcr:primaryType", "nt:unstructured");
            props.put("name", n);
            props.put("criteria", c);
            props.put("created", now);
            props.put("modified", now);
            store.create(folder, nodeName(n), props);
        }
        store.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", n);
        out.put("criteria", c);
        out.put("modified", SiteRail.iso(now));
        return out;
    }

    void delete(String name) throws PersistenceException {
        Resource s = name == null ? null : store.getResource(folderPath() + "/" + nodeName(name.trim()));
        if (s == null) {
            throw new IllegalArgumentException("No saved search " + name);
        }
        store.delete(s);
        store.commit();
    }

    private String folderPath() {
        String user = resolver.getUserID();
        if (user == null || user.isEmpty() || "anonymous".equals(user)) {
            throw new SecurityException("Sign in to save searches");
        }
        return ROOT + "/" + userName(user);
    }

    private Resource folder() throws PersistenceException {
        String path = folderPath();
        Resource f = store.getResource(path);
        if (f != null) {
            return f;
        }
        Resource root = store.getResource(ROOT);
        if (root == null) {
            Resource studio = store.getResource("/var/studio");
            if (studio == null) {
                throw new IllegalStateException(ROOT + " is missing (see the studio-siterail repoinit)");
            }
            root = store.create(studio, "searches", Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return store.create(root, path.substring(ROOT.length() + 1), Collections.singletonMap("jcr:primaryType", "sling:Folder"));
    }

    /** A user's folder: the user ID, readable, with anything a node name cannot hold escaped. */
    static String userName(String user) {
        StringBuilder b = new StringBuilder();
        for (char ch : user.toCharArray()) {
            if (ch < 0x80 && (Character.isLetterOrDigit(ch) || ch == '-' || ch == '.' && b.length() > 0)) {
                b.append(ch);
            } else {
                b.append('_').append(String.format("%04x", (int) ch));
            }
        }
        return b.length() <= 100 ? b.toString() : "u" + hash(user);
    }

    /** A search's node: the name can hold anything, so it is hashed (the name itself is a property). */
    static String nodeName(String name) {
        return "s" + hash(name);
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                b.append(String.format("%02x", d[i] & 0xff));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
