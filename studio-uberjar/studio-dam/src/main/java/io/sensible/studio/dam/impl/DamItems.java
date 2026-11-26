/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * How the Assets UI sees the repository: folders ({@code sling:Folder}, {@code sling:OrderedFolder},
 * {@code nt:folder}) and assets ({@code dam:Asset}), as JSON-ready maps.
 */
final class DamItems {

    static final String ROOT = "/content/dam";
    static final String THUMBNAIL = "cq5dam.thumbnail.319.319.png";
    static final String WEB_PREFIX = "cq5dam.web.";

    private DamItems() {
    }

    static boolean isAsset(Resource r) {
        return r != null && "dam:Asset".equals(type(r));
    }

    static boolean isFolder(Resource r) {
        String t = r == null ? null : type(r);
        return "sling:Folder".equals(t) || "sling:OrderedFolder".equals(t) || "nt:folder".equals(t);
    }

    /** Inside the DAM: {@code /content/dam} itself or below it. */
    static boolean inDam(String path) {
        return path != null && (path.equals(ROOT) || path.startsWith(ROOT + "/")) && !path.contains("/../")
                && !path.endsWith("/..") && !path.contains("//");
    }

    private static String type(Resource r) {
        return r.getValueMap().get("jcr:primaryType", String.class);
    }

    /** URL of a repository path, each segment encoded, {@code jcr:content} written as Sling's {@code _jcr_content}. */
    static String url(String path) {
        StringBuilder b = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            String s = seg.startsWith("jcr:") ? "_jcr_" + seg.substring(4) : seg;
            b.append('/').append(URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return b.toString();
    }

    /**
     * URL of a rendition (or any nt:file) with {@code ?_ck=<last modified>}: renditions are served
     * without cache headers, so browsers reuse them heuristically; after an edit or a reprocess the
     * URL changes and the new image shows at once.
     */
    static String url(Resource file) {
        String u = url(file.getPath());
        Resource content = file.getChild("jcr:content");
        Calendar modified = content == null ? null : content.getValueMap().get("jcr:lastModified", Calendar.class);
        return modified == null ? u : u + "?_ck=" + modified.getTimeInMillis();
    }

    static String title(Resource r) {
        if (isAsset(r)) {
            String t = first(r.getChild("jcr:content/metadata"), "dc:title");
            if (t != null && !t.isEmpty()) {
                return t;
            }
        }
        String t = first(r.getChild("jcr:content"), "jcr:title");
        if (t == null) {
            t = r.getValueMap().get("jcr:title", String.class);
        }
        return t == null || t.isEmpty() ? r.getName() : t;
    }

    private static String first(Resource r, String prop) {
        if (r == null) {
            return null;
        }
        Object v = r.getValueMap().get(prop);
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            return a.length == 0 ? null : String.valueOf(a[0]);
        }
        return v == null ? null : String.valueOf(v);
    }

    static final String PN_THUMBNAIL = "studio:thumbnail";

    /** The asset chosen as the folder's thumbnail, if it still exists inside the folder. */
    static Resource chosenThumbnail(Resource folder) {
        Resource c = folder.getChild("jcr:content");
        String p = c == null ? null : c.getValueMap().get(PN_THUMBNAIL, String.class);
        if (p == null || !p.startsWith(folder.getPath() + "/") || p.contains("..")) {
            return null;
        }
        Resource a = folder.getResourceResolver().getResource(p);
        return isAsset(a) ? a : null;
    }

