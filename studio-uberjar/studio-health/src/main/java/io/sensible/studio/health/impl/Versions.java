/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

/**
 * Version purge: every versionable node under the given roots keeps its newest {@code keep} versions and
 * every version younger than {@code keepDays}; the rest are removed from its history. The root version and
 * the current base version are never removed. Mirrors what AEM's Version Purge does with "max versions"
 * and "max age", on a schedule nobody has to remember.
 */
final class Versions {

    private Versions() {
    }

    /** What a purge did. */
    static final class Result {
        int nodes;
        int versions;
        int removed;
        final List<String> errors = new ArrayList<>();

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodes", nodes);
            m.put("versions", versions);
            m.put("removed", removed);
            if (!errors.isEmpty()) {
                m.put("errors", errors.size() > 20 ? errors.subList(0, 20) : errors);
            }
            return m;
        }
    }

    static Result purge(Session session, List<String> roots, int keep, int keepDays) throws RepositoryException {
        Result r = new Result();
        long cutoff = System.currentTimeMillis() - keepDays * 86_400_000L;
        VersionManager vm = session.getWorkspace().getVersionManager();
        for (String root : roots) {
            if (!session.nodeExists(root)) {
                continue;
            }
            String stmt = "SELECT * FROM [mix:versionable] AS n WHERE ISDESCENDANTNODE(n, '" + root.replace("'", "''") + "')";
            NodeIterator it = session.getWorkspace().getQueryManager().createQuery(stmt, Query.JCR_SQL2).execute().getNodes();
            while (it.hasNext()) {
                Node n = it.nextNode();
                r.nodes++;
                try {
                    r.removed += purgeNode(vm, n, keep, cutoff, r);
                } catch (RepositoryException e) {
                    r.errors.add(n.getPath() + ": " + e.getMessage());
                }
            }
        }
        return r;
    }

    private static int purgeNode(VersionManager vm, Node n, int keep, long cutoff, Result r) throws RepositoryException {
        VersionHistory history = vm.getVersionHistory(n.getPath());
        Version base = vm.getBaseVersion(n.getPath());
        String rootName = history.getRootVersion().getName();
        List<Version> all = new ArrayList<>();
        for (VersionIterator it = history.getAllVersions(); it.hasNext(); ) {
            Version v = it.nextVersion();
            if (!v.getName().equals(rootName)) {
                all.add(v);
            }
        }
        r.versions += all.size();
        // newest first; the first `keep` stay, and so does anything younger than the cutoff or the base
        all.sort(Comparator.comparing((Version v) -> created(v)).reversed());
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            Version v = all.get(i);
            if (created(v) >= cutoff || v.getName().equals(base.getName())) {
                continue;
            }
            if (history.getVersionLabels(v).length > 0) {
                continue; // a labelled version was kept on purpose
            }
            history.removeVersion(v.getName());
            removed++;
        }
        return removed;
    }

    private static long created(Version v) {
        try {
            Calendar c = v.getCreated();
            return c == null ? 0 : c.getTimeInMillis();
        } catch (RepositoryException e) {
            return 0;
        }
    }
}
