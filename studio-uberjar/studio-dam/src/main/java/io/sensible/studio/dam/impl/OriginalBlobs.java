/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * The studio-dam blob of an asset's original, for delivery: {@code studio:blobId} when studio-dam
 * processed the asset, otherwise uploaded on first use (content packages bring renditions, not
 * blob ids). On-demand uploads are remembered by path and modification time, so an edited
 * original is uploaded again; nothing is written to the repository on a GET.
 */
final class OriginalBlobs {

    static final int MAX_REMEMBERED = 10_000;

    private static final Map<String, String> UPLOADED = Collections.synchronizedMap(new LinkedHashMap<String, String>(256, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REMEMBERED;
        }
    });

    private OriginalBlobs() {
    }

    /** The blob of a processed asset, or {@code null}. */
    static String processed(Resource asset) {
        Resource content = asset == null ? null : asset.getChild("jcr:content");
        if (content == null) {
            return null;
        }
        ValueMap cv = content.getValueMap();
        if (!AssetProcessor.STATE_PROCESSED.equals(cv.get(AssetProcessor.PN_ASSET_STATE, String.class))) {
            return null;
        }
        return cv.get(AssetProcessor.PN_BLOB_ID, String.class);
    }

    /**
     * The blob of {@code asset}'s original at {@code lastModified}, uploading it when studio-dam
     * has no copy; {@code null} when it has no original or studio-dam refused it.
     */
    static String onDemand(Resource asset, long lastModified, DamClient client) throws IOException {
        if (asset == null) {
            return null;
        }
        String key = asset.getPath() + "@" + lastModified;
        String known = UPLOADED.get(key);
        if (known != null) {
            if (known.isEmpty()) {
                return null; // studio-dam refused it before (too large, unsupported)
            }
            DamHousekeeping.inUse(known);
            return known;
        }
        Resource original = asset.getChild("jcr:content/renditions/original");
        try (InputStream in = original == null ? null : original.adaptTo(InputStream.class)) {
            if (in == null) {
                return null;
            }
            // Content-addressed: uploading the same original twice stores it once.
            String blobId = client.putBlob(in);
            UPLOADED.put(key, blobId);
            DamHousekeeping.inUse(blobId);
            return blobId;
        } catch (DamClient.DamProcessingException e) {
            UPLOADED.put(key, "");
            return null;
        }
    }

    /** {@link #processed} or else {@link #onDemand}. */
    static String of(Resource asset, long lastModified, DamClient client) throws IOException {
        String id = processed(asset);
        return id != null ? id : onDemand(asset, lastModified, client);
    }
}
