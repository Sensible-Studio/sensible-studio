/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Operations Sensible Studio needs from the studio-dam service.
 *
 * <p>{@link IOException} means "try again later" (service down, network, 5xx).
 * {@link DamProcessingException} means the request itself can never succeed.
 */
public interface DamClient {

    /** Uploads a binary; returns its content id (SHA-256 hex). The stream is consumed but not closed. */
    String putBlob(InputStream in) throws IOException;

    /** Produces renditions and metadata for an uploaded original. */
    ProcessResult process(String blobId, String mimeType) throws IOException;

    /**
     * {@link #process(String, String)} plus the renditions of a processing profile
     * ({@link DamProfiles#renditions}).
     */
    default ProcessResult process(String blobId, String mimeType, java.util.List<java.util.Map<String, Object>> extra) throws IOException {
        return process(blobId, mimeType);
    }

    /**
     * {@link #process(String, String, java.util.List)} with the watermark drawn on the renditions
     * marked {@code watermark} ({@link DamWatermark#forProcessing}: blobId, position, opacity, size).
     */
    default ProcessResult process(String blobId, String mimeType, java.util.List<java.util.Map<String, Object>> extra,
            java.util.Map<String, Object> watermark) throws IOException {
        return process(blobId, mimeType, extra);
    }

    /** Streams a binary (rendition or original). The caller closes the stream. */
    InputStream getBlob(String blobId) throws IOException;

    /**
     * Crops, rotates and flips an uploaded original (operations as checked by
     * {@link ImageEdit#operations}). The result is stored as a new original in the same format.
     */
    EditResult edit(String blobId, List<Map<String, Object>> operations) throws IOException;

    /** @return {@code true} if the service answers its health check (makes a request) */
    boolean isHealthy();

    /** @return {@code true} if requests can be sent now (cheap; no request) */
    boolean isAvailable();

    /**
     * Resized image for public delivery. {@code width} is the bounding width; the image is never
     * upscaled. {@code format} is {@code jpeg}, {@code png} or {@code webp}.
     */
    DeliveredImage deliver(String blobId, int width, String format, int quality) throws IOException;

    /**
     * {@link #deliver(String, int, String, int)} after a transform on the original (studio-dam's
     * {@code t} parameter: {@code c<x>,<y>,<w>,<h>}, {@code r90|r180|r270}, {@code h}, {@code v},
     * joined with {@code -}). Empty or {@code null}: no transform.
     */
    default DeliveredImage deliver(String blobId, int width, String format, int quality, String transform) throws IOException {
        if (transform == null || transform.isEmpty()) {
            return deliver(blobId, width, format, quality);
        }
        throw new DamProcessingException(0, "this client cannot transform images");
    }

    /**
     * Signs a Sensible Studio delivery URL. The key is derived from the shared token, so URLs stay valid as
     * long as the token does, and cannot be forged by anyone who does not know it.
     *
     * @throws IOException if no token is available (service not configured)
     */
    String signUrl(String canonical) throws IOException;

    /** Whether the Smart Tags model is installed, and its files (name, size, sha256, url, present). */
    default Map<String, Object> smartTagsStatus() throws IOException {
        throw new DamProcessingException(501, "this client has no Smart Tags");
    }

    /** Uploads one model file; studio-dam keeps it only if its size and SHA-256 are the pinned ones. */
    default void installSmartTagsFile(String name, InputStream in) throws IOException {
        throw new DamProcessingException(501, "this client has no Smart Tags");
    }

    /**
     * The labels (each {@code id}, {@code text}) that fit an uploaded image best, as maps with
     * {@code id}, {@code confidence} and {@code score}. 409 ({@link DamProcessingException}) while
     * the model is not installed.
     */
    default List<Map<String, Object>> predictTags(String blobId, List<Map<String, String>> labels, int max, double minConfidence,
            double minScore) throws IOException {
        throw new DamProcessingException(501, "this client has no Smart Tags");
    }

    /**
     * The embedding of an uploaded image (768 numbers of length 1), for "find similar". 409
     * ({@link DamProcessingException}) while the model is not installed.
     */
    default float[] embed(String blobId) throws IOException {
        throw new DamProcessingException(501, "this client has no image embeddings");
    }

    /**
     * The embedding of a phrase in the space of {@link #embed}, with the model's scale and bias:
     * a picture fits the phrase as {@code sigmoid(scale * dot + bias)}. For finding pictures by words.
     */
    default TextEmbedding embedText(String text) throws IOException {
        throw new DamProcessingException(501, "this client has no text embeddings");
    }

