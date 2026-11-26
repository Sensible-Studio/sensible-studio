/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.wcm.spi.AssetDelivery;

/**
 * Lets Core Components' Image serve web-optimized images from studio-dam when the image policy
 * enables it ({@code enableAssetDelivery = true}, as WKND does).
 *
 * <p>Returns {@code null} — and Core Components fall back to their own image servlet — when
 * the asset is not processed yet, is not JPEG/PNG/WebP, asks for crop/rotate/flip (not done by
 * studio-dam yet), or the service is not available.
 *
 * <p>Parameters used, as passed by Core Components: {@code format}, {@code preferwebp},
 * {@code quality}, {@code width}, {@code seoname}, {@code c}, {@code r}, {@code flip}.
 */
@Component(service = AssetDelivery.class)
public class AssetDeliveryImpl implements AssetDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(AssetDeliveryImpl.class);

    @Reference
    private DamClient client;

    public AssetDeliveryImpl() {
    }

    AssetDeliveryImpl(DamClient client) {
        this.client = client;
    }

    @Override
    public String getDeliveryURL(Resource resource, Map<String, Object> parameter) {
        if (resource == null || parameter == null || !client.isAvailable()) {
            return null;
        }
        if (parameter.get("c") != null || parameter.get("r") != null || parameter.get("flip") != null) {
            return null;
        }
        Resource content = resource.getChild("jcr:content");
        if (content == null) {
            return null;
        }
        ValueMap props = content.getValueMap();
        String blobId = props.get(AssetProcessor.PN_BLOB_ID, String.class);
        if (blobId == null || !AssetProcessor.STATE_PROCESSED.equals(props.get(AssetProcessor.PN_ASSET_STATE, String.class))) {
            return null;
        }
        String format = format(str(parameter.get("format")), "true".equalsIgnoreCase(str(parameter.get("preferwebp"))));
        if (format == null) {
            return null;
        }
        int quality = "png".equals(format) ? 0 : intOr(parameter.get("quality"), DeliveryUrls.DEFAULT_QUALITY, 1, 100);
        String width = str(parameter.get("width"));
        if (width == null || !width.matches("\\d{1,10}")) {
            width = String.valueOf(DeliveryUrls.DEFAULT_WIDTH);
        }
        String seo = str(parameter.get("seoname"));
        try {
            String sig = client.signUrl(DeliveryUrls.canonical(blobId, format, quality));
            return DeliveryUrls.build(sig, blobId, width, quality, seo == null ? resource.getName() : seo, format);
        } catch (IOException e) {
            LOG.debug("No delivery URL for {}: {}", resource.getPath(), e.getMessage());
            return null;
        }
    }

    /** WebP when the caller prefers it (every current browser decodes it), else the requested format. */
    private static String format(String requested, boolean preferWebp) {
        if (requested == null) {
            return null;
        }
        String f = requested.toLowerCase(java.util.Locale.ROOT);
        if ("jpg".equals(f)) {
            f = "jpeg";
        }
        if (!"jpeg".equals(f) && !"png".equals(f) && !"webp".equals(f)) {
            return null;
        }
        return preferWebp ? "webp" : f;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOr(Object o, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(String.valueOf(o));
            return v < min || v > max ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
