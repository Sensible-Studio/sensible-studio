/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request parsing for the image editor: the operations sent to studio-dam, and image maps.
 *
 * <p>Operations are checked and rebuilt here rather than forwarded as received, so studio-dam only
 * ever sees the three known shapes. Image maps are stored in the asset's metadata
 * ({@code jcr:content/metadata/imageMap}, where AEM keeps them), in the area notation Core Components use:
 * <pre>
 * [rect(x1,y1,x2,y2)"href"|"target"|"alt"|(rx1,ry1,rx2,ry2)][circle(cx,cy,r)"href"|"target"|"alt"|(rcx,rcy,rr)]
 * [poly(x1,y1,x2,y2,x3,y3,…)"href"|"target"|"alt"|(rx1,ry1,rx2,ry2,rx3,ry3,…)]
 * </pre>
 * Absolute values are pixels of the original; the relative ones (in parentheses) are fractions
 * of its width and height (the circle radius is a fraction of the width).
 */
final class ImageEdit {

    static final int MAX_OPERATIONS = 64;
    static final int MAX_AREAS = 100;
    static final int MAX_HREF = 2000;
    static final int MAX_ALT = 500;
    static final Set<String> TARGETS = new HashSet<>(Arrays.asList("", "_self", "_blank", "_parent", "_top"));

    private ImageEdit() {
    }

