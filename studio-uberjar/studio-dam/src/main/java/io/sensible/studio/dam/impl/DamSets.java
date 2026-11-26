/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Image sets: several pictures kept as one file in Media, in an order somebody chose, to be put
 * on a page together (the Responsive Image component shows a set as a gallery).
 *
 * <p>A set is a {@code dam:Asset} without an original: {@code jcr:content/studio:imageSet = true}
 * and {@code jcr:content/studio:members}, the paths of its pictures in order. It holds no copy of
 * them: a picture that is edited shows edited in the set, one that is deleted is simply no longer
 * in it, and each reader sees the members they may read. Being an asset, a set is listed, moved,
 * published and deleted like any file.</p>
 */
final class DamSets {

    static final String PN_SET = "studio:imageSet";
    static final String PN_MEMBERS = "studio:members";
    /** How a set is shown: {@link #GALLERY} (the default) or {@link #SPIN}. */
    static final String PN_KIND = "studio:setKind";
    /** One picture or video at a time, picked by thumbnails. */
    static final String GALLERY = "gallery";
    /** Pictures of one thing from successive angles, turned by dragging: pictures only. */
    static final String SPIN = "spin";
    /** What the console and pickers see as a set's type (it has no file of its own). */
    static final String MIME = "image/vnd.studio.set";
    static final int MAX_MEMBERS = 200;

    private DamSets() {
    }

    static boolean isSet(Resource asset) {
        Resource c = asset == null ? null : asset.getChild("jcr:content");
        return c != null && c.getValueMap().get(PN_SET, false);
    }

    /** {@link #GALLERY} or {@link #SPIN}. */
    static String kind(Resource set) {
        Resource c = set.getChild("jcr:content");
        return c != null && SPIN.equals(c.getValueMap().get(PN_KIND, String.class)) ? SPIN : GALLERY;
    }

    static String validKind(String kind) throws DamApiServlet.ApiException {
        if (kind == null || kind.isEmpty() || GALLERY.equals(kind)) {
            return GALLERY;
        }
        if (SPIN.equals(kind)) {
            return SPIN;
        }
        throw new DamApiServlet.ApiException(400, "kind must be " + GALLERY + " or " + SPIN);
    }

    /** The member paths as stored, in order (also those that no longer exist). */
    static List<String> memberPaths(Resource set) {
        Resource c = set.getChild("jcr:content");
        String[] paths = c == null ? null : c.getValueMap().get(PN_MEMBERS, String[].class);
        List<String> out = new ArrayList<>();
        if (paths != null) {
            for (String p : paths) {
                out.add(p);
            }
        }
        return out;
    }

