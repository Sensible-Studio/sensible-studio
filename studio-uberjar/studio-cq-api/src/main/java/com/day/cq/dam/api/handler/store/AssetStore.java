/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api.handler.store;

import com.day.cq.dam.api.handler.AssetHandler;

/**
 * OSGi service returning the handler for a mime type.
 */
public interface AssetStore {

    /** @return the handler for the mime type, or {@code null} */
    AssetHandler getAssetHandler(String mimeType);
}
