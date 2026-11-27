/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.day.cq.wcm.api.components.ComponentManager;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;
import com.day.cq.wcm.foundation.WCMRenditionPicker;

/**
 * Serves Core Components' adaptive image URLs ({@code <component>.coreimg.<quality>.<width>.<ext>/<lastModified>/<name>.<ext>})
 * from studio-dam instead of resizing in Java on every request.
 *
 * <p>The filter takes a request when the Adaptive Image Servlet would answer it from a DAM asset:
 * the component points at a processed JPEG or PNG asset, the width and quality are the ones its
 * content policy allows and the last-modified part of the URL is current. The component's crop,
 * quarter turns and flips are applied by studio-dam the way the servlet applies them. Everything else
 * (redirects, 404s, inherited page images, images uploaded into the component, GIF, SVG, turns by
 * other angles) goes on to the servlet unchanged, and so does any request studio-dam cannot answer.</p>
 *
 * <p>Assets that came in through a content package (renditions made elsewhere, no
 * {@code studio:blobId}) are served too: the first request uploads the original to studio-dam.</p>
 *
 * <p>studio-dam resizes from the original (the servlet starts from the closest rendition), never
 * upscales, and keeps the result in its disk cache, so a repeated request is a file read.</p>
 */
@Component(service = Filter.class, property = {
        "sling.filter.scope=REQUEST",
        "sling.filter.selectors=" + CoreImageFilter.SELECTOR,
        "sling.filter.methods=GET",
        "sling.filter.methods=HEAD",
        "service.ranking:Integer=-1000"})
@Designate(ocd = CoreImageFilter.Config.class)
public class CoreImageFilter implements Filter {

    @ObjectClassDefinition(name = "Sensible Studio DAM: adaptive images from studio-dam",
            description = "Serves Core Components .coreimg URLs from the studio-dam image service.")
    public @interface Config {
        @AttributeDefinition(name = "Enabled", description = "Off: every .coreimg request goes to the Java servlet.")
        boolean enabled() default true;

        @AttributeDefinition(name = "WebP for browsers that accept it",
                description = "Answers .jpeg URLs with WebP when the request's Accept header lists image/webp, with "
                        + "'Vary: Accept'. Leave off behind caches that ignore Vary (the AEM Dispatcher does).")
        boolean webp() default false;
    }

    static final String SELECTOR = "coreimg";
    static final String IMAGE_RESOURCE_TYPE = "core/wcm/components/image";
    /** Core Components' default JPEG quality and resize width, used when the policy sets none. */
    static final int DEFAULT_QUALITY = 82;
    static final int DEFAULT_RESIZE_WIDTH = 1280;
    /** studio-dam's bounding box: taller results would come back narrower than asked. */
    static final int MAX_HEIGHT = HttpDamClient.MAX_DELIVERY_HEIGHT;

    private static final Logger LOG = LoggerFactory.getLogger(CoreImageFilter.class);
    /** {@code /<13-digit epoch millis>/<name>.<ext>}. */
    private static final Pattern SUFFIX = Pattern.compile("^/(\\d{13})/([^/]+)\\.([A-Za-z]+)$");

    @Reference
    private DamClient client;

    private volatile boolean enabled = true;
    private volatile boolean webp;

    public CoreImageFilter() {
    }

    CoreImageFilter(DamClient client, boolean webp) {
        this.client = client;
        this.webp = webp;
    }

