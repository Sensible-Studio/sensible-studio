/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory stand-in for studio-dam with a scripted answer. */
class FakeDamClient implements DamClient {

    final Map<String, byte[]> blobs = new HashMap<>();
    final List<String> processed = new ArrayList<>();
    ProcessResult nextResult;
    /** Answers for the next calls, in order, before {@link #nextResult}. */
    final java.util.ArrayDeque<ProcessResult> results = new java.util.ArrayDeque<>();
    IOException failWith;
    IOException failGetBlobWith;
    Runnable duringProcess;

    @Override
    public String putBlob(InputStream in) throws IOException {
        if (failWith != null) {
            throw failWith;
        }
        return store(in.readAllBytes());
    }

    String store(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder();
            for (byte b : d) {
                s.append(String.format("%02x", b));
            }
            blobs.put(s.toString(), bytes);
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The profile renditions asked for by the last process call. */
    java.util.List<java.util.Map<String, Object>> lastExtra = java.util.Collections.emptyList();

    @Override
    public ProcessResult process(String blobId, String mimeType, java.util.List<java.util.Map<String, Object>> extra) throws IOException {
        lastExtra = extra;
        return process(blobId, mimeType);
    }

    java.util.Map<String, Object> lastWatermark;

    @Override
    public ProcessResult process(String blobId, String mimeType, java.util.List<java.util.Map<String, Object>> extra,
            java.util.Map<String, Object> watermark) throws IOException {
        lastWatermark = watermark;
        return process(blobId, mimeType, extra);
    }

    @Override
    public ProcessResult process(String blobId, String mimeType) throws IOException {
        if (failWith != null) {
            throw failWith;
        }
        processed.add(blobId + " " + mimeType);
        if (duringProcess != null) {
            duringProcess.run();
        }
        return results.isEmpty() ? nextResult : results.poll();
    }

    @Override
    public InputStream getBlob(String blobId) throws IOException {
        if (failGetBlobWith != null) {
            throw failGetBlobWith;
        }
        byte[] b = blobs.get(blobId);
        if (b == null) {
            throw new IOException("no blob " + blobId);
        }
        return new ByteArrayInputStream(b);
    }

    final List<List<Map<String, Object>>> edits = new ArrayList<>();
    /** What {@link #edit} stores as the new original. */
    byte[] editedBytes = "edited".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    long editedWidth = 300;
    long editedHeight = 200;

    @Override
    public EditResult edit(String blobId, List<Map<String, Object>> operations) throws IOException {
        if (failWith != null) {
            throw failWith;
        }
        if (!blobs.containsKey(blobId)) {
            throw new IOException("no blob " + blobId);
        }
        edits.add(operations);
        String id = store(editedBytes);
        return new EditResult(id, "image/png", editedWidth, editedHeight, editedBytes.length);
    }

    /** {@link #embed}: the direction (0..767) the picture whose first byte is the key points in; others get 501. */
    final Map<Integer, float[]> embeddings = new HashMap<>();

    /** {@link #embedText}: the direction each phrase points in. */
    final Map<String, float[]> phrases = new HashMap<>();

    /** Blob ids this service has never seen (another instance's): delivering them answers 404. */
    final java.util.Set<String> unknownBlobs = new java.util.HashSet<>();

    /** {@link #videoPoster}: the frame ffmpeg would give, or null for a machine without ffmpeg. */
    byte[] videoFrame;
    /** what {@link #videoTools()} answers */
    Boolean ffmpeg;

    @Override
    public Boolean videoTools() {
        return ffmpeg;
    }
    /** {@link #videoPreview}: the preview ffmpeg would make, or null for a machine without ffmpeg. */
    byte[] videoPreviewBytes;
    int previews;

    @Override
    public byte[] videoPoster(String blobId) throws IOException {
        if (videoFrame == null) {
            throw new DamProcessingException(409, "ffmpeg is not installed");
        }
        return videoFrame;
    }

    @Override
    public String videoPreview(String blobId) throws IOException {
        if (videoPreviewBytes == null) {
            throw new DamProcessingException(409, "ffmpeg is not installed");
        }
        previews++;
        return store(videoPreviewBytes);
    }

    /** what videoEncode was asked: blob format height */
    final List<String> encoded = new ArrayList<>();

    @Override
    public String videoEncode(String blobId, String format, int height) throws IOException {
        if (videoPreviewBytes == null) {
            throw new DamProcessingException(409, "ffmpeg is not installed");
        }
        encoded.add(blobId + " " + format + " " + height);
        return store(("webm".equals(format) ? "webm " : "mp4 ").concat(String.valueOf(height)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The multilingual model: absent unless these are set (pictures by first byte, phrases in any language). */
    final Map<Integer, float[]> searchEmbeddings = new HashMap<>();
    final Map<String, float[]> searchPhrases = new HashMap<>();
    boolean searchInstalled;

    @Override
    public Map<String, Object> searchModelStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("model", "siglip-base-patch16-256-multilingual");
        m.put("installed", searchInstalled);
        return m;
    }

    @Override
    public float[] searchEmbed(String blobId) throws IOException {
        byte[] bytes = blobs.get(blobId);
        float[] e = !searchInstalled || bytes == null || bytes.length == 0 ? null : searchEmbeddings.get((int) bytes[0]);
        if (e == null) {
            throw new DamProcessingException(409, "the picture search model is not installed");
        }
        return e;
    }

    @Override
    public TextEmbedding searchEmbedText(String text) throws IOException {
        float[] e = searchInstalled ? searchPhrases.get(text) : null;
        if (e == null) {
            throw new DamProcessingException(409, "the picture search model is not installed");
        }
        return new TextEmbedding(e, 12, -6);
    }

    @Override
    public TextEmbedding embedText(String text) throws IOException {
        float[] e = phrases.get(text);
        if (e == null) {
            throw new DamProcessingException(501, "no such phrase in this fake");
        }
        return new TextEmbedding(e, 10, -5);
    }

    @Override
    public float[] embed(String blobId) throws IOException {
        byte[] bytes = blobs.get(blobId);
        float[] e = bytes == null || bytes.length == 0 ? null : embeddings.get((int) bytes[0]);
        if (e == null) {
            throw new DamProcessingException(501, "no embedding in this fake");
        }
        return e;
    }

    /** What {@link #predictTags} answers, and the labels it was last asked about. */
    List<Map<String, Object>> predictions = new ArrayList<>();
    List<Map<String, String>> lastLabels;
    IOException failPredictWith;

    @Override
    public List<Map<String, Object>> predictTags(String blobId, List<Map<String, String>> labels, int max, double minConfidence,
            double minScore) throws IOException {
        if (failPredictWith != null) {
            throw failPredictWith;
        }
        if (!blobs.containsKey(blobId)) {
            throw new IOException("no blob " + blobId);
        }
        lastLabels = labels;
        return predictions;
    }

    /** Smart Crop: the middle of a 1000x500 image, so tests can check the stored fractions. */
    final List<String> cropped = new java.util.ArrayList<>();

    @Override
    public Map<String, Object> smartCrop(String blobId, List<Map<String, Object>> ratios) throws IOException {
        if (failWith != null) {
            throw failWith;
        }
        cropped.add(blobId + " " + ratios.stream().map(r -> String.valueOf(r.get("name"))).collect(java.util.stream.Collectors.joining(",")));
        long iw = 1000;
        long ih = 500;
        List<Map<String, Object>> crops = new java.util.ArrayList<>();
        for (Map<String, Object> r : ratios) {
            long rw = ((Number) r.get("width")).longValue();
            long rh = ((Number) r.get("height")).longValue();
            long w;
            long h;
            if (iw * rh <= ih * rw) {
                w = iw;
                h = iw * rh / rw;
            } else {
                h = ih;
                w = ih * rw / rh;
            }
            long x = (iw - w) / 2;
            long y = (ih - h) / 2;
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            c.put("name", r.get("name"));
            c.put("x", x);
            c.put("y", y);
            c.put("width", w);
            c.put("height", h);
            c.put("normalized", List.of(x / (double) iw, y / (double) ih, w / (double) iw, h / (double) ih));
            crops.add(c);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("width", iw);
        out.put("height", ih);
        out.put("crops", crops);
        return out;
    }

    @Override
    public Map<String, Object> gcBlobs(java.util.Collection<String> keep, long graceSeconds, boolean dryRun) {
        gcKeep = new java.util.TreeSet<>(keep);
        Map<String, Object> blobs = new java.util.LinkedHashMap<>();
        blobs.put("removed", dryRun ? 2L : 0L);
        return java.util.Collections.singletonMap("blobs", blobs);
    }

    /** The keep list of the last {@link #gcBlobs}. */
    java.util.Set<String> gcKeep;

    @Override
    public Map<String, Object> smartTagsStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("model", "siglip-base-patch16-224");
        m.put("installed", true);
        return m;
    }

    @Override
    public boolean isHealthy() {
        return failWith == null;
    }

    boolean available = true;
    final List<String> delivered = new ArrayList<>();

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, String format, int quality) throws IOException {
        if (failWith != null) {
            throw failWith;
        }
        if (unknownBlobs.contains(blobId)) {
            throw new DamProcessingException(404, "no such blob");
        }
        delivered.add(blobId + " " + width + " " + format + " " + quality);
        byte[] body = ("image " + width).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new DeliveredImage("image/" + format, "miss", body.length, new ByteArrayInputStream(body));
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, String format, int quality, String transform) throws IOException {
        if (transform == null || transform.isEmpty()) {
            return deliver(blobId, width, format, quality);
        }
        if (failWith != null) {
            throw failWith;
        }
        delivered.add(blobId + " " + width + " " + format + " " + quality + " " + transform);
        byte[] body = ("image " + width).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new DeliveredImage("image/" + format, "miss", body.length, new ByteArrayInputStream(body));
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, int height, String format, int quality, String transform) throws IOException {
        if (failWith != null) {
            throw failWith;
        }
        if (unknownBlobs.contains(blobId)) {
            throw new DamProcessingException(404, "not found");
        }
        delivered.add(blobId + " " + width + "x" + height + " " + format + " " + quality + (transform == null || transform.isEmpty() ? "" : " " + transform));
        byte[] body = ("image " + width).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new DeliveredImage("image/" + format, "miss", body.length, new ByteArrayInputStream(body));
    }

    @Override
    public String signUrl(String canonical) {
        return HttpDamClient.hmacHex("test-key".getBytes(java.nio.charset.StandardCharsets.UTF_8), canonical).substring(0, 32);
    }
}
