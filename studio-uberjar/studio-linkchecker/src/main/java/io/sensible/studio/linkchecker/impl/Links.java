/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.linkchecker.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

/**
 * The links in content and what is known about them, over a JCR session.
 *
 * <p>Collecting: every string property under the scanned roots (default {@code /content}) is read for
 * <em>external</em> links ({@code http://}, {@code https://} anywhere in the text: rich text, link fields,
 * JSON) and <em>internal</em> ones (an {@code href}/{@code src} to {@code /content/...} in rich text, or a
 * property whose whole value is a {@code /content/...} path: link fields, image references). System
 * properties (a name with a namespace, {@code jcr:}, {@code cq:}, {@code sling:} …), renditions and binaries
 * are skipped.</p>
 *
 * <p>Storage: one node per link under {@code /var/studio/linkchecker/links/<2 hex>/<sha1 of the URL>} with
 * {@code url}, {@code kind} (external | internal), {@code status} (pending | valid | invalid), {@code code}
 * (the HTTP status), {@code error}, {@code lastChecked}, {@code firstSeen}, {@code lastSeen} and
 * {@code referrers} (where it is written: {@code <node path>@<property>}, at most {@link #MAX_REFERRERS}).
 * A scan keeps what was known about a link still in use and removes the links nobody uses any more.</p>
 */
public final class Links {

    public static final String ROOT = "/var/studio/linkchecker";
    public static final String LINKS = ROOT + "/links";
    public static final String DEFAULT_SCAN_ROOT = "/content";

    public static final String PENDING = "pending";
    public static final String VALID = "valid";
    public static final String INVALID = "invalid";

    public static final String EXTERNAL = "external";
    public static final String INTERNAL = "internal";

    static final int MAX_REFERRERS = 100;

    private static final Pattern EXTERNAL_URL = Pattern.compile("https?://[^\\s\"'<>\\\\`{}|^\\]\\[]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_ATTR = Pattern.compile("(?:href|src)\\s*=\\s*[\"'](/content/[^\"'#?\\s]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_VALUE = Pattern.compile("/content/[^\\s\"'<>]+");

    /** Subtrees that hold no authored links. */
    private static final Set<String> SKIP_NODES = new HashSet<>(java.util.Arrays.asList("renditions", "rep:policy", "jcr:system", "cq:tags"));

    private Links() {
    }

    // ------------------------------------------------------------------------------------------ collecting

    /** One place a link is written: the node and the property. */
    public static String referrer(String nodePath, String property) {
        return nodePath + "@" + property;
    }

    /** The links in a property value: external URLs, and internal /content paths. */
    static Map<String, String> linksIn(String name, String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        if (value.contains("://")) {
            Matcher m = EXTERNAL_URL.matcher(value);
            while (m.find()) {
                String url = trimUrl(m.group());
                if (url.length() > "https://".length() && !isLocal(url)) {
                    out.put(url, EXTERNAL);
                }
            }
        }
        if (value.contains("/content/")) {
            Matcher a = INTERNAL_ATTR.matcher(value);
            boolean html = false;
            while (a.find()) {
                html = true;
                out.put(cleanInternal(a.group(1)), INTERNAL);
            }
            String trimmed = value.trim();
            if (!html && trimmed.startsWith("/content/") && INTERNAL_VALUE.matcher(trimmed).matches()) {
                out.put(cleanInternal(trimmed), INTERNAL);
            }
        }
        return out;
    }

    /** A URL found in text loses the punctuation that ends the sentence or the markup around it. */
    static String trimUrl(String url) {
        String u = url;
        while (!u.isEmpty() && ".,;:!?)'\"&".indexOf(u.charAt(u.length() - 1)) >= 0) {
            if (u.endsWith("&amp;")) {
                u = u.substring(0, u.length() - 5);
                continue;
            }
            u = u.substring(0, u.length() - 1);
        }
        return u.replace("&amp;", "&");
    }

    /** Links to this instance (localhost) are not external sites. */
    static boolean isLocal(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])([:/].*)?");
    }

