/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.dam;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.osgi.service.component.annotations.Component;

import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.handler.AssetHandler;
import com.day.cq.dam.api.handler.store.AssetStore;

/**
 * One ImageIO-based handler for every raster type the JVM can decode. Vector and video formats
 * have no handler. The Rust dam-service is meant to take over heavy processing later.
 */
@Component(service = AssetStore.class)
public class AssetStoreImpl implements AssetStore {

    private static final AssetHandler IMAGEIO = new AssetHandler() {
        @Override
        public BufferedImage getImage(Rendition rendition) throws IOException {
            if (rendition == null) {
                return null;
            }
            try (InputStream in = rendition.getStream()) {
                return in == null ? null : ImageIO.read(in);
            }
        }
    };

    @Override
    public AssetHandler getAssetHandler(String mimeType) {
        if (mimeType == null || !mimeType.startsWith("image/") || mimeType.contains("svg")) {
            return null;
        }
        String format = mimeType.substring("image/".length());
        return ImageIO.getImageReadersByMIMEType(mimeType).hasNext() || ImageIO.getImageReadersByFormatName(format).hasNext()
                ? IMAGEIO : null;
    }
}
