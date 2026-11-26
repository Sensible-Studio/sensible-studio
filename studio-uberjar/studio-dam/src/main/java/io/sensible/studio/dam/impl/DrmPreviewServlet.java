/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * The preview of a licensed file while DRM is on (Assets Configurations): its web rendition with the
 * system watermark drawn on it, which is what the console shows large instead of the clean picture
 * (AEM's DRM form: "Watermarking need to be configured").
 *
 * <pre>
 * GET /bin/studio/dam/drmpreview.img?path=&lt;asset&gt;
 * </pre>
 * 404 when the file has no license, DRM is off, no watermark is set, or the picture cannot be read here
 * (the console then shows the usual preview). Read access is the requesting user's.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/drmpreview",
        "sling.servlet.extensions=img",
        "sling.servlet.methods=GET"})
public class DrmPreviewServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    /** Pictures larger than this are not drawn on here (pixels). */
    static final long MAX_PIXELS = 40_000_000L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String path = request.getParameter("path");
        Resource asset = path != null && DamItems.inDam(path) && !path.contains("..") ? request.getResourceResolver().getResource(path) : null;
        if (asset == null || !DamItems.isAsset(asset) || !DamAssetsConfig.drmEnabled(request.getResourceResolver())
                || DamAssetsConfig.license(asset) == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        byte[] body = render(asset);
        if (body == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(body.length > 1 && (body[0] & 0xff) == 0x89 ? "image/png" : "image/jpeg");
        response.setHeader("Cache-Control", "private, max-age=300");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** The asset's web rendition (else its thumbnail) with the watermark; null when it cannot be made. */
    static byte[] render(Resource asset) throws IOException {
        Resource rendition = DamItems.webRendition(asset);
        if (rendition == null) {
            rendition = asset.getChild("jcr:content/renditions/cq5dam.thumbnail.319.319.png");
        }
        if (rendition == null) {
            return null;
        }
        BufferedImage picture;
        try (InputStream in = stream(rendition)) {
            picture = in == null ? null : ImageIO.read(in);
        }
        if (picture == null || (long) picture.getWidth() * picture.getHeight() > MAX_PIXELS) {
            return null;
        }
        BufferedImage marked = DamWatermark.draw(asset.getResourceResolver(), picture);
        if (marked == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(marked, marked.getColorModel().hasAlpha() ? "png" : "jpg", out)) {
            return null;
        }
        return out.toByteArray();
    }

    private static InputStream stream(Resource file) {
        InputStream in = file.adaptTo(InputStream.class);
        if (in == null) {
            Resource c = file.getChild("jcr:content");
            in = c == null ? null : c.adaptTo(InputStream.class);
        }
        return in;
    }
}
