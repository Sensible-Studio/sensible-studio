/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Comments on a Media file, and notes drawn on its picture (AEM's asset Timeline comments and
 * Annotate): {@code <asset>/jcr:content/cq:annotations/<id>} with {@code text}, {@code author},
 * {@code created}, and for a note on the picture {@code x}, {@code y}, {@code w}, {@code h}
 * (fractions of the picture: the box the mark fits in), {@code color}, {@code shape} ({@code rect},
 * {@code ellipse}, {@code arrow} from its first point to its last, {@code free} a line drawn by hand),
 * {@code points} ({@code x,y x,y …} in fractions, for an arrow or a line) and, on a video,
 * {@code time} (seconds: the frame the note is about).
 *
 * <p>{@code cq:annotations} is the node the page editor's notes use too: publishing leaves it out,
 * so review talk never reaches the published site. Written with the session of the person:
 * whoever may change the file may comment on it; only the author of a comment, or an
 * administrator, removes it. A comment does not mark the file modified.</p>
 */
final class DamComments {

    static final String NODE = "cq:annotations";
    static final int MAX_TEXT = 4000;
    static final int MAX_COMMENTS = 1000;
    static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    static final String DEFAULT_COLOR = "#e68619";
    static final java.util.List<String> SHAPES = java.util.Arrays.asList("rect", "ellipse", "arrow", "free");
    static final int MAX_POINTS = 400;

    private DamComments() {
    }

    /** A region on the picture, in fractions of its width and height. */
    static final class Region {
        final double x;
        final double y;
        final double w;
        final double h;

        /** rect (the default), ellipse, arrow, free */
        String shape = "rect";
        /** arrow and free: the points, in fractions */
        final List<double[]> points = new ArrayList<>();
        /** a video: the second the note is about */
        Double time;

        Region(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        /** An arrow or a hand-drawn line: the points ("x,y x,y …"); the box becomes the one they fit in. */
        static Region of(String shape, String points, double x, double y, double w, double h) {
            String sh = shape == null || shape.isEmpty() ? "rect" : shape;
            if (!SHAPES.contains(sh)) {
                throw new IllegalArgumentException("A mark is a rect, an ellipse, an arrow or a free line");
            }
            if (!"arrow".equals(sh) && !"free".equals(sh)) {
                Region r = new Region(x, y, w, h);
                r.shape = sh;
                return r;
            }
            List<double[]> ps = new ArrayList<>();
            for (String pair : (points == null ? "" : points.trim()).split("\\s+")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String[] xy = pair.split(",");
                if (xy.length != 2) {
                    throw new IllegalArgumentException("Points are x,y pairs");
                }
                double px = Double.parseDouble(xy[0]);
                double py = Double.parseDouble(xy[1]);
                if (px < 0 || py < 0 || px > 1.0001 || py > 1.0001 || Double.isNaN(px) || Double.isNaN(py)) {
                    throw new IllegalArgumentException("The note must be on the picture");
                }
                ps.add(new double[] {px, py});
            }
            if (ps.size() < 2) {
                throw new IllegalArgumentException("An arrow or a line has two points at least");
            }
            if (ps.size() > MAX_POINTS) {
                throw new IllegalArgumentException("A line has at most " + MAX_POINTS + " points");
            }
            double x0 = 1;
            double y0 = 1;
            double x1 = 0;
            double y1 = 0;
            for (double[] pt : ps) {
                x0 = Math.min(x0, pt[0]);
                y0 = Math.min(y0, pt[1]);
                x1 = Math.max(x1, pt[0]);
                y1 = Math.max(y1, pt[1]);
            }
            // a straight line has no width or no height: give its box a little of each
            // four decimals, as the points: a tenth of a pixel on a 4K picture
            Region r = new Region(r4(x0), r4(y0), Math.max(r4(x1 - x0), 0.001), Math.max(r4(y1 - y0), 0.001));
            r.shape = sh;
            r.points.addAll(ps);
            return r;
        }

        boolean valid() {
            return x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1.0011 && y + h <= 1.0011 && (time == null || (time >= 0 && time < 864000));
        }

        String pointsText() {
            StringBuilder b = new StringBuilder();
            for (double[] pt : points) {
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(round(pt[0])).append(',').append(round(pt[1]));
            }
            return b.toString();
        }

        private static double r4(double d) {
            return Math.round(d * 10000) / 10000.0;
        }

        private static String round(double d) {
            return java.math.BigDecimal.valueOf(Math.round(d * 10000) / 10000.0).stripTrailingZeros().toPlainString();
        }
    }

