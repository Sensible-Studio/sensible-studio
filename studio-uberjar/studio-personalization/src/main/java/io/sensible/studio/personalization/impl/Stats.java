/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * What the visitors did with targeted components (the activity reports): per target and experience,
 * impressions (the experience was shown) and conversions (its goal was met), in total and per day, under
 * {@code /var/studio/personalization/stats/<sha1 of the target path>/<experience>[/days/<yyyy-MM-dd>]}.
 * Written by the tracking endpoint through the personalization service user; no visitor is identified.
 */
public final class Stats {

    public static final String ROOT = "/var/studio/personalization/stats";
    public static final String IMPRESSIONS = "impressions";
    public static final String CONVERSIONS = "conversions";
    private static final int KEEP_DAYS = 90;

    private Stats() {
    }

    public static String key(String targetPath) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(targetPath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adds one event; the caller saves. */
    public static void record(Session session, String targetPath, String experience, boolean conversion, String pagePath) throws RepositoryException {
        if (targetPath == null || !targetPath.startsWith("/content/") || experience == null || !experience.matches("[A-Za-z0-9_-]{1,64}")) {
            return;
        }
        Node root = mkdirs(session, ROOT);
        String k = key(targetPath);
        Node target = root.hasNode(k) ? root.getNode(k) : root.addNode(k, "nt:unstructured");
        target.setProperty("path", targetPath);
        if (pagePath != null && pagePath.startsWith("/content/")) {
            target.setProperty("page", pagePath);
        }
        target.setProperty("lastEvent", Calendar.getInstance());
        Node exp = target.hasNode(experience) ? target.getNode(experience) : target.addNode(experience, "nt:unstructured");
        bump(exp, conversion);
        Node days = exp.hasNode("days") ? exp.getNode("days") : exp.addNode("days", "nt:unstructured");
        String day = day(Calendar.getInstance());
        Node d = days.hasNode(day) ? days.getNode(day) : days.addNode(day, "nt:unstructured");
        bump(d, conversion);
        // keep the per-day history short
        List<Node> old = new ArrayList<>();
        for (NodeIterator it = days.getNodes(); it.hasNext();) {
            Node n = it.nextNode();
            if (n.getName().compareTo(day(daysAgo(KEEP_DAYS))) < 0) {
                old.add(n);
            }
        }
        for (Node n : old) {
            n.remove();
        }
    }

    private static void bump(Node n, boolean conversion) throws RepositoryException {
        String p = conversion ? CONVERSIONS : IMPRESSIONS;
        n.setProperty(p, (n.hasProperty(p) ? n.getProperty(p).getLong() : 0L) + 1);
    }

    /** The counters of a target: experience → {impressions, conversions, rate, days: [{day, impressions, conversions}]}. */
    public static Map<String, Object> read(Session session, String targetPath) throws RepositoryException {
        Map<String, Object> out = new LinkedHashMap<>();
        String path = ROOT + "/" + key(targetPath);
        if (!session.nodeExists(path)) {
            return out;
        }
        for (NodeIterator it = session.getNode(path).getNodes(); it.hasNext();) {
            Node exp = it.nextNode();
            if (exp.getName().startsWith("jcr:")) {
                continue;
            }
            out.put(exp.getName(), experience(exp));
        }
        return out;
    }

    static Map<String, Object> experience(Node exp) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        long i = exp.hasProperty(IMPRESSIONS) ? exp.getProperty(IMPRESSIONS).getLong() : 0L;
        long c = exp.hasProperty(CONVERSIONS) ? exp.getProperty(CONVERSIONS).getLong() : 0L;
        m.put(IMPRESSIONS, i);
        m.put(CONVERSIONS, c);
        m.put("rate", i == 0 ? 0.0 : Math.round(10000.0 * c / i) / 100.0);
        List<Object> days = new ArrayList<>();
        if (exp.hasNode("days")) {
            for (NodeIterator it = exp.getNode("days").getNodes(); it.hasNext();) {
                Node d = it.nextNode();
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("day", d.getName());
                dm.put(IMPRESSIONS, d.hasProperty(IMPRESSIONS) ? d.getProperty(IMPRESSIONS).getLong() : 0L);
                dm.put(CONVERSIONS, d.hasProperty(CONVERSIONS) ? d.getProperty(CONVERSIONS).getLong() : 0L);
                days.add(dm);
            }
        }
        days.sort((a, b) -> String.valueOf(((Map<?, ?>) a).get("day")).compareTo(String.valueOf(((Map<?, ?>) b).get("day"))));
        m.put("days", days);
        return m;
    }

    /** Every tracked target: {path, page, lastEvent, experiences: {…}}. */
    public static List<Map<String, Object>> all(Session session) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!session.nodeExists(ROOT)) {
            return out;
        }
        for (NodeIterator it = session.getNode(ROOT).getNodes(); it.hasNext();) {
            Node t = it.nextNode();
            if (!t.hasProperty("path")) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", t.getProperty("path").getString());
            m.put("page", t.hasProperty("page") ? t.getProperty("page").getString() : null);
            m.put("lastEvent", t.hasProperty("lastEvent") ? iso(t.getProperty("lastEvent").getDate()) : null);
            Map<String, Object> exps = new LinkedHashMap<>();
            for (NodeIterator e = t.getNodes(); e.hasNext();) {
                Node exp = e.nextNode();
                exps.put(exp.getName(), experience(exp));
            }
            m.put("experiences", exps);
            out.add(m);
        }
        return out;
    }

    public static void reset(Session session, String targetPath) throws RepositoryException {
        String path = ROOT + "/" + key(targetPath);
        if (session.nodeExists(path)) {
            session.getNode(path).remove();
        }
    }

    static String day(Calendar c) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    static Calendar daysAgo(int n) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, -n);
        return c;
    }

    static String iso(Calendar c) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    /**
     * The node at the path, made with its missing ancestors. It starts at the deepest node that exists, not at the
     * root: on a publish instance the service user sees its own tree only ("Root node is not accessible"), and
     * nothing was ever counted on the live site.
     */
    static Node mkdirs(Session session, String path) throws RepositoryException {
        if (session.nodeExists(path)) {
            return session.getNode(path);
        }
        int cut = path.lastIndexOf('/');
        if (cut <= 0) {
            throw new javax.jcr.PathNotFoundException(path);
        }
        return mkdirs(session, path.substring(0, cut)).addNode(path.substring(cut + 1), "sling:Folder");
    }
}
