/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.RenditionPicker;

/**
 * Picks the web rendition ({@code cq5dam.web.*}) of an asset, falling back to the original.
 */
public class WCMRenditionPicker implements RenditionPicker {

    @Override
    public Rendition getRendition(Asset asset) {
        if (asset == null) {
            return null;
        }
        for (Rendition r : asset.getRenditions()) {
            if (r.getName().startsWith("cq5dam.web.")) {
                return r;
            }
        }
        return asset.getOriginal();
    }
}
