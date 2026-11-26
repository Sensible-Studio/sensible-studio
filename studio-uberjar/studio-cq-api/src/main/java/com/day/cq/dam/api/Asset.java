/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.adapter.Adaptable;

/**
 * A digital asset: a {@code dam:Asset} node with renditions and metadata. Obtain with
 * {@code resource.adaptTo(Asset.class)}.
 */
public interface Asset extends Adaptable {

    String getPath();

    String getName();

    /** @return a stable identifier (the node identifier, else the path) */
    String getID();

    /** @return last modification time in milliseconds */
    long getLastModified();

    /** @return the original's mime type */
    String getMimeType();

    /** @return the metadata property, or {@code null} */
    Object getMetadata(String name);

    /** @return the metadata property as string, or {@code null} */
    String getMetadataValue(String name);

    /** @return same as {@link #getMetadataValue(String)}; Sensible Studio keeps metadata only in the repository */
    String getMetadataValueFromJcr(String name);

    /** @return all metadata properties */
    Map<String, Object> getMetadata();

    /** @return the {@code original} rendition, or {@code null} */
    Rendition getOriginal();

    /** @return the named rendition, or {@code null} */
    Rendition getRendition(String name);

    /** @return the rendition chosen by the picker */
    Rendition getRendition(RenditionPicker picker);

    /** @return all renditions */
    List<Rendition> getRenditions();
}
