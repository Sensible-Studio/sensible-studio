/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api;

import java.io.InputStream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * One binary representation of an asset ({@code original}, {@code cq5dam.web.1280.1280.jpeg}, ...),
 * stored as an {@code nt:file} below {@code jcr:content/renditions}.
 */
public interface Rendition extends Resource {

    /** @return the mime type */
    String getMimeType();

    /** @return properties of the rendition's {@code jcr:content} */
    ValueMap getProperties();

    /** @return size in bytes, {@code -1} if unknown */
    long getSize();

    /** @return the binary, or {@code null} */
    InputStream getStream();

    /** @return the asset this rendition belongs to */
    Asset getAsset();
}
