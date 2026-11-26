/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Color Tagging, as AEM's color tags: the colors a picture is mostly made of, by name, each with its share of the
 * picture. Found when a file is processed, from its thumbnail (64 × 64 pixels are enough), here in Java: no model
 * is needed.
 *
 * <pre>
 * asset jcr:content/metadata   studio:colors (names, most of the picture first), studio:colorShares (0..1),
 *                              studio:colorsFound (when)
 * </pre>
 * The names are a fixed set ({@link #NAMES}), so the search can filter by them ({@code meta=studio:colors=blue}).
 */
final class DamColors {

    static final String PN_COLORS = "studio:colors";
    static final String PN_SHARES = "studio:colorShares";
    static final String PN_FOUND = "studio:colorsFound";

    /** The color names, in the order a picker shows them. */
    static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "beige", "white", "gray", "black"));

    /** A color takes at least this share of the picture to be named. */
    static final double MIN_SHARE = 0.05;
    static final int MAX_COLORS = 5;
    private static final int SIDE = 64;
    /** Files colored at most in one request. */
    static final int MAX_BATCH = 2000;

    private DamColors() {
    }

    /** The name of one color. */
    static String name(int r, int g, int b) {
        double rf = r / 255.0;
        double gf = g / 255.0;
        double bf = b / 255.0;
        double max = Math.max(rf, Math.max(gf, bf));
        double min = Math.min(rf, Math.min(gf, bf));
        double l = (max + min) / 2;
        double d = max - min;
        double s = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
        double h;
        if (d == 0) {
            h = 0;
        } else if (max == rf) {
            h = 60 * (((gf - bf) / d) % 6);
        } else if (max == gf) {
            h = 60 * ((bf - rf) / d + 2);
        } else {
            h = 60 * ((rf - gf) / d + 4);
        }
        if (h < 0) {
            h += 360;
        }
        if (l < 0.12) {
            return "black";
        }
        if (l > 0.93) {
            return "white";
        }
        if (s < 0.15 || d < 0.08) {
            return l > 0.8 ? "white" : l < 0.22 ? "black" : "gray";
        }
        if (h >= 15 && h < 50) {
            if (l < 0.42) {
                return "brown";
            }
            if (s < 0.55 && l > 0.68) {
                return "beige";
            }
        }
        if (h < 15 || h >= 345) {
            return l > 0.75 ? "pink" : l < 0.3 ? "brown" : "red";
        }
        if (h < 40) {
            return "orange";
        }
        if (h < 70) {
            return l < 0.3 ? "brown" : "yellow";
        }
        if (h < 160) {
            return "green";
        }
        if (h < 195) {
            return "teal";
        }
        if (h < 255) {
            return "blue";
        }
        if (h < 290) {
            return "purple";
        }
        return "pink";
    }

    /** The colors of a picture, most of it first: {name, share} with a share of at least {@link #MIN_SHARE}. */
    static List<Object[]> of(BufferedImage image) {
        BufferedImage small = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, SIDE, SIDE, null);
        } finally {
            g.dispose();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                int argb = small.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    continue; // transparent: not part of the picture
                }
                counts.merge(name((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff), 1, Integer::sum);
                total++;
            }
        }
        List<Object[]> out = new ArrayList<>();
        if (total == 0) {
            return out;
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double share = e.getValue() / (double) total;
            if (share >= MIN_SHARE) {
                out.add(new Object[] {e.getKey(), Math.round(share * 100) / 100.0});
            }
        }
        out.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        return out.size() > MAX_COLORS ? new ArrayList<>(out.subList(0, MAX_COLORS)) : out;
    }

    /** The picture colors are read from: the thumbnail (small and always PNG), else what Smart Tags look at. */
    static Resource source(Resource asset) {
        if (DamSets.isSet(asset) || DamItems.isContentFragment(asset)) {
            return null;
        }
        Resource thumb = asset.getChild("jcr:content/renditions/" + DamItems.THUMBNAIL);
        return thumb != null ? thumb : DamSmartTags.source(asset);
    }

    /**
     * Finds and stores the colors of one asset (not saved: the caller commits). False when it is no picture or its
     * picture cannot be read.
     */
    static boolean tag(Resource asset) throws PersistenceException {
        if (!DamItems.isAsset(asset)) {
            return false;
        }
        Resource src = source(asset);
        if (src == null) {
            return false;
        }
        BufferedImage image;
        try (InputStream in = stream(src)) {
            image = in == null ? null : ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null) {
            return false;
        }
        write(asset, of(image));
        return true;
    }

    static void write(Resource asset, List<Object[]> colors) throws PersistenceException {
        Resource content = asset.getChild("jcr:content");
        Resource mdRes = content == null ? null : content.getChild("metadata");
        if (mdRes == null && content != null) {
            mdRes = asset.getResourceResolver().create(content, "metadata", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap md = mdRes == null ? null : mdRes.adaptTo(ModifiableValueMap.class);
        if (md == null) {
            throw new PersistenceException("cannot write the metadata of " + asset.getPath());
        }
        md.put(PN_COLORS, colors.stream().map(c -> (String) c[0]).toArray(String[]::new));
        md.put(PN_SHARES, colors.stream().map(c -> (Double) c[1]).toArray(Double[]::new));
        md.put(PN_FOUND, Calendar.getInstance());
    }

    /** The colors stored on an asset, as the console shows them; null when none were found yet. */
    static List<Map<String, Object>> describe(Resource asset) {
        Resource md = asset.getChild("jcr:content/metadata");
        if (md == null) {
            return null;
        }
        ValueMap v = md.getValueMap();
        String[] names = v.get(PN_COLORS, String[].class);
        if (names == null) {
            return null;
        }
        Double[] shares = v.get(PN_SHARES, new Double[0]);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", names[i]);
            m.put("share", i < shares.length ? shares[i] : 0.0);
            out.add(m);
        }
        return out;
    }

    /**
     * Colors the files below a folder (or the files named) that have none yet, or all of them {@code again}; at most
     * {@link #MAX_BATCH}. Saved every 100 files.
     */
    static Map<String, Object> tagAll(ResourceResolver resolver, List<Resource> roots, boolean again) throws PersistenceException {
        int tagged = 0;
        int skipped = 0;
        boolean more = false;
        Deque<Resource> queue = new ArrayDeque<>(roots);
        outer:
        while (!queue.isEmpty()) {
            Resource r = queue.poll();
            List<Resource> items = new ArrayList<>();
            if (DamItems.isAsset(r)) {
                items.add(r);
            } else {
                r.getChildren().forEach(items::add);
            }
            for (Resource c : items) {
                if (DamItems.isFolder(c)) {
                    queue.add(c);
                } else if (DamItems.isAsset(c)) {
                    Resource md = c.getChild("jcr:content/metadata");
                    if (!again && md != null && md.getValueMap().containsKey(PN_COLORS)) {
                        continue;
                    }
                    if (tagged >= MAX_BATCH) {
                        more = true;
                        break outer;
                    }
                    if (tag(c)) {
                        tagged++;
                        if (tagged % 100 == 0) {
                            resolver.commit();
                        }
                    } else {
                        skipped++;
                    }
                }
            }
        }
        resolver.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tagged", tagged);
        out.put("skipped", skipped);
        out.put("more", more);
        return out;
    }

    private static InputStream stream(Resource rendition) {
        InputStream in = rendition.adaptTo(InputStream.class);
        if (in == null && rendition.getChild("jcr:content") != null) {
            in = rendition.getChild("jcr:content").adaptTo(InputStream.class);
        }
        return in;
    }
}