    @Activate
    @Modified
    void activate(Config config) {
        enabled = config.enabled();
        webp = config.webp();
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (enabled && req instanceof SlingHttpServletRequest && res instanceof SlingHttpServletResponse) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            Plan plan = null;
            try {
                plan = plan(request);
            } catch (RuntimeException e) {
                LOG.debug("coreimg: not handled ({}), passing on", e.toString());
            }
            if (plan != null && serve(plan, request, (SlingHttpServletResponse) res)) {
                return;
            }
        }
        // The Core Components servlet answers GET only, and Sling says 404 to a HEAD for the same address: a link
        // checker or a CDN that probes with HEAD saw every such image as broken. A HEAD is answered as its GET
        // is; Sling sends the headers and drops the body.
        if (req instanceof SlingHttpServletRequest && "HEAD".equals(((SlingHttpServletRequest) req).getMethod())
                && req.getAttribute(HEAD_AS_GET) == null) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            javax.servlet.RequestDispatcher dispatcher = request.getRequestDispatcher(request.getResource());
            if (dispatcher != null) {
                req.setAttribute(HEAD_AS_GET, Boolean.TRUE);
                dispatcher.forward(new org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper(request) {
                    @Override
                    public String getMethod() {
                        return "GET";
                    }
                }, res);
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static final String HEAD_AS_GET = CoreImageFilter.class.getName() + ".headAsGet";

    /** What to fetch from studio-dam for a request, or {@code null} if the servlet should answer it. */
    static final class Plan {
        /** {@code studio:blobId}, or {@code null} for assets studio-dam never processed (imported content). */
        final String blobId;
        final String assetPath;
        final int width;
        final int quality;
        final String format;
        final String mimeType;
        final long lastModified;
        final String fileName;
        /** studio-dam transform ({@code c…-r90-h}), empty for a plain resize. */
        final String transform;

        Plan(String blobId, String assetPath, int width, int quality, String format, String mimeType, long lastModified,
                String fileName, String transform) {
            this.blobId = blobId;
            this.assetPath = assetPath;
            this.width = width;
            this.quality = quality;
            this.format = format;
            this.mimeType = mimeType;
            this.lastModified = lastModified;
            this.fileName = fileName;
            this.transform = transform;
        }
    }

    Plan plan(SlingHttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return null;
        }
        RequestPathInfo info = request.getRequestPathInfo();
        String[] selectors = info.getSelectors();
        if (selectors.length < 2 || selectors.length > 3 || !SELECTOR.equals(selectors[0])) {
            return null; // 1 selector: the servlet streams a rendition as it is; nothing to gain
        }
        String ext = info.getExtension() == null ? "" : info.getExtension().toLowerCase(Locale.ROOT);
        String mimeType = "jpeg".equals(ext) || "jpg".equals(ext) ? "image/jpeg" : "png".equals(ext) ? "image/png" : null;
        if (mimeType == null) {
            return null;
        }
        Matcher suffix = SUFFIX.matcher(info.getSuffix() == null ? "" : info.getSuffix());
        if (!suffix.matches() || !suffix.group(3).equals(info.getExtension())) {
            return null;
        }
        int width = positiveInt(selectors[selectors.length - 1]);
        int quality = selectors.length == 3 ? positiveInt(selectors[1]) : DEFAULT_QUALITY;
        if (width <= 0 || quality <= 0 || quality > 100) {
            return null;
        }

        Resource component = request.getResource();
        if (!component.isResourceType(IMAGE_RESOURCE_TYPE)) {
            return null;
        }
        ValueMap props = component.getValueMap();
        String fileReference = props.get("fileReference", String.class);
        if (fileReference == null || !fileReference.startsWith("/content/dam/") || props.get("imageFromPageImage", false)) {
            return null;
        }
        ResourceResolver resolver = request.getResourceResolver();
        Resource assetResource = resolver.getResource(fileReference);
        Asset asset = assetResource == null ? null : assetResource.adaptTo(Asset.class);
        Resource content = assetResource == null ? null : assetResource.getChild("jcr:content");
        if (asset == null || content == null || !mimeType.equals(asset.getMimeType())) {
            return null;
        }
        ValueMap cv = content.getValueMap();
        if (!AssetProcessor.STATE_PROCESSED.equals(cv.get(AssetProcessor.PN_ASSET_STATE, String.class))) {
            return null;
        }
        String blobId = cv.get(AssetProcessor.PN_BLOB_ID, String.class);
        // The servlet redirects stale URLs; only current ones are ours.
        long lastModified = lastModified(props, asset);
        if (Long.parseLong(suffix.group(1)) != lastModified) {
            return null;
        }
        ValueMap policy = policy(component, resolver, request);
        if (policy == null || !allowedWidths(policy).contains(width)
                || selectors.length == 3 && quality != policy.get("jpegQuality", DEFAULT_QUALITY)) {
            return null;
        }
        Transform transform = transform(props, asset, assetResource);
        if (transform == null || tooTall(transform, width)) {
            return null;
        }
        String format = "image/png".equals(mimeType) ? "png" : "jpeg";
        if (webp && "jpeg".equals(format) && acceptsWebp(request)) {
            format = "webp";
        }
        return new Plan(blobId, assetResource.getPath(), width, quality, format, mimeType, lastModified,
                suffix.group(2) + "." + info.getExtension(), transform.steps);
    }

    /** The component's own edits as studio-dam steps, and the size they leave. */
    static final class Transform {
        final String steps;
        final long width;
        final long height;

