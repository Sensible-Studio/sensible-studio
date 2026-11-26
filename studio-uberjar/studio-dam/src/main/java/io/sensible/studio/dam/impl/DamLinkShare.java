/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Share Link: a link that lets anyone who has it download chosen assets and folders until it
 * expires, without an account, as AEM's Link Sharing.
 *
 * <p>The link carries a random token (256 bits). Only its SHA-256 is stored, as the node name
 * under {@link #ROOT}, so reading the repository does not reveal working links. The token is
 * shown once, when the link is made. Downloads read through a dedicated service user that can
 * only read {@code /content/dam}; every requested path is checked against the shared ones.</p>
 *
 * <pre>
 * /var/studio/dam/linkshare/&lt;sha256 of token&gt;   studio:paths, studio:expires, studio:owner, studio:originals, studio:renditions, jcr:created
 * </pre>
 */
final class DamLinkShare {

    static final String ROOT = "/var/studio/dam/linkshare";
    static final String SUBSERVICE = "linkshare";
    static final int MAX_DAYS = 90;
    static final int MAX_PATHS = 1000;
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DamLinkShare() {
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String id(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder s = new StringBuilder();
            for (byte x : d) {
                s.append(String.format("%02x", x));
            }
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Makes a link. {@code user} decides what may be shared (it must be able to read every path);
     * {@code service} writes the link node.
     */
    static Map<String, Object> create(ResourceResolver user, ResourceResolver service, List<String> paths, int days, boolean renditions)
            throws DamApiServlet.ApiException, PersistenceException {
        if (days < 1 || days > MAX_DAYS) {
            throw new DamApiServlet.ApiException(400, "links expire after 1 to " + MAX_DAYS + " days");
        }
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, days);
        return create(user, service, paths, expires, true, renditions);
    }

    /**
     * Makes a link valid until {@code expires}, with the originals and/or the renditions (Assets Configurations ›
     * Link Share says what may be chosen; the caller applies it).
     */
    static Map<String, Object> create(ResourceResolver user, ResourceResolver service, List<String> paths, Calendar expires,
            boolean originals, boolean renditions) throws DamApiServlet.ApiException, PersistenceException {
        if (paths.isEmpty() || paths.size() > MAX_PATHS) {
            throw new DamApiServlet.ApiException(400, "share 1 to " + MAX_PATHS + " items");
        }
        if (!originals && !renditions) {
            throw new DamApiServlet.ApiException(400, "a link gives the originals, the renditions, or both");
        }
        if (expires == null || !expires.after(Calendar.getInstance())) {
            throw new DamApiServlet.ApiException(400, "a link expires in the future");
        }
        if (user.getUserID() == null || "anonymous".equals(user.getUserID())) {
            throw new DamApiServlet.ApiException(401, "login required");
        }
        for (String p : paths) {
            Resource r = DamItems.inDam(p) ? user.getResource(p) : null;
            if (!(DamItems.isAsset(r) || DamItems.isFolder(r)) || p.startsWith(DamCollections.ROOT + "/")) {
                throw new DamApiServlet.ApiException(404, "not found: " + p);
            }
        }
        String token = newToken();
        Resource root = ensureRoot(service);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("studio:paths", paths.toArray(new String[0]));
        props.put("studio:expires", expires);
        props.put("studio:owner", user.getUserID());
        props.put("studio:created", Calendar.getInstance());
        props.put("studio:renditions", renditions);
        props.put("studio:originals", originals);
        service.create(root, id(token), props);
        service.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("id", id(token));
        out.put("url", "/linkshare.html?sh=" + token);
        out.put("expires", expires);
        return out;
    }

    /** The link of a token, or null when unknown or expired. */
    static Resource find(ResourceResolver service, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return null;
        }
        Resource r = service.getResource(ROOT + "/" + id(token));
        if (r == null) {
            return null;
        }
        Calendar expires = r.getValueMap().get("studio:expires", Calendar.class);
        return expires != null && expires.after(Calendar.getInstance()) ? r : null;
    }

    static List<String> paths(Resource link) {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, link.getValueMap().get("studio:paths", new String[0]));
        return out;
    }

    /** Whether {@code path} is one of the shared items or inside a shared folder. */
    static boolean covers(Resource link, String path) {
        if (path == null || path.contains("..") || path.contains("//")) {
            return false;
        }
        for (String p : paths(link)) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /** The links of {@code owner} (never their tokens), for the console to list and revoke. */
    static List<Map<String, Object>> list(ResourceResolver service, String owner, String forPath) {
        Resource root = service.getResource(ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        Calendar now = Calendar.getInstance();
        for (Resource l : root.getChildren()) {
            ValueMap v = l.getValueMap();
            Calendar expires = v.get("studio:expires", Calendar.class);
            if (!owner.equals(v.get("studio:owner", String.class)) || expires == null || expires.before(now)) {
                continue;
            }
            List<String> paths = paths(l);
            if (forPath != null && !paths.contains(forPath)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getName());
            m.put("paths", paths);
            m.put("expires", expires);
            m.put("created", v.get("studio:created", Calendar.class));
            m.put("renditions", v.get("studio:renditions", false));
            out.add(m);
        }
        return out;
    }

    static void revoke(ResourceResolver service, String owner, boolean admin, String id) throws DamApiServlet.ApiException, PersistenceException {
        Resource l = id == null || !id.matches("^[0-9a-f]{64}$") ? null : service.getResource(ROOT + "/" + id);
        if (l == null || !(owner.equals(l.getValueMap().get("studio:owner", String.class)) || admin)) {
            throw new DamApiServlet.ApiException(404, "no such link");
        }
        service.delete(l);
        service.commit();
    }

    /** Expired links are removed when new ones are made (no job needed for a handful of nodes). */
    static int purge(ResourceResolver service) throws PersistenceException {
        Resource root = service.getResource(ROOT);
        int n = 0;
        if (root == null) {
            return 0;
        }
        Calendar now = Calendar.getInstance();
        for (Resource l : new ArrayList<>(asList(root.getChildren()))) {
            Calendar expires = l.getValueMap().get("studio:expires", Calendar.class);
            if (expires == null || expires.before(now)) {
                service.delete(l);
                n++;
            }
        }
        if (n > 0) {
            service.commit();
        }
        return n;
    }

    private static List<Resource> asList(Iterable<Resource> it) {
        List<Resource> out = new ArrayList<>();
        it.forEach(out::add);
        return out;
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