    /** The comments and notes of an asset, oldest first, with whether this reader may add some. */
    static Map<String, Object> list(ResourceResolver resolver, Resource asset) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource folder = asset.getChild("jcr:content/" + NODE);
        if (folder != null) {
            for (Resource c : folder.getChildren()) {
                Map<String, Object> m = describe(c, resolver.getUserID());
                if (m != null) {
                    out.add(m);
                }
            }
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("created"))));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("comments", out);
        res.put("canComment", canComment(resolver, asset));
        return res;
    }

    static boolean canComment(ResourceResolver resolver, Resource asset) {
        Session s = resolver.adaptTo(Session.class);
        if (s == null) {
            return true; // tests without JCR
        }
        try {
            return s.hasPermission(asset.getPath() + "/jcr:content", Session.ACTION_ADD_NODE);
        } catch (RepositoryException e) {
            return false;
        } catch (UnsupportedOperationException e) {
            return true; // a mock session: the repository decides on the write anyway
        }
    }

    /**
     * Adds a comment (a note on the picture when {@code region} is given).
     *
     * @throws IllegalArgumentException empty or too long text, a region outside the picture, a bad colour
     * @throws SecurityException when this person may not change the file
     */
    static Map<String, Object> add(ResourceResolver resolver, Resource asset, String text, Region region, String color)
            throws PersistenceException {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Write a comment first");
        }
        if (t.length() > MAX_TEXT) {
            throw new IllegalArgumentException("A comment has at most " + MAX_TEXT + " characters");
        }
        if (region != null && !region.valid()) {
            throw new IllegalArgumentException("The note must be on the picture");
        }
        if (color != null && !color.isEmpty() && !COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException("A colour is #rrggbb");
        }
        if (!canComment(resolver, asset)) {
            throw new SecurityException("Only those who may change this file comment on it");
        }
        Resource content = asset.getChild("jcr:content");
        if (content == null) {
            throw new IllegalArgumentException("not an asset");
        }
        Resource folder = content.getChild(NODE);
        if (folder == null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("jcr:primaryType", "nt:unstructured");
            folder = resolver.create(content, NODE, p);
        }
        int count = 0;
        for (Resource ignored : folder.getChildren()) {
            if (++count >= MAX_COMMENTS) {
                throw new IllegalArgumentException("This file has " + MAX_COMMENTS + " comments already");
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jcr:primaryType", "nt:unstructured");
        p.put("text", t);
        p.put("author", resolver.getUserID());
        p.put("created", Calendar.getInstance());
        if (region != null) {
            p.put("x", region.x);
            p.put("y", region.y);
            p.put("w", region.w);
            p.put("h", region.h);
            p.put("color", color == null || color.isEmpty() ? DEFAULT_COLOR : color);
            if (!"rect".equals(region.shape)) {
                p.put("shape", region.shape);
            }
            if (!region.points.isEmpty()) {
                p.put("points", region.pointsText());
            }
            if (region.time != null) {
                p.put("time", region.time);
            }
        }
        String name = "c" + System.currentTimeMillis();
        while (folder.getChild(name) != null) {
            name = name + "_";
        }
        Resource c = resolver.create(folder, name, p);
        return describe(c, resolver.getUserID());
    }

    /** Removes one comment: its author or an administrator. */
    static void remove(ResourceResolver resolver, Resource asset, String id) throws PersistenceException {
        Resource c = id == null || !id.matches("^c[0-9]+_*$") ? null : asset.getChild("jcr:content/" + NODE + "/" + id);
        if (c == null || c.getValueMap().get("text") == null) {
            throw new IllegalArgumentException("No comment " + id);
        }
        String author = c.getValueMap().get("author", String.class);
        if (!String.valueOf(resolver.getUserID()).equals(author) && !DamAccess.isAdmin(resolver)) {
            throw new SecurityException("Only the author of a comment may remove it");
        }
        resolver.delete(c);
    }

    private static Map<String, Object> describe(Resource c, String me) {
        ValueMap v = c.getValueMap();
        String text = v.get("text", String.class);
        Calendar created = v.get("created", v.get("jcr:created", Calendar.class));
        if (text == null || created == null) {
            return null; // not one of ours
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getName());
        m.put("text", text);
        String author = v.get("author", v.get("jcr:createdBy", String.class));
        m.put("author", author);
        m.put("created", created);
        m.put("mine", me != null && me.equals(author));
        Double x = v.get("x", Double.class);
        Double y = v.get("y", Double.class);
        Double w = v.get("w", Double.class);
        Double h = v.get("h", Double.class);
        if (x != null && y != null && w != null && h != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("x", x);
            r.put("y", y);
            r.put("w", w);
            r.put("h", h);
            String shape = v.get("shape", "rect");
            if (!"rect".equals(shape)) {
                r.put("shape", shape);
            }
            String points = v.get("points", String.class);
            if (points != null) {
                List<Object> ps = new ArrayList<>();
                for (String pair : points.trim().split("\\s+")) {
                    String[] xy = pair.split(",");
                    if (xy.length == 2) {
                        try {
                            ps.add(java.util.Arrays.asList(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
                        } catch (NumberFormatException e) {
                            // a broken pair is left out
                        }
                    }
                }
                r.put("points", ps);
            }
            Double time = v.get("time", Double.class);
            if (time != null) {
                r.put("time", time);
            }
            m.put("region", r);
            m.put("color", v.get("color", DEFAULT_COLOR));
        }
        return m;
    }
}
