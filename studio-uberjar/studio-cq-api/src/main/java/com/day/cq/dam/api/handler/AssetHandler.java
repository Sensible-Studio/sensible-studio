/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api.handler;

import java.awt.image.BufferedImage;
import java.io.IOException;

import com.day.cq.dam.api.Rendition;

/**
 * Format-specific asset processing.
 */
public interface AssetHandler {

    /** @return the rendition decoded as an image, or {@code null} if not an image */
    BufferedImage getImage(Rendition rendition) throws IOException;
}