    static String cleanInternal(String path) {
        String p = path;
        int cut = indexOfAny(p, '#', '?');
        if (cut >= 0) {
            p = p.substring(0, cut);
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    /** Whether a property can hold an authored link: a plain name, a string. */
    private static boolean readable(Property p) throws RepositoryException {
        String name = p.getName();
        if (name.indexOf(':') >= 0) {
            return false;
        }
        int t = p.getType();
        return t == PropertyType.STRING || t == PropertyType.URI || t == PropertyType.PATH;
    }

    /** Every link under the roots, with the places it is written (in document order). */
    public static Map<String, Found> collect(Session session, List<String> roots) throws RepositoryException {
        Map<String, Found> found = new TreeMap<>();
        for (String root : roots) {
            if (session.nodeExists(root)) {
                walk(session.getNode(root), found);
            }
        }
        return found;
    }

    private static void walk(Node node, Map<String, Found> found) throws RepositoryException {
        for (PropertyIterator it = node.getProperties(); it.hasNext(); ) {
            Property p = it.nextProperty();
            if (!readable(p)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            if (p.isMultiple()) {
                for (Value v : p.getValues()) {
                    values.add(v.getString());
                }
            } else {
                values.add(p.getString());
            }
            for (String v : values) {
                for (Map.Entry<String, String> l : linksIn(p.getName(), v).entrySet()) {
                    Found f = found.computeIfAbsent(l.getKey(), u -> new Found(u, l.getValue()));
                    if (f.referrers.size() < MAX_REFERRERS) {
                        f.referrers.add(referrer(node.getPath(), p.getName()));
                    }
                    f.count++;
                }
            }
        }
        for (NodeIterator it = node.getNodes(); it.hasNext(); ) {
            Node child = it.nextNode();
            if (!SKIP_NODES.contains(child.getName())) {
                walk(child, found);
            }
        }
    }

    /** A link found by a scan. */
    public static final class Found {
        public final String url;
        public final String kind;
        public final Set<String> referrers = new LinkedHashSet<>();
        public int count;

        Found(String url, String kind) {
            this.url = url;
            this.kind = kind;
        }
    }

    // ------------------------------------------------------------------------------------------ storage

    public static String idOf(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pathOf(String url) {
        String id = idOf(url);
        return LINKS + "/" + id.substring(0, 2) + "/" + id;
    }

    /** What a scan changed. */
    public static final class ScanResult {
        public int links;
        public int external;
        public int internal;
        public int added;
        public int removed;

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("links", links);
            m.put("external", external);
            m.put("internal", internal);
            m.put("added", added);
            m.put("removed", removed);
            return m;
        }
    }

    /**
     * Brings the stored links in line with the content: new links are added as pending, the ones still
     * used get their new referrers (their status is kept), the ones nobody uses any more are removed.
     * Saves.
     */
    public static ScanResult store(Session session, Map<String, Found> found) throws RepositoryException {
        Node links = folder(session, LINKS);
        Calendar now = Calendar.getInstance();
        ScanResult r = new ScanResult();
        Set<String> seen = new HashSet<>();
        int pending = 0;
        for (Found f : found.values()) {
            String path = pathOf(f.url);
            seen.add(path);
            Node bucket = folder(session, path.substring(0, path.lastIndexOf('/')));
            Node n;
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (bucket.hasNode(name)) {
                n = bucket.getNode(name);
            } else {
                n = bucket.addNode(name, "nt:unstructured");
                n.setProperty("url", f.url);
                n.setProperty("kind", f.kind);
                n.setProperty("status", PENDING);
                n.setProperty("firstSeen", now);
                r.added++;
            }
            n.setProperty("referrers", f.referrers.toArray(new String[0]));
            n.setProperty("referrerCount", (long) f.count);
            n.setProperty("lastSeen", now);
            if (EXTERNAL.equals(f.kind)) {
                r.external++;
            } else {
                r.internal++;
            }
            if (++pending % 500 == 0) {
                session.save();
            }
        }
        r.links = found.size();
        for (NodeIterator buckets = links.getNodes(); buckets.hasNext(); ) {
            Node bucket = buckets.nextNode();
            for (NodeIterator it = bucket.getNodes(); it.hasNext(); ) {
                Node n = it.nextNode();
                if (!seen.contains(n.getPath())) {
                    n.remove();
                    r.removed++;
                }
            }
            if (!bucket.getNodes().hasNext()) {
                bucket.remove();
            }
        }
        session.save();
        return r;
    }

    private static Node folder(Session session, String path) throws RepositoryException {
        if (session.nodeExists(path)) {
            return session.getNode(path);
        }
        Node parent = folder(session, path.substring(0, Math.max(1, path.lastIndexOf('/'))));
        return parent.addNode(path.substring(path.lastIndexOf('/') + 1), "sling:Folder");
    }

    /** Every stored link node. */
    public static List<Node> all(Session session) throws RepositoryException {
        List<Node> out = new ArrayList<>();
        if (!session.nodeExists(LINKS)) {
            return out;
        }
        for (NodeIterator buckets = session.getNode(LINKS).getNodes(); buckets.hasNext(); ) {
            for (NodeIterator it = buckets.nextNode().getNodes(); it.hasNext(); ) {
                Node n = it.nextNode();
                if (n.hasProperty("url")) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    public static Node find(Session session, String url) throws RepositoryException {
        String path = pathOf(url);
        return session.nodeExists(path) ? session.getNode(path) : null;
    }

    /** Whether a link is due for a check: never checked, or not since {@code periodMillis}. */
    public static boolean due(Node n, long periodMillis, long now) throws RepositoryException {
        if (!n.hasProperty("lastChecked")) {
            return true;
        }
        return now - n.getProperty("lastChecked").getDate().getTimeInMillis() >= periodMillis;
    }

    /** Writes the outcome of a check on the link's node (not saved). */
    public static void record(Node n, Check c) throws RepositoryException {
        n.setProperty("status", c.valid ? VALID : INVALID);
        if (c.code > 0) {
            n.setProperty("code", (long) c.code);
        } else if (n.hasProperty("code")) {
            n.getProperty("code").remove();
        }
        n.setProperty("error", c.error);
        n.setProperty("finalUrl", c.finalUrl);
        n.setProperty("lastChecked", Calendar.getInstance());
    }

    // ------------------------------------------------------------------------------------------ checking

    /** The outcome of checking one link. */
    public static final class Check {
        public final boolean valid;
        public final int code;
        public final String error;
        public final String finalUrl;

        public Check(boolean valid, int code, String error, String finalUrl) {
            this.valid = valid;
            this.code = code;
            this.error = error;
            this.finalUrl = finalUrl;
        }
    }

    /**
     * An internal link is valid when it names something in the repository: the path itself, or the path
     * without its selectors and extension ({@code /content/site/en.html} is the page {@code /content/site/en}).
     */
    public static Check checkInternal(Session session, String url) throws RepositoryException {
        String path = cleanInternal(url);
        if (exists(session, path)) {
            return new Check(true, 0, null, null);
        }
        int slash = path.lastIndexOf('/');
        int dot = path.indexOf('.', slash + 1);
        if (dot > slash + 1) {
            String bare = path.substring(0, dot);
            if (exists(session, bare)) {
                return new Check(true, 0, null, null);
            }
        }
        return new Check(false, 0, "Not found in the repository", null);
    }

    private static boolean exists(Session session, String path) {
        try {
            return session.nodeExists(path);
        } catch (RepositoryException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------ JSON

    /** A link as the console lists it. */
    public static Map<String, Object> toJson(Node n, int referrers) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", n.getProperty("url").getString());
        m.put("kind", string(n, "kind"));
        m.put("status", string(n, "status"));
        if (n.hasProperty("code")) {
            m.put("code", n.getProperty("code").getLong());
        }
        m.put("error", string(n, "error"));
        m.put("finalUrl", string(n, "finalUrl"));
        m.put("lastChecked", date(n, "lastChecked"));
        m.put("firstSeen", date(n, "firstSeen"));
        m.put("referrerCount", n.hasProperty("referrerCount") ? n.getProperty("referrerCount").getLong() : 0L);
        if (referrers > 0 && n.hasProperty("referrers")) {
            List<String> owners = new ArrayList<>();
            for (Value v : n.getProperty("referrers").getValues()) {
                String o = ownerOf(v.getString());
                if (!owners.contains(o)) {
                    owners.add(o);
                }
                if (owners.size() >= referrers) {
                    break;
                }
            }
            m.put("owners", owners);
        }
        return m;
    }

    /** Where a link is written, with the page (or file) it belongs to. */
    public static Map<String, Object> referrerJson(Session session, String referrer) throws RepositoryException {
        int at = referrer.lastIndexOf('@');
        String node = at < 0 ? referrer : referrer.substring(0, at);
        String property = at < 0 ? null : referrer.substring(at + 1);
        String owner = ownerOf(referrer);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owner", owner);
        m.put("ownerTitle", titleOf(session, owner));
        m.put("ownerKind", kindOf(session, owner));
        m.put("node", node);
        String rel = node.startsWith(owner + "/jcr:content") ? node.substring(owner.length() + "/jcr:content".length()) : null;
        m.put("component", rel == null || rel.isEmpty() ? null : rel.substring(1));
        m.put("property", property);
        return m;
    }

    /** The page or file a referrer belongs to: the path before {@code /jcr:content}. */
    public static String ownerOf(String referrer) {
        int at = referrer.lastIndexOf('@');
        String node = at < 0 ? referrer : referrer.substring(0, at);
        int jc = node.indexOf("/jcr:content");
        return jc < 0 ? node : node.substring(0, jc);
    }

    private static String titleOf(Session session, String owner) {
        try {
            String content = owner + "/jcr:content";
            if (session.nodeExists(content)) {
                Node c = session.getNode(content);
                if (c.hasProperty("jcr:title")) {
                    return c.getProperty("jcr:title").getString();
                }
                if (c.hasNode("metadata") && c.getNode("metadata").hasProperty("dc:title")) {
                    Property t = c.getNode("metadata").getProperty("dc:title");
                    return t.isMultiple() ? (t.getValues().length > 0 ? t.getValues()[0].getString() : null) : t.getString();
                }
            }
        } catch (RepositoryException e) {
            // no title
        }
        return owner.substring(owner.lastIndexOf('/') + 1);
    }

    private static String kindOf(Session session, String owner) {
        try {
            if (!session.nodeExists(owner)) {
                return "other";
            }
            Node n = session.getNode(owner);
            if (n.isNodeType("dam:Asset")) {
                Node c = n.hasNode("jcr:content") ? n.getNode("jcr:content") : null;
                return c != null && c.hasProperty("contentFragment") && c.getProperty("contentFragment").getBoolean() ? "record" : "media";
            }
            if (n.isNodeType("cq:Page")) {
                return owner.startsWith("/content/experience-fragments/") ? "block" : "page";
            }
        } catch (RepositoryException e) {
            // unknown
        }
        return "other";
    }

    private static String string(Node n, String name) throws RepositoryException {
        return n.hasProperty(name) ? n.getProperty(name).getString() : null;
    }

    private static String date(Node n, String name) throws RepositoryException {
        if (!n.hasProperty(name)) {
            return null;
        }
        return java.time.Instant.ofEpochMilli(n.getProperty(name).getDate().getTimeInMillis()).toString();
    }
}
