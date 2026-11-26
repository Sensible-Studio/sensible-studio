/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

/**
 * JCR versions of assets, for the Timeline: list, create (with label and comment), restore.
 *
 * <p>An asset becomes {@code mix:versionable} on its first version. The comment is written to
 * {@code jcr:content/cq:versionComment} just before the check-in, so the frozen copy carries it
 * (as AEM does for pages). Assets stay checked out, so they can be edited as before.</p>
 */
final class DamVersions {

    static final String PN_COMMENT = "cq:versionComment";
    static final String PN_CREATED_BY = "cq:versionCreatedBy";
    /** Labels must not clash with version names or JCR syntax. */
    static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile("^[\\p{L}\\p{N} _.\\-]{1,64}$");

    private DamVersions() {
    }

    static List<Map<String, Object>> list(Session session, String path) throws RepositoryException {
        Node node = session.getNode(path);
        if (!node.isNodeType("mix:versionable")) {
            return Collections.emptyList();
        }
        VersionHistory history = session.getWorkspace().getVersionManager().getVersionHistory(path);
        List<Map<String, Object>> out = new ArrayList<>();
        VersionIterator it = history.getAllVersions();
        while (it.hasNext()) {
            Version v = it.nextVersion();
            if ("jcr:rootVersion".equals(v.getName())) {
                continue;
            }
            out.add(describe(history, v));
        }
        Collections.reverse(out); // newest first
        return out;
    }

    static Map<String, Object> describe(VersionHistory history, Version v) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", v.getName());
        m.put("created", v.getCreated());
        List<String> labels = new ArrayList<>();
        Collections.addAll(labels, history.getVersionLabels(v));
        m.put("labels", labels);
        Node frozen = v.getFrozenNode();
        Node content = frozen.hasNode("jcr:content") ? frozen.getNode("jcr:content") : null;
        m.put("comment", content != null && content.hasProperty(PN_COMMENT) ? content.getProperty(PN_COMMENT).getString() : null);
        m.put("createdBy", content != null && content.hasProperty(PN_CREATED_BY) ? content.getProperty(PN_CREATED_BY).getString() : null);
        return m;
    }

    /** Checks the asset in (saving pending changes first) and out again; returns the new version. */
    static Map<String, Object> create(Session session, String path, String label, String comment, String user)
            throws RepositoryException, IllegalArgumentException {
        if (label != null && !label.trim().isEmpty() && !LABEL.matcher(label.trim()).matches()) {
            throw new IllegalArgumentException("labels may use letters, digits, spaces, '.', '_' and '-' (64 at most)");
        }
        Node node = session.getNode(path);
        if (!node.isNodeType("mix:versionable")) {
            node.addMixin("mix:versionable");
        }
        if (node.hasNode("jcr:content")) {
            Node content = node.getNode("jcr:content");
            String c = comment == null ? "" : comment.trim();
            if (c.length() > 2000) {
                c = c.substring(0, 2000);
            }
            if (c.isEmpty()) {
                if (content.hasProperty(PN_COMMENT)) {
                    content.getProperty(PN_COMMENT).remove();
                }
            } else {
                content.setProperty(PN_COMMENT, c);
            }
            content.setProperty(PN_CREATED_BY, user);
        }
        session.save();
        VersionManager vm = session.getWorkspace().getVersionManager();
        if (!vm.isCheckedOut(path)) {
            vm.checkout(path);
        }
        Version v = vm.checkin(path);
        vm.checkout(path);
        VersionHistory history = vm.getVersionHistory(path);
        if (label != null && !label.trim().isEmpty()) {
            history.addVersionLabel(v.getName(), label.trim(), true); // moves the label if another version had it
        }
        return describe(history, v);
    }

    /**
     * Restores {@code version} after saving the current state as a version of its own, so a
     * restore can be undone. Returns the version that was saved.
     */
    static Map<String, Object> restore(Session session, String path, String version, String user) throws RepositoryException {
        VersionManager vm = session.getWorkspace().getVersionManager();
        VersionHistory history = vm.getVersionHistory(path);
        Version target = history.getVersion(version); // throws VersionException if unknown
        Map<String, Object> saved = create(session, path, null, "Before restoring version " + version, user);
        vm.restore(target, true);
        vm.checkout(path);
        Node node = session.getNode(path);
        if (node.hasNode("jcr:content")) {
            Node content = node.getNode("jcr:content");
            content.setProperty("jcr:lastModified", Calendar.getInstance());
            content.setProperty("jcr:lastModifiedBy", user);
            session.save();
        }
        return saved;
    }
}
