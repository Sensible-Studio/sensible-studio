/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api;

/**
 * Chooses one rendition of an asset.
 */
public interface RenditionPicker {

    /** @return the chosen rendition, or {@code null} */
    Rendition getRendition(Asset asset);
}
