/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * The watermark (AEM's Assets watermark setting): one picture from Media drawn on the renditions
 * of a Processing Profile that ask for it, at a position, an opacity and a size (a share of the
 * rendition's width). Kept at {@value #PATH}:
 * {@code image} (a Media path), {@code position}, {@code opacity} (1–100), {@code size} (5–100).
 *
 * <p>The picture's own original is drawn (a PNG with transparency works best). Renditions made
 * before a change keep the old mark until the file is processed again.</p>
 */
final class DamWatermark {

    static final String PATH = "/conf/global/settings/dam/watermark";
    static final List<String> POSITIONS = Collections.unmodifiableList(
            Arrays.asList("center", "top-left", "top-right", "bottom-left", "bottom-right", "tile"));
    static final long DEFAULT_OPACITY = 50;
    static final long DEFAULT_SIZE = 25;
    private static final List<String> IMAGE_TYPES = Arrays.asList("image/png", "image/jpeg", "image/webp");

    private DamWatermark() {
    }

    /** What the console shows: the setting, and whether its picture is there. */
    static Map<String, Object> describe(ResourceResolver resolver) {
        Resource r = resolver.getResource(PATH);
        ValueMap v = r == null ? ValueMap.EMPTY : r.getValueMap();
        String image = v.get("image", String.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("image", image);
        m.put("position", position(v.get("position", String.class)));
        m.put("opacity", clamp(v.get("opacity", DEFAULT_OPACITY), 1, 100));
        m.put("size", clamp(v.get("size", DEFAULT_SIZE), 5, 100));
        Resource original = image == null ? null : picture(resolver, image);
        m.put("ready", original != null);
        m.put("thumbnail", original == null ? null : DamItems.asset(original.getParent().getParent().getParent()).get("thumbnail"));
        return m;
    }

    /**
     * Saves the setting; an empty {@code image} removes the watermark.
     *
     * @throws DamApiServlet.ApiException 400 for a value out of range or a file that is not a picture
     */
    static Map<String, Object> save(ResourceResolver resolver, String image, String position, String opacity, String size)
            throws DamApiServlet.ApiException, PersistenceException {
        String img = image == null ? "" : image.trim();
        if (!img.isEmpty() && picture(resolver, img) == null) {
            throw new DamApiServlet.ApiException(400, "The watermark must be a PNG, JPEG or WebP picture in Media: " + img);
        }
        String pos = position == null || position.isEmpty() ? "center" : position.toLowerCase(Locale.ROOT);
        if (!POSITIONS.contains(pos)) {
            throw new DamApiServlet.ApiException(400, "position must be one of " + POSITIONS);
        }
        long op = number(opacity, DEFAULT_OPACITY);
        long sz = number(size, DEFAULT_SIZE);
        if (op < 1 || op > 100) {
            throw new DamApiServlet.ApiException(400, "opacity must be 1 to 100");
        }
        if (sz < 5 || sz > 100) {
            throw new DamApiServlet.ApiException(400, "size must be 5 to 100 (% of the width)");
        }
        Resource node = ensure(resolver);
        ModifiableValueMap mv = node.adaptTo(ModifiableValueMap.class);
        if (mv == null) {
            throw new DamApiServlet.ApiException(403, "The watermark setting cannot be changed here");
        }
        if (img.isEmpty()) {
            mv.remove("image");
        } else {
            mv.put("image", img);
        }
        mv.put("position", pos);
        mv.put("opacity", op);
        mv.put("size", sz);
        return describe(resolver);
    }

    /**
     * For a processing whose profile asks for the watermark: the picture uploaded to studio-dam and
     * how to draw it, or null when none is set (the renditions are then made without one).
     */
    static Map<String, Object> forProcessing(ResourceResolver resolver, DamClient client) throws IOException {
        Resource r = resolver.getResource(PATH);
        if (r == null) {
            return null;
        }
        ValueMap v = r.getValueMap();
        String image = v.get("image", String.class);
        Resource original = image == null ? null : picture(resolver, image);
        if (original == null) {
            return null;
        }
        String blobId;
        try (InputStream in = streamOf(original)) {
            if (in == null) {
                return null;
            }
            blobId = client.putBlob(in);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blobId", blobId);
        m.put("position", position(v.get("position", String.class)));
        m.put("opacity", clamp(v.get("opacity", DEFAULT_OPACITY), 1, 100));
        m.put("size", clamp(v.get("size", DEFAULT_SIZE), 5, 100));
        return m;
    }

    /**
     * Draws the watermark on a picture, as set (position, opacity, size as a share of the width), with Java's
     * own imaging: for the preview of a licensed file (DRM). Null when no watermark is set or its picture
     * cannot be read here (WebP).
     */
    static java.awt.image.BufferedImage draw(ResourceResolver resolver, java.awt.image.BufferedImage picture) throws IOException {
        Resource r = resolver.getResource(PATH);
        if (r == null || picture == null) {
            return null;
        }
        ValueMap v = r.getValueMap();
        String image = v.get("image", String.class);
        Resource original = image == null ? null : picture(resolver, image);
        if (original == null) {
            return null;
        }
        java.awt.image.BufferedImage mark;
        try (InputStream in = streamOf(original)) {
            mark = in == null ? null : javax.imageio.ImageIO.read(in);
        }
        if (mark == null) {
            return null;
        }
        return draw(picture, mark, position(v.get("position", String.class)),
                clamp(v.get("opacity", DEFAULT_OPACITY), 1, 100), clamp(v.get("size", DEFAULT_SIZE), 5, 100));
    }

    static java.awt.image.BufferedImage draw(java.awt.image.BufferedImage picture, java.awt.image.BufferedImage mark,
            String position, long opacity, long size) {
        int w = picture.getWidth();
        int h = picture.getHeight();
        boolean alpha = picture.getColorModel().hasAlpha();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                alpha ? java.awt.image.BufferedImage.TYPE_INT_ARGB : java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.drawImage(picture, 0, 0, null);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacity / 100f));
            int mw = Math.max(1, (int) Math.round(w * size / 100.0));
            int mh = Math.max(1, (int) Math.round(mw * (double) mark.getHeight() / mark.getWidth()));
            int pad = Math.max(2, w / 50);
            if ("tile".equals(position)) {
                for (int y = pad; y < h; y += mh + pad * 2) {
                    for (int x = pad; x < w; x += mw + pad * 2) {
                        g.drawImage(mark, x, y, mw, mh, null);
                    }
                }
            } else {
                int x = position.endsWith("left") ? pad : position.endsWith("right") ? w - mw - pad : (w - mw) / 2;
                int y = position.startsWith("top") ? pad : position.startsWith("bottom") ? h - mh - pad : (h - mh) / 2;
                g.drawImage(mark, x, y, mw, mh, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    static boolean wanted(List<Map<String, Object>> extra) {
        for (Map<String, Object> e : extra) {
            if (Boolean.TRUE.equals(e.get(DamProfiles.PN_WATERMARK))) {
                return true;
            }
        }
        return false;
    }

    /** The original of a picture in Media, or null when the path is not one. */
    private static Resource picture(ResourceResolver resolver, String path) {
        if (!DamItems.inDam(path)) {
            return null;
        }
        Resource asset = resolver.getResource(path);
        if (asset == null || !"dam:Asset".equals(asset.getValueMap().get("jcr:primaryType", String.class))) {
            return null;
        }
        Resource md = asset.getChild("jcr:content/metadata");
        String mime = md == null ? null : md.getValueMap().get("dam:MIMEtype", md.getValueMap().get("dc:format", String.class));
        Resource original = asset.getChild("jcr:content/renditions/original");
        if (original == null) {
            return null;
        }
        if (mime == null) {
            Resource c = original.getChild("jcr:content");
            mime = c == null ? null : c.getValueMap().get("jcr:mimeType", String.class);
        }
        return mime != null && IMAGE_TYPES.contains(mime.toLowerCase(Locale.ROOT)) ? original : null;
    }

    private static InputStream streamOf(Resource original) {
        InputStream in = original.adaptTo(InputStream.class);
        if (in == null) {
            Resource c = original.getChild("jcr:content");
            in = c == null ? null : c.adaptTo(InputStream.class);
        }
        return in;
    }

    private static String position(String p) {
        return p != null && POSITIONS.contains(p) ? p : "center";
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long number(String s, long dflt) throws DamApiServlet.ApiException {
        if (s == null || s.trim().isEmpty()) {
            return dflt;
        }
        try {
            return Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            throw new DamApiServlet.ApiException(400, "not a number: " + s);
        }
    }

    private static Resource ensure(ResourceResolver resolver) throws PersistenceException {
        Resource r = resolver.getResource("/");
        String[] segs = PATH.substring(1).split("/");
        for (int i = 0; i < segs.length; i++) {
            Resource c = r.getChild(segs[i]);
            if (c == null) {
                String type = i == segs.length - 1 ? "nt:unstructured" : "sling:Folder";
                c = resolver.create(r, segs[i], Collections.singletonMap("jcr:primaryType", type));
            }
            r = c;
        }
        return r;
    }
}
