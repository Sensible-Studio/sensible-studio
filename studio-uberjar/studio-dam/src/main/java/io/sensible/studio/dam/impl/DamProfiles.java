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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Processing Profiles: renditions of your own (name, box, format, quality) that every asset of
 * a folder gets besides the standard ones, as in AEM as a Cloud Service.
 *
 * <pre>
 * /conf/global/settings/dam/processing/&lt;profile&gt;   jcr:title
 *     renditions/&lt;n&gt;   name, width, height, format (jpeg|png|webp), quality
 * &lt;folder&gt;/jcr:content   dam:processingProfile = &lt;profile path&gt;   (subfolders inherit it)
 * &lt;asset&gt;/jcr:content    dam:profileRenditions = names written by the last processing
 * </pre>
 */
final class DamProfiles {

    static final String ROOT = "/conf/global/settings/dam/processing";
    static final String PN_PROFILE = "dam:processingProfile";
    static final String PN_PRODUCED = "dam:profileRenditions";
    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}[a-z0-9]$|^[a-z0-9]$");
    static final Set<String> FORMATS = new HashSet<>(Arrays.asList("jpeg", "png", "webp"));
    /** Renditions of videos, made by ffmpeg next to studio-dam: {@code height} is the most lines. */
    static final Set<String> VIDEO_FORMATS = new HashSet<>(Arrays.asList("mp4", "webm"));
    static final long MIN_VIDEO_HEIGHT = 144;
    static final long MAX_VIDEO_HEIGHT = 2160;
    static final int MAX_RENDITIONS = 20;
    /** On an image rendition: the watermark of {@link DamWatermark} is drawn on it. */
    static final String PN_WATERMARK = "watermark";
    static final int MAX_SIDE = 8192;

    private DamProfiles() {
    }

    /** One rendition of a profile, as sent to studio-dam ({@code extra} of /process). */
    static Map<String, Object> rendition(String name, long width, long height, String format, long quality) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("width", width);
        m.put("height", height);
        m.put("format", format);
        m.put("quality", quality);
        return m;
    }

    /** The profile that applies to an asset: its folder's, or the nearest ancestor's. */
    static Resource profileFor(Resource asset) {
        for (Resource f = asset.getParent(); f != null && DamItems.inDam(f.getPath()); f = f.getParent()) {
            Resource c = f.getChild("jcr:content");
            String p = c == null ? null : c.getValueMap().get(PN_PROFILE, String.class);
            if (p != null && !p.isEmpty()) {
                // an empty value would stop inheritance; a missing profile counts as none
                return p.startsWith(ROOT + "/") ? asset.getResourceResolver().getResource(p) : null;
            }
        }
        return null;
    }

    /** The renditions of a profile, as maps for studio-dam; invalid entries are left out. */
    static List<Map<String, Object>> renditions(Resource profile) {
        if (profile == null) {
            return Collections.emptyList();
        }
        Resource rs = profile.getChild("renditions");
        List<Map<String, Object>> out = new ArrayList<>();
        if (rs == null) {
            return out;
        }
        for (Resource r : rs.getChildren()) {
            ValueMap v = r.getValueMap();
            String name = v.get("name", String.class);
            String format = v.get("format", "jpeg");
            long w = v.get("width", 0L);
            long h = v.get("height", 0L);
            if (name != null && NAME.matcher(name).matches() && !reserved(name) && FORMATS.contains(format) && w > 0 && h > 0
                    && w <= MAX_SIDE && h <= MAX_SIDE && out.size() < MAX_RENDITIONS) {
                Map<String, Object> m = rendition(name, w, h, format, Math.max(1, Math.min(100, v.get("quality", 80L))));
                if (v.get(PN_WATERMARK, false)) {
                    m.put(PN_WATERMARK, true);
                }
                out.add(m);
            }
        }
        return out;
    }

    /** The video renditions of a profile (mp4, webm): name, height and format; invalid entries are left out. */
    static List<Map<String, Object>> videoRenditions(Resource profile) {
        Resource rs = profile == null ? null : profile.getChild("renditions");
        List<Map<String, Object>> out = new ArrayList<>();
        if (rs == null) {
            return out;
        }
        for (Resource r : rs.getChildren()) {
            ValueMap v = r.getValueMap();
            String name = v.get("name", String.class);
            String format = v.get("format", "jpeg");
            long h = v.get("height", 0L);
            if (name != null && NAME.matcher(name).matches() && !reserved(name) && VIDEO_FORMATS.contains(format)
                    && h >= MIN_VIDEO_HEIGHT && h <= MAX_VIDEO_HEIGHT && out.size() < MAX_RENDITIONS) {
                out.add(rendition(name, v.get("width", 0L), h, format, 0));
            }
        }
        return out;
    }

    static boolean reserved(String name) {
        return "original".equals(name) || name.startsWith("cq5dam.");
    }

    /** {@code banner} + {@code webp} → {@code banner.webp}, the node name studio-dam gives it. */
    static String fileName(Map<String, Object> r) {
        return r.get("name") + "." + r.get("format");
    }

    // ----------------------------------------------------------------------------- administration

    static List<Map<String, Object>> list(ResourceResolver resolver) {
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
        List<Map<String, Object>> all = new ArrayList<>(renditions(profile));
        all.addAll(videoRenditions(profile));
        m.put("renditions", all);
        return m;
    }

    /**
     * Creates or replaces a profile. {@code renditions} come from the console as maps with
     * name, width, height, format and quality; everything is checked here.
     */
    static Map<String, Object> save(ResourceResolver resolver, String name, String title, List<Object> renditions)
            throws DamApiServlet.ApiException, PersistenceException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new DamApiServlet.ApiException(400, "profile names use a-z, 0-9, '.', '_' and '-'");
        }
        if (renditions.size() > MAX_RENDITIONS) {
            throw new DamApiServlet.ApiException(400, "at most " + MAX_RENDITIONS + " renditions");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object o : renditions) {
            if (!(o instanceof Map)) {
                throw new DamApiServlet.ApiException(400, "renditions must be objects");
            }
            Map<?, ?> r = (Map<?, ?>) o;
            String rn = String.valueOf(r.get("name")).trim();
            String format = r.get("format") == null ? "jpeg" : String.valueOf(r.get("format"));
            long w = num(r.get("width"));
            long h = num(r.get("height"));
            long q = r.get("quality") == null ? 80 : num(r.get("quality"));
            if (!NAME.matcher(rn).matches() || reserved(rn)) {
                throw new DamApiServlet.ApiException(400, "invalid rendition name: " + rn);
            }
            boolean video = VIDEO_FORMATS.contains(format);
            if (!FORMATS.contains(format) && !video) {
                throw new DamApiServlet.ApiException(400, "format must be jpeg, png, webp, mp4 or webm");
            }
            if (video) {
                // a video keeps its proportions: only the most lines count
                if (h < MIN_VIDEO_HEIGHT || h > MAX_VIDEO_HEIGHT) {
                    throw new DamApiServlet.ApiException(400, rn + ": a video's height must be " + MIN_VIDEO_HEIGHT + " to " + MAX_VIDEO_HEIGHT);
                }
                if (!names.add(rn + "." + format)) {
                    throw new DamApiServlet.ApiException(400, "rendition " + rn + "." + format + " listed twice");
                }
                clean.add(rendition(rn, Math.max(0, Math.min(MAX_SIDE, w)), h, format, 0));
                continue;
            }
            if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) {
                throw new DamApiServlet.ApiException(400, rn + ": width and height must be 1 to " + MAX_SIDE);
            }
            if (q < 1 || q > 100) {
                throw new DamApiServlet.ApiException(400, rn + ": quality must be 1 to 100");
            }
            if (!names.add(rn + "." + format)) {
                throw new DamApiServlet.ApiException(400, "rendition " + rn + "." + format + " listed twice");
            }
            Map<String, Object> m = rendition(rn, w, h, format, q);
            if (Boolean.parseBoolean(String.valueOf(r.get(PN_WATERMARK)))) {
                m.put(PN_WATERMARK, true);
            }
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
        Resource rs = resolver.create(profile, "renditions", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        int i = 0;
        for (Map<String, Object> r : clean) {
            Map<String, Object> p = new LinkedHashMap<>(r);
            p.put("jcr:primaryType", "nt:unstructured");
            resolver.create(rs, "r" + i++, p);
        }
        return describe(profile);
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

    static void delete(ResourceResolver resolver, String name) throws DamApiServlet.ApiException, PersistenceException {
        Resource p = name == null ? null : resolver.getResource(ROOT + "/" + name);
        if (p == null || name.contains("/")) {
            throw new DamApiServlet.ApiException(404, "no profile " + name);
        }
        resolver.delete(p);
    }

    /** Assigns a profile to a folder; {@code null} or empty removes it (the parent's applies again). */
    static void assign(Resource folder, String profilePath) throws DamApiServlet.ApiException, PersistenceException {
        if (profilePath != null && !profilePath.isEmpty()
                && (!profilePath.startsWith(ROOT + "/") || folder.getResourceResolver().getResource(profilePath) == null)) {
            throw new DamApiServlet.ApiException(404, "no profile " + profilePath);
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
            m.remove(PN_PROFILE);
        } else {
            m.put(PN_PROFILE, profilePath);
        }
    }
}
