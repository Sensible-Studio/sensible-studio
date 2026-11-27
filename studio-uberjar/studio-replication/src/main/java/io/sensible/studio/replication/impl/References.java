/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.replication.ReplicationStatus;

/**
 * What a page needs on the publish side besides itself, like AEM's Publish dialog lists: assets
 * ({@code fileReference}, {@code cq:featuredimage}), experience fragments ({@code fragmentVariationPath}),
 * content fragments ({@code fragmentPath}), the audiences of targeted components ({@code cq:segment}), and its
 * template's policies and structure when they are not published yet.
 */
final class References {

    private static final int MAX_DEPTH = 24;
    /** {@code cq:segment}: the audience of a targeted component's experience (the segment page must be on publish too). */
    private static final String[] PROPERTIES = {"fileReference", "fragmentVariationPath", "fragmentPath", "cq:template", "cq:segment"};

    private References() {
    }

    static List<Map<String, Object>> unpublished(ResourceResolver rr, Session session, ReplicatorImpl replicator, String path)
            throws RepositoryException {
        return unpublished(rr, session, replicator, path, null);
    }

    /** As above, on a tier (null: publish; {@code preview}: what the preview tier lacks). */
    static List<Map<String, Object>> unpublished(ResourceResolver rr, Session session, ReplicatorImpl replicator, String path, String tier)
            throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource page = path == null ? null : rr.getResource(path);
        if (page == null) {
            return out;
        }
        Set<String> refs = new LinkedHashSet<>();
        collect(page.getChild("jcr:content") != null ? page.getChild("jcr:content") : page, 0, refs);
        // An image set (a file in Media that names several pictures) is nothing without its pictures: they
        // are needed too, whether the set is what is published or something a page shows.
        Set<String> pictures = new LinkedHashSet<>();
        setMembers(page, pictures);
        for (String ref : refs) {
            setMembers(rr.getResource(ref), pictures);
        }
        pictures.remove(path);
        refs.addAll(pictures);
        for (String ref : refs) {
            Resource target = rr.getResource(ref);
            if (target == null) {
                continue;
            }
            // an experience fragment variation or a content fragment is a page/asset of its own; an asset too
            String top = ref;
            if (ref.startsWith("/content/dam/")) {
                // the asset: the dam:Asset node (fileReference may point at a rendition)
                for (Resource r = target; r != null && r.getPath().startsWith("/content/dam/"); r = r.getParent()) {
                    if ("dam:Asset".equals(r.getValueMap().get("jcr:primaryType", String.class))) {
                        top = r.getPath();
                        break;
                    }
                }
            }
            ReplicationStatus s = replicator.getReplicationStatus(session, top, tier);
            boolean published = s != null && s.isDelivered();
            if (published) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", top);
            m.put("type", top.startsWith("/content/dam/") ? "asset" : top.startsWith("/content/experience-fragments/") ? "experience-fragment"
                    : top.contains("/settings/wcm/segments/") ? "segment" : top.startsWith("/conf/") ? "template" : "page");
            Resource topRes = rr.getResource(top);
            Resource content = topRes == null ? null : topRes.getChild("jcr:content");
            String title = content == null ? null : content.getValueMap().get("jcr:title", String.class);
            if (title == null && content != null && content.getChild("metadata") != null) {
                Object t = content.getChild("metadata").getValueMap().get("dc:title");
                title = t instanceof String[] ? (((String[]) t).length > 0 ? ((String[]) t)[0] : null) : t == null ? null : String.valueOf(t);
            }
            m.put("title", title != null ? title : top.substring(top.lastIndexOf('/') + 1));
            m.put("status", s == null ? "never" : s.isActivated() ? "modified" : "deactivated");
            // an author's first publish offered the page's template, ticked, and failed on it: he may not publish /conf
            boolean may = true;
            try {
                replicator.checkPermission(session, com.day.cq.replication.ReplicationActionType.ACTIVATE, top);
            } catch (com.day.cq.replication.ReplicationException e) {
                may = false;
            }
            m.put("canPublish", may);
            out.add(m);
        }
        return out;
    }

    /** The pictures of {@code r} when it is an image set ({@code jcr:content/studio:imageSet}); nothing otherwise. */
    static void setMembers(Resource r, Set<String> into) {
        Resource content = r == null ? null : r.getChild("jcr:content");
        if (content == null || !content.getValueMap().get("studio:imageSet", false)) {
            return;
        }
        String[] members = content.getValueMap().get("studio:members", String[].class);
        if (members != null) {
            for (String m : members) {
                if (m != null && m.startsWith("/content/dam/")) {
                    into.add(m);
                }
            }
        }
    }

    private static void collect(Resource r, int depth, Set<String> refs) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (String p : PROPERTIES) {
            Object v = r.getValueMap().get(p);
            if (v instanceof String && ((String) v).startsWith("/")) {
                refs.add((String) v);
            } else if (v instanceof String[]) {
                for (String s : (String[]) v) {
                    if (s != null && s.startsWith("/")) {
                        refs.add(s);
                    }
                }
            }
        }
        for (Resource c : r.getChildren()) {
            if (!"cq:Page".equals(c.getValueMap().get("jcr:primaryType", String.class))) {
                collect(c, depth + 1, refs);
            }
        }
    }
}
