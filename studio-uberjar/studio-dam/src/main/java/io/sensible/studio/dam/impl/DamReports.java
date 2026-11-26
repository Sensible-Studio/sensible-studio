/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Asset Reports, as in Tools › Assets › Asset Reports: a report is made once, from what the
 * requesting user can read, and kept with its CSV until deleted.
 *
 * <pre>
 * /var/studio/dam/reports/&lt;id&gt;   studio:title, studio:description, studio:type, studio:path, studio:from, studio:to, studio:columns,
 *                             studio:owner, studio:created, studio:status (completed | failed), studio:error,
 *                             studio:rows, studio:truncated, studio:summary
 *   result.csv                the rows (nt:file)
 * </pre>
 *
 * The user's resolver reads the DAM (so a report never shows what its author cannot see); the
 * service resolver ({@link #SUBSERVICE}) keeps the reports, which only their owner (and admin)
 * list, open and delete.
 */
final class DamReports {

    static final String SUBSERVICE = "reports";
    static final String ROOT = "/var/studio/dam/reports";
    static final String CSV = "result.csv";
    /** Assets looked at, at most, per report. */
    static final int MAX_ASSETS = 100_000;
    /** Rows a page of the report view returns at most. */
    static final int MAX_PAGE = 500;

    /** The report types: id → (title, description, kind). */
    enum Type {
        UPLOAD("assetadditionreport", "Upload", "Report of uploaded assets", true),
        MODIFICATION("assetmodificationreport", "Modification", "Report of modified assets", true),
        EXPIRATION("assetexpiryreport", "Expiration", "Report of expired assets", true),
        PUBLISH("assetpublishreport", "Publish", "Report of published assets", true),
        DISK_USAGE("foldersizeandstrengthreport", "Disk Usage", "Report of disk usage", false),
        FILES("foldercontentreport", "Files", "Report of files in folder", true),
        LINK_SHARE("linksharereport", "Link Share", "Report of shared links", false);

        final String id;
        final String title;
        final String description;
        /** One row per asset, with selectable columns. */
        final boolean assets;

        Type(String id, String title, String description, boolean assets) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.assets = assets;
        }

        static Type of(String id) {
            for (Type t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** Columns of the asset reports, in their default order: key → header. */
    static final Map<String, String> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("title", "Title");
        COLUMNS.put("path", "Path");
        COLUMNS.put("type", "Type");
        COLUMNS.put("size", "Size");
        COLUMNS.put("added", "Added");
        COLUMNS.put("addedBy", "Added By");
        COLUMNS.put("modified", "Modified");
        COLUMNS.put("modifiedBy", "Modified By");
        COLUMNS.put("expires", "Expires");
        COLUMNS.put("published", "Published");
        COLUMNS.put("publishedBy", "Published By");
        COLUMNS.put("width", "Width");
        COLUMNS.put("height", "Height");
    }

    static final List<String> DEFAULT_COLUMNS = Arrays.asList("title", "path", "type", "size", "added", "modified");

    private DamReports() {
    }

    /** What the wizard needs: types and columns. */
    static Map<String, Object> catalog() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (Type t : Type.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("title", t.title);
            m.put("description", t.description);
            m.put("columns", t.assets);
            m.put("dates", t != Type.DISK_USAGE && t != Type.FILES);
            m.put("folder", t != Type.LINK_SHARE);
            types.add(m);
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map.Entry<String, String> c : COLUMNS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getKey());
            m.put("title", c.getValue());
            m.put("default", DEFAULT_COLUMNS.contains(c.getKey()));
            columns.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", types);
        out.put("columns", columns);
        return out;
    }

    /** What a report is asked to cover. */
    static final class Request {
        Type type;
        String title;
        String description;
        String path = DamItems.ROOT;
        Calendar from;
        Calendar to;
        List<String> columns = new ArrayList<>(DEFAULT_COLUMNS);
        /** metadata properties added as columns (e.g. {@code dc:creator}) */
        List<String> properties = new ArrayList<>();
        boolean excludeFragments;
        boolean includeRenditions;
        boolean excludeSubfolders;
    }

    /** {@code 2026-10-17} (start of day, or end of day with {@code endOfDay}) or an ISO date-time. */
    static Calendar date(String s, boolean endOfDay) throws DamApiServlet.ApiException {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            long ms;
            if (s.trim().length() == 10) {
                LocalDate d = LocalDate.parse(s.trim());
                ms = (endOfDay ? d.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
                        : d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
            } else {
                ms = OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli();
            }
            Calendar c = new GregorianCalendar(TimeZone.getDefault());
            c.setTimeInMillis(ms);
            return c;
        } catch (DateTimeParseException e) {
            throw new DamApiServlet.ApiException(400, "not a date: " + s);
        }
    }

    // ------------------------------------------------------------------------------------- running

    /** The rows of a report (first row the header), computed with the user's resolver. */
    static final class Result {
        final List<List<String>> rows = new ArrayList<>();
        boolean truncated;
        final Map<String, Object> summary = new LinkedHashMap<>();
    }

    static Result run(ResourceResolver user, ResourceResolver service, Request q, long now) throws DamApiServlet.ApiException {
        Result out = new Result();
        if (q.type == Type.LINK_SHARE) {
            linkShares(service, user.getUserID(), DamAccess.isAdmin(user), q, out);
            return out;
        }
        Resource root = DamItems.inDam(q.path) ? user.getResource(q.path) : null;
        if (!DamItems.isFolder(root)) {
            throw new DamApiServlet.ApiException(404, "no folder " + q.path);
        }
        if (q.type == Type.DISK_USAGE) {
            diskUsage(root, q, out);
            return out;
        }
        List<String> header = new ArrayList<>();
        for (String c : q.columns) {
            header.add(COLUMNS.get(c));
        }
        header.addAll(q.properties);
        out.rows.add(header);
        long total = 0;
        int seen = 0;
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            for (Resource c : queue.poll().getChildren()) {
                if (DamItems.isFolder(c)) {
                    if (!c.getPath().startsWith(DamCollections.ROOT + "/") && !c.getPath().equals(DamCollections.ROOT)) {
                        queue.add(c);
                    }
                    continue;
                }
                if (!DamItems.isAsset(c)) {
                    continue;
                }
                if (++seen > MAX_ASSETS) {
                    out.truncated = true;
                    queue.clear();
                    break;
                }
                if (q.excludeFragments && DamItems.isContentFragment(c) || !matches(c, q, now)) {
                    continue;
                }
                Map<String, Object> a = DamItems.asset(c);
                Object size = a.get("size");
                total += size instanceof Number ? ((Number) size).longValue() : 0;
                out.rows.add(row(c, a, q));
            }
        }
        out.summary.put("assets", out.rows.size() - 1);
        out.summary.put("size", total);
        return out;
    }

    private static boolean matches(Resource asset, Request q, long now) {
        ValueMap c = valueMap(asset.getChild("jcr:content"));
        ValueMap md = valueMap(asset.getChild("jcr:content/metadata"));
        Calendar when;
        switch (q.type) {
            case UPLOAD:
                when = asset.getValueMap().get("jcr:created", Calendar.class);
                break;
            case MODIFICATION:
                when = c.get("jcr:lastModified", Calendar.class);
                break;
            case EXPIRATION: {
                when = md.get("prism:expirationDate", Calendar.class);
                if (when == null) {
                    return false;
                }
                // without an end date: what has expired by now
                Calendar to = q.to;
                if (to == null) {
                    to = Calendar.getInstance();
                    to.setTimeInMillis(now);
                }
                return (q.from == null || !when.before(q.from)) && !when.after(to);
            }
            case PUBLISH:
                if (!"Activate".equals(c.get("cq:lastReplicationAction", String.class))) {
                    return false;
                }
                when = c.get("cq:lastReplicated", Calendar.class);
                break;
            default:
                return true; // FILES: everything in the folder
        }
        if (when == null) {
            return q.from == null && q.to == null && q.type == Type.UPLOAD;
        }
        return (q.from == null || !when.before(q.from)) && (q.to == null || !when.after(q.to));
    }

    private static List<String> row(Resource asset, Map<String, Object> a, Request q) {
        ValueMap c = valueMap(asset.getChild("jcr:content"));
        ValueMap md = valueMap(asset.getChild("jcr:content/metadata"));
        List<String> r = new ArrayList<>();
        for (String col : q.columns) {
            switch (col) {
                case "title":
                    r.add(String.valueOf(a.get("title")));
                    break;
                case "path":
                    r.add(asset.getPath());
                    break;
                case "type":
                    r.add(text(a.get("mimeType")));
                    break;
                case "size":
                    r.add(text(a.get("size")));
                    break;
                case "added":
                    r.add(text(asset.getValueMap().get("jcr:created", Calendar.class)));
                    break;
                case "addedBy":
                    r.add(text(asset.getValueMap().get("jcr:createdBy", String.class)));
                    break;
                case "modified":
                    r.add(text(c.get("jcr:lastModified", Calendar.class)));
                    break;
                case "modifiedBy":
                    r.add(text(c.get("jcr:lastModifiedBy", String.class)));
                    break;
                case "expires":
                    r.add(text(md.get("prism:expirationDate", Calendar.class)));
                    break;
                case "published":
                    r.add("Activate".equals(c.get("cq:lastReplicationAction", String.class))
                            ? text(c.get("cq:lastReplicated", Calendar.class)) : "");
                    break;
                case "publishedBy":
                    r.add("Activate".equals(c.get("cq:lastReplicationAction", String.class))
                            ? text(c.get("cq:lastReplicatedBy", String.class)) : "");
                    break;
                case "width":
                    r.add(text(a.get("width")));
                    break;
                case "height":
                    r.add(text(a.get("height")));
                    break;
                default:
                    r.add("");
            }
        }
        for (String p : q.properties) {
            r.add(text(md.get(p)));
        }
        return r;
    }

    /** Folders with the number and size of their assets (sub-folders included), largest first. */
    private static void diskUsage(Resource root, Request q, Result out) {
        out.rows.add(new ArrayList<>(Arrays.asList("Folder", "Title", "Assets", "Size", q.includeRenditions ? "Renditions Size" : null)));
        out.rows.get(0).removeIf(java.util.Objects::isNull);
        Map<String, long[]> totals = new LinkedHashMap<>(); // path → {assets, original bytes, rendition bytes}
        Map<String, String> titles = new LinkedHashMap<>();
        int[] seen = {0};
        walkFolders(root, totals, titles, q.includeRenditions, seen, out);
        List<Map.Entry<String, long[]>> list = new ArrayList<>(totals.entrySet());
        if (q.excludeSubfolders) {
            list.removeIf(e -> !e.getKey().equals(root.getPath()));
        }
        list.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -(e.getValue()[1] + e.getValue()[2]))
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, long[]> e : list) {
            List<String> r = new ArrayList<>(Arrays.asList(e.getKey(), titles.get(e.getKey()), String.valueOf(e.getValue()[0]),
                    String.valueOf(e.getValue()[1])));
            if (q.includeRenditions) {
                r.add(String.valueOf(e.getValue()[2]));
            }
            out.rows.add(r);
        }
        long[] all = totals.get(root.getPath());
        out.summary.put("folders", totals.size());
        out.summary.put("assets", all == null ? 0 : all[0]);
        out.summary.put("size", all == null ? 0 : all[1]);
        if (q.includeRenditions) {
            out.summary.put("renditionsSize", all == null ? 0 : all[2]);
        }
    }

    /** Post-order: a folder's totals include its sub-folders'. */
    private static long[] walkFolders(Resource folder, Map<String, long[]> totals, Map<String, String> titles, boolean renditions,
            int[] seen, Result out) {
        long[] t = new long[3];
        totals.put(folder.getPath(), t);
        titles.put(folder.getPath(), DamItems.ROOT.equals(folder.getPath()) ? "Assets" : DamItems.title(folder));
        for (Resource c : folder.getChildren()) {
            if (DamItems.isFolder(c) && !c.getPath().equals(DamCollections.ROOT)) {
                long[] sub = walkFolders(c, totals, titles, renditions, seen, out);
                for (int i = 0; i < 3; i++) {
                    t[i] += sub[i];
                }
            } else if (DamItems.isAsset(c)) {
                if (++seen[0] > MAX_ASSETS) {
                    out.truncated = true;
                    return t;
                }
                t[0]++;
                Object size = DamItems.asset(c).get("size");
                t[1] += size instanceof Number ? ((Number) size).longValue() : 0;
                if (renditions) {
                    Resource rs = c.getChild("jcr:content/renditions");
                    if (rs != null) {
                        for (Resource r : rs.getChildren()) {
                            if (!"original".equals(r.getName())) {
                                t[2] += renditionSize(r);
                            }
                        }
                    }
                }
            }
        }
        return t;
    }

    /** {@code dam:size} written with the rendition, else the binary's length as the repository reports it. */
    static long renditionSize(Resource rendition) {
        Resource md = rendition.getChild("jcr:content/metadata");
        Long size = md == null ? null : md.getValueMap().get("dam:size", Long.class);
        if (size != null) {
            return Math.max(0, size);
        }
        long len = rendition.getResourceMetadata().getContentLength();
        Resource c = rendition.getChild("jcr:content");
        if (len <= 0 && c != null) {
            len = c.getResourceMetadata().getContentLength();
            javax.jcr.Node n = c.adaptTo(javax.jcr.Node.class);
            try {
                if (len <= 0 && n != null && n.hasProperty("jcr:data")) {
                    len = n.getProperty("jcr:data").getLength();
                }
            } catch (javax.jcr.RepositoryException e) {
                len = 0;
            }
        }
        return Math.max(0, len);
    }

    /** Shared links: the user's own, all of them for admin; created within the dates. */
    private static void linkShares(ResourceResolver service, String user, boolean admin, Request q, Result out) {
        out.rows.add(Arrays.asList("Shared By", "Created", "Expires", "Status", "Items", "Renditions"));
        Resource root = service.getResource(DamLinkShare.ROOT);
        long now = System.currentTimeMillis();
        int active = 0;
        if (root != null) {
            for (Resource l : root.getChildren()) {
                ValueMap v = l.getValueMap();
                String owner = v.get("studio:owner", String.class);
                Calendar created = v.get("studio:created", Calendar.class);
                if (owner == null || !(owner.equals(user) || admin)) {
                    continue;
                }
                if (created != null && (q.from != null && created.before(q.from) || q.to != null && created.after(q.to))) {
                    continue;
                }
                Calendar expires = v.get("studio:expires", Calendar.class);
                boolean live = expires != null && expires.getTimeInMillis() > now;
                active += live ? 1 : 0;
                out.rows.add(Arrays.asList(owner, text(created), text(expires), live ? "Active" : "Expired",
                        String.join(" | ", v.get("studio:paths", new String[0])), v.get("studio:renditions", false) ? "yes" : "no"));
            }
        }
        out.summary.put("links", out.rows.size() - 1);
        out.summary.put("active", active);
    }

    // ------------------------------------------------------------------------------------- storage

    /** Runs the report and keeps it; a failure is kept too, with its message. */
    static Map<String, Object> create(ResourceResolver user, ResourceResolver service, Request q)
            throws DamApiServlet.ApiException, PersistenceException {
        String owner = user.getUserID();
        if (owner == null || "anonymous".equals(owner)) {
            throw new DamApiServlet.ApiException(401, "login required");
        }
        if (q.type == null) {
            throw new DamApiServlet.ApiException(400, "unknown report type");
        }
        if (q.title == null || q.title.trim().isEmpty()) {
            throw new DamApiServlet.ApiException(400, "a report needs a title");
        }
        if (q.from != null && q.to != null && q.from.after(q.to)) {
            throw new DamApiServlet.ApiException(400, "the start date is after the end date");
        }
        for (String c : q.columns) {
            if (!COLUMNS.containsKey(c)) {
                throw new DamApiServlet.ApiException(400, "unknown column " + c);
            }
        }
        for (String p : q.properties) {
            if (!DamCsv.PROPERTY.matcher(p).matches()) {
                throw new DamApiServlet.ApiException(400, "not a property name: " + p);
            }
        }
        if (q.type.assets && q.columns.isEmpty() && q.properties.isEmpty()) {
            throw new DamApiServlet.ApiException(400, "choose at least one column");
        }
        Result result = run(user, service, q, System.currentTimeMillis());

        Resource root = ensureRoot(service);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("studio:title", q.title.trim());
        if (q.description != null && !q.description.trim().isEmpty()) {
            props.put("studio:description", q.description.trim());
        }
        props.put("studio:type", q.type.id);
        if (q.type != Type.LINK_SHARE) {
            props.put("studio:path", q.path);
        }
        if (q.from != null) {
            props.put("studio:from", q.from);
        }
        if (q.to != null) {
            props.put("studio:to", q.to);
        }
        if (q.type.assets) {
            List<String> cols = new ArrayList<>(q.columns);
            cols.addAll(q.properties);
            props.put("studio:columns", cols.toArray(new String[0]));
        }
        props.put("studio:owner", owner);
        props.put("studio:created", Calendar.getInstance());
        props.put("studio:status", "completed");
        props.put("studio:rows", (long) (result.rows.size() - 1));
        props.put("studio:truncated", result.truncated);
        props.put("studio:summary", JsonOut.write(result.summary));
        Resource report = service.create(root, UUID.randomUUID().toString(), props);
        Resource file = service.create(report, CSV, Collections.singletonMap("jcr:primaryType", "nt:file"));
        Map<String, Object> binary = new LinkedHashMap<>();
        binary.put("jcr:primaryType", "nt:resource");
        binary.put("jcr:data", new ByteArrayInputStream(csv(result.rows).getBytes(StandardCharsets.UTF_8)));
        binary.put("jcr:mimeType", "text/csv");
        binary.put("jcr:lastModified", Calendar.getInstance());
        service.create(file, "jcr:content", binary);
        service.commit();
        return describe(report);
    }

    static String csv(List<List<String>> rows) {
        StringBuilder b = new StringBuilder();
        for (List<String> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(DamCsv.cell(r.get(i) == null ? "" : r.get(i)));
            }
            b.append("\r\n");
        }
        return b.toString();
    }

    /** The report node of {@code id} if {@code user} may see it, else null. */
    static Resource get(ResourceResolver service, String user, boolean admin, String id) {
        if (id == null || !id.matches("^[0-9a-f-]{36}$")) {
            return null;
        }
        Resource r = service.getResource(ROOT + "/" + id);
        return r != null && mayAccess(r, user, admin) ? r : null;
    }

    static boolean mayAccess(Resource report, String user, boolean admin) {
        return user != null && (user.equals(report.getValueMap().get("studio:owner", String.class)) || admin);
    }

    static List<Map<String, Object>> list(ResourceResolver service, String user, boolean admin) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = service.getResource(ROOT);
        if (root == null) {
            return out;
        }
        for (Resource r : root.getChildren()) {
            if (mayAccess(r, user, admin)) {
                out.add(describe(r));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> ((Calendar) m.get("created")).getTimeInMillis()).reversed());
        return out;
    }

    static Map<String, Object> describe(Resource r) {
        ValueMap v = r.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getName());
        m.put("title", v.get("studio:title", String.class));
        m.put("description", v.get("studio:description", String.class));
        Type t = Type.of(v.get("studio:type", String.class));
        m.put("type", v.get("studio:type", String.class));
        m.put("typeTitle", t == null ? v.get("studio:type", String.class) : t.title);
        m.put("path", v.get("studio:path", String.class));
        m.put("from", v.get("studio:from", Calendar.class));
        m.put("to", v.get("studio:to", Calendar.class));
        m.put("owner", v.get("studio:owner", String.class));
        Calendar created = v.get("studio:created", Calendar.class);
        m.put("created", created != null ? created : Calendar.getInstance());
        m.put("status", v.get("studio:status", "completed"));
        m.put("rows", v.get("studio:rows", 0L));
        m.put("truncated", v.get("studio:truncated", false));
        try {
            m.put("summary", Json.parse(v.get("studio:summary", "{}")));
        } catch (IllegalArgumentException e) {
            m.put("summary", Collections.emptyMap());
        }
        return m;
    }

    /** The stored CSV text of a report. */
    static String csvOf(Resource report) throws IOException {
        Resource file = report.getChild(CSV + "/jcr:content");
        try (InputStream in = file == null ? null : file.adaptTo(InputStream.class)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** One page of a report: its description, the header and {@code limit} rows from {@code offset}. */
    static Map<String, Object> page(Resource report, int offset, int limit) throws IOException {
        List<List<String>> rows = DamCsv.parse(csvOf(report));
        Map<String, Object> m = describe(report);
        m.put("header", rows.isEmpty() ? Collections.emptyList() : rows.get(0));
        int from = Math.max(0, offset) + 1;
        int to = Math.min(rows.size(), from + Math.max(1, Math.min(limit, MAX_PAGE)));
        m.put("offset", Math.max(0, offset));
        m.put("items", from < to ? rows.subList(from, to) : Collections.emptyList());
        return m;
    }

    static int delete(ResourceResolver service, String user, boolean admin, List<String> ids) throws PersistenceException {
        int n = 0;
        for (String id : ids) {
            Resource r = get(service, user, admin, id);
            if (r != null) {
                service.delete(r);
                n++;
            }
        }
        service.commit();
        return n;
    }

    /** File name for the download: the title, made safe. */
    static String fileName(Resource report) {
        String t = report.getValueMap().get("studio:title", "report").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return (t.isEmpty() ? "report" : t) + ".csv";
    }

    private static String text(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Calendar) {
            Calendar c = (Calendar) o;
            return c.toInstant().atZone(c.getTimeZone().toZoneId()).toOffsetDateTime().toString();
        }
        return String.valueOf(o);
    }

    private static ValueMap valueMap(Resource r) {
        return r == null ? ValueMap.EMPTY : r.getValueMap();
    }

    private static Resource ensureRoot(ResourceResolver service) throws PersistenceException {
        Resource r = service.getResource(ROOT);
        if (r != null) {
            return r;
        }
        r = service.getResource("/");
        for (String seg : ROOT.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : service.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }
}