        Transform(String steps, long width, long height) {
            this.steps = steps;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Crop, rotation and flips stored by Core Components' image editor, in the order their servlet
     * applies them: crop (on the original, scaled from the web rendition it was drawn on), turn,
     * horizontal mirror, vertical mirror. {@code null} when studio-dam cannot do the same: a turn
     * that is not a multiple of 90°, or an edit on a photo with an EXIF turn (the servlet works on
     * the stored pixels, studio-dam on the displayed ones).
     */
    static Transform transform(ValueMap props, Asset asset, Resource assetResource) {
        long origW = number(asset.getMetadataValue("tiff:ImageWidth"));
        long origH = number(asset.getMetadataValue("tiff:ImageLength"));
        if (origW <= 0 || origH <= 0) {
            return null;
        }
        List<String> steps = new ArrayList<>();
        long w = origW;
        long h = origH;
        int[] crop = cropRect(props.get("imageCrop", String.class));
        if (crop != null) {
            double scaling = cropScaling(asset, origW);
            long cx = (long) (crop[0] * scaling);
            long cy = (long) (crop[1] * scaling);
            long cw = (long) (crop[2] * scaling);
            long ch = (long) (crop[3] * scaling);
            if (cx < 0 || cy < 0 || cw <= 0 || ch <= 0 || cx >= origW || cy >= origH) {
                return null;
            }
            steps.add("c" + cx + "," + cy + "," + cw + "," + ch);
            w = Math.min(cw, origW - cx);
            h = Math.min(ch, origH - cy);
        }
        int rotation = rotation(props);
        if (rotation % 90 != 0) {
            return null;
        }
        rotation = Math.floorMod(rotation, 360);
        if (rotation != 0) {
            steps.add("r" + rotation);
            if (rotation != 180) {
                long t = w;
                w = h;
                h = t;
            }
        }
        if (props.get("imageFlipHorizontal", false)) {
            steps.add("h");
        }
        if (props.get("imageFlipVertical", false)) {
            steps.add("v");
        }
        if (!steps.isEmpty()) {
            String orientation = asset.getMetadataValue("tiff:Orientation");
            if (orientation != null && !orientation.trim().isEmpty() && !"1".equals(orientation.trim())) {
                return null;
            }
        }
        return new Transform(String.join("-", steps), w, h);
    }

    /** {@code x1,y1,x2,y2[/ratio]} → x, y, width, height; {@code null} when absent or unreadable (the servlet ignores it then). */
    static int[] cropRect(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return null;
        }
        String coords = csv.contains("/") ? csv.substring(0, csv.indexOf('/')) : csv;
        String[] parts = coords.split(",");
        try {
            int x1 = Integer.parseInt(parts[0].trim());
            int y1 = Integer.parseInt(parts[1].trim());
            int x2 = Integer.parseInt(parts[2].trim());
            int y2 = Integer.parseInt(parts[3].trim());
            return new int[] {x1, y1, x2 - x1, y2 - y1};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code imageRotate} as the servlet reads it: an integer, anything else is no rotation. */
    static int rotation(ValueMap props) {
        try {
            String r = props.get("imageRotate", String.class);
            return r == null ? 0 : Integer.parseInt(r.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Crops are drawn on the web rendition; the servlet scales them to the original by the ratio
     * of the two widths.
     */
    private static double cropScaling(Asset asset, long origW) {
        Rendition web = asset.getRendition(new WCMRenditionPicker());
        long webW = web == null ? origW : renditionWidth(web, origW);
        if (origW > webW) {
            return (double) origW / webW;
        }
        return origW > 0 ? (double) webW / origW : 1.0;
    }

    private static long renditionWidth(Rendition rendition, long origW) {
        if ("original".equals(rendition.getName())) {
            return origW;
        }
        long fromProps = number(rendition.getProperties().get("tiff:ImageWidth", String.class));
        if (fromProps > 0) {
            return fromProps;
        }
        try (InputStream in = rendition.getStream(); ImageInputStream iis = in == null ? null : ImageIO.createImageInputStream(in)) {
            if (iis != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);
                        return reader.getWidth(0);
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("coreimg: no size for {}: {}", rendition.getPath(), e.toString());
        }
        return origW;
    }

    private static long number(String s) {
        try {
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Newer of the component's and the asset's modification times, as in the URL Core Components write. */
    static long lastModified(ValueMap componentProps, Asset asset) {
        Calendar c = componentProps.get("jcr:lastModified", Calendar.class);
        if (c == null) {
            c = componentProps.get("cq:lastModified", Calendar.class);
        }
        long component = c == null ? 0 : c.getTimeInMillis();
        return Math.max(component, asset.getLastModified());
    }

    /**
     * The component's content policy, looked up the way Core Components do: through the teaser and
     * image delegates its component definition names. {@code null} when there is none.
     */
    ValueMap policy(Resource component, ResourceResolver resolver, SlingHttpServletRequest request) {
        ContentPolicyManager policies = resolver.adaptTo(ContentPolicyManager.class);
        if (policies == null) {
            return null;
        }
        Resource target = component;
        ComponentManager components = resolver.adaptTo(ComponentManager.class);
        if (components != null) {
            com.day.cq.wcm.api.components.Component def = components.getComponentOfResource(target);
            if (def != null && def.getProperties() != null) {
                String teaser = def.getProperties().get("teaserDelegate", String.class);
                if (teaser != null && !teaser.isEmpty()) {
                    target = new TypedResource(target, teaser);
                    com.day.cq.wcm.api.components.Component teaserDef = components.getComponentOfResource(target);
                    if (teaserDef != null && teaserDef.getProperties() != null) {
                        def = teaserDef;
                    }
                }
                String image = def.getProperties().get("imageDelegate", String.class);
                if (image != null && !image.isEmpty()) {
                    target = new TypedResource(target, image);
                }
            }
        }
        ContentPolicy policy = policies.getPolicy(target, request);
        return policy == null ? null : policy.getProperties();
    }

    /** {@code allowedRenditionWidths}, or the resize width alone when the policy lists none. */
    static List<Integer> allowedWidths(ValueMap policy) {
        List<Integer> widths = new ArrayList<>();
        for (String w : policy.get("allowedRenditionWidths", new String[0])) {
            int v = positiveInt(w);
            if (v <= 0) {
                return widths; // an invalid entry ends the list, as in Core Components
            }
            widths.add(v);
        }
        if (widths.isEmpty()) {
            int resize = policy.get("resizeWidth", 0);
            widths.add(resize > 0 ? resize : DEFAULT_RESIZE_WIDTH);
        }
        return widths;
    }

    private static boolean tooTall(Transform t, int width) {
        double outW = Math.min(width, t.width);
        return Math.round(outW * t.height / t.width) > MAX_HEIGHT;
    }

    private static boolean acceptsWebp(SlingHttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("image/webp");
    }

    /** Streams the image; {@code false} if studio-dam could not answer (the servlet then does). */
    boolean serve(Plan plan, SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (webp) {
            response.setHeader("Vary", "Accept");
        }
        long since = request.getDateHeader("If-Modified-Since");
        if (since > 0 && plan.lastModified / 1000 <= since / 1000) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return true;
        }
        DamClient.DeliveredImage image;
        try {
            if (!client.isAvailable()) {
                return false;
            }
            String blobId = plan.blobId != null ? plan.blobId
                    : OriginalBlobs.onDemand(request.getResourceResolver().getResource(plan.assetPath), plan.lastModified, client);
            if (blobId == null) {
                return false;
            }
            image = client.deliver(blobId, plan.width, plan.format, plan.quality, plan.transform);
        } catch (IOException e) {
            LOG.debug("coreimg: studio-dam did not deliver {} ({}); the servlet answers", request.getRequestURI(), e.getMessage());
            return false;
        }
        try (InputStream in = image.body) {
            response.setContentType("webp".equals(plan.format) ? "image/webp" : plan.mimeType);
            response.setDateHeader("Last-Modified", plan.lastModified);
            response.setHeader("Content-Disposition", "inline; filename=" + URLEncoder.encode(plan.fileName, StandardCharsets.UTF_8));
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (image.cacheStatus != null) {
                response.setHeader("X-Studio-Cache", image.cacheStatus);
            }
            if (image.length >= 0 && image.length <= Integer.MAX_VALUE) {
                response.setContentLength((int) image.length);
            }
            if (!"HEAD".equals(request.getMethod())) {
                OutputStream out = response.getOutputStream();
                in.transferTo(out);
            }
        }
        return true;
    }

    static int positiveInt(String s) {
        if (s == null || !s.trim().matches("\\d{1,9}")) {
            return -1;
        }
        return Integer.parseInt(s.trim());
    }

    /** The component seen as another resource type, to find a delegate's policy. */
    private static final class TypedResource extends ResourceWrapper {
        private final String type;

        TypedResource(Resource resource, String type) {
            super(resource);
            this.type = type;
        }

        @Override
        public String getResourceType() {
            return type;
        }

        @Override
        public boolean isResourceType(String resourceType) {
            return getResourceResolver().isResourceType(this, resourceType);
        }
    }
}
