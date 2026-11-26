package io.sensible.studio.cfm.impl.headless;

import graphql.schema.DataFetchingEnvironment;
import io.sensible.studio.cfm.impl.FragmentModel;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * A content reference resolved for GraphQL: an image ({@code ImageRef}), video or audio ({@code MultimediaRef}),
 * archive ({@code ArchiveRef}), other DAM asset ({@code DocumentRef}) or page ({@code PageRef}).
 */
final class AssetRef implements Gettable {

    static final String IMAGE = "ImageRef";
    static final String MULTIMEDIA = "MultimediaRef";
    static final String ARCHIVE = "ArchiveRef";
    static final String DOCUMENT = "DocumentRef";
    static final String PAGE = "PageRef";

    /** The URL prefix the dynamic media servlet answers on. */
    static final String DELIVER = "/adobe/dynamicmedia/deliver/";
    static final String ID_PREFIX = "dm-aid--";
    static final String PATH_ID_PREFIX = "studio-";

    final String path;
    final String typeName;
    final String mimeType;
    final Long width;
    final Long height;
    final Long size;
    final String uuid;

    private AssetRef(String path, String typeName, String mimeType, Long width, Long height, Long size, String uuid) {
        this.path = path;
        this.typeName = typeName;
        this.mimeType = mimeType;
        this.width = width;
        this.height = height;
        this.size = size;
        this.uuid = uuid;
    }

    /** The reference for a resource, or null when it is neither an asset nor a page. */
    static AssetRef of(Resource r) {
        if (r == null) return null;
        String type = r.getValueMap().get("jcr:primaryType", "");
        if ("cq:Page".equals(type)) return new AssetRef(r.getPath(), PAGE, "text/html", null, null, null, r.getValueMap().get("jcr:uuid", String.class));
        if (!"dam:Asset".equals(type)) return null;
        ValueMap meta = FragmentModel.valueMap(r.getChild("jcr:content/metadata"));
        String mime = meta.get("dam:MIMEtype", String.class);
        if (mime == null) mime = meta.get("dc:format", String.class);
        if (mime == null) mime = FragmentModel.valueMap(r.getChild("jcr:content/renditions/original/jcr:content")).get("jcr:mimeType", String.class);
        if (mime == null) mime = guessMime(r.getName());
        Long width = number(meta, "tiff:ImageWidth", "exif:PixelXDimension");
        Long height = number(meta, "tiff:ImageLength", "exif:PixelYDimension");
        Long size = number(meta, "dam:size");
        String m = mime.toLowerCase(Locale.ROOT);
        String kind;
        if (m.startsWith("image/")) kind = IMAGE;
        else if (m.startsWith("video/") || m.startsWith("audio/")) kind = MULTIMEDIA;
        else if (m.contains("zip") || m.contains("tar") || m.contains("compressed") || m.contains("gzip") || m.contains("rar")) kind = ARCHIVE;
        else kind = DOCUMENT;
        return new AssetRef(r.getPath(), kind, mime, width, height, size, r.getValueMap().get("jcr:uuid", String.class));
    }

    @Override
    public Object get(String field, DataFetchingEnvironment env) {
        HeadlessContext ctx = env.getGraphQlContext().get(HeadlessContext.KEY);
        switch (field) {
            case "_path": return path;
            case "_authorUrl": return ctx.authorUrl(path);
            case "_publishUrl": return ctx.publishUrl(path);
            case "_dynamicUrl": return IMAGE.equals(typeName) ? dynamicUrl(env.getLocalContext()) : null;
            case "mimeType":
            case "format": return mimeType;
            case "width": return width == null ? null : (int) (long) width;
            case "height": return height == null ? null : (int) (long) height;
            case "size": return size == null ? null : (int) Math.min(Integer.MAX_VALUE, size);
            default: return null;
        }
    }

    /**
     * The delivery URL of an image, like AEM's: {@code /adobe/dynamicmedia/deliver/dm-aid--<id>/<seo name>.<format>}
     * with the {@code _assetTransform} of the query as parameters.
     */
    String dynamicUrl(Object transform) {
        Map<?, ?> t = transform instanceof Map ? (Map<?, ?>) transform : null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "jpg";
        if ("jpeg".equals(ext)) ext = "jpg";
        String seo = base;
        StringBuilder q = new StringBuilder();
        if (t != null) {
            if (t.get("seoName") != null) seo = String.valueOf(t.get("seoName"));
            if (t.get("format") != null) ext = extensionOf(String.valueOf(t.get("format")));
            if (Boolean.TRUE.equals(t.get("preferWebp"))) param(q, "preferwebp", "true");
            if (t.get("width") != null) param(q, "width", String.valueOf(t.get("width")));
            if (t.get("quality") != null) param(q, "quality", String.valueOf(t.get("quality")));
            if (t.get("size") instanceof Map) {
                Map<?, ?> s = (Map<?, ?>) t.get("size");
                param(q, "size", s.get("width") + "," + s.get("height"));
            }
            if (t.get("crop") instanceof Map) {
                Map<?, ?> c = (Map<?, ?>) t.get("crop");
                param(q, "crop", c.get("xOrigin") + "," + c.get("yOrigin") + "," + c.get("width") + "," + c.get("height"));
            }
            if (t.get("rotation") != null) param(q, "rotate", String.valueOf(t.get("rotation")).replace("R", ""));
            if (t.get("flip") != null) {
                String f = String.valueOf(t.get("flip"));
                param(q, "flip", "HORIZONTAL".equals(f) ? "h" : "VERTICAL".equals(f) ? "v" : "hv");
            }
        }
        seo = seo.replaceAll("[^A-Za-z0-9._-]+", "-");
        return DELIVER + id() + "/" + seo + "." + ext + (q.length() > 0 ? "?" + q : "");
    }

    /** {@code dm-aid--<jcr:uuid>}, or a path based id for assets without one. */
    String id() {
        if (uuid != null && !uuid.isEmpty()) return ID_PREFIX + uuid;
        return ID_PREFIX + PATH_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    static String extensionOf(String format) {
        switch (format) {
            case "PNG":
            case "PNG8": return "png";
            case "GIF": return "gif";
            case "WEBP":
            case "WEBPLL":
            case "WEBPLY": return "webp";
            default: return "jpg";
        }
    }

    private static void param(StringBuilder q, String key, String value) {
        if (q.length() > 0) q.append('&');
        try {
            q.append(key).append('=').append(URLEncoder.encode(value, "UTF-8").replace("%2C", ","));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Long number(ValueMap meta, String... keys) {
        for (String k : keys) {
            Object v = meta.get(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v != null) {
                try {
                    return Long.parseLong(v.toString().trim());
                } catch (NumberFormatException ignored) {
                    // try the next key
                }
            }
        }
        return null;
    }

    static String guessMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
