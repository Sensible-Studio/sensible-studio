/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

@ExtendWith(SlingContextExtension.class)
class DamApiServletTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private final List<String> jobs = new ArrayList<>();
    private DamApiServlet servlet;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        JobManager jm = (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class},
                (p, m, a) -> {
                    if ("addJob".equals(m.getName())) {
                        jobs.add((String) ((Map<?, ?>) a[1]).get(AssetProcessingJob.PROP_PATH));
                    }
                    return null;
                });
        servlet = new DamApiServlet(jm);
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "WKND Site");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        am.createAsset("/content/dam/wknd/surf.jpg", new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/alps.png", new ByteArrayInputStream(new byte[] {4, 5}), "image/png", true);
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/metadata", "dc:title", "Surf in Bali",
                "dam:size", 3L, "tiff:ImageWidth", 1600L, "tiff:ImageLength", 900L);
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {9}),
                "/content/dam/wknd/surf.jpg/jcr:content/renditions/cq5dam.thumbnail.319.319.png", "image/png");
        resolver.commit();
    }

    private Map<String, Object> get(String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doGet(req, res);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    private Map<String, Object> post(String... params) throws Exception {
        return post(true, params);
    }

    private Map<String, Object> post(boolean csrf, String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("POST");
        if (csrf) {
            req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        }
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doPost(req, res);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> list) {
        return (List<Map<String, Object>>) list.get("items");
    }

    @SuppressWarnings("unchecked")
    private List<String> hits(String... params) throws Exception {
        String[] all = new String[params.length + 2];
        all[0] = "op";
        all[1] = "search";
        System.arraycopy(params, 0, all, 2, params.length);
        Map<String, Object> body = get(all);
        assertEquals(200L, body.get("_status"), String.valueOf(body));
        List<String> out = new java.util.ArrayList<>();
        for (Object i : (List<Object>) body.get("items")) {
            out.add((String) ((Map<String, Object>) i).get("path"));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void galleryPresetsAreSavedUnderAName() throws Exception {
        assertEquals(400L, post("op", "saveGalleryPreset", "title", "Bad", "thumbs", "top").get("_status"));
        assertEquals(400L, post("op", "saveGalleryPreset", "title", "Bad", "ratio", "2:1").get("_status"));
        Map<String, Object> saved = post("op", "saveGalleryPreset", "title", "Product gallery", "thumbs", "left", "arrows", "true",
                "ratio", "1:1", "zoom", "true");
        assertEquals(200L, saved.get("_status"), String.valueOf(saved));
        assertEquals("product-gallery", saved.get("name"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) get("op", "galleryPresets", "path", "/content/dam").get("presets");
        assertEquals(1, list.size());
        assertEquals("left", list.get(0).get("thumbs"));
        assertEquals(true, list.get(0).get("arrows"));
        assertEquals("1:1", list.get(0).get("ratio"));
        assertEquals(true, list.get(0).get("zoom"));
        assertEquals(200L, post("op", "saveGalleryPreset", "name", "product-gallery", "title", "Products", "thumbs", "dots").get("_status"));
        assertEquals("dots", ((List<Map<String, Object>>) get("op", "galleryPresets", "path", "/content/dam").get("presets")).get(0).get("thumbs"));
        assertEquals(200L, post("op", "deleteGalleryPreset", "name", "product-gallery").get("_status"));
        assertEquals(404L, post("op", "deleteGalleryPreset", "name", "product-gallery").get("_status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWatermarkIsAPictureInMedia() throws Exception {
        Map<String, Object> none = (Map<String, Object>) get("op", "profiles").get("watermark");
        assertEquals(false, none.get("ready"));
        assertEquals("center", none.get("position"));
        assertEquals(400L, post("op", "watermark", "image", "/content/dam/wknd/surf.jpg", "position", "middle").get("_status"));
        assertEquals(400L, post("op", "watermark", "image", "/content/dam/wknd", "position", "center").get("_status"));
        assertEquals(400L, post("op", "watermark", "image", "/content/dam/wknd/surf.jpg", "opacity", "0").get("_status"));
        Map<String, Object> set = post("op", "watermark", "image", "/content/dam/wknd/surf.jpg", "position", "tile",
                "opacity", "35", "size", "20");
        assertEquals(200L, set.get("_status"), String.valueOf(set));
        Map<String, Object> w = (Map<String, Object>) get("op", "profiles").get("watermark");
        assertEquals("/content/dam/wknd/surf.jpg", w.get("image"));
        assertEquals("tile", w.get("position"));
        assertEquals(35L, w.get("opacity"));
        assertEquals(true, w.get("ready"));

        Map<String, Object> saved = post("op", "saveProfile", "name", "proofs", "title", "Proofs", "renditions",
                "[{\"name\":\"proof\",\"width\":800,\"height\":800,\"format\":\"jpeg\",\"quality\":80,\"watermark\":true},"
                        + "{\"name\":\"clean\",\"width\":200,\"height\":200,\"format\":\"png\",\"quality\":80}]");
        List<Map<String, Object>> rs = (List<Map<String, Object>>) saved.get("renditions");
        assertEquals(true, rs.get(0).get("watermark"));
        assertNull(rs.get(1).get("watermark"));

        assertEquals(200L, post("op", "watermark", "image", "").get("_status"));
        assertNull(((Map<String, Object>) get("op", "profiles").get("watermark")).get("image"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void commentsAndNotesOnAPicture() throws Exception {
        assertEquals(200L, post("op", "comment", "path", "/content/dam/wknd/surf.jpg", "text", "Use the wider crop").get("_status"));
        Map<String, Object> note = post("op", "comment", "path", "/content/dam/wknd/surf.jpg", "text", "Logo too close to the edge",
                "x", "0.1", "y", "0.2", "w", "0.3", "h", "0.25", "color", "#2d9d78");
        assertEquals(200L, note.get("_status"));
        Map<String, Object> list = get("op", "comments", "path", "/content/dam/wknd/surf.jpg");
        List<Map<String, Object>> comments = (List<Map<String, Object>>) list.get("comments");
        assertEquals(2, comments.size(), String.valueOf(list));
        assertEquals("Use the wider crop", comments.get(0).get("text"));
        assertNull(comments.get(0).get("region"));
        assertEquals(0.3, ((Map<String, Object>) comments.get(1).get("region")).get("w"));
        assertEquals("#2d9d78", comments.get(1).get("color"));
        assertEquals(true, comments.get(1).get("mine"));
        assertNotNull(resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/cq:annotations"), "kept where publishing leaves it out");

        assertEquals(422L, post("op", "comment", "path", "/content/dam/wknd/surf.jpg", "text", "  ").get("_status"));
        assertEquals(422L, post("op", "comment", "path", "/content/dam/wknd/surf.jpg", "text", "off", "x", "0.9", "y", "0", "w", "0.5", "h", "0.1").get("_status"));
        assertEquals(400L, post("op", "comment", "path", "/content/dam/wknd/surf.jpg", "text", "half", "x", "0.1").get("_status"));
        assertEquals(422L, post("op", "comment", "path", "/content/dam/wknd/surf.jpg", "text", "red", "x", "0", "y", "0", "w", "0.1", "h", "0.1",
                "color", "red").get("_status"));

        String id = String.valueOf(comments.get(0).get("id"));
        assertEquals(200L, post("op", "deleteComment", "path", "/content/dam/wknd/surf.jpg", "id", id).get("_status"));
        assertEquals(1, ((List<?>) get("op", "comments", "path", "/content/dam/wknd/surf.jpg").get("comments")).size());
        assertEquals(422L, post("op", "deleteComment", "path", "/content/dam/wknd/surf.jpg", "id", "../../metadata").get("_status"));
    }

    @Test
    void anAssetExpiresOnTheDayGiven() throws Exception {
        assertEquals(200L, post("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "prism:expirationDate", "2020-01-31").get("_status"));
        Map<String, Object> a = get("op", "asset", "path", "/content/dam/wknd/surf.jpg");
        assertEquals(true, a.get("expired"), String.valueOf(a));
        assertTrue(String.valueOf(a.get("expires")).startsWith("2020-01-31T23:59:59"), String.valueOf(a.get("expires")));
        assertEquals(200L, post("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "prism:expirationDate", "2999-12-31").get("_status"));
        assertEquals(false, get("op", "asset", "path", "/content/dam/wknd/surf.jpg").get("expired"));
        assertEquals(400L, post("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "prism:expirationDate", "next week").get("_status"));
        assertEquals(200L, post("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "prism:expirationDate", "").get("_status"));
        Map<String, Object> cleared = get("op", "asset", "path", "/content/dam/wknd/surf.jpg");
        assertNull(cleared.get("expires"));
        assertNull(cleared.get("expired"));
    }

    @Test
    void searchesFullTextAndFilters() throws Exception {
        context.create().resource("/content/dam/wknd/alps.png/jcr:content/metadata", "dc:description", "Skiing in the Alpes Maritimes",
                "cq:tags", new String[] {"wknd-shared:activity/skiing"}, "dam:size", 2L);
        resolver.commit();
        assertEquals(List.of("/content/dam/wknd/surf.jpg"), hits("fulltext", "BALI"));
        assertEquals(List.of("/content/dam/wknd"), hits("fulltext", "wknd site"));
        assertEquals(List.of("/content/dam/wknd/alps.png"), hits("fulltext", "maritimes -surf"));
        assertEquals(List.of("/content/dam/wknd/alps.png"), hits("fulltext", "\"in the alpes\""));
        assertEquals(List.of(), hits("fulltext", "\"alpes in\""));
        // tag IDs are searched as words too, and filter with their sub-tags
        assertEquals(List.of("/content/dam/wknd/alps.png"), hits("fulltext", "skiing", "type", "image"));
        assertEquals(List.of("/content/dam/wknd/alps.png"), hits("tag", "wknd-shared:activity"));
        assertEquals(List.of("/content/dam/wknd/alps.png"), hits("tag", "wknd-shared:"));
        assertEquals(List.of(), hits("tag", "wknd-shared:activity/ski"));
        assertEquals(List.of("/content/dam/wknd/surf.jpg"), hits("orientation", "landscape"));
        assertEquals(List.of("/content/dam/wknd/surf.jpg"), hits("minSize", "3"));
        assertEquals(List.of(), hits("type", "video"));
        assertEquals(List.of("/content/dam/wknd/alps.png", "/content/dam/wknd/surf.jpg"), hits("type", "image", "sort", "name"));
        assertEquals(2L, get("op", "search", "path", "/content/dam/wknd", "type", "image").get("total"));
        assertEquals(400L, get("op", "search", "path", "/content/dam/wknd/surf.jpg").get("_status"));
    }

    @Test
    void checkedOutAssetsCanOnlyBeChangedByTheirOwner() throws Exception {
        assertEquals(200L, post("op", "checkout", "path", "/content/dam/wknd/surf.jpg").get("_status"));
        String me = resolver.getUserID();
        assertEquals(me, items(get("op", "list", "path", "/content/dam/wknd")).stream()
                .filter(i -> "surf.jpg".equals(i.get("name"))).findFirst().get().get("checkedOutBy"));
        assertEquals(200L, post("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "dc:title", "Mine").get("_status"));

        // someone else has it
        resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put(DamLocks.PN_LOCK, "bob");
        resolver.commit();
        assertEquals(423L, post("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "dc:title", "Theirs").get("_status"));
        assertEquals(423L, post("op", "delete", "path", "/content/dam/wknd/alps.png", "path", "/content/dam/wknd/surf.jpg").get("_status"));
        assertNotNull(resolver.getResource("/content/dam/wknd/alps.png"), "nothing deleted when one item is locked");
        assertEquals(423L, post("op", "delete", "path", "/content/dam/wknd").get("_status"), "folders with a locked asset");
        assertEquals(423L, post("op", "move", "path", "/content/dam/wknd/surf.jpg", "dest", "/content/dam").get("_status"));
        assertEquals(423L, post("op", "checkout", "path", "/content/dam/wknd/surf.jpg").get("_status"));
        assertEquals(200L, post("op", "metadata", "path", "/content/dam/wknd/alps.png", "dc:title", "Free").get("_status"));
    }

    @Test
    void relatesAssetsBothWays() throws Exception {
        assertEquals(1L, post("op", "relate", "path", "/content/dam/wknd/surf.jpg", "path", "/content/dam/wknd/alps.png").get("added"));
        assertEquals(0L, post("op", "relate", "path", "/content/dam/wknd/alps.png", "path", "/content/dam/wknd/surf.jpg").get("added"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rel = (List<Map<String, Object>>) get("op", "asset", "path", "/content/dam/wknd/alps.png").get("related");
        assertEquals("/content/dam/wknd/surf.jpg", rel.get(0).get("path"));
        assertEquals("Surf in Bali", rel.get(0).get("title"));
        post("op", "unrelate", "path", "/content/dam/wknd/surf.jpg", "other", "/content/dam/wknd/alps.png");
        assertEquals(List.of(), get("op", "asset", "path", "/content/dam/wknd/alps.png").get("related"));
        assertEquals(400L, post("op", "relate", "path", "/content/dam/wknd/surf.jpg").get("_status"));
    }

    @Test
    void extractsArchivesSafely() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String n : new String[] {"shoot/", "shoot/day one.jpg", "shoot/notes.txt", "run.sh", "../evil.jpg", "__MACOSX/x.jpg",
                    ".hidden/a.jpg", "surf.jpg"}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(n));
                if (!n.endsWith("/")) {
                    zip.write(n.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        new AssetManagerImpl(resolver).createAsset("/content/dam/wknd/pack.zip", new ByteArrayInputStream(bytes.toByteArray()),
                "application/zip", true);
        Map<String, Object> r = post("op", "extractArchive", "path", "/content/dam/wknd/pack.zip", "into", "folder");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        assertEquals(List.of("/content/dam/wknd/pack/shoot/day-one.jpg", "/content/dam/wknd/pack/shoot/notes.txt", "/content/dam/wknd/pack/surf.jpg"),
                r.get("created"));
        assertEquals(List.of("/content/dam/wknd/pack/shoot"), r.get("folders"));
        assertEquals("/content/dam/wknd/pack", r.get("into"));
        assertEquals(List.of("run.sh", "../evil.jpg", ".hidden/a.jpg"), r.get("skipped"));
        assertNull(resolver.getResource("/content/dam/evil.jpg"));
        assertNotNull(resolver.getResource("/content/dam/wknd/pack/shoot/day-one.jpg").adaptTo(com.day.cq.dam.api.Asset.class));

        // into the archive's own folder: surf.jpg exists and is skipped by default, renamed on request
        r = post("op", "extractArchive", "path", "/content/dam/wknd/pack.zip");
        assertTrue(((List<?>) r.get("skipped")).contains("surf.jpg"), String.valueOf(r));
        r = post("op", "extractArchive", "path", "/content/dam/wknd/pack.zip", "conflict", "rename");
        assertTrue(((List<?>) r.get("created")).contains("/content/dam/wknd/surf1.jpg"), String.valueOf(r));
        assertEquals(400L, post("op", "extractArchive", "path", "/content/dam/wknd/surf.jpg").get("_status"));
    }

    @Test
    void videosTakeAPosterFrame() throws Exception {
        new AssetManagerImpl(resolver).createAsset("/content/dam/wknd/clip.mp4", new ByteArrayInputStream(new byte[] {0, 0, 0, 24}),
                "video/mp4", true);
        context.create().resource("/content/dam/wknd/clip.mp4/jcr:content/metadata", "dc:format", "video/mp4", "dam:duration", 10.24);
        resolver.commit();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
        Map<String, Object> r = postFile("file", jpeg, "op", "poster", "path", "/content/dam/wknd/clip.mp4");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        assertEquals(List.of("/content/dam/wknd/clip.mp4"), r.get("queued"));
        assertTrue(jobs.contains("/content/dam/wknd/clip.mp4"));
        Resource poster = resolver.getResource("/content/dam/wknd/clip.mp4/jcr:content/renditions/" + AssetProcessor.POSTER);
        assertNotNull(poster);
        assertEquals("image/jpeg", poster.getChild("jcr:content").getValueMap().get("jcr:mimeType", String.class));
        Map<String, Object> asset = get("op", "asset", "path", "/content/dam/wknd/clip.mp4");
        assertEquals(10.24, ((Number) asset.get("duration")).doubleValue(), 1e-9);
        assertTrue(String.valueOf(asset.get("poster")).contains("/renditions/" + AssetProcessor.POSTER + "?"), String.valueOf(asset));
        assertNull(asset.get("videoTools"), "no client: nothing said");
        FakeDamClient dam = new FakeDamClient();
        java.lang.reflect.Field f = DamApiServlet.class.getDeclaredField("damClient");
        f.setAccessible(true);
        f.set(servlet, dam);
        dam.ffmpeg = false;
        assertEquals(false, get("op", "asset", "path", "/content/dam/wknd/clip.mp4").get("videoTools"), "no ffmpeg: the console says so");
        assertNull(get("op", "asset", "path", "/content/dam/wknd/surf.jpg").get("videoTools"), "pictures do not need it");
        dam.ffmpeg = true;
        assertNull(get("op", "asset", "path", "/content/dam/wknd/clip.mp4").get("videoTools"));
        f.set(servlet, null);

        // anything but a JPEG or PNG, and anything but a video, is refused
        assertEquals(400L, postFile("file", "<svg onload=alert(1)>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "op", "poster", "path", "/content/dam/wknd/clip.mp4").get("_status"));
        assertEquals(400L, postFile("file", jpeg, "op", "poster", "path", "/content/dam/wknd/surf.jpg").get("_status"));
        assertEquals(400L, post("op", "poster", "path", "/content/dam/wknd/clip.mp4").get("_status"));

        assertEquals(200L, post("op", "poster", "path", "/content/dam/wknd/clip.mp4", "remove", "true").get("_status"));
        assertNull(resolver.getResource("/content/dam/wknd/clip.mp4/jcr:content/renditions/" + AssetProcessor.POSTER));
    }

    @Test
    void readsTheDurationOfImportedVideos() throws Exception {
        new AssetManagerImpl(resolver).createAsset("/content/dam/wknd/old.mp4", new ByteArrayInputStream(new byte[] {1}), "video/mp4", true);
        context.create().resource("/content/dam/wknd/old.mp4/jcr:content/metadata/xmpDM:duration", "xmpDM:scale", "1/1000",
                "xmpDM:value", 10240L);
        resolver.commit();
        Map<String, Object> asset = get("op", "asset", "path", "/content/dam/wknd/old.mp4");
        assertEquals(10.24, ((Number) asset.get("duration")).doubleValue(), 1e-9);
        assertNull(get("op", "asset", "path", "/content/dam/wknd/surf.jpg").get("duration"));
    }

    private Map<String, Object> postFile(String name, byte[] content, String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("POST");
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        req.addRequestParameter(name, content, "application/octet-stream", "frame.jpg");
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doPost(req, res);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void managesProcessingProfiles() throws Exception {
        String renditions = "[{\"name\":\"banner\",\"width\":1200,\"height\":400,\"format\":\"webp\",\"quality\":70},"
                + "{\"name\":\"square\",\"width\":\"300\",\"height\":300,\"format\":\"png\"}]";
        Map<String, Object> saved = post("op", "saveProfile", "name", "web", "title", "Web banners", "renditions", renditions);
        assertEquals(200L, saved.get("_status"), String.valueOf(saved));
        assertEquals("/conf/global/settings/dam/processing/web", saved.get("path"));
        assertEquals(2, ((List<Object>) saved.get("renditions")).size());
        for (String bad : new String[] {"[{\"name\":\"original\",\"width\":1,\"height\":1}]",
                "[{\"name\":\"x\",\"width\":0,\"height\":1}]", "[{\"name\":\"x\",\"width\":1,\"height\":1,\"format\":\"gif\"}]",
                "[{\"name\":\"x\",\"width\":1,\"height\":1},{\"name\":\"x\",\"width\":2,\"height\":2}]", "{"}) {
            assertEquals(400L, post("op", "saveProfile", "name", "bad", "renditions", bad).get("_status"), bad);
        }
        assertEquals(400L, post("op", "saveProfile", "name", "../x", "renditions", "[]").get("_status"));

        assertEquals(200L, post("op", "folderProfile", "path", "/content/dam/wknd", "profile", "/conf/global/settings/dam/processing/web")
                .get("_status"));
        assertEquals(404L, post("op", "folderProfile", "path", "/content/dam/wknd", "profile", "/conf/global/settings/dam/processing/nope")
                .get("_status"));
        context.create().resource("/content/dam/wknd/sub", "jcr:primaryType", "sling:OrderedFolder");
        resolver.commit();
        Map<String, Object> profiles = get("op", "profiles", "path", "/content/dam/wknd/sub");
        assertEquals("/conf/global/settings/dam/processing/web", profiles.get("effective"), "inherited from the parent");
        assertEquals("Web banners", ((Map<String, Object>) ((List<Object>) profiles.get("profiles")).get(0)).get("title"));
        assertEquals("/conf/global/settings/dam/processing/web", items(get("op", "list", "path", "/content/dam")).stream()
                .filter(i -> "wknd".equals(i.get("name"))).findFirst().get().get("processingProfile"));

        post("op", "deleteProfile", "name", "web");
        assertEquals(List.of(), get("op", "profiles").get("profiles"));
    }

    @Test
    void exportsAndImportsMetadataAsCsv() throws Exception {
        org.apache.sling.api.resource.ModifiableValueMap md = resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/metadata")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        md.put("dc:subject", new String[] {"surf", "bali"});
        md.put("dc:description", "Waves, \"big\" ones\nand more");
        resolver.commit();
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.addRequestParameter("path", "/content/dam/wknd");
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new DamCsvServlet().doGet(req, res);
        String csv = new String(res.getOutput(), java.nio.charset.StandardCharsets.UTF_8);
        List<List<String>> rows = DamCsv.parse(csv);
        assertEquals("assetPath", rows.get(0).get(0));
        assertTrue(rows.get(0).contains("dc:subject {{String: multi}}"), String.valueOf(rows.get(0)));
        assertTrue(rows.get(0).contains("dam:size {{Long}}"), String.valueOf(rows.get(0)));
        int desc = rows.get(0).indexOf("dc:description {{String}}");
        List<String> surf = rows.stream().filter(r -> r.get(0).endsWith("surf.jpg")).findFirst().get();
        assertEquals("Waves, \"big\" ones\nand more", surf.get(desc), "quotes, commas and line breaks survive");
        assertEquals("surf|bali", surf.get(rows.get(0).indexOf("dc:subject {{String: multi}}")));

        String in = "assetPath,dc:title {{String}},dc:subject {{String: multi}},studio:rating {{Long}},studio:shot {{Date}}\r\n"
                + "/content/dam/wknd/surf.jpg,\"Surf, Bali\",a|b||c,5,2026-10-17\r\n"
                + "/content/dam/wknd/alps.png,,x,notanumber,\r\n"
                + "/content/dam/nope.jpg,T,,,\r\n";
        Map<String, Object> r = post("op", "importMetadata", "csv", in);
        assertEquals(1L, r.get("updated"), String.valueOf(r));
        assertEquals(2, ((List<?>) r.get("skipped")).size());
        org.apache.sling.api.resource.ValueMap after = resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/metadata").getValueMap();
        assertEquals("Surf, Bali", after.get("dc:title", String.class));
        assertEquals(List.of("a", "b", "c"), List.of(after.get("dc:subject", String[].class)));
        assertEquals(5L, after.get("studio:rating", Long.class));
        assertEquals(2026, after.get("studio:shot", java.util.Calendar.class).get(java.util.Calendar.YEAR));
        assertEquals(400L, post("op", "importMetadata", "csv", "path,dc:title\r\n").get("_status"));
        assertEquals(400L, post("op", "importMetadata", "csv", "assetPath,jcr:primaryType\r\n/content/dam/wknd/surf.jpg,nt:file").get("_status"));
        assertEquals("'=SUM(A1)", DamCsv.cell("=SUM(A1)"));
        assertEquals("-12", DamCsv.cell("-12"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectsAssetsWithoutMovingThem() throws Exception {
        Map<String, Object> c = post("op", "createCollection", "title", "Summer Campaign", "description", "picks");
        assertEquals(200L, c.get("_status"), String.valueOf(c));
        String path = (String) c.get("path");
        assertEquals("/content/dam/collections/summer-campaign", path);
        assertEquals(2L, post("op", "addToCollection", "collection", path, "path", "/content/dam/wknd/surf.jpg", "path", "/content/dam/wknd")
                .get("changed"));
        assertEquals(0L, post("op", "addToCollection", "collection", path, "path", "/content/dam/wknd/surf.jpg").get("changed"));
        assertEquals(404L, post("op", "addToCollection", "collection", path, "path", "/apps/x").get("_status"));
        Map<String, Object> view = get("op", "collection", "path", path);
        assertEquals(List.of("/content/dam/wknd/surf.jpg", "/content/dam/wknd"),
                ((List<Map<String, Object>>) view.get("items")).stream().map(i -> i.get("path")).collect(java.util.stream.Collectors.toList()));
        assertNotNull(resolver.getResource("/content/dam/wknd/surf.jpg"), "the asset stays where it is");
        // not a folder: the root listing does not show it
        assertTrue(items(get("op", "list", "path", "/content/dam")).stream().noneMatch(i -> "collections".equals(i.get("name"))));

        Map<String, Object> smart = post("op", "createCollection", "title", "Bali", "scope", "/content/dam/wknd", "query",
                "{\"fulltext\":\"bali\",\"types\":[\"image\"]}");
        assertEquals(200L, smart.get("_status"), String.valueOf(smart));
        assertEquals(List.of("/content/dam/wknd/surf.jpg"), ((List<Map<String, Object>>) get("op", "collection", "path", (String) smart.get("path"))
                .get("items")).stream().map(i -> i.get("path")).collect(java.util.stream.Collectors.toList()));
        assertEquals(400L, post("op", "addToCollection", "collection", (String) smart.get("path"), "path", "/content/dam/wknd/alps.png")
                .get("_status"));

        List<Map<String, Object>> all = (List<Map<String, Object>>) get("op", "collections").get("collections");
        assertEquals(List.of("Bali", "Summer Campaign"), all.stream().map(x -> x.get("title")).collect(java.util.stream.Collectors.toList()));
        assertEquals(2L, all.get(1).get("count"));
        post("op", "removeFromCollection", "collection", path, "path", "/content/dam/wknd");
        assertEquals(1L, get("op", "collection", "path", path).get("total"));
        post("op", "deleteCollection", "collection", path);
        assertNull(resolver.getResource(path));
    }

    @Test
    @SuppressWarnings("unchecked")
    void foldersSchemaShapesThePropertiesForm() throws Exception {
        String def = "{\"tabs\":[{\"title\":\"Brand\",\"fields\":["
                + "{\"label\":\"Title\",\"property\":\"dc:title\",\"type\":\"text\",\"required\":true},"
                + "{\"label\":\"Campaign\",\"property\":\"wknd:campaign\",\"type\":\"select\",\"options\":[{\"value\":\"summer\",\"text\":\"Summer\"},{\"value\":\"winter\"}]},"
                + "{\"label\":\"Usage until\",\"property\":\"prism:expirationDate\",\"type\":\"date\"},"
                + "{\"label\":\"Rating\",\"property\":\"xmp:Rating\",\"type\":\"number\"},"
                + "{\"label\":\"Approved\",\"property\":\"wknd:approved\",\"type\":\"checkbox\"}]}]}";
        Map<String, Object> saved = post("op", "saveSchema", "name", "brand", "title", "Brand", "definition", def);
        assertEquals(200L, saved.get("_status"), String.valueOf(saved));
        for (String bad : new String[] {"{\"tabs\":[]}", "{\"tabs\":[{\"title\":\"T\",\"fields\":[{\"property\":\"jcr:title\"}]}]}",
                "{\"tabs\":[{\"title\":\"T\",\"fields\":[{\"property\":\"dc:x\",\"type\":\"select\"}]}]}",
                "{\"tabs\":[{\"title\":\"T\",\"fields\":[{\"property\":\"dc:x\"},{\"property\":\"dc:x\"}]}]}"}) {
            assertEquals(400L, post("op", "saveSchema", "name", "bad", "definition", bad).get("_status"), bad);
        }
        assertEquals(200L, post("op", "folderSchema", "path", "/content/dam/wknd", "schema", "/conf/global/settings/dam/adminui-extension/metadataschema/brand")
                .get("_status"));
        Map<String, Object> details = get("op", "asset", "path", "/content/dam/wknd/surf.jpg");
        Map<String, Object> schema = (Map<String, Object>) details.get("schema");
        assertEquals("Brand", schema.get("title"));
        assertEquals("Summer", ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) schema.get("tabs")).get(0)).get("fields")).get(1)).get("options")).get(0)).get("text"));

        Map<String, Object> w = post("op", "metadataFields", "path", "/content/dam/wknd/surf.jpg", "fields",
                "{\"dc:title\":\"New\",\"wknd:campaign\":\"summer\",\"prism:expirationDate\":{\"date\":\"2027-01-31\"},\"xmp:Rating\":4,\"wknd:approved\":true,\"dc:subject\":[\"a\",\"b\"]}");
        assertEquals(200L, w.get("_status"), String.valueOf(w));
        org.apache.sling.api.resource.ValueMap md = resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/metadata").getValueMap();
        assertEquals("New", md.get("dc:title", String.class));
        assertEquals(4L, md.get("xmp:Rating", Long.class));
        assertEquals(true, md.get("wknd:approved", Boolean.class));
        assertEquals(2027, md.get("prism:expirationDate", java.util.Calendar.class).get(java.util.Calendar.YEAR));
        assertEquals(400L, post("op", "metadataFields", "path", "/content/dam/wknd/surf.jpg", "fields", "{\"jcr:primaryType\":\"x\"}").get("_status"));
        post("op", "metadataFields", "path", "/content/dam/wknd/surf.jpg", "fields", "{\"wknd:campaign\":null}");
        assertNull(resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/metadata").getValueMap().get("wknd:campaign"));
        assertEquals("/conf/global/settings/dam/adminui-extension/metadataschema/brand",
                get("op", "schemas", "path", "/content/dam/wknd/surf.jpg").get("effective"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsDuplicatesBySha1() throws Exception {
        context.create().resource("/content/dam/wknd/alps.png/jcr:content/metadata", "dam:sha1", "479b58c3531bd476dc73943f58cd5286e1a37952");
        resolver.commit();
        List<Map<String, Object>> d = (List<Map<String, Object>>) get("op", "duplicates", "sha1", "479B58C3531BD476DC73943F58CD5286E1A37952")
                .get("duplicates");
        assertEquals(List.of("/content/dam/wknd/alps.png"), d.stream().map(x -> x.get("path")).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of(), get("op", "duplicates", "sha1", "0000000000000000000000000000000000000000").get("duplicates"));
        assertEquals(400L, get("op", "duplicates", "sha1", "abc").get("_status"));
    }

    @Test
    void foldersCanShowAChosenThumbnail() throws Exception {
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {7}),
                "/content/dam/wknd/alps.png/jcr:content/renditions/cq5dam.thumbnail.319.319.png", "image/png");
        resolver.commit();
        assertEquals(200L, post("op", "folderThumbnail", "path", "/content/dam/wknd", "asset", "/content/dam/wknd/alps.png").get("_status"));
        Map<String, Object> wknd = items(get("op", "list", "path", "/content/dam")).stream().filter(i -> "wknd".equals(i.get("name"))).findFirst().get();
        assertEquals(1, ((List<?>) wknd.get("previews")).size());
        assertTrue(String.valueOf(((List<?>) wknd.get("previews")).get(0)).contains("/alps.png/"), String.valueOf(wknd.get("previews")));
        assertEquals("/content/dam/wknd/alps.png", wknd.get("thumbnailAsset"));
        assertEquals(400L, post("op", "folderThumbnail", "path", "/content/dam/wknd", "asset", "/content/dam/wknd/../wknd/alps.png").get("_status"));
        assertEquals(400L, post("op", "folderThumbnail", "path", "/content/dam/wknd", "asset", "/content/dam/other.jpg").get("_status"));
        post("op", "folderThumbnail", "path", "/content/dam/wknd", "asset", "");
        assertNull(items(get("op", "list", "path", "/content/dam")).stream().filter(i -> "wknd".equals(i.get("name"))).findFirst().get()
                .get("thumbnailAsset"));
    }

    @Test
    void listsFoldersAndAssets() throws Exception {
        Map<String, Object> root = get("op", "list", "path", "/content/dam");
        assertEquals("Assets", root.get("title"));
        Map<String, Object> folder = items(root).get(0);
        assertEquals("folder", folder.get("type"));
        assertEquals("WKND Site", folder.get("title"));
        assertEquals(List.of("/content/dam/wknd/surf.jpg/_jcr_content/renditions/cq5dam.thumbnail.319.319.png"), folder.get("previews"));

        Map<String, Object> wknd = get("op", "list", "path", "/content/dam/wknd/", "sort", "title");
        assertEquals(2L, wknd.get("total"));
        Map<String, Object> first = items(wknd).get(0);
        assertEquals("alps.png", first.get("title"), "no dc:title: the name");
        Map<String, Object> surf = items(wknd).get(1);
        assertEquals("Surf in Bali", surf.get("title"));
        assertEquals(1600L, surf.get("width"));
        assertEquals("image/jpeg", surf.get("mimeType"));
        assertEquals("processing", surf.get("state"));
        // The binary's last-modified time busts browser caches after an edit or a reprocess.
        assertTrue(String.valueOf(surf.get("original")).matches("/content/dam/wknd/surf\\.jpg/_jcr_content/renditions/original\\?_ck=\\d+"),
                String.valueOf(surf.get("original")));
        assertEquals(2, ((List<?>) wknd.get("breadcrumbs")).size());

        Map<String, Object> desc = get("op", "list", "path", "/content/dam/wknd", "sort", "title", "order", "desc", "limit", "1");
        assertEquals("Surf in Bali", items(desc).get(0).get("title"));
        assertEquals(1, items(desc).size());
    }

    @Test
    void folderPreviewsLookIntoSubfolders() throws Exception {
        context.create().resource("/content/dam/top", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/top/en", "jcr:primaryType", "sling:OrderedFolder");
        new AssetManagerImpl(resolver).createAsset("/content/dam/top/en/deep.jpg", new ByteArrayInputStream(new byte[] {1}), "image/jpeg", true);
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {9}),
                "/content/dam/top/en/deep.jpg/jcr:content/renditions/cq5dam.thumbnail.319.319.png", "image/png");
        resolver.commit();
        Map<String, Object> root = get("op", "list", "path", "/content/dam");
        Map<String, Object> top = items(root).stream().filter(i -> "top".equals(i.get("name"))).findFirst().get();
        assertEquals(List.of("/content/dam/top/en/deep.jpg/_jcr_content/renditions/cq5dam.thumbnail.319.319.png"), top.get("previews"));
    }

    @Test
    void marksContentFragments() throws Exception {
        context.create().resource("/content/dam/wknd/cf", "jcr:primaryType", "dam:Asset");
        context.create().resource("/content/dam/wknd/cf/jcr:content", "jcr:primaryType", "dam:AssetContent",
                "contentFragment", true, "jcr:title", "Yosemite", "jcr:description", "Granite cliffs");
        resolver.commit();
        Map<String, Object> cf = items(get("op", "list", "path", "/content/dam/wknd")).stream()
                .filter(i -> "cf".equals(i.get("name"))).findFirst().get();
        assertEquals(true, cf.get("contentFragment"));
        assertEquals("Granite cliffs", cf.get("description"));
        assertEquals("Yosemite", cf.get("title"));
    }

    @Test
    void refusesPathsOutsideTheDam() throws Exception {
        assertEquals(400L, get("op", "list", "path", "/apps").get("_status"));
        assertEquals(400L, get("op", "list", "path", "/content/dam/../../apps").get("_status"));
        assertEquals(404L, get("op", "list", "path", "/content/dam/missing").get("_status"));
        assertEquals(400L, post("op", "delete", "path", "/content/dam").get("_status"));
        assertEquals(400L, post("op", "delete", "path", "/apps/x").get("_status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsAssetDetails() throws Exception {
        Map<String, Object> d = get("op", "asset", "path", "/content/dam/wknd/surf.jpg");
        assertEquals("Surf in Bali", ((Map<String, Object>) d.get("metadata")).get("dc:title"));
        List<Map<String, Object>> renditions = (List<Map<String, Object>>) d.get("renditions");
        assertTrue(renditions.stream().anyMatch(r -> "original".equals(r.get("name"))));
        assertTrue(renditions.stream().anyMatch(r -> "cq5dam.thumbnail.319.319.png".equals(r.get("name"))));
        assertEquals(400L, get("op", "asset", "path", "/content/dam/wknd").get("_status"));
    }

    @Test
    void createsFoldersWithAemStyleNames() throws Exception {
        Map<String, Object> r = post("op", "createFolder", "parent", "/content/dam/wknd", "title", "Ski Touring – Mont Blanc 2026");
        assertEquals("/content/dam/wknd/ski-touring-mont-blanc-2026", r.get("path"));
        ValueMap c = resolver.getResource("/content/dam/wknd/ski-touring-mont-blanc-2026/jcr:content").getValueMap();
        assertEquals("Ski Touring – Mont Blanc 2026", c.get("jcr:title", String.class));
        assertEquals(409L, post("op", "createFolder", "parent", "/content/dam/wknd", "title", "ski touring mont blanc 2026").get("_status"));
        assertEquals(400L, post("op", "createFolder", "parent", "/content/dam/wknd", "title", "  ").get("_status"));
        assertEquals(404L, post("op", "createFolder", "parent", "/content/dam/wknd/surf.jpg", "title", "x").get("_status"));
    }

    @Test
    void requiresTheCsrfHeader() throws Exception {
        assertEquals(403L, post(false, "op", "createFolder", "parent", "/content/dam", "title", "x").get("_status"));
        assertNull(resolver.getResource("/content/dam/x"));
    }

    @Test
    void movesAndRenames() throws Exception {
        post("op", "createFolder", "parent", "/content/dam", "title", "Archive");
        Map<String, Object> r = post("op", "move", "path", "/content/dam/wknd/surf.jpg", "dest", "/content/dam/archive", "name", "Surf 2026.JPG");
        assertEquals(List.of("/content/dam/archive/surf-2026.jpg"), r.get("paths"));
        assertNotNull(resolver.getResource("/content/dam/archive/surf-2026.jpg/jcr:content/renditions/original"));
        assertNull(resolver.getResource("/content/dam/wknd/surf.jpg"));

        assertEquals(400L, post("op", "move", "path", "/content/dam/archive", "dest", "/content/dam/archive").get("_status"),
                "into itself");
        post("op", "copy", "path", "/content/dam/wknd/alps.png", "dest", "/content/dam/archive");
        assertEquals(409L, post("op", "move", "path", "/content/dam/wknd/alps.png", "dest", "/content/dam/archive").get("_status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationsAndCollectionsFollowAMove() throws Exception {
        assertEquals(200L, post("op", "relate", "path", "/content/dam/wknd/surf.jpg", "path", "/content/dam/wknd/alps.png").get("_status"));
        Map<String, Object> c = post("op", "createCollection", "title", "Picks");
        String collection = (String) c.get("path");
        assertNotNull(collection, String.valueOf(c));
        post("op", "addToCollection", "collection", collection, "path", "/content/dam/wknd/surf.jpg", "path", "/content/dam/wknd");
        post("op", "createFolder", "parent", "/content/dam", "title", "Archive");
        // the folder moves: the asset inside moves with it
        Map<String, Object> r = post("op", "move", "path", "/content/dam/wknd", "dest", "/content/dam/archive");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        assertTrue(((Number) r.get("followed")).intValue() >= 3, String.valueOf(r));
        ValueMap a = resolver.getResource("/content/dam/archive/wknd/surf.jpg/jcr:content/related").getValueMap();
        assertEquals(List.of("/content/dam/archive/wknd/alps.png"), List.of(a.get("others", String[].class)));
        ValueMap b = resolver.getResource("/content/dam/archive/wknd/alps.png/jcr:content/related").getValueMap();
        assertEquals(List.of("/content/dam/archive/wknd/surf.jpg"), List.of(b.get("others", String[].class)));
        assertEquals(List.of("/content/dam/archive/wknd/surf.jpg", "/content/dam/archive/wknd"),
                List.of(resolver.getResource(collection).getValueMap().get("studio:members", String[].class)));
        assertEquals("/content/dam/x/y", DamMoveFollow.map(Map.of("/content/dam/a", "/content/dam/x"), "/content/dam/a/y"));
        assertEquals("/content/dam/ab", DamMoveFollow.map(Map.of("/content/dam/a", "/content/dam/x"), "/content/dam/ab"));
    }

    @Test
    void movesAdjustReferencesInTheChosenPages() throws Exception {
        context.create().resource("/content/site/en", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/site/en/jcr:content", "jcr:primaryType", "nt:unstructured",
                "sling:resourceType", "site/page");
        context.create().resource("/content/site/en/jcr:content/root/image", "jcr:primaryType", "nt:unstructured",
                "fileReference", "/content/dam/wknd/surf.jpg");
        context.create().resource("/content/site/en/jcr:content/root/text", "jcr:primaryType", "nt:unstructured",
                "text", "<img src=\"/content/dam/wknd/surf.jpg/_jcr_content/renditions/original\"> /content/dam/wknd/surf.jpgx",
                "links", new String[] {"/content/dam/wknd/alps.png", "/content/dam/wknd/surf.jpg"});
        // A page the author did not tick, and a child page of the ticked one: both stay as they are.
        context.create().resource("/content/site/de/jcr:content/root/image", "jcr:primaryType", "nt:unstructured",
                "fileReference", "/content/dam/wknd/surf.jpg");
        context.create().resource("/content/site/en/child/jcr:content/root/image", "jcr:primaryType", "nt:unstructured",
                "fileReference", "/content/dam/wknd/surf.jpg");
        resolver.commit();

        Map<String, Object> r = post("op", "move", "path", "/content/dam/wknd/surf.jpg", "dest", "/content/dam/wknd",
                "name", "beach.jpg", "adjust", "/content/site/en");
        assertEquals(List.of("/content/dam/wknd/beach.jpg"), r.get("paths"));
        assertEquals(3L, r.get("adjusted"));
        assertEquals("/content/dam/wknd/beach.jpg", value("/content/site/en/jcr:content/root/image", "fileReference"));
        assertEquals("<img src=\"/content/dam/wknd/beach.jpg/_jcr_content/renditions/original\"> /content/dam/wknd/surf.jpgx",
                value("/content/site/en/jcr:content/root/text", "text"), "rendition URLs follow; longer names do not match");
        assertEquals(List.of("/content/dam/wknd/alps.png", "/content/dam/wknd/beach.jpg"),
                List.of(resolver.getResource("/content/site/en/jcr:content/root/text").getValueMap().get("links", String[].class)));
        assertEquals("/content/dam/wknd/surf.jpg", value("/content/site/de/jcr:content/root/image", "fileReference"));
        assertEquals("/content/dam/wknd/surf.jpg", value("/content/site/en/child/jcr:content/root/image", "fileReference"));

        // Folders: everything below the old path moves with it.
        post("op", "createFolder", "parent", "/content/dam", "title", "Archive");
        Map<String, Object> f = post("op", "move", "path", "/content/dam/wknd", "dest", "/content/dam/archive",
                "adjust", "/content/site/de/jcr:content/root/image");
        assertEquals(1L, f.get("adjusted"));
        assertEquals("/content/dam/archive/wknd/surf.jpg", value("/content/site/de/jcr:content/root/image", "fileReference"));

        assertEquals(403L, post("op", "move", "path", "/content/dam/archive/wknd/alps.png", "dest", "/content/dam",
                "adjust", "/apps/x").get("_status"), "only /content and /conf");
        assertNotNull(resolver.getResource("/content/dam/archive/wknd/alps.png"), "a refused adjustment undoes the move");
    }

    private String value(String path, String name) {
        return resolver.getResource(path).getValueMap().get(name, String.class);
    }

    @Test
    void copiesWithUniqueNames() throws Exception {
        Map<String, Object> a = post("op", "copy", "path", "/content/dam/wknd/surf.jpg", "dest", "/content/dam/wknd");
        assertEquals(List.of("/content/dam/wknd/surf1.jpg"), a.get("paths"));
        Map<String, Object> b = post("op", "copy", "path", "/content/dam/wknd/surf.jpg", "dest", "/content/dam/wknd");
        assertEquals(List.of("/content/dam/wknd/surf2.jpg"), b.get("paths"));
        assertEquals("Surf in Bali", resolver.getResource("/content/dam/wknd/surf1.jpg/jcr:content/metadata")
                .getValueMap().get("dc:title", String.class));
        assertEquals(400L, post("op", "copy", "path", "/content/dam/wknd", "dest", "/content/dam/wknd").get("_status"));
    }

    @Test
    void deletes() throws Exception {
        Map<String, Object> r = post("op", "delete", "path", "/content/dam/wknd/surf.jpg", "path", "/content/dam/wknd/alps.png");
        assertEquals(2, ((List<?>) r.get("deleted")).size());
        assertNull(resolver.getResource("/content/dam/wknd/surf.jpg"));
        assertNull(resolver.getResource("/content/dam/wknd/alps.png"));
        assertFalse(resolver.getResource("/content/dam/wknd") == null);
    }

    @Test
    void deletingAPublishedFileTakesItOffTheLiveSiteFirst() throws Exception {
        org.apache.sling.api.resource.ModifiableValueMap m = resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        m.put("cq:lastReplicationAction", "Activate");
        resolver.commit();
        assertTrue(DamApiServlet.hasPublished(resolver.getResource("/content/dam/wknd")), "a folder with a published file");
        assertFalse(DamApiServlet.hasPublished(resolver.getResource("/content/dam/wknd/alps.png")));
        // without publishing here, a published file stays: it would be left on the live site
        assertEquals(409L, post("op", "delete", "path", "/content/dam/wknd/alps.png", "path", "/content/dam/wknd/surf.jpg").get("_status"));
        assertNotNull(resolver.getResource("/content/dam/wknd/alps.png"), "nothing deleted");
        List<String> calls = new java.util.ArrayList<>();
        com.day.cq.replication.Replicator rep = (com.day.cq.replication.Replicator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {com.day.cq.replication.Replicator.class}, (proxy, method, args) -> {
                    if ("replicate".equals(method.getName())) {
                        calls.add(args[1] + " " + args[2]);
                    }
                    return null;
                });
        Map<String, Object> r = DamApiServlet.delete(resolver,
                java.util.Arrays.asList("/content/dam/wknd/alps.png", "/content/dam/wknd/surf.jpg"), rep);
        assertEquals(List.of("/content/dam/wknd/surf.jpg"), r.get("unpublished"));
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).toUpperCase().contains("DEACTIVATE") && calls.get(0).endsWith("/content/dam/wknd/surf.jpg"), calls.get(0));
        assertNull(resolver.getResource("/content/dam/wknd/surf.jpg"));
    }

    @Test
    void editsBasicMetadataOnly() throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("POST");
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        req.setParameterMap(Map.of("op", "metadata", "path", "/content/dam/wknd/surf.jpg", "dc:title", "Bali",
                "dc:subject", new String[] {"surf", "ocean", "surf", " "}, "dam:sha1", "forged", "dc:description", ""));
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doPost(req, res);
        assertEquals(200, res.getStatus(), res.getOutputAsString());
        ValueMap md = resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/metadata").getValueMap();
        assertEquals("Bali", md.get("dc:title", String.class));
        assertEquals(List.of("surf", "ocean"), List.of(md.get("dc:subject", String[].class)));
        assertNull(md.get("dam:sha1"), "only Basic fields can be written here");
        assertNull(md.get("dc:description"));
    }

    @Test
    void reprocessQueuesJobs() throws Exception {
        resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("dam:assetState", "processed");
        resolver.commit();
        post("op", "reprocess", "path", "/content/dam/wknd/surf.jpg");
        assertEquals(List.of("/content/dam/wknd/surf.jpg"), jobs);
        assertEquals("processing", resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content").getValueMap().get("dam:assetState"));
    }

    @Test
    void renamesFolderTitles() throws Exception {
        post("op", "folderTitle", "path", "/content/dam/wknd", "title", "WKND");
        assertEquals("WKND", resolver.getResource("/content/dam/wknd/jcr:content").getValueMap().get("jcr:title"));
    }

    @Test
    void nodeNamesAreSafe() {
        assertEquals("summer-ski", DamApiServlet.nodeName("Summer Ski!"));
        assertEquals("sare-si-piper", DamApiServlet.nodeName("Sare și Piper"));
        assertEquals("etc", DamApiServlet.nodeName("../etc"));
        assertNull(DamApiServlet.nodeName("..."));
    }

    private void picture(String name) throws Exception {
        String path = "/content/dam/wknd/" + name;
        context.create().resource(path, "jcr:primaryType", "dam:Asset");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "dam:AssetContent");
        context.create().resource(path + "/jcr:content/metadata", "dc:title", name, "dam:MIMEtype", "image/jpeg");
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {1}), path + "/jcr:content/renditions/original", "image/jpeg");
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {2}),
                path + "/jcr:content/renditions/cq5dam.thumbnail.319.319.png", "image/png");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anImageSetNamesPicturesInAnOrder() throws Exception {
        picture("one.jpg");
        picture("two.jpg");
        picture("three.jpg");
        resolver.commit();
        Map<String, Object> made = post("op", "createSet", "parent", "/content/dam/wknd", "title", "Bali Trip",
                "member", "/content/dam/wknd/two.jpg", "member", "/content/dam/wknd/one.jpg", "member", "/content/dam/wknd/two.jpg");
        assertEquals(200L, made.get("_status"), String.valueOf(made));
        assertEquals("/content/dam/wknd/bali-trip", made.get("path"));

        Map<String, Object> set = get("op", "asset", "path", "/content/dam/wknd/bali-trip");
        assertEquals(Boolean.TRUE, set.get("imageSet"));
        assertEquals("Bali Trip", set.get("title"));
        assertEquals(2L, ((Number) set.get("members")).longValue(), "a picture named twice is in the set once");
        List<Map<String, Object>> pictures = (List<Map<String, Object>>) set.get("setMembers");
        assertEquals("/content/dam/wknd/two.jpg", pictures.get(0).get("path"));
        assertEquals("/content/dam/wknd/one.jpg", pictures.get(1).get("path"));
        assertNotNull(set.get("thumbnail"), "shown by its first picture");
        assertNotNull(resolver.getResource("/content/dam/wknd/bali-trip/jcr:content/renditions/cq5dam.thumbnail.319.319.png"),
                "and it carries that picture's thumbnail as its own, for whatever shows a file by its renditions");

        assertNull(resolver.getResource("/content/dam/wknd/bali-trip/jcr:content/renditions/original"), "but no original");
        assertEquals(DamSets.MIME, resolver.getResource("/content/dam/wknd/bali-trip/jcr:content/metadata").getValueMap().get("dc:format", String.class));
        assertNull(DamSmartTags.source(resolver.getResource("/content/dam/wknd/bali-trip")), "nor is it a picture to tag or compare");

        // another order, another picture
        assertEquals(200L, post("op", "setMembers", "path", "/content/dam/wknd/bali-trip", "member", "/content/dam/wknd/three.jpg",
                "member", "/content/dam/wknd/two.jpg").get("_status"));
        pictures = (List<Map<String, Object>>) get("op", "asset", "path", "/content/dam/wknd/bali-trip").get("setMembers");
        assertEquals("/content/dam/wknd/three.jpg", pictures.get(0).get("path"));

        // a deleted picture is no longer in it, and the set says one is missing
        resolver.delete(resolver.getResource("/content/dam/wknd/three.jpg"));
        resolver.commit();
        set = get("op", "asset", "path", "/content/dam/wknd/bali-trip");
        assertEquals(1, ((List<?>) set.get("setMembers")).size());
        assertEquals(1L, ((Number) set.get("setMissing")).longValue());

        // a gallery also holds videos; a spin does not, and a gallery with a video cannot become one
        new AssetManagerImpl(resolver).createAsset("/content/dam/wknd/clip.mp4", new ByteArrayInputStream(new byte[] {7}), "video/mp4", true);
        assertEquals("gallery", get("op", "asset", "path", "/content/dam/wknd/bali-trip").get("setKind"));
        assertEquals(200L, post("op", "setMembers", "path", "/content/dam/wknd/bali-trip", "member", "/content/dam/wknd/two.jpg",
                "member", "/content/dam/wknd/clip.mp4").get("_status"));
        assertEquals(400L, post("op", "setMembers", "path", "/content/dam/wknd/bali-trip", "kind", "spin", "member", "/content/dam/wknd/two.jpg",
                "member", "/content/dam/wknd/clip.mp4").get("_status"));
        assertEquals(200L, post("op", "setMembers", "path", "/content/dam/wknd/bali-trip", "kind", "spin", "member", "/content/dam/wknd/two.jpg",
                "member", "/content/dam/wknd/one.jpg").get("_status"));
        assertEquals("spin", get("op", "asset", "path", "/content/dam/wknd/bali-trip").get("setKind"));
        assertEquals(400L, post("op", "createSet", "parent", "/content/dam/wknd", "title", "Turn", "kind", "wheel", "member", "/content/dam/wknd/one.jpg").get("_status"));
        assertEquals(400L, post("op", "createSet", "parent", "/content/dam/wknd", "title", "Turn", "kind", "spin", "member", "/content/dam/wknd/clip.mp4").get("_status"));

        // not pictures, nothing, another set, a second set of the same title
        assertEquals(400L, post("op", "setMembers", "path", "/content/dam/wknd/bali-trip", "member", "/content/dam/wknd/bali-trip").get("_status"));
        assertEquals(400L, post("op", "setMembers", "path", "/content/dam/wknd/bali-trip").get("_status"));
        assertEquals(404L, post("op", "createSet", "parent", "/content/dam/wknd", "title", "X", "member", "/content/dam/wknd/nope.jpg").get("_status"));
        assertEquals(400L, post("op", "setMembers", "path", "/content/dam/wknd/one.jpg", "member", "/content/dam/wknd/two.jpg").get("_status"));
        assertEquals("/content/dam/wknd/bali-trip1",
                post("op", "createSet", "parent", "/content/dam/wknd", "title", "Bali Trip", "member", "/content/dam/wknd/one.jpg").get("path"));
        // the first picture removed: the set is shown by the next one that is there
        Resource setRes = resolver.getResource("/content/dam/wknd/bali-trip");
        java.util.List<String> named = DamSets.memberPaths(setRes);
        resolver.delete(resolver.getResource("/content/dam/wknd/one.jpg/jcr:content/renditions/cq5dam.thumbnail.319.319.png"));
        resolver.delete(resolver.getResource("/content/dam/wknd/two.jpg"));
        DamSets.refreshPreview(resolver, setRes, named);
        resolver.commit();
        assertNull(resolver.getResource("/content/dam/wknd/bali-trip/jcr:content/renditions/cq5dam.thumbnail.319.319.png"),
                "the removed picture's thumbnail is gone with it (one.jpg has none here)");
        assertNotNull(resolver.getResource("/content/dam/wknd/bali-trip/jcr:content/renditions"), "renditions taken from one.jpg");
    }
}
