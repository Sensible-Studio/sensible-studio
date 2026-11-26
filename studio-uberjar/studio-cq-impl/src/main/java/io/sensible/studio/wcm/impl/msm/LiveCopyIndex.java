/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Which blueprint each live copy comes from, for the source-side questions ("is this page a blueprint, which live
 * copies does it have"). Built by walking the page tree below {@code /content} (pages and folders only, never their
 * content) and kept until a live copy configuration or a page is added, moved or removed.
 *
 * <p>Entries are cached per user, so nobody learns about live copies they cannot read.
 */
final class LiveCopyIndex {

    /** One live copy: its root page and its blueprint. */
    static final class Entry {
        final String root;
        final String master;

        Entry(String root, String master) {
            this.root = root;
            this.master = master;
        }

        @Override
        public String toString() {
            return root + " <- " + master;
        }
    }

    private static final Set<String> CONTAINERS = new HashSet<>(Arrays.asList(
            Msm.NT_PAGE, "sling:Folder", "sling:OrderedFolder", "nt:folder"));
    /** Trees without pages: assets, tags, user generated content. */
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList(
            "/content/dam", "/content/cq:tags", "/content/usergenerated", "/content/projects"));

    private final Map<String, List<Entry>> byUser = new ConcurrentHashMap<>();

    List<Entry> entries(ResourceResolver resolver) {
        String user = resolver.getUserID() == null ? "" : resolver.getUserID();
        return byUser.computeIfAbsent(user, u -> build(resolver));
    }

    void invalidate() {
        byUser.clear();
    }

    static List<Entry> build(ResourceResolver resolver) {
        List<Entry> out = new ArrayList<>();
        Resource content = resolver.getResource("/content");
        if (content != null) {
            walk(content, out);
        }
        return Collections.unmodifiableList(out);
    }

    private static void walk(Resource r, List<Entry> out) {
        for (Resource child : r.getChildren()) {
            String type = child.getValueMap().get(Msm.PRIMARY, String.class);
            if (!CONTAINERS.contains(type) || SKIPPED.contains(child.getPath())) {
                continue;
            }
            if (Msm.NT_PAGE.equals(type)) {
                Resource cfg = child.getChild(Msm.JCR_CONTENT + "/cq:LiveSyncConfig");
                String master = cfg == null ? null : cfg.getValueMap().get(PN_MASTER, String.class);
                if (master != null && !master.isEmpty()) {
                    out.add(new Entry(child.getPath(), master));
                }
            }
            walk(child, out);
        }
    }
}
