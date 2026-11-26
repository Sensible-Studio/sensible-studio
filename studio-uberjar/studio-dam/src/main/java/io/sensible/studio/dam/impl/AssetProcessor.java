/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes one asset: sends the original to studio-dam, then writes what comes back.
 *
 * <pre>
 * jcr:content
 *   dam:assetState        processing → processed (or failed)
 *   studio:blobId            content id of the original in studio-dam (used for delivery URLs)
 *   metadata              extracted metadata, merged (see below)
 *   dam:failedRenditions  one child per rendition that cannot be produced
 *   renditions/cq5dam.*   nt:file + jcr:content/metadata {dam:sha1, dam:size, dc:format, tiff:*}
 * </pre>
 *
 * <p>Metadata merge: technical values ({@code dam:*}, {@code tiff:*}, {@code exif:*},
 * {@code exifEX:*}) always reflect the current original. Descriptive values from XMP
 * ({@code dc:title}, keywords, ...) are only written when absent, so author edits survive a
 * re-upload.
 */
public final class AssetProcessor {

    public static final String PN_ASSET_STATE = "dam:assetState";
    public static final String PN_BLOB_ID = "studio:blobId";
    public static final String STATE_PROCESSED = "processed";
    public static final String STATE_FAILED = "failed";

    private static final Logger LOG = LoggerFactory.getLogger(AssetProcessor.class);
    private static final String GENERATED_PREFIX = "cq5dam.";
    /**
     * A frame of a video, chosen in the console (studio-dam does not decode video). The thumbnails
     * and web rendition of a video are made from it; processing keeps it.
     */
    public static final String POSTER = "cq5dam.poster.jpg";
    private static final String[] TECHNICAL_PREFIXES = {"dam:", "tiff:", "exif:", "exifEX:"};
    private static final Set<String> DATE_PROPERTIES = new HashSet<>(List.of(
            "exif:DateTimeOriginal", "exif:DateTimeDigitized", "xmp:CreateDate", "xmp:ModifyDate",
            "xmp:MetadataDate", "photoshop:DateCreated", "dc:modified"));

    /** What happened, for logs and job results. */
    public enum Outcome { PROCESSED, NOT_AN_ASSET, NO_ORIGINAL, FAILED, SUPERSEDED }

    private final DamClient client;

    public AssetProcessor(DamClient client) {
        this.client = client;
    }

