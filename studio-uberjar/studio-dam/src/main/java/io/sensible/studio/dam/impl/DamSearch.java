/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * Search of the Assets console: full text over names and the descriptive metadata, plus the
 * filters of AEM's search rail (file type, tags, last modified, file size, orientation).
 *
 * <p>It walks the folder tree below the scope instead of querying an index: the Sling Starter
 * has no full-text index for DAM metadata, and a walk that reads only {@code jcr:content} and
 * {@code jcr:content/metadata} of each asset is fast enough for DAMs of tens of thousands of
 * assets. {@link #MAX_VISITED} bounds the work; the response says when it was reached.</p>
 *
 * <p>Full text: every term must occur (case and accents ignored); {@code -term} excludes;
 * {@code "two words"} is a phrase. Matches in the title rank first, then the name.</p>
 */
final class DamSearch {

    static final int MAX_VISITED = 200_000;
    static final Set<String> KINDS = new TreeSet<>(Arrays.asList("image", "video", "audio", "pdf", "document", "archive", "fragment", "file"));
    /** Metadata searched by the full text besides title and name. */
    static final String[] TEXT_PROPERTIES = {"dc:description", "dc:subject", "dc:creator", "dc:contributor", "dc:rights", "cq:tags",
            DamSmartTags.PN_TAGS, DamSmartTags.PN_KEYWORDS};

    /** What to look for; empty fields do not filter. */
    static final class Query {
        String fulltext = "";
        Set<String> kinds = new TreeSet<>();
        List<String> tags = new ArrayList<>();
        /** only assets modified in the last {@code modifiedDays} days, 0 = any */
        int modifiedDays;
        long minSize = -1;
        long maxSize = -1;
        /** landscape, portrait, square or null */
        String orientation;
        String sort; // relevance (null), name, title, modified, size
        boolean descending;
        /** a metadata property has one of these values (Search Forms' options filters); properties are ANDed */
        Map<String, Set<String>> meta = new java.util.TreeMap<>();
        /** a metadata property contains this text, any case (Search Forms' text filters) */
        Map<String, String> metaText = new java.util.TreeMap<>();

        boolean assetFiltersOnly() {
            return !kinds.isEmpty() || !tags.isEmpty() || modifiedDays > 0 || minSize >= 0 || maxSize >= 0 || orientation != null
                    || !meta.isEmpty() || !metaText.isEmpty();
        }

        /** {@code prop=value}, as the console sends it ({@code meta} parameters); a bad property is left out. */
        void addMeta(String pair) {
            int i = pair == null ? -1 : pair.indexOf('=');
            if (i > 0 && i < pair.length() - 1 && DamSearchForms.PROPERTY.matcher(pair.substring(0, i)).matches()) {
                meta.computeIfAbsent(pair.substring(0, i), k -> new TreeSet<>()).add(pair.substring(i + 1));
            }
        }

        void addMetaText(String pair) {
            int i = pair == null ? -1 : pair.indexOf('=');
            if (i > 0 && i < pair.length() - 1 && DamSearchForms.PROPERTY.matcher(pair.substring(0, i)).matches()) {
                metaText.put(pair.substring(0, i), pair.substring(i + 1).trim());
            }
        }
    }

    /**
     * The filters of a smart collection, as the console sends them: {@code fulltext}, {@code types},
     * {@code tags}, {@code modified} ("7d"), {@code size} (small, medium, large), {@code orientation}.
     */
    @SuppressWarnings("unchecked")
    static Query fromJson(Object json) {
        if (!(json instanceof Map)) {
            throw new IllegalArgumentException("a search is a JSON object");
        }
        Map<String, Object> m = (Map<String, Object>) json;
        Query q = new Query();
        q.fulltext = m.get("fulltext") == null ? "" : String.valueOf(m.get("fulltext"));
        if (m.get("types") instanceof List) {
            for (Object t : (List<Object>) m.get("types")) {
                if (KINDS.contains(String.valueOf(t))) {
                    q.kinds.add(String.valueOf(t));
                }
            }
        }
        if (m.get("tags") instanceof List) {
            for (Object t : (List<Object>) m.get("tags")) {
                q.tags.add(String.valueOf(t));
            }
        }
        Object mod = m.get("modified");
        if (mod != null && String.valueOf(mod).matches("\\d{1,4}d")) {
            String s = String.valueOf(mod);
            q.modifiedDays = Integer.parseInt(s.substring(0, s.length() - 1));
        }
        long mb = 1024L * 1024;
        Object size = m.get("size");
        if ("small".equals(size)) {
            q.maxSize = mb - 1;
        } else if ("medium".equals(size)) {
            q.minSize = mb;
            q.maxSize = 10 * mb;
        } else if ("large".equals(size)) {
            q.minSize = 10 * mb + 1;
        }
        Object o = m.get("orientation");
        q.orientation = "landscape".equals(o) || "portrait".equals(o) || "square".equals(o) ? (String) o : null;
        if (m.get("meta") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("meta")).entrySet()) {
                if (e.getValue() instanceof List) {
                    for (Object v : (List<?>) e.getValue()) {
                        q.addMeta(e.getKey() + "=" + v);
                    }
                }
            }
        }
        if (m.get("metaText") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("metaText")).entrySet()) {
                q.addMetaText(e.getKey() + "=" + e.getValue());
            }
        }
        return q;
    }

    static final class Result {
        final List<Map<String, Object>> hits = new ArrayList<>();
        boolean truncated;
        /** "index" (the Oak Lucene index, {@link DamIndex}) or "walk" (folder tree). */
        String engine = "walk";
    }

    /**
     * The search with the Lucene index when the session can query it, else by walking the tree.
     * Both give the same matches; the index is fast at any size and also finds text inside PDFs.
     */
    static Result find(javax.jcr.Session session, Resource scope, Query q, long now) {
        Result r = session == null || !DamIndex.ready(session) ? null : searchIndexed(session, scope, q, now);
        return r != null ? r : search(scope, q, now);
    }

    /** Null when the query cannot run (no index, a repository error): the caller walks instead. */
    static Result searchIndexed(javax.jcr.Session session, Resource scope, Query q, long now) {
        try {
            List<String> paths = DamIndexQueries.assets(session, scope.getPath(), q, now);
            org.apache.sling.api.resource.ResourceResolver resolver = scope.getResourceResolver();
            Result result = new Result();
            result.engine = "index";
            result.truncated = paths.size() >= DamIndexQueries.MAX_ROWS;
            List<Object[]> scored = new ArrayList<>();
            int rank = paths.size();
            boolean text = !terms(q.fulltext).isEmpty();
            if (text && !q.assetFiltersOnly()) {
                List<String> folders = DamIndexQueries.folders(session, scope.getPath(), q.fulltext);
                int frank = rank + folders.size();
                for (String p : folders) {
                    Resource f = resolver.getResource(p);
                    if (DamItems.isFolder(f)) {
                        scored.add(new Object[] {frank--, DamItems.folder(f, false)});
                    }
                }
            }
            for (String p : paths) {
                Resource a = resolver.getResource(p);
                if (!DamItems.isAsset(a)) {
                    continue;
                }
                Map<String, Object> item = DamItems.asset(a);
                if (matchesFilters(item, metadata(a), q, now)) {
                    scored.add(new Object[] {rank, item});
                }
                rank--;
            }
            scored.sort(comparator(q));
            for (Object[] s : scored) {
                result.hits.add(castItem(s[1]));
            }
            return result;
        } catch (javax.jcr.RepositoryException | RuntimeException e) {
            // e.g. no index here, or Oak refusing to traverse a large tree: walk instead
            return null;
        }
    }

    private DamSearch() {
    }

    static Result search(Resource scope, Query q, long now) {
        List<String[]> terms = terms(q.fulltext);
        Result result = new Result();
        List<Object[]> scored = new ArrayList<>(); // {score, item}
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(scope);
        int visited = 0;
        while (!queue.isEmpty()) {
            Resource f = queue.poll();
            for (Resource c : f.getChildren()) {
                if (++visited > MAX_VISITED) {
                    result.truncated = true;
                    queue.clear();
                    break;
                }
                if (DamItems.isAsset(c)) {
                    Map<String, Object> item = DamItems.asset(c);
                    ValueMap md = metadata(c);
                    if (matchesFilters(item, md, q, now)) {
                        int score = score(item, md, terms);
                        if (score > 0) {
                            scored.add(new Object[] {score, item});
                        }
                    }
                } else if (DamItems.isFolder(c)) {
                    queue.add(c);
                    if (!terms.isEmpty() && !q.assetFiltersOnly()) {
                        Map<String, Object> folder = DamItems.folder(c, false);
                        int score = score(folder, ValueMap.EMPTY, terms);
                        if (score > 0) {
                            scored.add(new Object[] {score, folder});
                        }
                    }
                }
            }
        }
        Comparator<Object[]> order = comparator(q);
        scored.sort(order);
        for (Object[] s : scored) {
            result.hits.add(castItem(s[1]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castItem(Object o) {
        return (Map<String, Object>) o;
    }

    private static Comparator<Object[]> comparator(Query q) {
        Comparator<Object[]> byModified = Comparator.comparingLong(s -> -millis(castItem(s[1]).get("modified")));
        if (q.sort == null || "relevance".equals(q.sort)) {
            return Comparator.<Object[]>comparingInt(s -> -(Integer) s[0]).thenComparing(byModified);
        }
        Comparator<Object[]> c;
        switch (q.sort) {
            case "name":
                c = Comparator.comparing(s -> String.valueOf(castItem(s[1]).get("name")), String.CASE_INSENSITIVE_ORDER);
                break;
            case "title":
                c = Comparator.comparing(s -> String.valueOf(castItem(s[1]).get("title")), String.CASE_INSENSITIVE_ORDER);
                break;
            case "size":
                c = Comparator.comparingLong(s -> {
                    Object v = castItem(s[1]).get("size");
                    return v instanceof Number ? ((Number) v).longValue() : -1L;
                });
                break;
            default: // modified
                c = Comparator.comparingLong(s -> millis(castItem(s[1]).get("modified")));
        }
        return q.descending ? c.reversed() : c;
    }

    private static long millis(Object v) {
        return v instanceof Calendar ? ((Calendar) v).getTimeInMillis() : 0L;
    }

    private static ValueMap metadata(Resource asset) {
        Resource md = asset.getChild("jcr:content/metadata");
        return md == null ? ValueMap.EMPTY : md.getValueMap();
    }

    static boolean matchesFilters(Map<String, Object> item, ValueMap md, Query q, long now) {
        if (!q.kinds.isEmpty() && !q.kinds.contains(kind(item))) {
            return false;
        }
        if (!q.tags.isEmpty()) {
            String[] has = md.get("cq:tags", new String[0]);
            boolean any = false;
            for (String want : q.tags) {
                for (String t : has) {
                    if (t.equals(want) || (want.endsWith(":") ? t.startsWith(want) : t.startsWith(want + "/"))) {
                        any = true;
                    }
                }
            }
            if (!any) {
                return false;
            }
        }
        if (q.modifiedDays > 0 && millis(item.get("modified")) < now - q.modifiedDays * 86_400_000L) {
            return false;
        }
        Object size = item.get("size");
        long s = size instanceof Number ? ((Number) size).longValue() : -1;
        if ((q.minSize >= 0 && s < q.minSize) || (q.maxSize >= 0 && (s < 0 || s > q.maxSize))) {
            return false;
        }
        if (q.orientation != null) {
            Object w = item.get("width");
            Object h = item.get("height");
            if (!(w instanceof Number) || !(h instanceof Number)) {
                return false;
            }
            long dw = ((Number) w).longValue();
            long dh = ((Number) h).longValue();
            String o = dw > dh ? "landscape" : dh > dw ? "portrait" : "square";
            if (!o.equals(q.orientation)) {
                return false;
            }
        }
        for (Map.Entry<String, Set<String>> e : q.meta.entrySet()) {
            boolean any = false;
            for (String v : DamSearchForms.strings(md.get(e.getKey()))) {
                for (String want : e.getValue()) {
                    any |= v.equalsIgnoreCase(want);
                }
            }
            if (!any) {
                return false;
            }
        }
        for (Map.Entry<String, String> e : q.metaText.entrySet()) {
            String want = e.getValue().toLowerCase(java.util.Locale.ROOT);
            boolean any = false;
            for (String v : DamSearchForms.strings(md.get(e.getKey()))) {
                any |= v.toLowerCase(java.util.Locale.ROOT).contains(want);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** Same groups as the console's type icons (author-ui assets/format.ts kindOf). */
    static String kind(Map<String, Object> item) {
        if (Boolean.TRUE.equals(item.get("contentFragment"))) {
            return "fragment";
        }
        Object mt = item.get("mimeType");
        String m = mt == null ? "" : mt.toString();
        if (m.startsWith("image/")) {
            return "image";
        }
        if (m.startsWith("video/")) {
            return "video";
        }
        if (m.startsWith("audio/")) {
            return "audio";
        }
        if ("application/pdf".equals(m)) {
            return "pdf";
        }
        if (m.contains("zip") || m.contains("compressed")) {
            return "archive";
        }
        if (m.startsWith("text/") || m.contains("officedocument") || m.contains("msword") || m.contains("opendocument")) {
            return "document";
        }
        return "file";
    }

    // ---------------------------------------------------------------------------------- full text

    /** Terms as {text, "+"|"-"}; phrases in double quotes stay one term. */
    static List<String[]> terms(String fulltext) {
        List<String[]> out = new ArrayList<>();
        if (fulltext == null) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?)(?:\"([^\"]*)\"|(\\S+))").matcher(fulltext);
        while (m.find()) {
            String t = normalize(m.group(2) != null ? m.group(2) : m.group(3)).trim();
            if (!t.isEmpty() && !"-".equals(t)) {
                out.add(new String[] {t, m.group(1).isEmpty() ? "+" : "-"});
            }
        }
        return out;
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** 0 = no match; without terms everything matches with 1. */
    static int score(Map<String, Object> item, ValueMap md, List<String[]> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String title = normalize(String.valueOf(item.get("title")));
        String name = normalize(String.valueOf(item.get("name")));
        StringBuilder rest = new StringBuilder();
        for (String p : TEXT_PROPERTIES) {
            Object v = md.get(p);
            if (v instanceof Object[]) {
                for (Object o : (Object[]) v) {
                    rest.append(' ').append(o);
                    if ("cq:tags".equals(p)) {
                        // "wknd-shared:activity/surfing" is found by "surfing" too
                        rest.append(' ').append(String.valueOf(o).replaceAll("[:/]", " "));
                    }
                }
            } else if (v != null) {
                rest.append(' ').append(v);
            }
        }
        Object desc = item.get("description");
        if (desc != null) {
            rest.append(' ').append(desc);
        }
        String other = normalize(rest.toString());
        int score = 0;
        for (String[] t : terms) {
            boolean inTitle = title.contains(t[0]);
            boolean inName = name.contains(t[0]);
            boolean found = inTitle || inName || other.contains(t[0]);
            if ("-".equals(t[1])) {
                if (found) {
                    return 0;
                }
                continue;
            }
            if (!found) {
                return 0;
            }
            score += inTitle ? (title.equals(t[0]) ? 20 : 10) : inName ? 5 : 1;
        }
        return Math.max(score, 1);
    }

    /**
     * Assets whose original has this SHA-1 ({@code dam:sha1}, written by processing): the
     * duplicate check of the upload. At most {@code max}, anywhere below {@code root}.
     */
    static List<Map<String, Object>> bySha1(Resource root, String sha1, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && out.size() < max) {
            for (Resource c : queue.poll().getChildren()) {
                if (++visited > MAX_VISITED || out.size() >= max) {
                    queue.clear();
                    break;
                }
                if (DamItems.isAsset(c)) {
                    if (sha1.equalsIgnoreCase(metadata(c).get("dam:sha1", ""))) {
                        out.add(DamItems.asset(c));
                    }
                } else if (DamItems.isFolder(c)) {
                    queue.add(c);
                }
            }
        }
        return out;
    }

    /** The JSON of a search: like a listing, with the hits as items. */
    static Map<String, Object> response(Resource scope, Result r, int offset, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", scope.getPath());
        out.put("total", r.hits.size());
        out.put("offset", offset);
        out.put("truncated", r.truncated);
        out.put("engine", r.engine);
        int from = Math.min(offset, r.hits.size());
        int to = Math.min(r.hits.size(), from + limit);
        out.put("items", from >= to ? Collections.emptyList() : r.hits.subList(from, to));
        return out;
    }
}
