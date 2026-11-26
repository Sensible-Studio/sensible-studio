/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api;

import java.io.InputStream;

/**
 * Creates and looks up assets. Obtain with {@code resolver.adaptTo(AssetManager.class)}.
 *
 * <p>Sensible Studio implements the subset needed to create assets; processing (renditions, metadata)
 * happens asynchronously after the original is stored.
 */
public interface AssetManager {

    /**
     * Creates an asset at {@code path}, or replaces the original binary of an existing one.
     *
     * @param path     absolute path of the asset node, e.g. {@code /content/dam/site/photo.jpg}
     * @param is       the original binary; not closed by this method
     * @param mimeType mime type of the binary
     * @param doSave   {@code true} to commit the resolver before returning
     * @return the asset, or {@code null} if it could not be created
     */
    Asset createAsset(String path, InputStream is, String mimeType, boolean doSave);

    /** @return the asset whose node has this identifier, or {@code null} */
    Asset getAssetByIdentifier(String id);

    /** @return {@code true} if a {@code dam:Asset} exists at {@code assetPath} */
    boolean assetExists(String assetPath);
}