    /**
     * @throws IOException when studio-dam is unreachable or answers with a transient error; the
     *         caller should retry. Permanent errors are recorded on the asset instead.
     */
    public Outcome process(ResourceResolver resolver, String assetPath) throws IOException {
        Resource asset = resolver.getResource(assetPath);
        if (asset == null || !"dam:Asset".equals(asset.getValueMap().get("jcr:primaryType", String.class))) {
            return Outcome.NOT_AN_ASSET;
        }
        Resource content = asset.getChild("jcr:content");
        Resource renditions = content == null ? null : content.getChild("renditions");
        Resource original = renditions == null ? null : renditions.getChild("original");
        if (original == null) {
            return Outcome.NO_ORIGINAL;
        }
        String mime = mimeOf(original);
        Object originalVersion = versionOf(original);
        // a live copy's file got its renditions with the original from its source: nothing to make
        Long rolled = content.getValueMap().get(DamLiveCopy.PN_ROLLED_ORIGINAL, Long.class);
        if (rolled != null && rolled.equals(originalVersion) && DamLiveCopy.hasMixin(content, DamLiveCopy.MIX_RELATIONSHIP)
                && !DamLiveCopy.hasMixin(content, DamLiveCopy.MIX_CANCELLED)) {
            return Outcome.SUPERSEDED;
        }

        try {
            return processOriginal(resolver, assetPath, content, renditions, original, mime, originalVersion);
        } catch (DamClient.DamProcessingException | RuntimeException e) {
            // Permanent: retrying would fail the same way. Record it on the asset.
            markFailed(resolver, assetPath, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return Outcome.FAILED;
        }
    }

    private Outcome processOriginal(ResourceResolver resolver, String assetPath, Resource content, Resource renditions,
            Resource original, String mime, Object originalVersion) throws IOException {
        ProcessResult result;
        String blobId;
        Set<String> profileNames = new HashSet<>();
        List<Map<String, Object>> extra = DamProfiles.renditions(DamProfiles.profileFor(resolver.getResource(assetPath)));
        try (InputStream in = streamOf(original)) {
            if (in == null) {
                return Outcome.NO_ORIGINAL;
            }
            blobId = client.putBlob(in);
            if (extra.isEmpty()) {
                result = client.process(blobId, mime);
            } else if (DamWatermark.wanted(extra)) {
                Map<String, Object> mark = null;
                try {
                    mark = DamWatermark.forProcessing(resolver, client);
                } catch (IOException e) {
                    LOG.warn("Watermark not drawn on {}: {}", assetPath, e.getMessage());
                }
                if (mark == null) {
                    LOG.info("Processing profile of {} asks for the watermark, but none is set", assetPath);
                }
                result = mark == null ? client.process(blobId, mime, extra) : client.process(blobId, mime, extra, mark);
            } else {
                result = client.process(blobId, mime, extra);
            }
            for (Map<String, Object> e : extra) {
                profileNames.add(DamProfiles.fileName(e));
            }
        }
        // Metadata always comes from the original; the images of a video from its poster frame.
        ProcessResult images = posterImages(resolver, renditions, mime, result, extra, blobId);

        Set<String> produced = new HashSet<>();
        Set<String> previousProfile = new HashSet<>(Arrays.asList(content.getValueMap().get(DamProfiles.PN_PRODUCED, new String[0])));
        for (ProcessResult.RenditionInfo r : images.getRenditions()) {
            // Only generated names: never let an answer overwrite "original" or anything else.
            if (!isSafeName(r.name) || !(r.name.startsWith(GENERATED_PREFIX) || profileNames.contains(r.name))) {
                LOG.warn("Ignoring rendition with unexpected name {} for {}", r.name, assetPath);
                continue;
            }
            try (InputStream data = client.getBlob(r.blobId)) {
                writeRendition(resolver, renditions, r, data);
            }
            produced.add(r.name);
        }
        if (videoPreview(resolver, renditions, mime, result, blobId)) {
            produced.add(VIDEO_PREVIEW);
        }
        // the profile's video renditions (mp4, webm at a height), for videos only
        String type = result.getMimeType() != null ? result.getMimeType() : mime;
        if (isVideo(type)) {
            for (Map<String, Object> v : DamProfiles.videoRenditions(DamProfiles.profileFor(resolver.getResource(assetPath)))) {
                String file = DamProfiles.fileName(v);
                profileNames.add(file);
                if (videoRendition(resolver, renditions, blobId, file, String.valueOf(v.get("format")), ((Number) v.get("height")).intValue())) {
                    produced.add(file);
                }
            }
        }
        for (Resource child : copy(renditions.getChildren())) {
            boolean ours = (child.getName().startsWith(GENERATED_PREFIX) && !POSTER.equals(child.getName()))
                    || previousProfile.contains(child.getName());
            if (ours && !produced.contains(child.getName())) {
                resolver.delete(child);
            }
        }

        mergeMetadata(resolver, content, result);
        try {
            // the folder's metadata profile fills what the file did not bring
            DamForms.fill(resolver.getResource(assetPath));
        } catch (DamApiServlet.ApiException | RuntimeException e) {
            LOG.warn("Metadata profile not applied to {}: {}", assetPath, e.getMessage());
        }
        writeFailures(resolver, content, images.getFailed());

        // A newer original may have arrived while we worked: its own job will write the result.
        resolver.refresh();
        Resource current = resolver.getResource(original.getPath());
        if (current == null || !java.util.Objects.equals(originalVersion, versionOf(current))) {
            resolver.revert();
            LOG.info("Original of {} changed during processing; leaving it to the newer job", assetPath);
            return Outcome.SUPERSEDED;
        }

        ModifiableValueMap state = content.adaptTo(ModifiableValueMap.class);
        state.put(PN_ASSET_STATE, STATE_PROCESSED);
        state.put(PN_BLOB_ID, blobId);
        Set<String> fromProfile = new java.util.TreeSet<>(produced);
        fromProfile.retainAll(profileNames);
        if (fromProfile.isEmpty()) {
            state.remove(DamProfiles.PN_PRODUCED);
        } else {
            state.put(DamProfiles.PN_PRODUCED, fromProfile.toArray(new String[0]));
        }
        state.remove("dam:processingError");
        resolver.commit();
        LOG.info("Processed {}: {} renditions, {} unsupported", assetPath, produced.size(), result.getFailed().size());
        sets(resolver, assetPath);
        smartCrops(resolver, assetPath);
        smartTags(resolver, assetPath);
        colors(resolver, assetPath);
        if (DamSimilar.available(client)) {
            embedding(resolver, assetPath, DamSimilar.Space.TAGS);
        }
        if (DamSimilar.available(client, DamSimilar.Space.SEARCH)) {
            embedding(resolver, assetPath, DamSimilar.Space.SEARCH);
        }
        return Outcome.PROCESSED;
    }

    /**
     * Smart Tags after processing, when they are switched on. Never fails the processing: without
     * the model, or with studio-dam busy, the asset simply has no smart tags yet.
     */
    /** Smart crops for the folder's image profile, from the fresh original. Never fails the processing. */
    /** Image sets that start with this picture show its new renditions. Never fails the processing. */
    private static void sets(ResourceResolver resolver, String assetPath) {
        try {
            resolver.refresh();
            if (DamSets.refreshSetsStartingWith(resolver, assetPath) > 0) {
                resolver.commit();
            }
        } catch (PersistenceException | RuntimeException e) {
            resolver.revert();
            LOG.info("Image sets starting with {} keep their old picture: {}", assetPath, e.getMessage());
        }
    }

    private void smartCrops(ResourceResolver resolver, String assetPath) {
        resolver.refresh();
        Resource asset = resolver.getResource(assetPath);
        if (asset != null) {
            DamSmartCrop.after(resolver, client, asset);
        }
    }

    /**
     * The picture's embedding for "find similar", refreshed with every processing. Skipped quietly
     * while the model is not installed; never fails the processing.
     */
    private void embedding(ResourceResolver resolver, String assetPath, DamSimilar.Space space) {
        try {
            resolver.refresh();
            Resource asset = resolver.getResource(assetPath);
            if (asset != null && DamSimilar.index(client, asset, space)) {
                resolver.commit();
            }
        } catch (IOException | RuntimeException e) {
            resolver.revert();
            LOG.debug("No embedding for {}: {}", assetPath, e.getMessage());
        }
    }

    /** Color tags, from the fresh thumbnail. Never fails the processing. */
    private static void colors(ResourceResolver resolver, String assetPath) {
        try {
            resolver.refresh();
            Resource asset = resolver.getResource(assetPath);
            if (asset != null && DamColors.tag(asset)) {
                resolver.commit();
            }
        } catch (PersistenceException | RuntimeException e) {
            resolver.revert();
            LOG.info("No color tags for {}: {}", assetPath, e.getMessage());
        }
    }

    private void smartTags(ResourceResolver resolver, String assetPath) {
        DamSmartTags.Settings settings = DamSmartTags.settings(resolver);
        try {
            resolver.refresh();
            Resource asset = resolver.getResource(assetPath);
            if (asset == null || !DamSmartTags.enabledIn(asset.getParent(), settings)) {
                return;
            }
            if (asset != null && DamSmartTags.tag(resolver, client, asset, settings) == DamSmartTags.Outcome.TAGGED) {
                resolver.commit();
            }
        } catch (IOException | RuntimeException e) {
            resolver.revert();
            LOG.info("No smart tags for {}: {}", assetPath, e.getMessage());
        }
    }

    /**
     * The renditions of a video made from its poster frame, if it has one; else {@code result}
     * itself. A poster studio-dam cannot read leaves the video without thumbnails, as before.
     *
     * <p>A video without a frame somebody picked gets one from studio-dam when the machine has
     * ffmpeg. Such a frame is marked as made here ({@code jcr:lastModifiedBy} =
     * {@value #AUTOMATIC}) and is taken again at every processing, since the video may be
     * another one by then; a frame a person picked is kept.</p>
     */
    private ProcessResult posterImages(ResourceResolver resolver, Resource renditions, String mime, ProcessResult result,
            List<Map<String, Object>> extra, String blobId) throws IOException {
        if (!(isVideo(mime) || isVideo(result.getMimeType()))) {
            return result;
        }
        Resource poster = renditions.getChild(POSTER);
        if (poster == null || automatic(poster)) {
            try {
                byte[] frame = client.videoPoster(blobId);
                if (frame != null && frame.length > 0) {
                    if (poster != null) {
                        resolver.delete(poster);
                    }
                    poster = writeFile(resolver, renditions, POSTER, new java.io.ByteArrayInputStream(frame), "image/jpeg");
                }
            } catch (DamClient.DamProcessingException e) {
                // 409: no ffmpeg on this machine; 501: a client without video. Neither is news.
                if (e.getStatus() != 409 && e.getStatus() != 501) {
                    LOG.info("No frame could be taken from {}: {}", renditions.getPath(), e.getMessage());
                }
            }
        }
        if (poster == null) {
            return result;
        }
        try (InputStream in = streamOf(poster)) {
            if (in == null) {
                return result;
            }
            String blob = client.putBlob(in);
            String type = mimeOf(poster) != null ? mimeOf(poster) : "image/jpeg";
            return extra.isEmpty() ? client.process(blob, type) : client.process(blob, type, extra);
        } catch (DamClient.DamProcessingException e) {
            LOG.warn("Poster frame of {} not usable: {}", renditions.getPath(), e.getMessage());
            return result;
        }
    }

    /** Who wrote a rendition made by processing rather than by a person. */
    static final String AUTOMATIC = "studio-dam";
    /** A video as an MP4 any browser plays, made when the original is not one. */
    public static final String VIDEO_PREVIEW = "cq5dam.video.preview.mp4";

    private static boolean automatic(Resource file) {
        Resource c = file.getChild("jcr:content");
        return c != null && AUTOMATIC.equals(c.getValueMap().get("jcr:lastModifiedBy", String.class));
    }

    private static Resource writeFile(ResourceResolver resolver, Resource folder, String name, InputStream data, String mimeType)
            throws PersistenceException {
        Resource existing = folder.getChild(name);
        if (existing != null) {
            resolver.delete(existing);
        }
        Resource file = resolver.create(folder, name, map("jcr:primaryType", "nt:file"));
        Map<String, Object> binary = map("jcr:primaryType", "oak:Resource");
        binary.put("jcr:data", data);
        binary.put("jcr:mimeType", mimeType);
        binary.put("jcr:lastModified", Calendar.getInstance());
        binary.put("jcr:lastModifiedBy", AUTOMATIC);
        resolver.create(file, "jcr:content", binary);
        return file;
    }

    /** The containers and codecs every current browser plays: such a video needs no preview. */
    static boolean playsInBrowsers(String mime, Object codec) {
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        String c = codec == null ? "" : codec.toString();
        boolean container = m.equals("video/mp4") || m.equals("video/webm") || m.equals("video/quicktime");
        return container && (c.startsWith("H.264") || c.startsWith("VP8") || c.startsWith("VP9") || c.startsWith("AV1"));
    }

    /**
     * Makes (or keeps) the browser-playable preview of a video whose original browsers do not
     * play. True when the asset has one after this; false lets the caller remove an old one.
     */
    private boolean videoPreview(ResourceResolver resolver, Resource renditions, String mime, ProcessResult result, String blobId)
            throws IOException {
        String type = result.getMimeType() != null ? result.getMimeType() : mime;
        if (!isVideo(type) || playsInBrowsers(type, result.getMetadata().get("xmpDM:videoCompressor"))) {
            return false;
        }
        try {
            String blob = client.videoPreview(blobId);
            try (InputStream data = client.getBlob(blob)) {
                writeFile(resolver, renditions, VIDEO_PREVIEW, data, "video/mp4");
            }
            return true;
        } catch (DamClient.DamProcessingException e) {
            if (e.getStatus() != 409 && e.getStatus() != 501) {
                LOG.info("No preview could be made of {}: {}", renditions.getPath(), e.getMessage());
            }
            // without ffmpeg now, a preview made earlier is still the best there is
            return renditions.getChild(VIDEO_PREVIEW) != null;
        }
    }

    /**
     * One video rendition of the Processing Profile, encoded by ffmpeg next to studio-dam. True
     * when the asset has it after this (a rendition made earlier stays while ffmpeg is missing).
     */
    private boolean videoRendition(ResourceResolver resolver, Resource renditions, String blobId, String file, String format, int height)
            throws IOException {
        try {
            String blob = client.videoEncode(blobId, format, height);
            try (InputStream data = client.getBlob(blob)) {
                writeFile(resolver, renditions, file, data, "webm".equals(format) ? "video/webm" : "video/mp4");
            }
            return true;
        } catch (DamClient.DamProcessingException e) {
            if (e.getStatus() != 409 && e.getStatus() != 501) {
                LOG.info("No {} could be made of {}: {}", file, renditions.getPath(), e.getMessage());
            }
            return renditions.getChild(file) != null;
        }
    }

    static boolean isVideo(String mime) {
        return mime != null && mime.toLowerCase(java.util.Locale.ROOT).startsWith("video/");
    }

    /**
     * Records a processing failure on the asset ({@code dam:assetState = failed},
     * {@code dam:processingError}). Also used by the job when retries run out.
     */
    public static void markFailed(ResourceResolver resolver, String assetPath, String message) throws PersistenceException {
        resolver.revert();
        resolver.refresh();
        Resource content = resolver.getResource(assetPath + "/jcr:content");
        ModifiableValueMap state = content == null ? null : content.adaptTo(ModifiableValueMap.class);
        if (state == null) {
            return;
        }
        state.put(PN_ASSET_STATE, STATE_FAILED);
        state.put("dam:processingError", message.length() > 1000 ? message.substring(0, 1000) : message);
        resolver.commit();
        LOG.warn("Processing failed for {}: {}", assetPath, message);
    }

    private static Object versionOf(Resource original) {
        Resource c = original.getChild("jcr:content");
        Calendar modified = c == null ? null : c.getValueMap().get("jcr:lastModified", Calendar.class);
        return modified == null ? null : modified.getTimeInMillis();
    }

    private static void writeRendition(ResourceResolver resolver, Resource folder, ProcessResult.RenditionInfo r,
            InputStream data) throws PersistenceException {
        Resource existing = folder.getChild(r.name);
        if (existing != null) {
            resolver.delete(existing);
        }
        Resource file = resolver.create(folder, r.name, map("jcr:primaryType", "nt:file"));
        Map<String, Object> binary = map("jcr:primaryType", "oak:Resource");
        binary.put("jcr:mixinTypes", new String[] {"dam:Metadata"});
        binary.put("jcr:data", data);
        binary.put("jcr:mimeType", r.mimeType);
        binary.put("jcr:lastModified", Calendar.getInstance());
        binary.put("jcr:lastModifiedBy", "studio-dam");
        Resource jcrContent = resolver.create(file, "jcr:content", binary);
        Map<String, Object> md = map("jcr:primaryType", "nt:unstructured");
        md.put("dam:sha1", r.sha1);
        md.put("dam:size", r.size);
        md.put("dc:format", r.mimeType);
        md.put("tiff:ImageWidth", r.width);
        md.put("tiff:ImageLength", r.height);
        resolver.create(jcrContent, "metadata", md);
    }

    private static void mergeMetadata(ResourceResolver resolver, Resource content, ProcessResult result)
            throws PersistenceException {
        Resource mdRes = content.getChild("metadata");
        if (mdRes == null) {
            mdRes = resolver.create(content, "metadata", map("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap md = mdRes.adaptTo(ModifiableValueMap.class);

        // XMP first (descriptive), then the service's technical values on top.
        for (Map.Entry<String, Object> e : XmpReader.read(result.getXmpPacket()).entrySet()) {
            Object value = toJcr(e.getKey(), e.getValue());
            if (value == null) {
                continue;
            }
            if (isTechnical(e.getKey()) || !md.containsKey(e.getKey())) {
                md.put(e.getKey(), value);
            }
        }
        for (Map.Entry<String, Object> e : result.getMetadata().entrySet()) {
            Object value = toJcr(e.getKey(), e.getValue());
            if (value != null) {
                md.put(e.getKey(), value);
            }
        }
        if (result.getMimeType() != null) {
            md.put("dam:MIMEtype", result.getMimeType());
            md.put("dc:format", result.getMimeType());
        }
        md.put("dam:extracted", Calendar.getInstance());
    }

    private static void writeFailures(ResourceResolver resolver, Resource content, List<ProcessResult.Failure> failed)
            throws PersistenceException {
        Resource old = content.getChild("dam:failedRenditions");
        if (old != null) {
            resolver.delete(old);
        }
        if (failed.isEmpty()) {
            return;
        }
        Resource node = resolver.create(content, "dam:failedRenditions", map("jcr:primaryType", "nt:unstructured"));
        for (ProcessResult.Failure f : failed) {
            if (!isSafeName(f.name)) {
                continue;
            }
            Map<String, Object> p = map("jcr:primaryType", "nt:unstructured");
            p.put("reason", f.reason);
            p.put("message", f.message);
            resolver.create(node, f.name, p);
        }
    }

    private static boolean isTechnical(String key) {
        for (String p : TECHNICAL_PREFIXES) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** JSON/XMP values to JCR property values. Decimals become {@code BigDecimal} ({@code {Decimal}} in JCR). */
    static Object toJcr(String key, Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String && DATE_PROPERTIES.contains(key)) {
            Calendar c = parseDate((String) v);
            return c != null ? c : v;
        }
        if (v instanceof Long || v instanceof String || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Double) {
            return BigDecimal.valueOf((Double) v);
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                return null;
            }
            boolean allLong = true;
            for (Object o : l) {
                allLong &= o instanceof Long;
            }
            if (allLong) {
                return l.toArray(new Long[0]);
            }
            String[] out = new String[l.size()];
            for (int k = 0; k < l.size(); k++) {
                out[k] = String.valueOf(l.get(k));
            }
            return out;
        }
        return String.valueOf(v);
    }

    /** ISO dates with or without offset; local times are taken as UTC. */
    static Calendar parseDate(String s) {
        try {
            OffsetDateTime odt;
            try {
                odt = OffsetDateTime.parse(s);
            } catch (DateTimeParseException e) {
                odt = LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);
            }
            GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone(odt.getOffset()));
            c.setTimeInMillis(odt.toInstant().toEpochMilli());
            return c;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isSafeName(String name) {
        return name != null && name.matches("[A-Za-z0-9._-]{1,128}") && !name.startsWith(".");
    }

    private static String mimeOf(Resource original) {
        Resource c = original.getChild("jcr:content");
        return c == null ? null : c.getValueMap().get("jcr:mimeType", String.class);
    }

    private static InputStream streamOf(Resource original) {
        InputStream in = original.adaptTo(InputStream.class);
        if (in == null) {
            Resource c = original.getChild("jcr:content");
            in = c == null ? null : c.adaptTo(InputStream.class);
        }
        return in;
    }

    private static List<Resource> copy(Iterable<Resource> it) {
        List<Resource> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
