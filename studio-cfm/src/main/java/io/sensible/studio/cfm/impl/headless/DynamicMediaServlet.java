package io.sensible.studio.cfm.impl.headless;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Image delivery behind GraphQL's {@code _dynamicUrl}:
 * {@code /adobe/dynamicmedia/deliver/dm-aid--<asset id>/<seo name>.<jpg|png|gif|webp>} with the parameters
 * {@code width}, {@code size=w,h}, {@code quality}, {@code crop=x,y,w,h}, {@code rotate} and {@code flip}.
 * Images are scaled from the closest rendition; nothing is ever enlarged. WebP is answered as JPEG (or PNG for
 * images with transparency) unless the JVM can write WebP.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/adobe/dynamicmedia/deliver",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class DynamicMediaServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int MAX_SIDE = 6000;

    static {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String suffix = request.getRequestPathInfo().getSuffix();
        deliver(request, response, suffix == null ? "" : suffix.substring(1));
    }

    /**
     * Answers one delivery URL; {@code rest} is what follows {@code /adobe/dynamicmedia/deliver/}, e.g.
     * {@code dm-aid--<id>/<seo name>.jpg}. Called by {@link DynamicMediaFilter} for the URLs Sling cannot route
     * to a path servlet (no extension right after {@code deliver}).
     */
    void deliver(SlingHttpServletRequest request, SlingHttpServletResponse response, String rest) throws IOException {
        String[] parts = rest == null || rest.isEmpty() ? new String[0] : rest.split("/");
        if (parts.length < 1 || !parts[0].startsWith(AssetRef.ID_PREFIX)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Resource asset = asset(request.getResourceResolver(), parts[0].substring(AssetRef.ID_PREFIX.length()));
        Resource original = asset == null ? null : asset.getChild("jcr:content/renditions/original");
        if (original == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String file = parts.length > 1 ? parts[parts.length - 1] : asset.getName();
        int dot = file.lastIndexOf('.');
        String format = dot > 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "jpg";
        Options o = Options.of(request);

        Calendar modified = asset.getChild("jcr:content") == null ? null
                : asset.getChild("jcr:content").getValueMap().get("jcr:lastModified", Calendar.class);
        String etag = "\"" + Integer.toHexString((asset.getPath() + "|" + (modified == null ? 0 : modified.getTimeInMillis())
                + "|" + format + "|" + request.getQueryString()).hashCode()) + "\"";
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", "public, max-age=600");
        if (etag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        String mime = AssetRef.of(asset) == null ? null : AssetRef.of(asset).mimeType;
        boolean raster = mime != null && (mime.contains("jpeg") || mime.contains("png") || mime.contains("gif") || mime.contains("bmp"));
        if (!raster) {
            stream(original, mime, response); // SVG and other formats are delivered as they are
            return;
        }
        Resource source = o.crop == null ? closestRendition(asset, o.targetWidth(), original) : original;
        BufferedImage image;
        try (InputStream in = open(source)) {
            image = in == null ? null : ImageIO.read(in);
        }
        if (image == null) {
            stream(original, mime, response);
            return;
        }
        image = o.apply(image);
        String outFormat = "webp".equals(format) ? (canWrite("webp") ? "webp" : image.getColorModel().hasAlpha() ? "png" : "jpg") : format;
        if (!"png".equals(outFormat) && !"gif".equals(outFormat) && !"webp".equals(outFormat)) outFormat = "jpg";
        byte[] bytes = encode(image, outFormat, o.quality);
        response.setContentType("jpg".equals(outFormat) ? "image/jpeg" : "image/" + outFormat);
        response.setContentLength(bytes.length);
        if (!"HEAD".equals(request.getMethod())) response.getOutputStream().write(bytes);
    }

    /** {@code <jcr:uuid>} or {@code studio-<base64url path>}. */
    static Resource asset(ResourceResolver resolver, String id) {
        if (id.startsWith(AssetRef.PATH_ID_PREFIX)) {
            try {
                String path = new String(Base64.getUrlDecoder().decode(id.substring(AssetRef.PATH_ID_PREFIX.length())), StandardCharsets.UTF_8);
                return path.startsWith("/content/") ? resolver.getResource(path) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (!UUID.matcher(id).matches()) return null;
        try {
            Iterator<Resource> it = resolver.findResources(
                    "SELECT * FROM [dam:Asset] AS a WHERE a.[jcr:uuid] = '" + id + "'", "JCR-SQL2");
            return it.hasNext() ? it.next() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The smallest web/zoom rendition at least {@code width} wide, else the original. */
    private static Resource closestRendition(Resource asset, Integer width, Resource original) {
        if (width == null) return original;
        Resource renditions = asset.getChild("jcr:content/renditions");
        Resource best = null;
        int bestBox = Integer.MAX_VALUE;
        for (Resource r : renditions.getChildren()) {
            String n = r.getName();
            if (!n.startsWith("cq5dam.web.") && !n.startsWith("cq5dam.zoom.")) continue;
            String[] p = n.split("\\.");
            if (p.length < 4) continue;
            try {
                int box = Integer.parseInt(p[2]);
                if (box >= width && box < bestBox) {
                    best = r;
                    bestBox = box;
                }
            } catch (NumberFormatException ignored) {
                // not a sized rendition
            }
        }
        return best != null ? best : original;
    }

    private static InputStream open(Resource file) {
        InputStream in = file.adaptTo(InputStream.class);
        if (in == null && file.getChild("jcr:content") != null) in = file.getChild("jcr:content").adaptTo(InputStream.class);
        if (in == null && file.getChild("jcr:content") != null) {
            ValueMap vm = file.getChild("jcr:content").getValueMap();
            in = vm.get("jcr:data", InputStream.class);
        }
        return in;
    }

    private static void stream(Resource file, String mime, HttpServletResponse response) throws IOException {
        try (InputStream in = open(file)) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType(mime == null ? "application/octet-stream" : mime);
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
        }
    }

    private static boolean canWrite(String format) {
        return ImageIO.getImageWritersByFormatName(format).hasNext();
    }

    static byte[] encode(BufferedImage image, String format, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if ("jpg".equals(format)) {
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(image, 0, 0, null);
                g.dispose();
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(1, Math.min(100, quality)) / 100f);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            } finally {
                writer.dispose();
            }
        } else {
            ImageIO.write(image, format, out);
        }
        return out.toByteArray();
    }

    /** The transformation parameters of a request. */
    static final class Options {
        Integer width;
        Integer height;
        int quality = 80;
        int[] crop;
        int rotate;
        String flip;

        static Options of(SlingHttpServletRequest request) {
            Options o = new Options();
            o.width = positive(request.getParameter("width"));
            String size = request.getParameter("size");
            if (size != null && size.contains(",")) {
                String[] s = size.split(",");
                o.width = positive(s[0]);
                o.height = positive(s[1]);
            }
            Integer q = positive(request.getParameter("quality"));
            if (q != null) o.quality = Math.min(100, q);
            String crop = request.getParameter("crop");
            if (crop != null) {
                String[] c = crop.split(",");
                if (c.length == 4) {
                    try {
                        o.crop = new int[]{Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim()), Integer.parseInt(c[3].trim())};
                    } catch (NumberFormatException ignored) {
                        o.crop = null;
                    }
                }
            }
            Integer r = positive(request.getParameter("rotate"));
            o.rotate = r == null ? 0 : r % 360;
            o.flip = request.getParameter("flip");
            return o;
        }

        Integer targetWidth() {
            return width;
        }

        BufferedImage apply(BufferedImage image) {
            BufferedImage img = image;
            if (crop != null) {
                int x = Math.max(0, Math.min(crop[0], img.getWidth() - 1));
                int y = Math.max(0, Math.min(crop[1], img.getHeight() - 1));
                int w = Math.max(1, Math.min(crop[2], img.getWidth() - x));
                int h = Math.max(1, Math.min(crop[3], img.getHeight() - y));
                img = img.getSubimage(x, y, w, h);
            }
            if (rotate == 90 || rotate == 180 || rotate == 270) img = rotate(img, rotate);
            if (flip != null) img = flip(img, flip.contains("h"), flip.contains("v"));
            Integer w = width;
            Integer h = height;
            if (w != null || h != null) {
                double sx = w == null ? Double.MAX_VALUE : (double) Math.min(w, MAX_SIDE) / img.getWidth();
                double sy = h == null ? Double.MAX_VALUE : (double) Math.min(h, MAX_SIDE) / img.getHeight();
                double scale = Math.min(1.0, Math.min(sx, sy)); // fit inside, never enlarge
                if (scale < 1.0) img = scale(img, Math.max(1, (int) Math.round(img.getWidth() * scale)), Math.max(1, (int) Math.round(img.getHeight() * scale)));
            }
            return img;
        }

        private static Integer positive(String s) {
            try {
                int v = s == null ? -1 : Integer.parseInt(s.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        // halve repeatedly for quality, then the last step bicubic
        BufferedImage img = src;
        int cw = src.getWidth();
        int ch = src.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            img = draw(img, cw, ch);
        }
        return cw == w && ch == h ? img : draw(img, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage rotate(BufferedImage src, int degrees) {
        boolean swap = degrees != 180;
        int w = swap ? src.getHeight() : src.getWidth();
        int h = swap ? src.getWidth() : src.getHeight();
        BufferedImage out = new BufferedImage(w, h, src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        AffineTransform t = new AffineTransform();
        t.translate(w / 2.0, h / 2.0);
        t.rotate(Math.toRadians(degrees));
        t.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    private static BufferedImage flip(BufferedImage src, boolean horizontal, boolean vertical) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        AffineTransform t = new AffineTransform();
        t.scale(horizontal ? -1 : 1, vertical ? -1 : 1);
        t.translate(horizontal ? -src.getWidth() : 0, vertical ? -src.getHeight() : 0);
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }
}
