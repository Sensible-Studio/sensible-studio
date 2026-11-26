/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;

/**
 * The searches of the console as JCR-SQL2 queries, answered by {@link DamIndex}. They run with
 * the user's session, so Oak leaves out what the user cannot read. Values go in as bind
 * variables; only the scope path is written into the statement, after {@link #literal} checks it.
 *
 * <p>What the index cannot answer exactly (file type groups, orientation) is filtered afterwards
 * by {@link DamSearch#matchesFilters}, on the much smaller set of matches.</p>
 */
final class DamIndexQueries {

    /** Rows read at most per query (the console shows the first page of them). */
    static final long MAX_ROWS = 10_000;

    private DamIndexQueries() {
    }

    /** Paths of matching assets, best first (relevance when there is text, else newest first). */
    static List<String> assets(Session session, String scope, DamSearch.Query q, long now) throws RepositoryException {
        StringBuilder sql = new StringBuilder("SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, ")
                .append(literal(scope)).append(')');
        Map<String, Object> binds = new LinkedHashMap<>();
        String ft = fulltext(q.fulltext);
        if (ft != null) {
            sql.append(" AND CONTAINS(a.*, $ft)");
            binds.put("ft", ft);
        }
        if (!q.tags.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < q.tags.size(); i++) {
                String t = q.tags.get(i);
                if (i > 0) {
                    sql.append(" OR ");
                }
                if (t.endsWith(":")) {
                    sql.append("a.[jcr:content/metadata/cq:tags] LIKE $tl").append(i);
                    binds.put("tl" + i, t + "%");
                } else {
                    sql.append("a.[jcr:content/metadata/cq:tags] = $t").append(i)
                            .append(" OR a.[jcr:content/metadata/cq:tags] LIKE $tl").append(i);
                    binds.put("t" + i, t);
                    binds.put("tl" + i, t + "/%");
                }
            }
            sql.append(')');
        }
        if (q.minSize >= 0) {
            sql.append(" AND a.[jcr:content/metadata/dam:size] >= $min");
            binds.put("min", q.minSize);
        }
        if (q.maxSize >= 0) {
            sql.append(" AND a.[jcr:content/metadata/dam:size] <= $max");
            binds.put("max", q.maxSize);
        }
        if (q.modifiedDays > 0) {
            Calendar since = Calendar.getInstance();
            since.setTimeInMillis(now - q.modifiedDays * 86_400_000L);
            sql.append(" AND a.[jcr:content/jcr:lastModified] >= $since");
            binds.put("since", since);
        }
        sql.append(ft != null ? " ORDER BY [jcr:score] DESC" : " ORDER BY a.[jcr:content/jcr:lastModified] DESC");
        sql.append(" OPTION(INDEX TAG ").append(DamIndex.TAG).append(')');
        return run(session, sql.toString(), binds);
    }

    /** Folders below the scope whose title matches the text. */
    static List<String> folders(Session session, String scope, String text) throws RepositoryException {
        String ft = fulltext(text);
        if (ft == null) {
            return new ArrayList<>();
        }
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("ft", ft);
        return run(session, "SELECT [jcr:path] FROM [sling:Folder] AS f WHERE ISDESCENDANTNODE(f, " + literal(scope)
                + ") AND CONTAINS(f.*, $ft) ORDER BY [jcr:score] DESC OPTION(INDEX TAG " + DamIndex.TAG + ")", binds);
    }

    /** Assets anywhere in the DAM whose original has this SHA-1. */
    static List<String> bySha1(Session session, String sha1) throws RepositoryException {
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("sha1", sha1);
        return run(session, "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, " + literal(DamItems.ROOT)
                + ") AND a.[jcr:content/metadata/dam:sha1] = $sha1 OPTION(INDEX TAG " + DamIndex.TAG + ")", binds);
    }

    /** The assets studio-dam processed into {@code blobId} (index only). */
    static List<String> byBlob(Session session, String blobId) throws RepositoryException {
        Map<String, Object> binds = new java.util.HashMap<>();
        binds.put("blob", blobId);
        return run(session, "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, " + literal(DamItems.ROOT)
                + ") AND a.[jcr:content/" + AssetProcessor.PN_BLOB_ID + "] = $blob OPTION(INDEX TAG " + DamIndex.TAG + ")", binds);
    }

    /** The image sets that name {@code member} among their pictures (index only). */
    static List<String> setsWith(Session session, String member) throws RepositoryException {
        Map<String, Object> binds = new java.util.HashMap<>();
        binds.put("member", member);
        return run(session, "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, " + literal(DamItems.ROOT)
                + ") AND a.[jcr:content/" + DamSets.PN_MEMBERS + "] = $member OPTION(INDEX TAG " + DamIndex.TAG + ")", binds);
    }

    /** How Oak runs a statement (for tests and diagnostics): the plan names the index. */
    static String explain(Session session, String sql) throws RepositoryException {
        Query q = session.getWorkspace().getQueryManager().createQuery("EXPLAIN " + sql, Query.JCR_SQL2);
        RowIterator it = q.execute().getRows();
        return it.hasNext() ? it.nextRow().getValue("plan").getString() : "";
    }

    private static List<String> run(Session session, String sql, Map<String, Object> binds) throws RepositoryException {
        QueryManager qm = session.getWorkspace().getQueryManager();
        Query query = qm.createQuery(sql, Query.JCR_SQL2);
        ValueFactory vf = session.getValueFactory();
        for (Map.Entry<String, Object> b : binds.entrySet()) {
            Object v = b.getValue();
            query.bindValue(b.getKey(), v instanceof Long ? vf.createValue((Long) v)
                    : v instanceof Calendar ? vf.createValue((Calendar) v) : vf.createValue(String.valueOf(v)));
        }
        query.setLimit(MAX_ROWS);
        List<String> out = new ArrayList<>();
        for (RowIterator it = query.execute().getRows(); it.hasNext();) {
            Row r = it.nextRow();
            out.add(r.getPath());
        }
        return out;
    }

    /** A path as a JCR-SQL2 string literal; anything unusual is refused rather than escaped. */
    static String literal(String path) throws RepositoryException {
        if (path == null || !DamItems.inDam(path) || !path.matches("^[\\p{L}\\p{N} _.:/@()+,=-]+$")) {
            throw new RepositoryException("path not usable in a query: " + path);
        }
        return "'" + path.replace("'", "''") + "'";
    }

    /**
     * The console's search words as an Oak full-text expression: every word must match, words
     * also match as prefixes ({@code surf} finds {@code surfing}), {@code -word} excludes and
     * {@code "two words"} is a phrase. Characters with a meaning to the parser are dropped.
     * Null when there is nothing to look for (only exclusions cannot be searched alone).
     */
    static String fulltext(String text) {
        // Like DamSearch.terms, but keeping accents: Lucene's analyzer indexes "Pică" as "pică".
        List<String[]> terms = new ArrayList<>();
        if (text != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?)(?:\"([^\"]*)\"|(\\S+))").matcher(text);
            while (m.find()) {
                String t = (m.group(2) != null ? m.group(2) : m.group(3)).toLowerCase(java.util.Locale.ROOT).trim();
                terms.add(new String[] {t, m.group(1).isEmpty() ? "+" : "-"});
            }
        }
        StringBuilder b = new StringBuilder();
        boolean positive = false;
        for (String[] t : terms) {
            String words = t[0].replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
            if (words.isEmpty()) {
                continue;
            }
            boolean phrase = words.contains(" ");
            String expr = phrase ? "\"" + words + "\"" : words + "*";
            if (b.length() > 0) {
                b.append(' ');
            }
            if ("-".equals(t[1])) {
                b.append('-').append(phrase ? expr : words);
            } else {
                b.append(expr);
                positive = true;
            }
        }
        return positive ? b.toString() : null;
    }
}
