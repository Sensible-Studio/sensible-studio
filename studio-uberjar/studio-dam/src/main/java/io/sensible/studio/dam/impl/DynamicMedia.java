/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Dynamic Media, phase 1: image presets and the public image URL.
 *
 * <pre>
 * /is/image/&lt;path below /content/dam, extension optional&gt;?wid=800&amp;hei=600&amp;fmt=webp&amp;qlt=80&amp;$banner$
 * </pre>
 *
 * <p>The parameters follow the public Image Serving reference (a widely implemented URL
 * vocabulary), which lets components written for it work unchanged: {@code wid}, {@code hei},
 * {@code fmt} (jpeg, png, png-alpha, webp, webp-alpha), {@code qlt} (1..100), {@code fit}
 * (fit, constrain, crop, stretch, hfit, vfit; a second value {@code ,1} allows upscaling),
 * {@code crop=x,y,w,h} in pixels or {@code cropN=x,y,w,h} as fractions of the image,
 * {@code rotate} (90, 180, 270), {@code flip} (h, v, hv), {@code scl} (divide by),
 * {@code op_sharpen=1}, {@code op_usm=amount,radius,threshold}, {@code op_blur=sigma},
 * {@code op_brightness} and {@code op_contrast} (-100..100), the looks {@code op_saturation} (-100..100),
 * {@code op_grayscale=1}, {@code op_sepia=1}, {@code op_overlay=RRGGBB,opacity}, {@code op_gradient=towards,RRGGBB,from,to}
 * (towards b/t/l/r, opacities 0..100), {@code op_vignette=0..100}, {@code op_radius=px}, {@code op_border=px,RRGGBB},
 * {@code bgc=RRGGBB}, and
 * {@code $name$} for a preset, whose parameters apply first so explicit ones override them.
 * Unknown parameters are ignored, as the reference says. This is our own implementation of
 * that vocabulary; nothing of another product's code is used.</p>
 *
 * <p>Presets live in {@value #ROOT}, one node per preset with its parameters as properties.</p>
 */
final class DynamicMedia {

    static final String ROOT = "/conf/global/settings/dam/dm/presets";
    static final String PREFIX = "/is/image";

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");

    /** Parameters a preset may hold (the URL vocabulary above, minus the ones that only make sense per request). */
    static final List<String> PRESET_PARAMS = Arrays.asList("wid", "hei", "fmt", "qlt", "fit", "scl", "op_sharpen", "op_usm",
            "op_blur", "op_brightness", "op_contrast", "op_saturation", "op_grayscale", "op_sepia", "op_overlay", "op_gradient",
            "op_vignette", "op_radius", "op_border", "bgc", "rotate", "flip");

    static final int MAX_SIDE = 4096;
    static final int DEFAULT_QUALITY = 80;

    private DynamicMedia() {
    }

    /** What studio-dam is asked for: a box, a format, a quality and a transform. */
    static final class Plan {
        final int width;
        final int height;
        /** jpeg, png or webp */
        final String format;
        final int quality;
        /** the studio-dam transform (see dam-service/src/delivery.rs), or empty */
        final String transform;

        Plan(int width, int height, String format, int quality, String transform) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.quality = quality;
            this.transform = transform;
        }

        /** The same plan with a smart crop applied before everything else. */
        Plan croppedFirst(String cropStep) {
            return new Plan(width, height, format, quality, transform.isEmpty() ? cropStep : cropStep + "-" + transform);
        }

        /** The part of the cache key that depends on the request. */
        String key() {
            return width + "x" + height + "." + format + "?q=" + quality + "&t=" + transform;
        }
    }

    /** Thrown for parameter values the vocabulary does not allow. */
    static final class BadParameter extends Exception {
        private static final long serialVersionUID = 1L;

        BadParameter(String message) {
            super(message);
        }
    }

    /**
     * Resolves the parameters of a request (presets expanded, explicit values overriding) into a
     * plan for a {@code srcWidth}×{@code srcHeight} image. {@code params} holds every query
     * parameter, in order, with {@code $name$} entries for presets.
     */
    static Plan plan(ResourceResolver resolver, Map<String, String> params, long srcWidth, long srcHeight) throws BadParameter {
        Map<String, String> p = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey();
            if (k.length() > 2 && k.startsWith("$") && k.endsWith("$")) {
                Resource preset = resolver == null ? null : resolver.getResource(ROOT + "/" + k.substring(1, k.length() - 1));
                if (preset == null || k.substring(1, k.length() - 1).contains("/")) {
                    throw new BadParameter("unknown preset " + k);
                }
                ValueMap v = preset.getValueMap();
                for (String name : PRESET_PARAMS) {
                    String value = v.get(name, String.class);
                    if (value != null && !value.isEmpty()) {
                        p.put(name, value);
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!e.getKey().startsWith("$")) {
                p.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue() == null ? "" : e.getValue().trim());
            }
        }
        return planOf(p, srcWidth, srcHeight);
    }

    static Plan planOf(Map<String, String> p, long srcWidth, long srcHeight) throws BadParameter {
        List<String> steps = new ArrayList<>();

        // geometry on the source, in the order the reference applies them: crop, rotate, flip
        String crop = p.get("crop");
        String cropN = p.get("cropn");
        if (cropN != null && !cropN.isEmpty()) {
            double[] n = doubles(cropN, 4, "cropN");
            for (double d : n) {
                if (d < 0 || d > 1) {
                    throw new BadParameter("cropN values are fractions 0..1");
                }
            }
            long x = Math.round(n[0] * srcWidth);
            long y = Math.round(n[1] * srcHeight);
            long w = Math.max(1, Math.round(n[2] * srcWidth));
            long h = Math.max(1, Math.round(n[3] * srcHeight));
            steps.add("c" + x + "," + y + "," + w + "," + h);
        } else if (crop != null && !crop.isEmpty()) {
            long[] c = longs(crop, 4, "crop");
            if (c[2] < 1 || c[3] < 1 || c[0] < 0 || c[1] < 0) {
                throw new BadParameter("crop is x,y,w,h in pixels");
            }
            steps.add("c" + c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
        }
        String rotate = p.get("rotate");
        if (rotate != null && !rotate.isEmpty()) {
            long r = ((Math.round(number(rotate, "rotate")) % 360) + 360) % 360;
            if (r != 0) {
                if (r != 90 && r != 180 && r != 270) {
                    throw new BadParameter("rotate must be 0, 90, 180 or 270");
                }
                steps.add("r" + r);
            }
        }
        String flip = p.getOrDefault("flip", "").toLowerCase(Locale.ROOT);
        if (flip.contains("h")) {
            steps.add("h");
        }
        if (flip.contains("v")) {
            steps.add("v");
        }
        if (!flip.isEmpty() && !flip.matches("h|v|hv|vh")) {
            throw new BadParameter("flip must be h, v or hv");
        }

        // the box
        long wid = p.containsKey("wid") && !p.get("wid").isEmpty() ? Math.round(number(p.get("wid"), "wid")) : 0;
        long hei = p.containsKey("hei") && !p.get("hei").isEmpty() ? Math.round(number(p.get("hei"), "hei")) : 0;
        if (wid < 0 || hei < 0 || wid > MAX_SIDE || hei > MAX_SIDE) {
            throw new BadParameter("wid and hei must be 1 to " + MAX_SIDE);
        }
        String scl = p.get("scl");
        if (scl != null && !scl.isEmpty()) {
            double s = number(scl, "scl");
            if (s <= 0) {
                throw new BadParameter("scl must be positive");
            }
            if (wid == 0 && hei == 0) {
                wid = Math.max(1, Math.min(MAX_SIDE, Math.round(srcWidth / s)));
                hei = Math.max(1, Math.min(MAX_SIDE, Math.round(srcHeight / s)));
            }
        }
        String fit = p.getOrDefault("fit", "fit").toLowerCase(Locale.ROOT);
        String[] fitParts = fit.split(",");
        String mode = fitParts[0].trim();
        boolean upscale = fitParts.length > 1 && "1".equals(fitParts[1].trim());
        switch (mode) {
            case "fit":
            case "constrain":
            case "":
                break;
            case "hfit":
                hei = 0;
                break;
            case "vfit":
                wid = 0;
                break;
            case "crop":
                if (wid == 0 || hei == 0) {
                    throw new BadParameter("fit=crop needs wid and hei");
                }
                steps.add("fcrop");
                break;
            case "stretch":
                if (wid == 0 || hei == 0) {
                    throw new BadParameter("fit=stretch needs wid and hei");
                }
                steps.add("fstretch");
                break;
            default:
                throw new BadParameter("fit must be fit, constrain, crop, stretch, hfit or vfit");
        }
        if (upscale) {
            steps.add("u");
        }
        if (wid == 0 && hei == 0) {
            // the reference's default: the image as it is (bounded by what we serve)
            wid = MAX_SIDE;
            hei = MAX_SIDE;
        } else if (wid == 0) {
            wid = MAX_SIDE;
        } else if (hei == 0) {
            hei = MAX_SIDE;
        }

        // after the resize
        String usm = p.get("op_usm");
        if (usm != null && !usm.isEmpty()) {
            double[] u = doubles(usm, 3, "op_usm");
            steps.add(usmStep(u[0], u[1], u[2]));
        } else if (isOn(p.get("op_sharpen"))) {
            steps.add(usmStep(1.0, 1.0, 0));
        }
        String blur = p.get("op_blur");
        if (blur != null && !blur.isEmpty()) {
            double b = number(blur, "op_blur");
            if (b <= 0 || b > 100) {
                throw new BadParameter("op_blur is a radius 0..100");
            }
            steps.add("b" + Math.max(1, Math.round(b * 10)));
        }
        String brightness = p.get("op_brightness");
        if (brightness != null && !brightness.isEmpty()) {
            long v = Math.round(number(brightness, "op_brightness"));
            if (v < -100 || v > 100) {
                throw new BadParameter("op_brightness is -100..100");
            }
            if (v != 0) {
                steps.add("l" + signed(v));
            }
        }
        String contrast = p.get("op_contrast");
        if (contrast != null && !contrast.isEmpty()) {
            long v = Math.round(number(contrast, "op_contrast"));
            if (v < -100 || v > 100) {
                throw new BadParameter("op_contrast is -100..100");
            }
            if (v != 0) {
                steps.add("k" + signed(v));
            }
        }
        // looks: colour, then overlays, then the shape, then the background last
        String saturation = p.get("op_saturation");
        if (saturation != null && !saturation.isEmpty()) {
            long v = Math.round(number(saturation, "op_saturation"));
            if (v < -100 || v > 100) {
                throw new BadParameter("op_saturation is -100..100");
            }
            if (v != 0) {
                steps.add("a" + signed(v));
            }
        }
        if (isOn(p.get("op_grayscale"))) {
            steps.add("m");
        }
        if (isOn(p.get("op_sepia"))) {
            steps.add("p");
        }
        String overlay = p.get("op_overlay");
        if (overlay != null && !overlay.isEmpty()) {
            // RRGGBB,opacity
            String[] parts = overlay.split(",");
            if (parts.length != 2) {
                throw new BadParameter("op_overlay is RRGGBB,opacity");
            }
            long opacity = Math.round(number(parts[1], "op_overlay"));
            if (opacity < 0 || opacity > 100) {
                throw new BadParameter("op_overlay opacity is 0..100");
            }
            if (opacity > 0) {
                steps.add("o" + colour(parts[0], "op_overlay") + "," + opacity);
            }
        }
        String gradient = p.get("op_gradient");
        if (gradient != null && !gradient.isEmpty()) {
            // towards,RRGGBB,from,to — towards is b, t, l or r; from/to are opacities 0..100
            String[] parts = gradient.split(",");
            if (parts.length != 4) {
                throw new BadParameter("op_gradient is towards,RRGGBB,from,to");
            }
            String towards = parts[0].toLowerCase(Locale.ROOT);
            if (!towards.matches("[btlr]")) {
                throw new BadParameter("op_gradient towards is b, t, l or r");
            }
            long from = Math.round(number(parts[2], "op_gradient"));
            long to = Math.round(number(parts[3], "op_gradient"));
            if (from < 0 || from > 100 || to < 0 || to > 100) {
                throw new BadParameter("op_gradient opacities are 0..100");
            }
            if (from > 0 || to > 0) {
                steps.add("d" + towards + "," + colour(parts[1], "op_gradient") + "," + from + "," + to);
            }
        }
        String vignette = p.get("op_vignette");
        if (vignette != null && !vignette.isEmpty()) {
            long v = Math.round(number(vignette, "op_vignette"));
            if (v < 0 || v > 100) {
                throw new BadParameter("op_vignette is 0..100");
            }
            if (v > 0) {
                steps.add("w" + v);
            }
        }
        String radius = p.get("op_radius");
        if (radius != null && !radius.isEmpty()) {
            long v = Math.round(number(radius, "op_radius"));
            if (v < 0 || v > 4096) {
                throw new BadParameter("op_radius is 0..4096 pixels");
            }
            if (v > 0) {
                steps.add("e" + v);
            }
        }
        String border = p.get("op_border");
        if (border != null && !border.isEmpty()) {
            // width,RRGGBB
            String[] parts = border.split(",");
            if (parts.length != 2) {
                throw new BadParameter("op_border is width,RRGGBB");
            }
            long w = Math.round(number(parts[0], "op_border"));
            if (w < 0 || w > 1000) {
                throw new BadParameter("op_border width is 0..1000 pixels");
            }
            if (w > 0) {
                steps.add("x" + w + "," + colour(parts[1], "op_border"));
            }
        }
        String bgc = p.get("bgc");
        if (bgc != null && !bgc.isEmpty()) {
            steps.add("g" + colour(bgc, "bgc"));
        }

        // the format
        String fmt = p.getOrDefault("fmt", "jpeg").toLowerCase(Locale.ROOT);
        String format;
        switch (fmt) {
            case "jpeg":
            case "jpg":
                format = "jpeg";
                break;
            case "png":
            case "png-alpha":
            case "png8":
            case "png8-alpha":
                format = "png";
                break;
            case "webp":
            case "webp-alpha":
                format = "webp";
                break;
            default:
                throw new BadParameter("fmt must be jpeg, png, png-alpha, webp or webp-alpha");
        }
        int quality = DEFAULT_QUALITY;
        String qlt = p.get("qlt");
        if (qlt != null && !qlt.isEmpty()) {
            long q = Math.round(number(qlt.split(",")[0], "qlt"));
            if (q < 1 || q > 100) {
                throw new BadParameter("qlt is 1..100");
            }
            quality = (int) q;
        }
        if ("png".equals(format)) {
            quality = 0;
        }
        return new Plan((int) wid, (int) hei, format, quality, String.join("-", steps));
    }

    private static String usmStep(double amount, double radius, double threshold) throws BadParameter {
        if (amount < 0 || amount > 5 || radius <= 0 || radius > 250 || threshold < 0 || threshold > 255) {
            throw new BadParameter("op_usm is amount 0..5, radius 0..250, threshold 0..255");
        }
        return "s" + Math.round(amount * 100) + "," + Math.max(1, Math.round(radius * 10)) + "," + Math.round(threshold);
    }

    /** `RRGGBB` (a leading `#` allowed), lower-case. */
    private static String colour(String value, String name) throws BadParameter {
        String hex = value.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{6}")) {
            throw new BadParameter(name + " colour is RRGGBB");
        }
        return hex;
    }

    private static String signed(long v) {
        return v < 0 ? "n" + (-v) : String.valueOf(v);
    }

    private static boolean isOn(String v) {
        return v != null && (v.isEmpty() || "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v));
    }

    private static double number(String s, String name) throws BadParameter {
        try {
            double d = Double.parseDouble(s.trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new BadParameter(name + " must be a number");
        }
    }

    private static double[] doubles(String s, int n, String name) throws BadParameter {
        String[] parts = s.split(",");
        if (parts.length != n) {
            throw new BadParameter(name + " needs " + n + " values");
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = number(parts[i], name);
        }
        return out;
    }

    private static long[] longs(String s, int n, String name) throws BadParameter {
        double[] d = doubles(s, n, name);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.round(d[i]);
        }
        return out;
    }

    // --------------------------------------------------------------------------------- the URL

    /** The public path of an asset: {@code /is/image/wknd/en/surf} for {@code /content/dam/wknd/en/surf.jpg}. */
    static String publicPath(String assetPath) {
        if (assetPath == null || !assetPath.startsWith(DamItems.ROOT + "/")) {
            return null;
        }
        String rel = assetPath.substring(DamItems.ROOT.length());
        int slash = rel.lastIndexOf('/');
        int dot = rel.lastIndexOf('.');
        if (dot > slash + 1) {
            rel = rel.substring(0, dot);
        }
        return PREFIX + rel;
    }

    /**
     * The asset a public path names: with its extension, or the one asset in the folder whose
     * name without extension matches. {@code null} when there is none the resolver can read.
     */
    static Resource resolve(ResourceResolver resolver, String suffix) {
        if (suffix == null || suffix.isEmpty() || suffix.contains("..") || suffix.contains("//")) {
            return null;
        }
        String path = DamItems.ROOT + (suffix.startsWith("/") ? suffix : "/" + suffix);
        Resource exact = resolver.getResource(path);
        if (DamItems.isAsset(exact)) {
            return exact;
        }
        int slash = path.lastIndexOf('/');
        Resource folder = resolver.getResource(path.substring(0, slash));
        if (folder == null) {
            return null;
        }
        String stem = path.substring(slash + 1);
        for (Resource c : folder.getChildren()) {
            String n = c.getName();
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.substring(0, dot).equals(stem) && DamItems.isAsset(c)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------- presets

    static List<Map<String, Object>> presets(ResourceResolver resolver) {
        Resource root = resolver.getResource(ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (root != null) {
            for (Resource p : root.getChildren()) {
                if (!p.getName().startsWith("jcr:") && !p.getName().startsWith("rep:")) {
                    out.add(describe(p));
                }
            }
        }
        return out;
    }

    static Map<String, Object> describe(Resource preset) {
        ValueMap v = preset.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", preset.getName());
        m.put("title", v.get("jcr:title", preset.getName()));
        m.put("description", v.get("jcr:description", String.class));
        Map<String, Object> params = new LinkedHashMap<>();
        for (String name : PRESET_PARAMS) {
            String value = v.get(name, String.class);
            if (value != null && !value.isEmpty()) {
                params.put(name, value);
            }
        }
        m.put("params", params);
        m.put("query", query(params));
        return m;
    }

    /** {@code wid=800&fmt=webp}: what the preset expands to in a URL. */
    static String query(Map<String, Object> params) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        return q.toString();
    }

    /** Creates or replaces a preset; its parameters are checked by planning a 4000×3000 image with them. */
    static Map<String, Object> save(ResourceResolver resolver, String name, String title, String description, Map<String, String> params)
            throws DamApiServlet.ApiException, PersistenceException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new DamApiServlet.ApiException(400, "preset names use letters, digits, '.', '_' and '-'");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (String k : PRESET_PARAMS) {
            String v = params.get(k);
            if (v != null && !v.trim().isEmpty()) {
                clean.put(k, v.trim());
            }
        }
        if (clean.isEmpty()) {
            throw new DamApiServlet.ApiException(400, "a preset needs at least one parameter");
        }
        try {
            planOf(new LinkedHashMap<>(clean), 4000, 3000);
        } catch (BadParameter e) {
            throw new DamApiServlet.ApiException(400, e.getMessage());
        }
        Resource root = ensureRoot(resolver);
        Resource existing = root.getChild(name);
        if (existing != null) {
            resolver.delete(existing);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("jcr:title", title == null || title.trim().isEmpty() ? name : title.trim());
        if (description != null && !description.trim().isEmpty()) {
            props.put("jcr:description", description.trim());
        }
        props.putAll(clean);
        return describe(resolver.create(root, name, props));
    }

    static void delete(ResourceResolver resolver, String name) throws DamApiServlet.ApiException, PersistenceException {
        Resource p = name == null || name.contains("/") ? null : resolver.getResource(ROOT + "/" + name);
        if (p == null) {
            throw new DamApiServlet.ApiException(404, "no preset " + name);
        }
        resolver.delete(p);
    }

    private static Resource ensureRoot(ResourceResolver resolver) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : ROOT.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }

    /** The parameter names a request may carry, for the console's URL builder. */
    static Set<String> parameterNames() {
        return new LinkedHashSet<>(Arrays.asList("wid", "hei", "fmt", "qlt", "fit", "crop", "cropN", "rotate", "flip", "scl",
                "op_sharpen", "op_usm", "op_blur", "op_brightness", "op_contrast", "op_saturation", "op_grayscale", "op_sepia",
                "op_overlay", "op_gradient", "op_vignette", "op_radius", "op_border", "bgc"));
    }
}
