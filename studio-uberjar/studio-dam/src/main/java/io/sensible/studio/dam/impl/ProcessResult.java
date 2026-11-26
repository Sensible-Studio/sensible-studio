/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The answer of {@code POST /process}, see dam-service/README.md. */
public final class ProcessResult {

    /** One produced rendition, stored in studio-dam under {@link #blobId}. */
    public static final class RenditionInfo {
        public final String name;
        public final String blobId;
        public final String mimeType;
        public final long size;
        public final String sha1;
        public final long width;
        public final long height;

        public RenditionInfo(String name, String blobId, String mimeType, long size, String sha1, long width, long height) {
            this.name = name;
            this.blobId = blobId;
            this.mimeType = mimeType;
            this.size = size;
            this.sha1 = sha1;
            this.width = width;
            this.height = height;
        }
    }

    /** A rendition that cannot be produced for this file type. */
    public static final class Failure {
        public final String name;
        public final String reason;
        public final String message;

        public Failure(String name, String reason, String message) {
            this.name = name;
            this.reason = reason;
            this.message = message;
        }
    }

    private final String blobId;
    private final String mimeType;
    private final Map<String, Object> metadata;
    private final String xmpPacket;
    private final List<RenditionInfo> renditions;
    private final List<Failure> failed;

    public ProcessResult(String blobId, String mimeType, Map<String, Object> metadata, String xmpPacket,
            List<RenditionInfo> renditions, List<Failure> failed) {
        this.blobId = blobId;
        this.mimeType = mimeType;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.xmpPacket = xmpPacket;
        this.renditions = Collections.unmodifiableList(new ArrayList<>(renditions));
        this.failed = Collections.unmodifiableList(new ArrayList<>(failed));
    }

    @SuppressWarnings("unchecked")
    static ProcessResult fromJson(String json) {
        Map<String, Object> root = Json.parseObject(json);
        List<RenditionInfo> renditions = new ArrayList<>();
        for (Object o : list(root.get("renditions"))) {
            Map<String, Object> r = (Map<String, Object>) o;
            renditions.add(new RenditionInfo(str(r.get("name")), str(r.get("blobId")), str(r.get("mimeType")),
                    num(r.get("size")), str(r.get("sha1")), num(r.get("width")), num(r.get("height"))));
        }
        List<Failure> failed = new ArrayList<>();
        for (Object o : list(root.get("failed"))) {
            Map<String, Object> f = (Map<String, Object>) o;
            failed.add(new Failure(str(f.get("name")), str(f.get("reason")), str(f.get("message"))));
        }
        Object md = root.get("metadata");
        return new ProcessResult(str(root.get("blobId")), str(root.get("mimeType")),
                md instanceof Map ? (Map<String, Object>) md : Collections.emptyMap(),
                str(root.get("xmpPacket")), renditions, failed);
    }

    private static List<?> list(Object o) {
        return o instanceof List ? (List<?>) o : Collections.emptyList();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }

    public String getBlobId() {
        return blobId;
    }

    public String getMimeType() {
        return mimeType;
    }

    /** Metadata in JCR property names: Long, Double, String, Boolean or List values. */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** Raw XMP packet of the original, or {@code null}. */
    public String getXmpPacket() {
        return xmpPacket;
    }

    public List<RenditionInfo> getRenditions() {
        return renditions;
    }

    public List<Failure> getFailed() {
        return failed;
    }
}
