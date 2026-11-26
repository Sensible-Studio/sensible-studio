/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.wcm.spi;

import java.util.Map;

import org.apache.sling.api.resource.Resource;

/**
 * Optional service that delivers assets from a CDN. Sensible Studio registers no implementation, so
 * components fall back to their own image servlets.
 */
public interface AssetDelivery {

    /**
     * @param resource  the asset
     * @param parameter delivery parameters (width, format, quality, ...)
     * @return the delivery URL, or {@code null}
     */
    String getDeliveryURL(Resource resource, Map<String, Object> parameter);
}
