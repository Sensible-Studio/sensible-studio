/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import javax.jcr.version.VersionHistory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The version storage, read for pages that were deleted without a deletion record.
 *
 * <p>Oak notes on each version history where its node was versioned: a property named after the workspace (mixin
 * {@code rep:VersionablePaths}). With the index {@value #INDEX} (the content package in {@code oak-index/}) the histories below a path
 * are found by a query on that property; without it — or while it is not yet built — the version storage is walked,
 * which stops after {@link #MAX_VISITS} nodes. Either is done when a page goes ({@code DeletedPagesListener}) and
 * once at startup, not per request.</p>
 */
final class VersionHistories {

    static final String STORAGE = "/jcr:system/jcr:versionStorage";
    static final int MAX_VISITS = 200_000;
    /** The Lucene index on the workspace property of version histories. */
    static final String INDEX = "studioVersionablePaths";
    private static final String CONTENT = "/jcr:content";
    private static final Logger LOG = LoggerFactory.getLogger(VersionHistories.class);

    private VersionHistories() {
    }

    /**
     * The page content histories that were versioned below {@code prefix} (a page path, or {@code /content}) and whose
     * content no longer exists anywhere, keyed by page path. Histories with no version but the root one are left out.
     */
    static Map<String, VersionHistory> deletedBelow(Session s, String prefix) throws RepositoryException {
        Map<String, VersionHistory> out = new LinkedHashMap<>();
        if (!s.nodeExists(STORAGE)) {
            return out;
        }
        String workspace = s.getWorkspace().getName();
        String below = prefix.endsWith("/") ? prefix : prefix + "/";
        long start = System.currentTimeMillis();
        if (indexed(s, workspace)) {
            try {
                for (RowIterator it = query(s, workspace, below).getRows(); it.hasNext();) {
                    Row row = it.nextRow();
                    consider(s, row.getNode(), below, workspace, out);
                }
                lastScan = "index, " + (System.currentTimeMillis() - start) + " ms";
                return out;
            } catch (RepositoryException e) {
                LOG.warn("Querying {} failed, walking the version storage instead", INDEX, e);
                out.clear();
            }
        }
        int[] visits = {0};
        walk(s, s.getNode(STORAGE), below, workspace, out, visits);
        lastScan = "walk of " + visits[0] + " nodes, " + (System.currentTimeMillis() - start) + " ms";
        return out;
    }

    /** How the last search ran ("index, 3 ms" or "walk of 1200 nodes, 40 ms"), for the log. */
    static volatile String lastScan = "";

    /** The histories versioned below a path, by the workspace property (JCR-SQL2, LIKE on the prefix). */
    static QueryResult query(Session s, String workspace, String below) throws RepositoryException {
        return s.getWorkspace().getQueryManager().createQuery(sql(workspace, below), Query.JCR_SQL2).execute();
    }

    static String sql(String workspace, String below) {
        String like = below.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return "SELECT [jcr:path] FROM [nt:versionHistory] AS h WHERE ISDESCENDANTNODE(h, '" + STORAGE + "') AND h.["
                + workspace.replace("]", "") + "] LIKE '" + like.replace("'", "''") + "'";
    }

    /**
     * Whether the query would use the index ({@code EXPLAIN} names it): without it Oak would traverse every version
     * and frozen node, far more than the walk visits. The answer is kept for a minute.
     */
    static boolean indexed(Session s, String workspace) {
        long now = System.currentTimeMillis();
        if (now < checkedUntil) {
            return lastIndexed;
        }
        boolean yes;
        try {
            QueryResult r = s.getWorkspace().getQueryManager().createQuery("explain " + sql(workspace, "/content/"), Query.JCR_SQL2).execute();
            RowIterator it = r.getRows();
            yes = it.hasNext() && String.valueOf(it.nextRow().getValue("plan").getString()).contains(INDEX);
        } catch (RepositoryException | RuntimeException e) {
            yes = false;
        }
        lastIndexed = yes;
        checkedUntil = now + 60_000;
        return yes;
    }

    private static volatile boolean lastIndexed;
    private static volatile long checkedUntil;

    /** For tests: forget whether the index was there. */
    static void recheckIndex() {
        checkedUntil = 0;
    }

    private static void walk(Session s, Node n, String prefix, String workspace, Map<String, VersionHistory> out, int[] visits)
            throws RepositoryException {
        for (NodeIterator it = n.getNodes(); it.hasNext() && visits[0] < MAX_VISITS;) {
            Node c = it.nextNode();
            visits[0]++;
            if (c.isNodeType("nt:versionHistory")) {
                consider(s, c, prefix, workspace, out);
            } else if (c.isNodeType("rep:versionStorage") || c.getName().length() == 2) {
                walk(s, c, prefix, workspace, out, visits); // the hash folders (ab/cd/ef/...)
            }
        }
    }

    /** A history found by the walk or the query: kept when its page content was below the prefix and is gone. */
    private static void consider(Session s, Node c, String prefix, String workspace, Map<String, VersionHistory> out)
            throws RepositoryException {
        if (!c.hasProperty(workspace)) {
            return;
        }
        String path = c.getProperty(workspace).getString();
        if (!path.startsWith(prefix) || !path.endsWith(CONTENT) || s.nodeExists(path)) {
            return;
        }
        // a node met while walking (or a query row's node) is a plain node; by its identifier it is a version history
        Node byId = s.getNodeByIdentifier(c.getIdentifier());
        if (!(byId instanceof VersionHistory)) {
            return;
        }
        VersionHistory h = (VersionHistory) byId;
        if (!hasVersions(h) || livePath(s, h) != null) {
            return; // nothing to restore, or moved rather than deleted
        }
        out.put(path.substring(0, path.length() - CONTENT.length()), h);
    }

    /** Whether a history has a version besides its root version (iterators may not know their size). */
    private static boolean hasVersions(VersionHistory h) throws RepositoryException {
        for (javax.jcr.version.VersionIterator it = h.getAllVersions(); it.hasNext();) {
            if (!"jcr:rootVersion".equals(it.nextVersion().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Where the versioned node of a history is now (a moved page), or null when it no longer exists. */
    static String livePath(Session s, VersionHistory h) throws RepositoryException {
        try {
            return s.getNodeByIdentifier(h.getVersionableIdentifier()).getPath();
        } catch (ItemNotFoundException e) {
            return null;
        }
    }
}
