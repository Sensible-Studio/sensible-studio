/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.Asset;

/**
 * The Dynamic Media image URL, {@code /is/image/<asset>?wid=…&$preset$} (see {@link DynamicMedia}).
 *
 * <p>Access is the repository's: the asset is resolved with the request's session, so on a
 * publish instance only published assets answer, and on author whoever may read the asset may
 * fetch it. Responses carry an ETag of the original's content and the parameters, and a
 * {@code Last-Modified} of the asset, so browsers and the dispatcher revalidate cheaply when
 * the asset changes.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.resourceTypes=" + DynamicMediaResourceProvider.TYPE,
        "sling.servlet.methods=GET",
        "sling.servlet.methods=HEAD"})
public class DynamicMediaServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(DynamicMediaServlet.class);

    /** Browsers and CDNs keep an image this long before asking again (with the ETag). */
    static final int MAX_AGE_SECONDS = 36_000;

    @Reference
    private transient DamClient client;

    public DynamicMediaServlet() {
    }

    DynamicMediaServlet(DamClient client) {
        this.client = client;
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        // the resource is synthetic (see DynamicMediaResourceProvider): its path names the asset
        String path = request.getResource().getPath();
        String suffix = request.getRequestPathInfo().getSuffix();
        String rel = path.startsWith(DynamicMedia.PREFIX) ? path.substring(DynamicMedia.PREFIX.length()) : path;
        String target = rel + (suffix == null ? "" : suffix);
        // /is/image/<asset>:<smart crop>
        String crop = null;
        int colon = target.lastIndexOf(':');
        if (colon > target.lastIndexOf('/')) {
            crop = target.substring(colon + 1);
            target = target.substring(0, colon);
        }
        Resource asset = DynamicMedia.resolve(request.getResourceResolver(), target);
        Asset a = asset == null ? null : asset.adaptTo(Asset.class);
        if (a == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String mime = a.getMimeType();
        if (mime == null || !mime.startsWith("image/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "not an image");
            return;
        }
        long srcW = number(a.getMetadataValue("tiff:ImageWidth"));
        long srcH = number(a.getMetadataValue("tiff:ImageLength"));
        if (srcW <= 0 || srcH <= 0) {
            srcW = 4096;
            srcH = 4096;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, RequestParameter[]> e : request.getRequestParameterMap().entrySet()) {
            RequestParameter[] v = e.getValue();
            params.put(e.getKey(), v == null || v.length == 0 ? "" : v[v.length - 1].getString());
        }
        String cropStep = null;
        if (crop != null) {
            cropStep = DamSmartCrop.cropStep(asset, crop);
            if (cropStep == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "no smart crop " + crop);
                return;
            }
        }
        DynamicMedia.Plan plan;
        try {
            plan = DynamicMedia.plan(request.getResourceResolver(), params, srcW, srcH);
            if (cropStep != null) {
                plan = plan.croppedFirst(cropStep);
            }
        } catch (DynamicMedia.BadParameter e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        if (!client.isAvailable()) {
            response.setHeader("Retry-After", "5");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        long lastModified = lastModified(asset, a);
        String blobId;
        try {
            blobId = OriginalBlobs.of(asset, lastModified, client);
        } catch (IOException e) {
            LOG.warn("Dynamic Media: cannot upload {}: {}", asset.getPath(), e.getMessage());
            response.setHeader("Retry-After", "5");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (blobId == null) {
            response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "this image cannot be delivered");
            return;
        }
        String etag = "\"" + blobId.substring(0, 16) + "-" + Integer.toHexString(plan.key().hashCode()) + "\"";
        String ifNoneMatch = request.getHeader("If-None-Match");
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", "public, max-age=" + MAX_AGE_SECONDS);
        response.setHeader("Vary", "Accept");
        response.setDateHeader("Last-Modified", lastModified);
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        DamClient.DeliveredImage image;
        try {
            try {
                image = client.deliver(blobId, plan.width, plan.height, plan.format, plan.quality, plan.transform);
            } catch (DamClient.DamProcessingException e) {
                if (e.getStatus() != 404) {
                    throw e;
                }
                // The blob id kept with the asset names a blob this studio-dam does not have: the asset was
                // processed by another instance's (it arrived here by publication), or the store was emptied.
                // The original is here, so it is uploaded and the image made from that.
                String uploaded = OriginalBlobs.onDemand(asset, lastModified, client);
                if (uploaded == null) {
                    throw e;
                }
                image = client.deliver(uploaded, plan.width, plan.height, plan.format, plan.quality, plan.transform);
            }
        } catch (DamClient.DamProcessingException e) {
            response.sendError(e.getStatus() == 404 ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        } catch (IOException e) {
            LOG.warn("Dynamic Media: studio-dam failed for {}: {}", asset.getPath(), e.getMessage());
            response.setHeader("Retry-After", "5");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        try (InputStream in = image.body) {
            response.setContentType(image.contentType);
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
    }

    static long lastModified(Resource asset, Asset a) {
        Resource content = asset.getChild("jcr:content");
        ValueMap cv = content == null ? ValueMap.EMPTY : content.getValueMap();
        Calendar c = cv.get("jcr:lastModified", Calendar.class);
        return Math.max(c == null ? 0 : c.getTimeInMillis(), a.getLastModified());
    }

    private static long number(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