    /**
     * A frame of an uploaded video as a JPEG, taken by the ffmpeg of the machine studio-dam runs
     * on. 409 when there is no ffmpeg, 415 when the file is not a video it reads.
     */
    default byte[] videoPoster(String blobId) throws IOException {
        throw new DamProcessingException(501, "this client reads no video frames");
    }

    /**
     * An uploaded video as an MP4 any browser plays (H.264, 720 lines at most): the blob id of
     * the result. Takes as long as the video is long; same errors as {@link #videoPoster}.
     */
    default String videoPreview(String blobId) throws IOException {
        throw new DamProcessingException(501, "this client makes no video previews");
    }

    /**
     * An uploaded video encoded as {@code format} ({@code mp4} or {@code webm}) with at most
     * {@code height} lines: the blob id of the result (Processing Profiles' video renditions).
     */
    default String videoEncode(String blobId, String format, int height) throws IOException {
        throw new DamProcessingException(501, "this client encodes no video");
    }

    /**
     * Whether the machine studio-dam runs on has ffmpeg (posters, thumbnails and browser copies of
     * videos need it); {@code null} when that cannot be told.
     */
    default Boolean videoTools() {
        return null;
    }

    /** {@link #smartTagsStatus} of the optional multilingual model (picture search in any language). */
    default Map<String, Object> searchModelStatus() throws IOException {
        throw new DamProcessingException(501, "this client has no search model");
    }

    /** {@link #installSmartTagsFile} for the multilingual model. */
    default void installSearchModelFile(String name, InputStream in) throws IOException {
        throw new DamProcessingException(501, "this client has no search model");
    }

    /** {@link #embed} with the multilingual model; 409 while it is not installed. */
    default float[] searchEmbed(String blobId) throws IOException {
        throw new DamProcessingException(501, "this client has no search model");
    }

    /** {@link #embedText} with the multilingual model: the phrase may be in any language. */
    default TextEmbedding searchEmbedText(String text) throws IOException {
        throw new DamProcessingException(501, "this client has no search model");
    }

    /** What {@link #embedText} answers. */
    final class TextEmbedding {
        public final float[] embedding;
        public final double scale;
        public final double bias;

        public TextEmbedding(float[] embedding, double scale, double bias) {
            this.embedding = embedding;
            this.scale = scale;
            this.bias = bias;
        }
    }

    /**
     * {@link #deliver(String, int, String, int, String)} with a bounding box: Dynamic Media
     * requests give a height too, and a fit mode in the transform.
     */
    default DeliveredImage deliver(String blobId, int width, int height, String format, int quality, String transform)
            throws IOException {
        return deliver(blobId, width, format, quality, transform);
    }

    /**
     * Smart Crop: for each ratio (maps with name, width, height) the window of the image that
     * keeps its subject. Returns {@code width}, {@code height} of the image and {@code crops}
     * (name, x, y, width, height, normalized).
     */
    default Map<String, Object> smartCrop(String blobId, List<Map<String, Object>> ratios) throws IOException {
        throw new DamProcessingException(501, "this client has no Smart Crop");
    }

    /**
     * Removes from studio-dam the blobs not in {@code keep} and older than {@code graceSeconds}
     * (with {@code dryRun}, only counts them). Returns {@code blobs} (files, bytes, removed, freed).
     */
    default Map<String, Object> gcBlobs(java.util.Collection<String> keep, long graceSeconds, boolean dryRun) throws IOException {
        throw new DamProcessingException(501, "this client has no blob GC");
    }

    /** Body and headers of a delivered image. The caller closes {@link #body}. */
    final class DeliveredImage {
        public final String contentType;
        public final String cacheStatus;
        public final long length;
        public final InputStream body;

        public DeliveredImage(String contentType, String cacheStatus, long length, InputStream body) {
            this.contentType = contentType;
            this.cacheStatus = cacheStatus;
            this.length = length;
            this.body = body;
        }
    }

    /** The original produced by {@link #edit}. */
    final class EditResult {
        public final String blobId;
        public final String mimeType;
        public final long width;
        public final long height;
        public final long size;

        public EditResult(String blobId, String mimeType, long width, long height, long size) {
            this.blobId = blobId;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.size = size;
        }
    }

    /** Thrown for requests the service rejects for good (bad input, unsupported file, auth). */
    class DamProcessingException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public DamProcessingException(int status, String message) {
            super("studio-dam returned " + status + ": " + message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