    /** The pictures of the set that exist and that this reader may see, in order. */
    static List<Resource> members(Resource set) {
        List<Resource> out = new ArrayList<>();
        ResourceResolver resolver = set.getResourceResolver();
        for (String p : memberPaths(set)) {
            Resource r = DamItems.inDam(p) ? resolver.getResource(p) : null;
            if (r != null && isMember(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /** An image asset: not a set, not a record, with an original that is an image. */
    static boolean isPicture(Resource r) {
        if (!DamItems.isAsset(r) || isSet(r) || DamItems.isContentFragment(r)) {
            return false;
        }
        Object mime = DamItems.asset(r).get("mimeType");
        return mime != null && mime.toString().startsWith("image/");
    }

    /** A file a set can hold: a picture, or a video (a gallery shows both; a spin only pictures). */
    static boolean isMember(Resource r) {
        if (isPicture(r)) {
            return true;
        }
        if (!DamItems.isAsset(r) || isSet(r) || DamItems.isContentFragment(r)) {
            return false;
        }
        Object mime = DamItems.asset(r).get("mimeType");
        return mime != null && mime.toString().startsWith("video/");
    }

    /** The paths as they will be stored: pictures (and videos, in a gallery), each once, at least one, in the order given. */
    static List<String> valid(ResourceResolver resolver, List<String> paths, String kind) throws DamApiServlet.ApiException {
        Set<String> out = new LinkedHashSet<>();
        for (String p : paths) {
            Resource r = DamItems.inDam(p) ? resolver.getResource(p) : null;
            if (r == null) {
                throw new DamApiServlet.ApiException(404, "not found: " + p);
            }
            if (SPIN.equals(kind) ? !isPicture(r) : !isMember(r)) {
                throw new DamApiServlet.ApiException(400,
                        (SPIN.equals(kind) ? "only pictures can be in a spin set: " : "only pictures and videos can be in a set: ") + p);
            }
            out.add(r.getPath());
        }
        if (out.isEmpty()) {
            throw new DamApiServlet.ApiException(400, "a set needs at least one picture");
        }
        if (out.size() > MAX_MEMBERS) {
            throw new DamApiServlet.ApiException(400, "a set holds at most " + MAX_MEMBERS + " pictures");
        }
        return new ArrayList<>(out);
    }

    /** Creates the set {@code name} in {@code folder}. Nothing is committed. */
    static Resource create(ResourceResolver resolver, Resource folder, String name, String title, List<String> members, String kind)
            throws DamApiServlet.ApiException, PersistenceException {
        List<String> paths = valid(resolver, members, kind);
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "dam:Asset");
        Resource set = resolver.create(folder, name, props);
        Map<String, Object> content = new HashMap<>();
        content.put("jcr:primaryType", "dam:AssetContent");
        content.put(PN_SET, true);
        content.put(PN_MEMBERS, paths.toArray(new String[0]));
        content.put(PN_KIND, kind);
        content.put("jcr:title", title);
        content.put("jcr:lastModified", Calendar.getInstance());
        content.put("jcr:lastModifiedBy", resolver.getUserID());
        Resource c = resolver.create(set, "jcr:content", content);
        Map<String, Object> md = new HashMap<>();
        md.put("jcr:primaryType", "nt:unstructured");
        md.put("dc:title", title);
        md.put("dc:format", MIME); // what the page editor's Media panel lists and drops a file by
        resolver.create(c, "metadata", md);
        refreshPreview(resolver, set, paths);
        return set;
    }

    /**
     * Replaces the members (their order is the order given) and, when {@code kind} is given, how
     * the set is shown. Nothing is committed.
     */
    static void setMembers(ResourceResolver resolver, Resource set, List<String> members, String kind)
            throws DamApiServlet.ApiException {
        String shown = kind == null ? kind(set) : kind;
        List<String> paths = valid(resolver, members, shown);
        Resource c = set.getChild("jcr:content");
        ModifiableValueMap props = c == null ? null : c.adaptTo(ModifiableValueMap.class);
        if (props == null) {
            throw new DamApiServlet.ApiException(403, "you may not change this set");
        }
        props.put(PN_MEMBERS, paths.toArray(new String[0]));
        props.put(PN_KIND, shown);
        props.put("jcr:lastModified", Calendar.getInstance());
        props.put("jcr:lastModifiedBy", resolver.getUserID());
        // sets made before they said what they are get it now
        Resource md = c.getChild("metadata");
        ModifiableValueMap mdProps = md == null ? null : md.adaptTo(ModifiableValueMap.class);
        if (mdProps != null && !MIME.equals(mdProps.get("dc:format", String.class))) {
            mdProps.put("dc:format", MIME);
        }
        try {
            refreshPreview(resolver, set, paths);
        } catch (PersistenceException e) {
            throw new DamApiServlet.ApiException(403, "you may not change this set");
        }
    }

    /**
     * Gives the set the thumbnail and web renditions of its first picture, as its own. A set has
     * no file to make them from, and everything that shows a file by its renditions (pickers, the
     * page editor, previews) would show a blank otherwise. Refreshed whenever the members change.
     */
    static void refreshPreview(ResourceResolver resolver, Resource set, List<String> members) throws PersistenceException {
        Resource content = set.getChild("jcr:content");
        if (content == null) {
            return;
        }
        Resource mine = content.getChild("renditions");
        if (mine != null) {
            resolver.delete(mine);
        }
        Resource first = null;
        for (String m : members) { // the first picture that is still there
            first = resolver.getResource(m);
            if (first != null) {
                break;
            }
        }
        Resource theirs = first == null ? null : first.getChild("jcr:content/renditions");
        if (theirs == null) {
            return;
        }
        Map<String, Object> folder = new HashMap<>();
        folder.put("jcr:primaryType", "nt:folder");
        mine = resolver.create(content, "renditions", folder);
        for (Resource r : theirs.getChildren()) {
            String name = r.getName();
            if (!name.startsWith("cq5dam.thumbnail.") && !name.startsWith("cq5dam.web.")) {
                continue;
            }
            Resource data = r.getChild("jcr:content");
            java.io.InputStream in = data == null ? null : data.getValueMap().get("jcr:data", java.io.InputStream.class);
            if (in == null) {
                continue;
            }
            Map<String, Object> file = new HashMap<>();
            file.put("jcr:primaryType", "nt:file");
            Resource copy = resolver.create(mine, name, file);
            Map<String, Object> binary = new HashMap<>();
            binary.put("jcr:primaryType", "oak:Resource");
            binary.put("jcr:data", in);
            binary.put("jcr:mimeType", data.getValueMap().get("jcr:mimeType", "image/png"));
            binary.put("jcr:lastModified", Calendar.getInstance());
            resolver.create(copy, "jcr:content", binary);
        }
    }

    /**
     * Gives every set whose first picture is {@code picture} that picture's renditions again (after
     * it was edited or processed anew), or, when {@code picture} was removed, those of the next one. Needs the Media index; until it is built nothing is
     * refreshed, and the next change of the set's pictures refreshes it. Nothing is committed.
     *
     * @return how many sets were refreshed
     */
    static int refreshSetsStartingWith(ResourceResolver resolver, String picture) throws PersistenceException {
        javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
        if (session == null || !DamIndex.ready(session)) {
            return 0;
        }
        List<String> sets;
        try {
            sets = DamIndexQueries.setsWith(session, picture);
        } catch (javax.jcr.RepositoryException e) {
            return 0;
        }
        int n = 0;
        for (String path : sets) {
            Resource set = resolver.getResource(path);
            if (set == null || !isSet(set)) {
                continue;
            }
            List<String> members = memberPaths(set);
            for (String m : members) {
                // the picture the set is shown by: the first that is there (or this one, edited or just removed)
                if (picture.equals(m)) {
                    refreshPreview(resolver, set, members);
                    n++;
                    break;
                }
                if (resolver.getResource(m) != null) {
                    break;
                }
            }
        }
        return n;
    }
}
