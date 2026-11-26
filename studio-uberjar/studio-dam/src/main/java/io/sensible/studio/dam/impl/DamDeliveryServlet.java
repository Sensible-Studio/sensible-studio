/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@link DeliveryUrls} from studio-dam. Public (works for anonymous visitors on publish):
 * the signature, made with a key only Sensible Studio and studio-dam know, is the access check — only images
 * that Sensible Studio itself put into a rendered page can be fetched.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=" + DeliveryUrls.PREFIX,
        "sling.servlet.methods=GET",
        "sling.servlet.methods=HEAD"})
public class DamDeliveryServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(DamDeliveryServlet.class);

    @Reference
    private transient DamClient client;

    public DamDeliveryServlet() {
    }

    DamDeliveryServlet(DamClient client) {
        this.client = client;
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        DeliveryUrls.Request req = DeliveryUrls.parse(request.getRequestPathInfo().getSuffix());
        if (req == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String expected;
        try {
            expected = client.signUrl(req.canonical());
        } catch (IOException e) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (!DeliveryUrls.signatureMatches(expected, req.signature)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        DamClient.DeliveredImage image;
        try {
            try {
                image = client.deliver(req.blobId, DeliveryUrls.snapWidth(req.width), req.format, req.quality);
            } catch (DamClient.DamProcessingException e) {
                image = e.getStatus() == 404 ? fromOriginal(request.getResourceResolver(), req) : null;
                if (image == null) {
                    throw e;
                }
            }
        } catch (DamClient.DamProcessingException e) {
            response.sendError(e.getStatus() == 404 ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_REQUEST);
            return;
        } catch (IOException e) {
            LOG.warn("studio-dam delivery failed: {}", e.getMessage());
            response.setHeader("Retry-After", "5");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        try (InputStream in = image.body) {
            response.setContentType(image.contentType);
            // Blob id = content hash, so the URL never changes meaning.
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
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

    /**
     * The blob named in the URL is not in this instance's studio-dam: the asset was processed by
     * another instance (it arrived here by publication) or the store was emptied. Blob ids are
     * content hashes, so the asset that names it is found through the Media index and its original
     * uploaded; the visitor must be able to read that asset. {@code null} when there is none.
     */
    DamClient.DeliveredImage fromOriginal(ResourceResolver resolver, DeliveryUrls.Request req) throws IOException {
        List<String> assets = assetsWithBlob(resolver, req.blobId);
        for (String path : assets) {
            Resource asset = resolver.getResource(path);
            Resource content = asset == null ? null : asset.getChild("jcr:content");
            if (content == null) {
                continue;
            }
            Calendar modified = content.getValueMap().get("jcr:lastModified", Calendar.class);
            String uploaded = OriginalBlobs.onDemand(asset, modified == null ? 0 : modified.getTimeInMillis(), client);
            if (uploaded != null) {
                LOG.info("Delivering {} from its original: studio-dam had no blob {}", path, req.blobId);
                return client.deliver(uploaded, DeliveryUrls.snapWidth(req.width), req.format, req.quality);
            }
        }
        return null;
    }

    /** The assets (this reader may read) that studio-dam processed into {@code blobId}; empty before the Media index is built. */
    List<String> assetsWithBlob(ResourceResolver resolver, String blobId) {
        Session session = resolver.adaptTo(Session.class);
        if (session == null || !DamIndex.ready(session)) {
            return Collections.emptyList();
        }
        try {
            return DamIndexQueries.byBlob(session, blobId);
        } catch (RepositoryException e) {
            return Collections.emptyList();
        }
    }
}