    static Map<String, Object> folder(Resource r, boolean withPreviews) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "folder");
        m.put("path", r.getPath());
        m.put("name", r.getName());
        m.put("title", title(r));
        Calendar modified = r.getValueMap().get("jcr:lastModified", Calendar.class);
        if (modified == null) {
            modified = r.getValueMap().get("jcr:created", Calendar.class);
        }
        m.put("modified", modified);
        Resource content = r.getChild("jcr:content");
        String profile = content == null ? null : content.getValueMap().get(DamProfiles.PN_PROFILE, String.class);
        if (profile != null) {
            m.put("processingProfile", profile);
        }
        String live = DamLiveCopy.status(r);
        if (live != null) {
            m.put("liveCopy", live);
        }
        String chosen = content == null ? null : content.getValueMap().get(PN_THUMBNAIL, String.class);
        if (chosen != null) {
            m.put("thumbnailAsset", chosen);
        }
        String smart = content == null ? null : content.getValueMap().get(DamSmartTags.PN_FOLDER, String.class);
        if (smart != null) {
            m.put("smartTagging", smart);
        }
        String schema = content == null ? null : content.getValueMap().get(DamSchemas.PN_SCHEMA, String.class);
        if (schema != null) {
            m.put("metadataSchema", schema);
        }
        for (DamForms.Kind kind : DamForms.Kind.values()) {
            String form = content == null ? null : content.getValueMap().get(kind.property, String.class);
            if (form != null) {
                m.put(kind.property, form);
            }
        }
        String imageProfile = content == null ? null : content.getValueMap().get(DamSmartCrop.PN_FOLDER, String.class);
        if (imageProfile != null) {
            m.put("imageProfile", imageProfile);
        }
        if (withPreviews) {
            m.put("previews", previews(r));
        }
        return m;
    }

    /**
     * Up to three thumbnails for a folder card, breadth first through sub-folders (AEM shows
     * images from deeper levels too). Bounded so a large tree costs little.
     */
    static List<String> previews(Resource folder) {
        List<String> previews = new ArrayList<>();
        // A thumbnail chosen in the folder properties replaces the collage.
        Resource chosen = chosenThumbnail(folder);
        if (chosen != null) {
            Resource thumb = chosen.getChild("jcr:content/renditions/" + THUMBNAIL);
            if (thumb != null) {
                previews.add(url(thumb));
                return previews;
            }
        }
        java.util.ArrayDeque<Resource> queue = new java.util.ArrayDeque<>();
        queue.add(folder);
        int visited = 0;
        while (!queue.isEmpty() && previews.size() < 3 && visited < 300) {
            Resource f = queue.poll();
            for (Resource c : f.getChildren()) {
                if (++visited > 300 || previews.size() >= 3) {
                    break;
                }
                if (isAsset(c)) {
                    Resource thumb = c.getChild("jcr:content/renditions/" + THUMBNAIL);
                    if (thumb != null) {
                        previews.add(url(thumb));
                    }
                } else if (isFolder(c) && queue.size() < 50) {
                    queue.add(c);
                }
            }
        }
        return previews;
    }

    static boolean isContentFragment(Resource asset) {
        Resource c = asset.getChild("jcr:content");
        return c != null && c.getValueMap().get("contentFragment", false);
    }

    /** When an asset stops being usable (AEM's Expires): after it, it is marked expired. */
    static final String PN_EXPIRES = "prism:expirationDate";

    static Map<String, Object> asset(Resource r) {
        Map<String, Object> m = new LinkedHashMap<>();
        Resource content = r.getChild("jcr:content");
        ValueMap c = content == null ? ValueMap.EMPTY : content.getValueMap();
        Resource mdRes = r.getChild("jcr:content/metadata");
        ValueMap md = mdRes == null ? ValueMap.EMPTY : mdRes.getValueMap();
        Resource original = r.getChild("jcr:content/renditions/original");

        m.put("type", "asset");
        m.put("path", r.getPath());
        m.put("name", r.getName());
        m.put("title", title(r));
        String mime = md.get("dam:MIMEtype", md.get("dc:format", String.class));
        if (mime == null && original != null) {
            Resource oc = original.getChild("jcr:content");
            mime = oc == null ? null : oc.getValueMap().get("jcr:mimeType", String.class);
        }
        m.put("mimeType", mime);
        if (md.containsKey(DamAssetsConfig.RESTRICTIONS) || md.containsKey(DamAssetsConfig.WEB_STATEMENT)) {
            // DRM (Assets Configurations): with DRM on, the console downloads it only after its license is agreed to
            m.put("licensed", true);
        }
        Long size = md.get("dam:size", Long.class);
        if (size == null && original != null) {
            long len = original.getResourceMetadata().getContentLength();
            size = len > 0 ? len : null;
        }
        m.put("size", size);
        m.put("width", md.get("tiff:ImageWidth", md.get("exif:PixelXDimension", Long.class)));
        m.put("height", md.get("tiff:ImageLength", md.get("exif:PixelYDimension", Long.class)));
        Calendar modified = c.get("jcr:lastModified", Calendar.class);
        Calendar created = r.getValueMap().get("jcr:created", Calendar.class);
        m.put("modified", modified != null ? modified : created);
        m.put("created", created);
        m.put("state", c.get("dam:assetState", String.class));
        m.put("checkedOutBy", c.get(DamLocks.PN_LOCK, String.class));
        Calendar expires = md.get(PN_EXPIRES, Calendar.class);
        if (expires != null) {
            m.put("expires", expires);
            m.put("expired", expires.before(Calendar.getInstance()));
        }
        String live = DamLiveCopy.status(r);
        if (live != null) {
            m.put("liveCopy", live);
        }
        Resource thumb = r.getChild("jcr:content/renditions/" + THUMBNAIL);
        m.put("thumbnail", thumb == null ? null : url(thumb));
        Resource web = webRendition(r);
        m.put("web", web == null ? null : url(web));
        m.put("original", original == null ? null : url(original));
        Double duration = duration(mdRes);
        if (duration != null) {
            m.put("duration", duration);
        }
        Resource videoPreview = r.getChild("jcr:content/renditions/" + AssetProcessor.VIDEO_PREVIEW);
        if (videoPreview != null) {
            m.put("preview", url(videoPreview)); // the video as browsers play it, when the original is not that
        }
        Resource poster = r.getChild("jcr:content/renditions/" + AssetProcessor.POSTER);
        if (poster != null) {
            m.put("poster", url(poster));
        }
        if (DamSets.isSet(r)) {
            // a set has no file of its own: it is shown by its first picture
            List<Resource> members = DamSets.members(r);
            m.put("imageSet", true);
            m.put("setKind", DamSets.kind(r));
            m.put("members", members.size());
            m.put("mimeType", DamSets.MIME);
            if (!members.isEmpty()) {
                Map<String, Object> first = asset(members.get(0));
                m.put("thumbnail", first.get("thumbnail"));
                m.put("web", first.get("web"));
            }
        }
        if (isContentFragment(r)) {
            m.put("contentFragment", true);
            String d = c.get("jcr:description", String.class);
            m.put("description", d);
        }
        return m;
    }

    /**
     * Length in seconds of a video or audio asset: {@code dam:duration} as Sensible Studio writes it, else
     * the XMP Dynamic Media structure of imported assets ({@code xmpDM:duration} with
     * {@code xmpDM:value} in units of {@code xmpDM:scale}, e.g. {@code 1/1000}).
     */
    static Double duration(Resource metadata) {
        if (metadata == null) {
            return null;
        }
        Double d = metadata.getValueMap().get("dam:duration", Double.class);
        if (d != null) {
            return d > 0 ? d : null;
        }
        Resource s = metadata.getChild("xmpDM:duration");
        if (s == null) {
            return null;
        }
        Double value = s.getValueMap().get("xmpDM:value", Double.class);
        String scale = s.getValueMap().get("xmpDM:scale", "1");
        if (value == null) {
            return null;
        }
        try {
            String[] q = scale.split("/");
            double f = q.length == 2 ? Double.parseDouble(q[0].trim()) / Double.parseDouble(q[1].trim()) : Double.parseDouble(q[0].trim());
            double secs = value * f;
            return secs > 0 && !Double.isInfinite(secs) && !Double.isNaN(secs) ? secs : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Resource webRendition(Resource asset) {
        Resource folder = asset.getChild("jcr:content/renditions");
        if (folder == null) {
            return null;
        }
        for (Resource c : folder.getChildren()) {
            if (c.getName().startsWith(WEB_PREFIX)) {
                return c;
            }
        }
        return null;
    }

    static Map<String, Object> item(Resource r) {
        return isAsset(r) ? asset(r) : folder(r, true);
    }
}
