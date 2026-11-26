/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.tagging.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * The tag tree below {@code /content/cq:tags} as the Tags console sees it: browsing, creating and editing tags
 * (title, description, localized titles {@code jcr:title.<lang>}), moving, merging and deleting them, and the
 * content that uses them ({@code cq:tags} properties below {@code /content}).
 *
 * <p>Tag IDs follow AEM: the first level below the root is the namespace, {@code ns:a/b} is the tag at
 * {@code /content/cq:tags/ns/a/b}, {@code ns:} the namespace itself, an ID without namespace belongs to
 * {@code default}, and content may also hold the tag's path. Moving, merging or deleting a tag rewrites (or removes)
 * the values that point at it or at its sub-tags, so content never keeps references to tags that are gone.
 */
final class TagService {

    static final String ROOT = "/content/cq:tags";
    static final String TAG_TYPE = "cq:Tag";
    static final String TAGS_PROPERTY = "cq:tags";
    static final String TAG_RESOURCE_TYPE = "cq/tagging/components/tag";
    static final List<String> DEFAULT_LANGUAGES = Collections.unmodifiableList(
            Arrays.asList("en", "de", "es", "fr", "it", "pt_br", "zh_cn", "zh_tw", "ja", "ko_kr"));

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}([_-][A-Za-z0-9]{2,4})?");
    private static final String TITLE_PREFIX = "jcr:title.";
    private static final int MAX_REFERENCES = 500;

    private final ResourceResolver resolver;

    TagService(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** The tag (or the root) at {@code path}, its ancestors and its child tags. */
    Map<String, Object> children(String path) {
        Resource item = tagOrRoot(path);
        List<Map<String, Object>> children = new ArrayList<>();
        for (Resource child : item.getChildren()) {
            if (isTag(child)) {
                children.add(summary(child));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item.getPath().equals(ROOT) ? root(item) : summary(item));
        out.put("trail", trail(item));
        out.put("children", children);
        return out;
    }

    /** One tag with its localized titles and the languages offered for them. */
    Map<String, Object> tag(String path) {
        Resource tag = requireTag(path);
        Map<String, Object> out = summary(tag);
        Map<String, Object> localized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : tag.getValueMap().entrySet()) {
            if (e.getKey().startsWith(TITLE_PREFIX) && e.getValue() instanceof String) {
                localized.put(e.getKey().substring(TITLE_PREFIX.length()), e.getValue());
            }
        }
        out.put("localized", localized);
        out.put("languages", languages());
        return out;
    }

    /** Languages tags can be translated into: {@code languages} on the tag root, else a default list. */
    List<String> languages() {
        Resource root = resolver.getResource(ROOT);
        String[] configured = root == null ? null : root.getValueMap().get("languages", String[].class);
        return configured == null || configured.length == 0 ? DEFAULT_LANGUAGES : Arrays.asList(configured);
    }

    /** Content below {@code /content} that is tagged with the tag at {@code path} or one of its sub-tags. */
    List<Map<String, Object>> references(String path) {
        Resource tag = requireTag(path);
        String id = idOf(tag.getPath());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Resource r : tagged(Collections.singletonList(id))) {
            if (out.size() >= MAX_REFERENCES) {
                break;
            }
            out.add(reference(r, id));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ writing

    Map<String, Object> create(String parentPath, String title, String name, String description)
            throws PersistenceException {
        Resource parent = tagOrRoot(parentPath);
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("A title is required");
        }
        String tagName = name == null || name.trim().isEmpty() ? nameFrom(title) : name.trim();
        checkName(tagName);
        if (parent.getChild(tagName) != null) {
            throw new IllegalArgumentException("A tag named " + tagName + " already exists in " + parent.getPath());
        }
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", TAG_TYPE);
        props.put("sling:resourceType", TAG_RESOURCE_TYPE);
        props.put("jcr:title", title.trim());
        if (description != null && !description.trim().isEmpty()) {
            props.put("jcr:description", description.trim());
        }
        stamp(props);
        Resource created = resolver.create(parent, tagName, props);
        resolver.commit();
        return summary(created);
    }

    /**
     * Title, description and localized titles; a localized title that is empty is removed. Null arguments leave the
     * value as it is.
     */
    Map<String, Object> update(String path, String title, String description, Map<String, String> localized)
            throws PersistenceException {
        Resource tag = requireTag(path);
        ModifiableValueMap props = writable(tag);
        if (title != null) {
            if (title.trim().isEmpty()) {
                throw new IllegalArgumentException("A title is required");
            }
            props.put("jcr:title", title.trim());
        }
        if (description != null) {
            if (description.trim().isEmpty()) {
                props.remove("jcr:description");
            } else {
                props.put("jcr:description", description.trim());
            }
        }
        for (Map.Entry<String, String> e : localized.entrySet()) {
            if (!LANGUAGE.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("Not a language: " + e.getKey());
            }
            String key = TITLE_PREFIX + e.getKey();
            if (e.getValue() == null || e.getValue().trim().isEmpty()) {
                props.remove(key);
            } else {
                props.put(key, e.getValue().trim());
            }
        }
        stamp(props);
        resolver.commit();
        return tag(path);
    }

    /** Moves (and/or renames) a tag, then points the content that used it, or its sub-tags, at the new place. */
    Map<String, Object> move(String path, String destParentPath, String name) throws PersistenceException, RepositoryException {
        Resource tag = requireTag(path);
        Resource dest = tagOrRoot(destParentPath == null || destParentPath.isEmpty() ? parentPath(path) : destParentPath);
        String newName = name == null || name.trim().isEmpty() ? tag.getName() : name.trim();
        checkName(newName);
        String target = dest.getPath() + "/" + newName;
        if (target.equals(tag.getPath())) {
            return result(target, 0);
        }
        if (inside(dest.getPath(), tag.getPath())) {
            throw new IllegalArgumentException("A tag cannot be moved below itself");
        }
        if (dest.getChild(newName) != null) {
            throw new IllegalArgumentException("A tag named " + newName + " already exists in " + dest.getPath());
        }
        String fromId = idOf(tag.getPath());
        String toId = idOf(target);
        relocate(tag, dest, newName);
        Resource moved = resolver.getResource(target);
        if (moved != null) {
            stamp(writable(moved));
        }
        int adjusted = rewrite(fromId, v -> join(toId, v.substring(fromId.length())));
        resolver.commit();
        return result(target, adjusted);
    }

    /**
     * Merges a tag into another: content tagged with the source (or its sub-tags) gets the target instead, the
     * source's sub-tags move below the target (merged with same-named ones there), and the source is deleted.
     */
    Map<String, Object> merge(String path, String targetPath) throws PersistenceException, RepositoryException {
        Resource source = requireTag(path);
        Resource target = requireTag(targetPath);
        if (source.getPath().equals(target.getPath())) {
            throw new IllegalArgumentException("A tag cannot be merged into itself");
        }
        if (inside(target.getPath(), source.getPath()) || inside(source.getPath(), target.getPath())) {
            throw new IllegalArgumentException("A tag cannot be merged into one of its own sub-tags or parents");
        }
        String fromId = idOf(source.getPath());
        String toId = idOf(target.getPath());
        int adjusted = rewrite(fromId, v -> join(toId, v.substring(fromId.length())));
        mergeChildren(source, target);
        resolver.delete(resolver.getResource(source.getPath()));
        stamp(writable(resolver.getResource(target.getPath())));
        resolver.commit();
        return result(target.getPath(), adjusted);
    }

    /** Deletes tags and removes them (and their sub-tags) from the content that used them. */
    Map<String, Object> delete(List<String> paths) throws PersistenceException {
        List<Resource> tags = new ArrayList<>();
        for (String p : paths) {
            tags.add(requireTag(p));
        }
        List<String> ids = new ArrayList<>();
        for (Resource t : tags) {
            ids.add(idOf(t.getPath()));
        }
        int adjusted = 0;
        for (Resource r : tagged(ids)) {
            ModifiableValueMap props = writable(r);
            List<String> kept = new ArrayList<>();
            for (String v : values(props.get(TAGS_PROPERTY))) {
                if (ids.stream().noneMatch(id -> matches(v, id))) {
                    kept.add(v);
                }
            }
            props.put(TAGS_PROPERTY, kept.toArray(new String[0]));
            adjusted++;
        }
        List<String> deleted = new ArrayList<>();
        for (Resource t : tags) {
            Resource still = resolver.getResource(t.getPath());
            if (still != null) {
                resolver.delete(still);
                deleted.add(t.getPath());
            }
        }
        resolver.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("adjusted", adjusted);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ tag IDs

    /** {@code /content/cq:tags/ns/a/b} -> {@code ns:a/b}; the namespace itself -> {@code ns:}. */
    static String idOf(String path) {
        String rel = path.substring(ROOT.length() + 1);
        int slash = rel.indexOf('/');
        return slash < 0 ? rel + ":" : rel.substring(0, slash) + ":" + rel.substring(slash + 1);
    }

    /** A stored value in ID form ({@code ns:a/b}), or null when it is not a tag reference. */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.startsWith("/")) {
            if (!v.startsWith(ROOT + "/")) {
                return null;
            }
            v = v.replaceAll("/+$", "");
            return v.equals(ROOT) ? null : idOf(v);
        }
        return v.indexOf(':') < 0 ? "default:" + v : v;
    }

    /** Whether a stored value refers to the tag {@code id} or one of its sub-tags. */
    static boolean matches(String value, String id) {
        String v = normalize(value);
        if (v == null) {
            return false;
        }
        return id.endsWith(":") ? v.startsWith(id) : v.equals(id) || v.startsWith(id + "/");
    }

    /** {@code ns:b} + {@code /x} -> {@code ns:b/x}; {@code ns:} + {@code x} -> {@code ns:x}. */
    static String join(String id, String suffix) {
        String s = suffix.startsWith("/") ? suffix.substring(1) : suffix;
        if (s.isEmpty()) {
            return id;
        }
        return id.endsWith(":") ? id + s : id + "/" + s;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private int rewrite(String fromId, Function<String, String> to) throws PersistenceException {
        int adjusted = 0;
        for (Resource r : tagged(Collections.singletonList(fromId))) {
            ModifiableValueMap props = writable(r);
            Set<String> next = new LinkedHashSet<>();
            for (String v : values(props.get(TAGS_PROPERTY))) {
                next.add(matches(v, fromId) ? to.apply(normalize(v)) : v);
            }
            props.put(TAGS_PROPERTY, next.toArray(new String[0]));
            adjusted++;
        }
        return adjusted;
    }

    /** Resources below {@code /content} (outside the tag tree) whose {@code cq:tags} refer to any of {@code ids}. */
    private List<Resource> tagged(List<String> ids) {
        List<Resource> out = new ArrayList<>();
        Resource content = resolver.getResource("/content");
        if (content != null) {
            walk(content, ids, out);
        }
        return out;
    }

    private void walk(Resource r, List<String> ids, List<Resource> out) {
        String path = r.getPath();
        if (path.equals(ROOT) || "renditions".equals(r.getName()) || "rep:policy".equals(r.getName())) {
            return;
        }
        Object tags = r.getValueMap().get(TAGS_PROPERTY);
        if (tags != null) {
            for (String v : values(tags)) {
                if (ids.stream().anyMatch(id -> matches(v, id))) {
                    out.add(r);
                    break;
                }
            }
        }
        for (Resource child : r.getChildren()) {
            walk(child, ids, out);
        }
    }

    private Map<String, Object> reference(Resource r, String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.getPath());
        Resource owner = r;
        String kind = "content";
        while (owner != null) {
            String type = owner.getValueMap().get("jcr:primaryType", "");
            if ("cq:Page".equals(type)) {
                kind = "page";
                break;
            }
            if ("dam:Asset".equals(type)) {
                kind = "asset";
                break;
            }
            owner = owner.getParent();
        }
        if (owner == null) {
            owner = r;
        }
        out.put("kind", kind);
        out.put("owner", owner.getPath());
        out.put("title", titleOf(owner, kind));
        List<String> used = new ArrayList<>();
        for (String v : values(r.getValueMap().get(TAGS_PROPERTY))) {
            if (matches(v, id)) {
                used.add(v);
            }
        }
        out.put("tags", used);
        return out;
    }

    private static String titleOf(Resource owner, String kind) {
        Resource content = owner.getChild("jcr:content");
        String title = null;
        if ("page".equals(kind) && content != null) {
            title = content.getValueMap().get("jcr:title", String.class);
        } else if ("asset".equals(kind) && content != null && content.getChild("metadata") != null) {
            Object t = content.getChild("metadata").getValueMap().get("dc:title");
            title = t instanceof String[] ? (((String[]) t).length > 0 ? ((String[]) t)[0] : null) : (String) t;
        }
        return title == null || title.isEmpty() ? owner.getName() : title;
    }

    private void mergeChildren(Resource source, Resource target) throws PersistenceException, RepositoryException {
        List<Resource> children = new ArrayList<>();
        for (Resource c : source.getChildren()) {
            if (isTag(c)) {
                children.add(c);
            }
        }
        for (Resource child : children) {
            Resource existing = target.getChild(child.getName());
            if (existing != null && isTag(existing)) {
                mergeChildren(child, existing);
            } else {
                relocate(child, target, child.getName());
            }
        }
    }

    /** Moves {@code src} to {@code dest/name}: a JCR move when there is a session, else copy and delete. */
    private void relocate(Resource src, Resource dest, String name) throws PersistenceException, RepositoryException {
        Session session = resolver.adaptTo(Session.class);
        if (session != null) {
            session.move(src.getPath(), dest.getPath() + "/" + name);
            return;
        }
        copy(src, dest, name);
        resolver.delete(src);
    }

    private void copy(Resource src, Resource dest, String name) throws PersistenceException {
        Map<String, Object> props = new HashMap<>(src.getValueMap());
        Resource copy = resolver.create(dest, name, props);
        for (Resource c : src.getChildren()) {
            copy(c, copy, c.getName());
        }
    }

    private Map<String, Object> summary(Resource tag) {
        ValueMap vm = tag.getValueMap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", tag.getPath());
        out.put("name", tag.getName());
        out.put("tagID", idOf(tag.getPath()));
        out.put("title", vm.get("jcr:title", tag.getName()));
        out.put("description", vm.get("jcr:description", String.class));
        out.put("namespace", parentPath(tag.getPath()).equals(ROOT));
        boolean hasChildren = false;
        for (Resource c : tag.getChildren()) {
            if (isTag(c)) {
                hasChildren = true;
                break;
            }
        }
        out.put("hasChildren", hasChildren);
        out.put("created", iso(vm.get("jcr:created", Calendar.class)));
        out.put("lastModified", iso(vm.get("jcr:lastModified", Calendar.class)));
        out.put("lastModifiedBy", vm.get("jcr:lastModifiedBy", vm.get("jcr:createdBy", String.class)));
        return out;
    }

    private static Map<String, Object> root(Resource root) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", root.getPath());
        out.put("name", root.getName());
        out.put("title", "Tags");
        out.put("root", true);
        out.put("hasChildren", true);
        return out;
    }

    private List<Map<String, Object>> trail(Resource item) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Resource r = item; r != null && inside(r.getPath(), ROOT); r = r.getParent()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("path", r.getPath());
            step.put("title", r.getPath().equals(ROOT) ? "Tags" : r.getValueMap().get("jcr:title", r.getName()));
            out.add(0, step);
        }
        return out;
    }

    private Resource tagOrRoot(String path) {
        String p = path == null || path.isEmpty() ? ROOT : path.replaceAll("/+$", "");
        if (p.equals(ROOT)) {
            Resource root = resolver.getResource(ROOT);
            if (root == null) {
                throw new IllegalArgumentException("There is no tag root " + ROOT);
            }
            return root;
        }
        return requireTag(p);
    }

    private Resource requireTag(String path) {
        if (path == null || !inside(path, ROOT) || path.equals(ROOT)) {
            throw new IllegalArgumentException("Not a tag path: " + path);
        }
        Resource r = resolver.getResource(path);
        if (r == null || !isTag(r)) {
            throw new IllegalArgumentException("No tag at " + path);
        }
        return r;
    }

    static boolean isTag(Resource r) {
        return TAG_TYPE.equals(r.getValueMap().get("jcr:primaryType", String.class));
    }

    private static ModifiableValueMap writable(Resource r) {
        ModifiableValueMap m = r.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new IllegalStateException("Not allowed to modify " + r.getPath());
        }
        return m;
    }

    private void stamp(Map<String, Object> props) {
        props.put("jcr:lastModified", Calendar.getInstance());
        props.put("jcr:lastModifiedBy", resolver.getUserID());
    }

    private static Map<String, Object> result(String path, int adjusted) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("adjusted", adjusted);
        return out;
    }

    static List<String> values(Object v) {
        if (v instanceof String[]) {
            return Arrays.asList((String[]) v);
        }
        if (v instanceof String) {
            return Collections.singletonList((String) v);
        }
        if (v instanceof Object[]) {
            List<String> out = new ArrayList<>();
            for (Object o : (Object[]) v) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return Collections.emptyList();
    }

    static void checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid tag name: " + name
                    + " (letters, digits, '_', '-' and '.', not starting with '.' or '-')");
        }
    }

    /** A tag name from a title, as AEM suggests it: lower case, other characters as '-'. */
    static String nameFrom(String title) {
        String n = title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "tag" : n;
    }

    static boolean inside(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    static String parentPath(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    private static String iso(Calendar c) {
        return c == null ? null : Instant.ofEpochMilli(c.getTimeInMillis()).toString();
    }
}
