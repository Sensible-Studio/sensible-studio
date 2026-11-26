/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic Media Smart Crop. An <em>image profile</em> ({@value #ROOT}{@code /<name>}) lists
 * aspect ratios (name, width, height). A folder chooses a profile ({@value #PN_FOLDER} on its
 * {@code jcr:content}, inherited by subfolders); every image processed in it gets one crop per
 * ratio, computed by studio-dam around the picture's subject and stored under
 * {@code jcr:content/smartcrops/<name>} as fractions of the image (left, top, width, height),
 * plus the size of the image they refer to. An author can move or resize a crop in the console
 * ({@code manual=true}: a recompute leaves it alone unless asked to).
 *
 * <p>A crop is served as {@code /is/image/<asset>:<name>}; the other parameters apply to the
 * cropped image.</p>
 */
final class DamSmartCrop {

    static final String ROOT = "/conf/global/settings/dam/dm/imageprofiles";
    static final String PN_FOLDER = "studio:imageProfile";
    static final String NODE = "smartcrops";
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");
    static final int MAX_RATIOS = 20;

    private static final Logger LOG = LoggerFactory.getLogger(DamSmartCrop.class);

    private DamSmartCrop() {
    }

    // ------------------------------------------------------------------------------- profiles

    static List<Map<String, Object>> profiles(ResourceResolver resolver) {
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

    static Map<String, Object> describe(Resource profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", profile.getPath());
        m.put("name", profile.getName());
        m.put("title", profile.getValueMap().get("jcr:title", profile.getName()));
        m.put("ratios", ratios(profile));
        return m;
    }

    /** The ratios of a profile as maps with name, width, height (invalid ones left out). */
    static List<Map<String, Object>> ratios(Resource profile) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource rs = profile == null ? null : profile.getChild("ratios");
        if (rs != null) {
            for (Resource r : rs.getChildren()) {
                ValueMap v = r.getValueMap();
                String name = v.get("name", String.class);
                long w = v.get("width", 0L);
                long h = v.get("height", 0L);
                if (name != null && NAME.matcher(name).matches() && w > 0 && h > 0) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("width", w);
                    m.put("height", h);
                    out.add(m);
                }
            }
        }
        return out;
    }

    static Map<String, Object> save(ResourceResolver resolver, String name, String title, List<Object> ratios)
            throws DamApiServlet.ApiException, PersistenceException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new DamApiServlet.ApiException(400, "profile names use letters, digits, '.', '_' and '-'");
        }
        if (ratios.isEmpty() || ratios.size() > MAX_RATIOS) {
            throw new DamApiServlet.ApiException(400, "between 1 and " + MAX_RATIOS + " ratios");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object o : ratios) {
            if (!(o instanceof Map)) {
                throw new DamApiServlet.ApiException(400, "ratios must be objects");
            }
            Map<?, ?> r = (Map<?, ?>) o;
            String rn = String.valueOf(r.get("name")).trim();
            long w = num(r.get("width"));
            long h = num(r.get("height"));
            if (!NAME.matcher(rn).matches()) {
                throw new DamApiServlet.ApiException(400, "invalid crop name: " + rn);
            }
            if (w < 1 || h < 1 || w > 10_000 || h > 10_000) {
                throw new DamApiServlet.ApiException(400, rn + ": width and height are 1 to 10000");
            }
            if (!names.add(rn)) {
                throw new DamApiServlet.ApiException(400, "crop " + rn + " listed twice");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jcr:primaryType", "nt:unstructured");
            m.put("name", rn);
            m.put("width", w);
            m.put("height", h);
            clean.add(m);
        }
        Resource root = ensureRoot(resolver);
        Resource existing = root.getChild(name);
        if (existing != null) {
            resolver.delete(existing);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("jcr:title", title == null || title.trim().isEmpty() ? name : title.trim());
        Resource profile = resolver.create(root, name, props);
        Resource rs = resolver.create(profile, "ratios", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        int i = 0;
        for (Map<String, Object> r : clean) {
            resolver.create(rs, "r" + i++, r);
        }
        return describe(profile);
    }

    static void delete(ResourceResolver resolver, String name) throws DamApiServlet.ApiException, PersistenceException {
        Resource p = name == null || name.contains("/") ? null : resolver.getResource(ROOT + "/" + name);
        if (p == null) {
            throw new DamApiServlet.ApiException(404, "no image profile " + name);
        }
        resolver.delete(p);
    }

    private static long num(Object o) throws DamApiServlet.ApiException {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new DamApiServlet.ApiException(400, "not a number: " + o);
        }
    }

    private static Resource ensureRoot(ResourceResolver resolver) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : ROOT.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }

    // -------------------------------------------------------------------------------- folders

    /** Sets a folder's image profile (a path below {@link #ROOT}); null or empty removes it. */
    static void assign(Resource folder, String profilePath) throws DamApiServlet.ApiException, PersistenceException {
        if (profilePath != null && !profilePath.isEmpty()
                && (!profilePath.startsWith(ROOT + "/") || folder.getResourceResolver().getResource(profilePath) == null)) {
            throw new DamApiServlet.ApiException(404, "no image profile " + profilePath);
        }
        Resource c = folder.getChild("jcr:content");
        if (c == null) {
            c = folder.getResourceResolver().create(folder, "jcr:content", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap m = c.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + folder.getPath());
        }
        if (profilePath == null || profilePath.isEmpty()) {
            m.remove(PN_FOLDER);
        } else {
            m.put(PN_FOLDER, profilePath);
        }
    }

    /** The profile that applies to an asset (its folder's or the nearest ancestor's), or null. */
    static Resource profileFor(Resource asset) {
        for (Resource f = asset == null ? null : asset.getParent(); f != null && DamItems.inDam(f.getPath()); f = f.getParent()) {
            Resource c = f.getChild("jcr:content");
            String p = c == null ? null : c.getValueMap().get(PN_FOLDER, String.class);
            if (p != null && !p.isEmpty()) {
                return p.startsWith(ROOT + "/") ? asset.getResourceResolver().getResource(p) : null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------- crops

    /**
     * Computes the asset's crops for the profile that applies (none: existing crops are
     * removed) from its original. Manual crops stay unless {@code force}. Returns whether
     * anything changed; the caller commits.
     */
    static boolean compute(ResourceResolver resolver, DamClient client, Resource asset, boolean force) throws IOException, PersistenceException {
        Resource content = asset.getChild("jcr:content");
        if (content == null || !isImage(asset)) {
            return false;
        }
        Resource profile = profileFor(asset);
        List<Map<String, Object>> ratios = ratios(profile);
        Resource node = content.getChild(NODE);
        if (ratios.isEmpty()) {
            if (node != null) {
                resolver.delete(node);
                return true;
            }
            return false;
        }
        String blobId = OriginalBlobs.of(asset, DynamicMediaServlet.lastModified(asset, asset.adaptTo(com.day.cq.dam.api.Asset.class)), client);
        if (blobId == null) {
            return false;
        }
        Map<String, Object> keep = new LinkedHashMap<>();
        List<Map<String, Object>> ask = new ArrayList<>();
        for (Map<String, Object> r : ratios) {
            String name = (String) r.get("name");
            Resource existing = node == null ? null : node.getChild(name);
            boolean manual = existing != null && existing.getValueMap().get("manual", false);
            boolean sameRatio = existing != null && existing.getValueMap().get("ratioWidth", 0L) == (long) r.get("width")
                    && existing.getValueMap().get("ratioHeight", 0L) == (long) r.get("height");
            if (manual && sameRatio && !force) {
                keep.put(name, Boolean.TRUE);
                continue;
            }
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("name", name);
            q.put("width", r.get("width"));
            q.put("height", r.get("height"));
            ask.add(q);
        }
        Map<String, Object> result = ask.isEmpty() ? Collections.emptyMap() : client.smartCrop(blobId, ask);
        if (node == null) {
            node = resolver.create(content, NODE, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        // crops of ratios no longer in the profile go
        Set<String> wanted = new HashSet<>();
        for (Map<String, Object> r : ratios) {
            wanted.add((String) r.get("name"));
        }
        List<Resource> children = new ArrayList<>();
        node.getChildren().forEach(children::add);
        for (Resource c : children) {
            if (!wanted.contains(c.getName())) {
                resolver.delete(c);
            }
        }
        Object crops = result.get("crops");
        if (crops instanceof List) {
            long iw = number(result.get("width"));
            long ih = number(result.get("height"));
            for (Object o : (List<?>) crops) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> c = (Map<?, ?>) o;
                String name = String.valueOf(c.get("name"));
                Map<String, Object> r = ratioNamed(ratios, name);
                if (r == null) {
                    continue;
                }
                Object n = c.get("normalized");
                double[] norm = new double[4];
                if (n instanceof List && ((List<?>) n).size() == 4) {
                    for (int i = 0; i < 4; i++) {
                        norm[i] = ((Number) ((List<?>) n).get(i)).doubleValue();
                    }
                } else {
                    norm = new double[] {number(c.get("x")) / (double) iw, number(c.get("y")) / (double) ih,
                            number(c.get("width")) / (double) iw, number(c.get("height")) / (double) ih};
                }
                write(resolver, node, name, r, norm, iw, ih, false);
            }
        }
        return true;
    }

    private static Map<String, Object> ratioNamed(List<Map<String, Object>> ratios, String name) {
        for (Map<String, Object> r : ratios) {
            if (name.equals(r.get("name"))) {
                return r;
            }
        }
        return null;
    }

    private static void write(ResourceResolver resolver, Resource node, String name, Map<String, Object> ratio, double[] norm, long iw,
            long ih, boolean manual) throws PersistenceException {
        Resource existing = node.getChild(name);
        if (existing != null) {
            resolver.delete(existing);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jcr:primaryType", "nt:unstructured");
        p.put("left", norm[0]);
        p.put("top", norm[1]);
        p.put("width", norm[2]);
        p.put("height", norm[3]);
        p.put("imageWidth", iw);
        p.put("imageHeight", ih);
        p.put("ratioWidth", ratio.get("width"));
        p.put("ratioHeight", ratio.get("height"));
        p.put("manual", manual);
        p.put("jcr:lastModified", Calendar.getInstance());
        resolver.create(node, name, p);
    }

    /**
     * An author's crop: fractions of the image, kept to the ratio's proportions (the height
     * follows the width) and inside the image.
     */
    static Map<String, Object> adjust(ResourceResolver resolver, Resource asset, String name, double left, double top, double width)
            throws DamApiServlet.ApiException, PersistenceException {
        Resource content = asset.getChild("jcr:content");
        Resource node = content == null ? null : content.getChild(NODE);
        Resource existing = node == null || name == null ? null : node.getChild(name);
        if (existing == null) {
            throw new DamApiServlet.ApiException(404, "no smart crop " + name);
        }
        ValueMap v = existing.getValueMap();
        long iw = v.get("imageWidth", 0L);
        long ih = v.get("imageHeight", 0L);
        long rw = v.get("ratioWidth", 1L);
        long rh = v.get("ratioHeight", 1L);
        if (iw <= 0 || ih <= 0 || !(width > 0) || width > 1 || left < 0 || top < 0 || Double.isNaN(left) || Double.isNaN(top)) {
            throw new DamApiServlet.ApiException(400, "left, top and width are fractions of the image");
        }
        // the height that keeps the ratio, in fractions: h = w * iw/ih * rh/rw
        double height = width * iw / ih * rh / rw;
        if (height > 1) {
            height = 1;
            width = height * ih / iw * rw / rh;
        }
        if (left + width > 1) {
            left = 1 - width;
        }
        if (top + height > 1) {
            top = 1 - height;
        }
        Map<String, Object> ratio = new LinkedHashMap<>();
        ratio.put("width", rw);
        ratio.put("height", rh);
        write(resolver, node, name, ratio, new double[] {left, top, width, height}, iw, ih, true);
        return describeCrop(node.getChild(name), asset);
    }

    /** The asset's crops for the console: fractions, pixels and the URL of each. */
    static List<Map<String, Object>> crops(Resource asset) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource node = asset == null ? null : asset.getChild("jcr:content/" + NODE);
        if (node != null) {
            for (Resource c : node.getChildren()) {
                out.add(describeCrop(c, asset));
            }
        }
        return out;
    }

    static Map<String, Object> describeCrop(Resource c, Resource asset) {
        ValueMap v = c.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("left", v.get("left", 0.0));
        m.put("top", v.get("top", 0.0));
        m.put("width", v.get("width", 1.0));
        m.put("height", v.get("height", 1.0));
        m.put("imageWidth", v.get("imageWidth", 0L));
        m.put("imageHeight", v.get("imageHeight", 0L));
        m.put("ratioWidth", v.get("ratioWidth", 0L));
        m.put("ratioHeight", v.get("ratioHeight", 0L));
        m.put("manual", v.get("manual", false));
        String base = DynamicMedia.publicPath(asset.getPath());
        m.put("url", base == null ? null : base + ":" + c.getName());
        return m;
    }

    /** The crop step for the delivery transform ({@code c<x>,<y>,<w>,<h>} in pixels), or null. */
    static String cropStep(Resource asset, String name) {
        Resource c = asset == null || name == null ? null : asset.getChild("jcr:content/" + NODE + "/" + name);
        if (c == null) {
            return null;
        }
        ValueMap v = c.getValueMap();
        long iw = v.get("imageWidth", 0L);
        long ih = v.get("imageHeight", 0L);
        if (iw <= 0 || ih <= 0) {
            return null;
        }
        long x = Math.round(v.get("left", 0.0) * iw);
        long y = Math.round(v.get("top", 0.0) * ih);
        long w = Math.max(1, Math.round(v.get("width", 1.0) * iw));
        long h = Math.max(1, Math.round(v.get("height", 1.0) * ih));
        return "c" + x + "," + y + "," + w + "," + h;
    }

    static boolean isImage(Resource asset) {
        com.day.cq.dam.api.Asset a = asset == null ? null : asset.adaptTo(com.day.cq.dam.api.Asset.class);
        String mime = a == null ? null : a.getMimeType();
        return mime != null && mime.startsWith("image/") && !DamItems.isContentFragment(asset);
    }

    private static long number(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** Logs and swallows: smart crops never fail the processing. */
    static void after(ResourceResolver resolver, DamClient client, Resource asset) {
        try {
            if (compute(resolver, client, asset, false)) {
                resolver.commit();
            }
        } catch (IOException | RuntimeException e) {
            resolver.revert();
            LOG.info("No smart crops for {}: {}", asset.getPath(), e.getMessage());
        }
    }
}