    /** Thrown for a malformed request (HTTP 400). */
    static final class InvalidEdit extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidEdit(String message) {
            super(message);
        }
    }

    /**
     * Parses {@code [{"op":"rotate","degrees":90},{"op":"flip","axis":"horizontal"},
     * {"op":"crop","x":0.1,"y":0,"width":0.5,"height":0.5}]}. Crop values are fractions of the
     * image as it is at that step.
     */
    static List<Map<String, Object>> operations(String json) throws InvalidEdit {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new InvalidEdit("operations: " + e.getMessage());
        }
        if (!(parsed instanceof List)) {
            throw new InvalidEdit("operations must be a JSON array");
        }
        List<?> list = (List<?>) parsed;
        if (list.size() > MAX_OPERATIONS) {
            throw new InvalidEdit("at most " + MAX_OPERATIONS + " operations");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw new InvalidEdit("each operation must be an object");
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String op = String.valueOf(m.get("op"));
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("op", op);
            switch (op) {
                case "rotate": {
                    long deg = Math.round(number(m, "degrees"));
                    if (deg % 90 != 0) {
                        throw new InvalidEdit("rotation must be a multiple of 90 degrees");
                    }
                    clean.put("degrees", (int) Math.floorMod(deg, 360));
                    break;
                }
                case "flip": {
                    String axis = String.valueOf(m.get("axis"));
                    if (!axis.equals("horizontal") && !axis.equals("vertical")) {
                        throw new InvalidEdit("flip axis must be horizontal or vertical");
                    }
                    clean.put("axis", axis);
                    break;
                }
                case "crop": {
                    double x = number(m, "x");
                    double y = number(m, "y");
                    double w = number(m, "width");
                    double h = number(m, "height");
                    if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1.000001 || y + h > 1.000001) {
                        throw new InvalidEdit("crop must lie inside the image (fractions 0..1)");
                    }
                    clean.put("x", x);
                    clean.put("y", y);
                    clean.put("width", Math.min(w, 1 - x));
                    clean.put("height", Math.min(h, 1 - y));
                    break;
                }
                default:
                    throw new InvalidEdit("unknown operation: " + op);
            }
            out.add(clean);
        }
        return out;
    }

    private static double number(Map<?, ?> m, String key) throws InvalidEdit {
        Object v = m.get(key);
        if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())) {
            throw new InvalidEdit(key + " must be a number");
        }
        return ((Number) v).doubleValue();
    }

    /** One image map area; coordinates are fractions of the image. */
    static final class Area {
        final String shape;
        /** rect: x1, y1, x2, y2; circle: cx, cy, r (r as a fraction of the width); poly: x1, y1, x2, y2, … */
        final double[] coords;
        final String href;
        final String target;
        final String alt;

        Area(String shape, double[] coords, String href, String target, String alt) {
            this.shape = shape;
            this.coords = coords;
            this.href = href;
            this.target = target;
            this.alt = alt;
        }
    }

    /** A polygon has at most this many corners. */
    static final int MAX_POLY_CORNERS = 200;

    /**
     * Parses {@code [{"shape":"rect","coords":[x1,y1,x2,y2],"href":"","target":"","alt":""}, ...]}
     * (relative coordinates).
     */
    static List<Area> areas(String json) throws InvalidEdit {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new InvalidEdit("imageMap: " + e.getMessage());
        }
        if (!(parsed instanceof List)) {
            throw new InvalidEdit("imageMap must be a JSON array");
        }
        List<?> list = (List<?>) parsed;
        if (list.size() > MAX_AREAS) {
            throw new InvalidEdit("at most " + MAX_AREAS + " map areas");
        }
        List<Area> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw new InvalidEdit("each map area must be an object");
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String shape = String.valueOf(m.get("shape"));
            Object c = m.get("coords");
            int n;
            if (shape.equals("poly")) {
                // corners as x, y pairs: three or more
                n = c instanceof List ? ((List<?>) c).size() : -1;
                if (n < 6 || n % 2 != 0 || n > MAX_POLY_CORNERS * 2) {
                    throw new InvalidEdit("poly needs 3 to " + MAX_POLY_CORNERS + " corners (x, y pairs)");
                }
            } else {
                n = shape.equals("rect") ? 4 : shape.equals("circle") ? 3 : -1;
                if (n < 0) {
                    throw new InvalidEdit("map area shape must be rect, circle or poly");
                }
                if (!(c instanceof List) || ((List<?>) c).size() != n) {
                    throw new InvalidEdit(shape + " needs " + n + " coordinates");
                }
            }
            double[] coords = new double[n];
            for (int k = 0; k < n; k++) {
                Object v = ((List<?>) c).get(k);
                if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())) {
                    throw new InvalidEdit("coordinates must be numbers");
                }
                coords[k] = clamp01(((Number) v).doubleValue());
            }
            if (shape.equals("rect")) {
                coords = new double[] {Math.min(coords[0], coords[2]), Math.min(coords[1], coords[3]),
                    Math.max(coords[0], coords[2]), Math.max(coords[1], coords[3])};
            }
            String href = text(m.get("href"), MAX_HREF);
            if (!safeHref(href)) {
                throw new InvalidEdit("link must be a path, http(s), mailto or tel URL: " + href);
            }
            String target = text(m.get("target"), 20);
            if (!TARGETS.contains(target)) {
                throw new InvalidEdit("unknown target: " + target);
            }
            out.add(new Area(shape, coords, href, target, text(m.get("alt"), MAX_ALT)));
        }
        return out;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Trims and drops the characters the area notation uses as delimiters. */
    private static String text(Object v, int max) throws InvalidEdit {
        String s = v == null ? "" : String.valueOf(v).replaceAll("[\"|\\[\\]\\s]+", " ").trim();
        if (s.length() > max) {
            throw new InvalidEdit("value longer than " + max + " characters");
        }
        return s;
    }

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");

    /** Paths, anchors and http(s)/mailto/tel URLs; never javascript: or data: (the map ends up in pages). */
    static boolean safeHref(String href) {
        if (href.startsWith("//") || href.startsWith("\\")) {
            return false; // protocol-relative: another site
        }
        if (href.isEmpty() || href.startsWith("/") || href.startsWith("#")) {
            return true;
        }
        Matcher m = SCHEME.matcher(href);
        if (!m.find()) {
            // relative path such as "page.html"
            return !href.contains(":");
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel");
    }

    /** Areas in Core Components notation for an image of {@code width}×{@code height} pixels. */
    static String format(List<Area> areas, long width, long height) {
        StringBuilder b = new StringBuilder();
        for (Area a : areas) {
            double[] c = a.coords;
            b.append('[').append(a.shape).append('(');
            if (a.shape.equals("rect")) {
                b.append(Math.round(c[0] * width)).append(',').append(Math.round(c[1] * height)).append(',')
                        .append(Math.round(c[2] * width)).append(',').append(Math.round(c[3] * height));
            } else if (a.shape.equals("poly")) {
                for (int k = 0; k < c.length; k++) {
                    if (k > 0) {
                        b.append(',');
                    }
                    b.append(Math.round(c[k] * (k % 2 == 0 ? width : height)));
                }
            } else {
                b.append(Math.round(c[0] * width)).append(',').append(Math.round(c[1] * height)).append(',')
                        .append(Math.round(c[2] * width));
            }
            b.append(")\"").append(a.href).append("\"|\"").append(a.target).append("\"|\"").append(a.alt).append("\"|(");
            for (int k = 0; k < c.length; k++) {
                if (k > 0) {
                    b.append(',');
                }
                b.append(fraction(c[k]));
            }
            b.append(")]");
        }
        return b.toString();
    }

    private static String fraction(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }
}
