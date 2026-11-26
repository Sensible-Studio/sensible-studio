/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

@ExtendWith(SlingContextExtension.class)
class AssetProcessorTest {

    private static final String PATH = "/content/dam/wknd/freeride.jpg";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        byte[] jpeg;
        try (InputStream in = getClass().getResourceAsStream("/wknd/freeride.jpg")) {
            jpeg = in.readAllBytes();
        }
        assertNotNull(new AssetManagerImpl(resolver).createAsset(PATH, new ByteArrayInputStream(jpeg), "image/jpeg", true));
    }

    private ProcessResult scripted(String... renditionNames) throws Exception {
        List<ProcessResult.RenditionInfo> renditions = new java.util.ArrayList<>();
        for (String name : renditionNames) {
            byte[] data = ("pixels of " + name).getBytes(StandardCharsets.UTF_8);
            String id = dam.store(data);
            renditions.add(new ProcessResult.RenditionInfo(name, id, name.endsWith(".png") ? "image/png" : "image/jpeg",
                    data.length, "sha1-" + name, 64, 32));
        }
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("dam:sha1", "479b58c3531bd476dc73943f58cd5286e1a37952");
        md.put("dam:size", 102189L);
        md.put("tiff:ImageWidth", 720L);
        md.put("dam:Physicalwidthininches", 2.4);
        md.put("exif:ISOSpeedRatings", Arrays.asList(100L));
        md.put("exif:DateTimeOriginal", "2016-03-03T15:32:12");
        return new ProcessResult("blob", "image/jpeg", md, JsonAndXmpTest.xmpOf("/wknd/freeride.jpg"),
                renditions, Collections.emptyList());
    }

    @Test
    void writesRenditionsMetadataAndState() throws Exception {
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png", "cq5dam.web.1280.1280.jpeg");
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, PATH));
        assertEquals(1, dam.processed.size());
        assertTrue(dam.processed.get(0).endsWith(" image/jpeg"), "the original's mime type is passed on");

        Asset asset = resolver.getResource(PATH).adaptTo(Asset.class);
        Rendition web = asset.getRendition("cq5dam.web.1280.1280.jpeg");
        assertNotNull(web);
        assertEquals("image/jpeg", web.getMimeType());
        try (InputStream in = web.getStream()) {
            assertEquals("pixels of cq5dam.web.1280.1280.jpeg", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        ValueMap rmd = resolver.getResource(PATH + "/jcr:content/renditions/cq5dam.web.1280.1280.jpeg/jcr:content/metadata").getValueMap();
        assertEquals(64L, rmd.get("tiff:ImageWidth", Long.class));
        assertEquals("sha1-cq5dam.web.1280.1280.jpeg", rmd.get("dam:sha1", String.class));

        ValueMap content = resolver.getResource(PATH + "/jcr:content").getValueMap();
        assertEquals("processed", content.get("dam:assetState", String.class));
        assertEquals(64, content.get("studio:blobId", String.class).length());

        ValueMap md = resolver.getResource(PATH + "/jcr:content/metadata").getValueMap();
        assertEquals("479b58c3531bd476dc73943f58cd5286e1a37952", md.get("dam:sha1", String.class));
        assertEquals(102189L, md.get("dam:size", Long.class));
        assertEquals(new BigDecimal("2.4"), md.get("dam:Physicalwidthininches"));
        assertArrayEquals(new Long[] {100L}, (Long[]) md.get("exif:ISOSpeedRatings"));
        assertTrue(md.get("exif:DateTimeOriginal") instanceof Calendar, "dates become JCR dates");
        assertEquals("Freeride", md.get("dc:title", String.class), "from XMP");
        assertNotNull(md.get("dam:extracted", Calendar.class));
        assertEquals("image/jpeg", md.get("dc:format", String.class));
    }

    @Test
    void authorEditsSurviveReprocessingButStaleRenditionsGo() throws Exception {
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png", "cq5dam.zoom.2048.2048.jpeg");
        new AssetProcessor(dam).process(resolver, PATH);
        ModifiableValueMap md = resolver.getResource(PATH + "/jcr:content/metadata").adaptTo(ModifiableValueMap.class);
        md.put("dc:title", "Edited by an author");
        md.put("dam:size", 1L);
        resolver.commit();

        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png", "cq5dam.web.1280.1280.png");
        new AssetProcessor(dam).process(resolver, PATH);
        ValueMap after = resolver.getResource(PATH + "/jcr:content/metadata").getValueMap();
        assertEquals("Edited by an author", after.get("dc:title", String.class));
        assertEquals(102189L, after.get("dam:size", Long.class), "technical values are refreshed");
        Resource renditions = resolver.getResource(PATH + "/jcr:content/renditions");
        assertNull(renditions.getChild("cq5dam.zoom.2048.2048.jpeg"), "not produced any more");
        assertNotNull(renditions.getChild("cq5dam.web.1280.1280.png"));
        assertNotNull(renditions.getChild("original"), "the original is never touched");
    }

    @Test
    void foldersProcessingProfileAddsRenditions() throws Exception {
        context.create().resource("/conf/global/settings/dam/processing/web/renditions/r0", "name", "banner", "width", 1200L,
                "height", 400L, "format", "webp", "quality", 70L);
        context.create().resource("/content/dam/wknd/jcr:content", DamProfiles.PN_PROFILE, "/conf/global/settings/dam/processing/web");
        resolver.commit();
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png", "banner.webp", "intruder.jpg");
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, PATH));
        assertEquals(1, dam.lastExtra.size());
        assertEquals("banner", dam.lastExtra.get(0).get("name"));
        assertEquals(1200L, dam.lastExtra.get(0).get("width"));
        Resource renditions = resolver.getResource(PATH + "/jcr:content/renditions");
        assertNotNull(renditions.getChild("banner.webp"));
        assertNull(renditions.getChild("intruder.jpg"), "only names the profile asked for");
        assertArrayEquals(new String[] {"banner.webp"},
                resolver.getResource(PATH + "/jcr:content").getValueMap().get(DamProfiles.PN_PRODUCED, String[].class));

        // the folder drops the profile: the next processing removes its rendition
        resolver.getResource("/content/dam/wknd/jcr:content").adaptTo(ModifiableValueMap.class).remove(DamProfiles.PN_PROFILE);
        resolver.commit();
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png");
        new AssetProcessor(dam).process(resolver, PATH);
        assertNull(resolver.getResource(PATH + "/jcr:content/renditions/banner.webp"));
        assertTrue(dam.lastExtra.isEmpty() || dam.lastExtra.size() == 1, "two-argument call when there is no profile");
        assertNull(resolver.getResource(PATH + "/jcr:content").getValueMap().get(DamProfiles.PN_PRODUCED));
    }

    @Test
    void unsupportedFilesRecordFailedRenditions() throws Exception {
        dam.nextResult = new ProcessResult("blob", "application/pdf", Collections.singletonMap("dam:size", 10L), null,
                Collections.emptyList(),
                List.of(new ProcessResult.Failure("cq5dam.thumbnail.48.48.png", "RenditionFormatUnsupported", "no")));
        new AssetProcessor(dam).process(resolver, PATH);
        Resource failed = resolver.getResource(PATH + "/jcr:content/dam:failedRenditions/cq5dam.thumbnail.48.48.png");
        assertEquals("RenditionFormatUnsupported", failed.getValueMap().get("reason", String.class));
        assertEquals("application/pdf", resolver.getResource(PATH + "/jcr:content/metadata").getValueMap().get("dam:MIMEtype"));
    }

    @Test
    void aLiveCopysFileIsNotProcessedAgainForTheOriginalItWasGiven() throws Exception {
        new AssetManagerImpl(resolver).createAsset("/content/dam/lc/pic.jpg", new ByteArrayInputStream(new byte[] {1}), "image/jpeg", true);
        Resource orig = resolver.getResource("/content/dam/lc/pic.jpg/jcr:content/renditions/original/jcr:content");
        java.util.Calendar modified = orig.getValueMap().get("jcr:lastModified", java.util.Calendar.class);
        org.apache.sling.api.resource.ModifiableValueMap c =
                resolver.getResource("/content/dam/lc/pic.jpg/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        c.put("jcr:mixinTypes", new String[] {DamLiveCopy.MIX_RELATIONSHIP});
        c.put(DamLiveCopy.PN_ROLLED_ORIGINAL, modified == null ? 0L : modified.getTimeInMillis());
        if (modified == null) {
            orig.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("jcr:lastModified", new java.util.GregorianCalendar(1970, 0, 1, 0, 0, 0));
            c.put(DamLiveCopy.PN_ROLLED_ORIGINAL, orig.getValueMap().get("jcr:lastModified", java.util.Calendar.class).getTimeInMillis());
        }
        int before = dam.processed.size();
        assertEquals(AssetProcessor.Outcome.SUPERSEDED, new AssetProcessor(dam).process(resolver, "/content/dam/lc/pic.jpg"));
        assertEquals(before, dam.processed.size(), "its renditions came from the source");

        // suspended in the live copy: processed like any file
        c.put("jcr:mixinTypes", new String[] {DamLiveCopy.MIX_RELATIONSHIP, DamLiveCopy.MIX_CANCELLED});
        dam.results.add(scripted("cq5dam.thumbnail.319.319.png"));
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, "/content/dam/lc/pic.jpg"));
    }

    @Test
    void theWatermarkGoesToTheRenditionsThatAskForIt() throws Exception {
        context.create().resource("/conf/global/settings/dam/processing/marked", "jcr:title", "Marked");
        context.create().resource("/conf/global/settings/dam/processing/marked/renditions/r0", "name", "preview", "format", "jpeg",
                "width", 800L, "height", 800L, "quality", 80L, "watermark", true);
        context.create().resource("/conf/global/settings/dam/processing/marked/renditions/r1", "name", "clean", "format", "png",
                "width", 200L, "height", 200L);
        context.create().resource("/content/dam/proofs", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/proofs/jcr:content", DamProfiles.PN_PROFILE, "/conf/global/settings/dam/processing/marked");
        new AssetManagerImpl(resolver).createAsset("/content/dam/proofs/pic.jpg", new ByteArrayInputStream(new byte[] {1}), "image/jpeg", true);

        // no watermark set: the renditions are made, without one
        dam.results.add(scripted("cq5dam.thumbnail.319.319.png", "preview.jpeg", "clean.png"));
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, "/content/dam/proofs/pic.jpg"));
        assertNull(dam.lastWatermark);
        assertEquals(Boolean.TRUE, dam.lastExtra.get(0).get("watermark"));
        assertNull(dam.lastExtra.get(1).get("watermark"));

        // a logo in Media becomes the watermark
        new AssetManagerImpl(resolver).createAsset("/content/dam/brand/logo.png", new ByteArrayInputStream(new byte[] {9, 9}), "image/png", true);
        context.create().resource(DamWatermark.PATH, "image", "/content/dam/brand/logo.png", "position", "bottom-right",
                "opacity", 40L, "size", 30L);
        dam.results.add(scripted("cq5dam.thumbnail.319.319.png", "preview.jpeg", "clean.png"));
        new AssetProcessor(dam).process(resolver, "/content/dam/proofs/pic.jpg");
        assertNotNull(dam.lastWatermark);
        assertEquals("bottom-right", dam.lastWatermark.get("position"));
        assertEquals(40L, dam.lastWatermark.get("opacity"));
        assertEquals(30L, dam.lastWatermark.get("size"));
        assertArrayEquals(new byte[] {9, 9}, dam.blobs.get(String.valueOf(dam.lastWatermark.get("blobId"))));
    }

    @Test
    void aProcessingProfileGivesVideosTheirEncodings() throws Exception {
        context.create().resource("/conf/global/settings/dam/processing/video", "jcr:title", "Video");
        context.create().resource("/conf/global/settings/dam/processing/video/renditions/r0", "name", "web720", "format", "mp4", "height", 720L, "width", 1280L);
        context.create().resource("/conf/global/settings/dam/processing/video/renditions/r1", "name", "small", "format", "webm", "height", 360L, "width", 0L);
        context.create().resource("/conf/global/settings/dam/processing/video/renditions/r2", "name", "card", "format", "jpeg", "height", 300L, "width", 400L, "quality", 80L);
        context.create().resource("/content/dam/films", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/films/jcr:content", DamProfiles.PN_PROFILE, "/conf/global/settings/dam/processing/video");
        String clip = "/content/dam/films/film.mp4";
        new AssetManagerImpl(resolver).createAsset(clip, new ByteArrayInputStream(new byte[] {0, 0, 0, 24}), "video/mp4", true);
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("xmpDM:videoCompressor", "H.264 (avc1)");
        dam.videoFrame = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 7};
        dam.videoPreviewBytes = new byte[] {1};
        dam.results.add(new ProcessResult("blob", "video/mp4", md, null, Collections.emptyList(), Collections.emptyList()));
        dam.results.add(scripted("cq5dam.thumbnail.319.319.png", "card.jpeg"));
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, clip));
        Resource renditions = resolver.getResource(clip + "/jcr:content/renditions");
        assertEquals("video/mp4", renditions.getChild("web720.mp4/jcr:content").getValueMap().get("jcr:mimeType", String.class));
        assertEquals("video/webm", renditions.getChild("small.webm/jcr:content").getValueMap().get("jcr:mimeType", String.class));
        assertEquals(2, dam.encoded.size(), String.valueOf(dam.encoded));
        assertTrue(dam.encoded.get(0).endsWith(" mp4 720") && dam.encoded.get(1).endsWith(" webm 360"), String.valueOf(dam.encoded));
        assertNotNull(renditions.getChild("card.jpeg"), "the image renditions come from the frame, as before");
        assertEquals(java.util.Set.of("card.jpeg", "small.webm", "web720.mp4"),
                java.util.Set.of(resolver.getResource(clip + "/jcr:content").getValueMap().get(DamProfiles.PN_PRODUCED, String[].class)));

        // the profile loses its webm: processed again, that rendition goes
        resolver.delete(resolver.getResource("/conf/global/settings/dam/processing/video/renditions/r1"));
        dam.results.add(new ProcessResult("blob", "video/mp4", md, null, Collections.emptyList(), Collections.emptyList()));
        dam.results.add(scripted("cq5dam.thumbnail.319.319.png", "card.jpeg"));
        new AssetProcessor(dam).process(resolver, clip);
        assertNull(resolver.getResource(clip + "/jcr:content/renditions/small.webm"));
        assertNotNull(resolver.getResource(clip + "/jcr:content/renditions/web720.mp4"));

        // a picture in the same folder gets no video
        int before = dam.encoded.size();
        new AssetManagerImpl(resolver).createAsset("/content/dam/films/still.jpg", new ByteArrayInputStream(new byte[] {1}), "image/jpeg", true);
        dam.results.add(scripted("cq5dam.thumbnail.319.319.png", "card.jpeg"));
        new AssetProcessor(dam).process(resolver, "/content/dam/films/still.jpg");
        assertEquals(before, dam.encoded.size());
    }

    @Test
    void withFfmpegAVideoGetsItsFrameAndAPreviewByItself() throws Exception {
        String clip = "/content/dam/wknd/film.mov";
        new AssetManagerImpl(resolver).createAsset(clip, new ByteArrayInputStream(new byte[] {0, 0, 0, 24}), "video/quicktime", true);
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("xmpDM:videoCompressor", "Apple ProRes (apch)"); // no browser plays this
        ProcessResult video = new ProcessResult("blob", "video/quicktime", md, null, Collections.emptyList(), Collections.emptyList());
        ProcessResult frame = scripted("cq5dam.thumbnail.319.319.png", "cq5dam.web.1280.1280.jpeg");
        dam.videoFrame = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 7};
        dam.videoPreviewBytes = new byte[] {0, 0, 0, 32, 'f', 't', 'y', 'p'};
        dam.results.add(video);
        dam.results.add(frame);
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, clip));
        Resource renditions = resolver.getResource(clip + "/jcr:content/renditions");
        Resource poster = renditions.getChild(AssetProcessor.POSTER);
        assertNotNull(poster, "a frame was taken");
        assertEquals(AssetProcessor.AUTOMATIC, poster.getChild("jcr:content").getValueMap().get("jcr:lastModifiedBy", String.class));
        assertNotNull(renditions.getChild("cq5dam.thumbnail.319.319.png"), "and the thumbnails made from it");
        Resource preview = renditions.getChild(AssetProcessor.VIDEO_PREVIEW);
        assertNotNull(preview, "a preview browsers play");
        assertEquals("video/mp4", preview.getChild("jcr:content").getValueMap().get("jcr:mimeType", String.class));
        assertNotNull(DamItems.asset(resolver.getResource(clip)).get("preview"));

        // processed again on a machine without ffmpeg: what was made stays
        dam.videoFrame = null;
        dam.videoPreviewBytes = null;
        dam.results.add(video);
        dam.results.add(frame);
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, clip));
        renditions = resolver.getResource(clip + "/jcr:content/renditions");
        assertNotNull(renditions.getChild(AssetProcessor.POSTER));
        assertNotNull(renditions.getChild(AssetProcessor.VIDEO_PREVIEW));

        // the same film as H.264: browsers play the original, the preview goes
        md.put("xmpDM:videoCompressor", "H.264 (avc1)");
        dam.videoPreviewBytes = new byte[] {1};
        int before = dam.previews;
        dam.results.add(new ProcessResult("blob", "video/quicktime", md, null, Collections.emptyList(), Collections.emptyList()));
        dam.results.add(frame);
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, clip));
        assertEquals(before, dam.previews, "no preview is made of a video browsers play");
        assertNull(resolver.getResource(clip + "/jcr:content/renditions/" + AssetProcessor.VIDEO_PREVIEW));

        assertTrue(AssetProcessor.playsInBrowsers("video/mp4", "H.264 (avc1)"));
        assertTrue(AssetProcessor.playsInBrowsers("video/webm", "VP9"));
        assertTrue(!AssetProcessor.playsInBrowsers("video/mp4", "H.265 (HEVC) (hvc1)"));
        assertTrue(!AssetProcessor.playsInBrowsers("video/x-matroska", "H.264"));
    }

    @Test
    void videosGetTheirThumbnailsFromThePosterFrame() throws Exception {
        String clip = "/content/dam/wknd/clip.mp4";
        new AssetManagerImpl(resolver).createAsset(clip, new ByteArrayInputStream(new byte[] {0, 0, 0, 24}), "video/mp4", true);
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("dam:duration", 10.24);
        md.put("tiff:ImageWidth", 1920L);
        md.put("xmpDM:videoCompressor", "H.264 (avc1)");
        ProcessResult video = new ProcessResult("blob", "video/mp4", md, null, Collections.emptyList(),
                List.of(new ProcessResult.Failure("cq5dam.thumbnail.48.48.png", "RenditionFormatUnsupported", "no")));

        // no poster yet: metadata only, the thumbnails are recorded as failed
        dam.nextResult = video;
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, clip));
        assertNotNull(resolver.getResource(clip + "/jcr:content/dam:failedRenditions"));
        assertEquals(new BigDecimal("10.24"), resolver.getResource(clip + "/jcr:content/metadata").getValueMap().get("dam:duration"));

        // a frame chosen in the console: thumbnails from it, metadata still from the video
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}),
                clip + "/jcr:content/renditions/" + AssetProcessor.POSTER, "image/jpeg");
        resolver.commit();
        dam.processed.clear();
        dam.results.add(video);
        ProcessResult frame = scripted("cq5dam.thumbnail.48.48.png", "cq5dam.thumbnail.319.319.png", "cq5dam.web.1280.1280.jpeg");
        dam.results.add(frame);
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, clip));
        assertEquals(2, dam.processed.size());
        assertTrue(dam.processed.get(0).endsWith(" video/mp4"), dam.processed.toString());
        assertTrue(dam.processed.get(1).endsWith(" image/jpeg"), dam.processed.toString());
        Resource renditions = resolver.getResource(clip + "/jcr:content/renditions");
        assertNotNull(renditions.getChild("cq5dam.thumbnail.319.319.png"));
        assertNotNull(renditions.getChild("cq5dam.web.1280.1280.jpeg"));
        assertNotNull(renditions.getChild(AssetProcessor.POSTER), "processing keeps the poster");
        assertNotNull(renditions.getChild("original"));
        assertNull(resolver.getResource(clip + "/jcr:content/dam:failedRenditions"));
        ValueMap after = resolver.getResource(clip + "/jcr:content/metadata").getValueMap();
        assertEquals(1920L, after.get("tiff:ImageWidth", Long.class), "the video's size, not the frame's");
        assertEquals("H.264 (avc1)", after.get("xmpDM:videoCompressor", String.class));
        assertEquals("video/mp4", after.get("dc:format", String.class));

        // an image with a poster rendition (should not happen) is processed as an image
        dam.processed.clear();
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {1}), PATH + "/jcr:content/renditions/" + AssetProcessor.POSTER, "image/jpeg");
        resolver.commit();
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png");
        new AssetProcessor(dam).process(resolver, PATH);
        assertEquals(1, dam.processed.size());
    }

    @Test
    void transientErrorsAreThrownForRetry() {
        dam.failWith = new IOException("connection refused");
        assertThrows(IOException.class, () -> new AssetProcessor(dam).process(resolver, PATH));
        assertEquals("processing", resolver.getResource(PATH + "/jcr:content").getValueMap().get("dam:assetState"));
    }

    @Test
    void permanentErrorsMarkTheAssetFailed() throws Exception {
        dam.failWith = new DamClient.DamProcessingException(413, "payload too large");
        assertEquals(AssetProcessor.Outcome.FAILED, new AssetProcessor(dam).process(resolver, PATH));
        ValueMap content = resolver.getResource(PATH + "/jcr:content").getValueMap();
        assertEquals("failed", content.get("dam:assetState", String.class));
        assertTrue(content.get("dam:processingError", String.class).contains("413"));
    }

    @Test
    void ignoresNonAssetsAndUnsafeRenditionNames() throws Exception {
        context.create().resource("/content/dam/folder", "jcr:primaryType", "sling:OrderedFolder");
        assertEquals(AssetProcessor.Outcome.NOT_AN_ASSET, new AssetProcessor(dam).process(resolver, "/content/dam/folder"));
        assertEquals(AssetProcessor.Outcome.NOT_AN_ASSET, new AssetProcessor(dam).process(resolver, "/content/dam/missing.jpg"));

        dam.nextResult = scripted("../../evil", "cq5dam.thumbnail.48.48.png");
        new AssetProcessor(dam).process(resolver, PATH);
        Resource renditions = resolver.getResource(PATH + "/jcr:content/renditions");
        assertNotNull(renditions.getChild("cq5dam.thumbnail.48.48.png"));
        assertNull(resolver.getResource("/content/evil"));
    }

    @Test
    void permanentErrorsWhileWritingAlsoMarkTheAssetFailed() throws Exception {
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png");
        dam.failGetBlobWith = new DamClient.DamProcessingException(404, "no such blob");
        assertEquals(AssetProcessor.Outcome.FAILED, new AssetProcessor(dam).process(resolver, PATH));
        assertEquals("failed", resolver.getResource(PATH + "/jcr:content").getValueMap().get("dam:assetState"));
        assertNull(resolver.getResource(PATH + "/jcr:content/renditions/cq5dam.thumbnail.48.48.png"), "nothing half-written");
    }

    @Test
    void neverLetsAnAnswerOverwriteTheOriginal() throws Exception {
        dam.nextResult = scripted("original", "cq5dam.thumbnail.48.48.png");
        new AssetProcessor(dam).process(resolver, PATH);
        try (InputStream in = resolver.getResource(PATH).adaptTo(Asset.class).getOriginal().getStream()) {
            assertTrue(in.readAllBytes().length > 1000, "still the uploaded JPEG");
        }
    }

    @Test
    void aNewerOriginalWinsOverAnOlderJob() throws Exception {
        dam.nextResult = scripted("cq5dam.thumbnail.48.48.png");
        dam.duringProcess = () -> {
            // Someone re-uploads while this job is waiting for studio-dam.
            ModifiableValueMap orig = resolver.getResource(PATH + "/jcr:content/renditions/original/jcr:content")
                    .adaptTo(ModifiableValueMap.class);
            Calendar later = Calendar.getInstance();
            later.add(Calendar.MINUTE, 1);
            orig.put("jcr:lastModified", later);
        };
        assertEquals(AssetProcessor.Outcome.SUPERSEDED, new AssetProcessor(dam).process(resolver, PATH));
        assertEquals("processing", resolver.getResource(PATH + "/jcr:content").getValueMap().get("dam:assetState"));
    }
}
