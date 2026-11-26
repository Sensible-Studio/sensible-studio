/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
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
class DynamicMediaTest {

    private static final String SURF = "/content/dam/wknd/en/surf.jpg";
    private static final String BLOB = "613269c22d868b76da4f3926548b0cd8068cd5f307f4b9bbb75244e4c4b519b1";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/wknd/en", "jcr:primaryType", "sling:Folder");
        new AssetManagerImpl(resolver).createAsset(SURF, new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        new AssetManagerImpl(resolver).createAsset("/content/dam/wknd/en/notes.txt", new ByteArrayInputStream(new byte[] {1}), "text/plain", true);
        org.apache.sling.api.resource.ModifiableValueMap c = resolver.getResource(SURF + "/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        c.put(AssetProcessor.PN_ASSET_STATE, AssetProcessor.STATE_PROCESSED);
        c.put(AssetProcessor.PN_BLOB_ID, BLOB);
        org.apache.sling.api.resource.ModifiableValueMap md = resolver.getResource(SURF + "/jcr:content/metadata")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        md.put("tiff:ImageWidth", 2000L);
        md.put("tiff:ImageLength", 1000L);
        resolver.commit();
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private DynamicMedia.Plan plan(String... kv) throws DynamicMedia.BadParameter {
        return DynamicMedia.plan(resolver, params(kv), 2000, 1000);
    }

    @Test
    void parametersBecomeABoxAndATransform() throws Exception {
        DynamicMedia.Plan p = plan("wid", "800");
        assertEquals("800x4096.jpeg?q=80&t=", p.key(), "width only: height free, JPEG 80");
        assertEquals("4096x600.webp?q=70&t=", plan("hei", "600", "fmt", "webp", "qlt", "70").key());
        assertEquals("4096x4096.png?q=0&t=", plan("fmt", "png-alpha", "qlt", "90").key(), "PNG has no quality");
        assertEquals("300x300.jpeg?q=80&t=fcrop", plan("wid", "300", "hei", "300", "fit", "crop").key());
        assertEquals("300x300.jpeg?q=80&t=fstretch-u", plan("wid", "300", "hei", "300", "fit", "stretch,1").key());
        assertEquals("800x4096.jpeg?q=80&t=", plan("wid", "800", "hei", "600", "fit", "hfit").key());
        assertEquals("500x250.jpeg?q=80&t=", plan("scl", "4").key(), "scl divides the source size");
        assertEquals("4096x4096.jpeg?q=80&t=c10,20,300,200-r90-h-v", plan("crop", "10,20,300,200", "rotate", "90", "flip", "hv").key());
        assertEquals("4096x4096.jpeg?q=80&t=c500,250,1000,500", plan("cropN", "0.25,0.25,0.5,0.5").key(), "fractions of 2000x1000");
        assertEquals("4096x4096.jpeg?q=80&t=s100,10,0", plan("op_sharpen", "1").key());
        assertEquals("4096x4096.jpeg?q=80&t=s150,25,3-b20-ln10-k15-gffffff",
                plan("op_usm", "1.5,2.5,3", "op_blur", "2", "op_brightness", "-10", "op_contrast", "15", "bgc", "FFFFFF").key());
        assertEquals("4096x4096.jpeg?q=80&t=", plan("op_brightness", "0", "unknown", "x").key(), "zero and unknown parameters do nothing");
        assertEquals("4096x4096.jpeg?q=80&t=an30-m-p-o102030,40-db,000000,0,70-w50-e24-x4,ff0000-g#ffffff".replace("g#", "g"),
                plan("op_saturation", "-30", "op_grayscale", "1", "op_sepia", "true", "op_overlay", "#102030,40", "op_gradient", "B,000000,0,70",
                        "op_vignette", "50", "op_radius", "24", "op_border", "4,FF0000", "bgc", "#ffffff").key(),
                "the looks, in a fixed order, the background last");
        assertEquals("4096x4096.jpeg?q=80&t=", plan("op_overlay", "000000,0", "op_gradient", "b,000000,0,0", "op_vignette", "0", "op_radius", "0",
                "op_border", "0,000000", "op_saturation", "0").key(), "zero looks do nothing");
        for (String[] bad : new String[][] {{"wid", "5000"}, {"fmt", "gif"}, {"qlt", "0"}, {"fit", "bogus"}, {"fit", "crop"},
                {"rotate", "45"}, {"flip", "x"}, {"crop", "1,2,3"}, {"cropN", "2,0,1,1"}, {"op_usm", "9,1,1"}, {"bgc", "red"},
                {"op_contrast", "200"}, {"op_saturation", "101"}, {"op_overlay", "000000"}, {"op_overlay", "000000,101"},
                {"op_gradient", "x,000000,0,70"}, {"op_gradient", "b,000000,0"}, {"op_vignette", "-1"}, {"op_radius", "5000"},
                {"op_border", "4,red"}, {"$nope$", ""}}) {
            assertThrows(DynamicMedia.BadParameter.class, () -> plan(bad), bad[0] + "=" + bad[1]);
        }
    }

    @Test
    void everyStepThePlanWritesPassesTheClientWhitelist() throws Exception {
        for (String t : new String[] {"c10,20,300,200-r90-h-v", "s150,25,3-b20-ln10-k15-gffffff", "an30-m-p-o102030,40-db,000000,0,70-w50-e24-x4,ff0000-gffffff",
                "fcrop-u"}) {
            assertTrue(HttpDamClient.validTransform(t), t);
        }
        for (String t : new String[] {"q1", "d,000000,0,70", "oGGGGGG,1", "e", "x4", "m-m-m-m-m-m-m-m-m-m-m-m-m-m-m-m-m-m-m-m-m"}) {
            assertFalse(HttpDamClient.validTransform(t), t);
        }
    }

    @Test
    void presetsExpandFirstAndExplicitParametersWin() throws Exception {
        Map<String, Object> saved = DynamicMedia.save(resolver, "banner", "Banner", "Wide hero", params("wid", "1600", "hei", "600", "fmt", "webp",
                "qlt", "75", "fit", "crop", "op_sharpen", "1"));
        assertEquals("wid=1600&hei=600&fmt=webp&qlt=75&fit=crop&op_sharpen=1", saved.get("query"));
        assertEquals("1600x600.webp?q=75&t=fcrop-s100,10,0", plan("$banner$", "").key());
        assertEquals("1600x600.jpeg?q=75&t=fcrop-s100,10,0", plan("$banner$", "", "fmt", "jpeg").key(), "explicit wins");
        assertEquals("1600x600.jpeg?q=75&t=fcrop-s100,10,0", plan("fmt", "jpeg", "$banner$", "").key(), "wherever it stands");
        assertEquals(1, DynamicMedia.presets(resolver).size());
        assertThrows(DamApiServlet.ApiException.class, () -> DynamicMedia.save(resolver, "bad name", "x", null, params("wid", "1")));
        assertThrows(DamApiServlet.ApiException.class, () -> DynamicMedia.save(resolver, "empty", "x", null, params()));
        assertThrows(DamApiServlet.ApiException.class, () -> DynamicMedia.save(resolver, "huge", "x", null, params("wid", "99999")));
        DynamicMedia.delete(resolver, "banner");
        assertTrue(DynamicMedia.presets(resolver).isEmpty());
        assertThrows(DamApiServlet.ApiException.class, () -> DynamicMedia.delete(resolver, "banner"));
    }

    @Test
    void publicPathsDropTheExtensionAndResolveBack() {
        assertEquals("/is/image/wknd/en/surf", DynamicMedia.publicPath(SURF));
        assertEquals("/is/image/wknd/en/a.b", DynamicMedia.publicPath("/content/dam/wknd/en/a.b.jpg"));
        assertNull(DynamicMedia.publicPath("/content/wknd/x.jpg"));
        assertEquals(SURF, DynamicMedia.resolve(resolver, "/wknd/en/surf").getPath());
        assertEquals(SURF, DynamicMedia.resolve(resolver, "/wknd/en/surf.jpg").getPath());
        assertNull(DynamicMedia.resolve(resolver, "/wknd/en/nope"));
        assertNull(DynamicMedia.resolve(resolver, "/wknd/en"), "a folder is not an image");
        assertNull(DynamicMedia.resolve(resolver, "/../wknd/en/surf"));
    }

    private MockSlingHttpServletResponse get(String suffix, String query, String ifNoneMatch) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("GET");
        req.setResource(new org.apache.sling.api.resource.SyntheticResource(resolver, DynamicMedia.PREFIX + suffix, DynamicMediaResourceProvider.TYPE));
        for (String kv : query.split("&")) {
            if (!kv.isEmpty()) {
                int eq = kv.indexOf('=');
                req.addRequestParameter(eq < 0 ? kv : kv.substring(0, eq), eq < 0 ? "" : kv.substring(eq + 1));
            }
        }
        if (ifNoneMatch != null) {
            req.addHeader("If-None-Match", ifNoneMatch);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new DynamicMediaServlet(dam).doGet(req, res);
        return res;
    }

    @Test
    void servletDeliversWithCachingHeaders() throws Exception {
        DynamicMedia.save(resolver, "thumb", "Thumb", null, params("wid", "200", "hei", "200", "fit", "crop", "fmt", "webp"));
        MockSlingHttpServletResponse ok = get("/wknd/en/surf", "$thumb$&qlt=50", null);
        assertEquals(200, ok.getStatus());
        assertEquals("image/webp", ok.getContentType());
        assertEquals(BLOB + " 200x200 webp 50 fcrop", dam.delivered.get(0));
        assertEquals("public, max-age=36000", ok.getHeader("Cache-Control"));
        String etag = ok.getHeader("ETag");
        assertNotNull(etag);
        assertNotNull(ok.getHeader("Last-Modified"));
        assertEquals(304, get("/wknd/en/surf", "$thumb$&qlt=50", etag).getStatus());
        assertTrue(!etag.equals(get("/wknd/en/surf", "$thumb$&qlt=60", null).getHeader("ETag")), "other parameters, other ETag");

        assertEquals(404, get("/wknd/en/nope", "", null).getStatus());
        assertEquals(404, get("/wknd/en/notes", "", null).getStatus(), "not an image");
        assertEquals(400, get("/wknd/en/surf", "wid=abc", null).getStatus());
        assertEquals(400, get("/wknd/en/surf", "$nope$", null).getStatus());
        dam.available = false;
        assertEquals(503, get("/wknd/en/surf", "wid=10", null).getStatus());
    }

    @Test
    void anAssetProcessedOnAnotherInstanceIsDeliveredFromItsOriginal() throws Exception {
        // published from the author: the blob id it carries is one of the author's studio-dam
        dam.unknownBlobs.add(BLOB);
        MockSlingHttpServletResponse ok = get("/wknd/en/surf", "wid=100", null);
        assertEquals(200, ok.getStatus());
        assertEquals(1, dam.blobs.size(), "the original went up to this instance's studio-dam");
        assertTrue(!dam.delivered.get(0).startsWith(BLOB + " "), dam.delivered.toString());
        assertEquals(200, get("/wknd/en/surf", "wid=120", null).getStatus());
        assertEquals(1, dam.blobs.size(), "once");
    }

    @Test
    void importedAssetsAreUploadedOnFirstUse() throws Exception {
        org.apache.sling.api.resource.ModifiableValueMap c = resolver.getResource(SURF + "/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        c.remove(AssetProcessor.PN_BLOB_ID);
        c.remove(AssetProcessor.PN_ASSET_STATE);
        MockSlingHttpServletResponse ok = get("/wknd/en/surf", "wid=100", null);
        assertEquals(200, ok.getStatus());
        assertEquals(1, dam.blobs.size(), "the original went up once");
        get("/wknd/en/surf", "wid=120", null);
        assertEquals(1, dam.blobs.size(), "and is remembered");
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiListsPresetsWithUrlsAndOnlyAdminChangesThem() throws Exception {
        DamApiServlet servlet = new DamApiServlet(null, dam);
        Map<String, Object> created = call(servlet, "admin", "POST", "op", "saveImagePreset", "name", "hero", "title", "Hero", "wid", "1600", "fmt", "webp");
        assertEquals(200L, created.get("_status"));
        assertEquals(403L, call(servlet, "ana", "POST", "op", "saveImagePreset", "name", "x", "wid", "10").get("_status"));
        Map<String, Object> listed = call(servlet, "ana", "GET", "op", "imagePresets", "path", SURF);
        assertEquals("/is/image/wknd/en/surf", listed.get("url"));
        List<Map<String, Object>> presets = (List<Map<String, Object>>) listed.get("presets");
        assertEquals("/is/image/wknd/en/surf?$hero$", presets.get(0).get("url"));
        assertEquals(false, listed.get("admin"));
        assertEquals(403L, call(servlet, "ana", "POST", "op", "deleteImagePreset", "name", "hero").get("_status"));
        assertEquals(200L, call(servlet, "admin", "POST", "op", "deleteImagePreset", "name", "hero").get("_status"));
        assertTrue(((List<?>) call(servlet, "admin", "GET", "op", "imagePresets", "path", "/content/dam").get("presets")).isEmpty());
    }

    private Map<String, Object> call(DamApiServlet servlet, String user, String method, String... params) throws Exception {
        ResourceResolver as = new ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return user;
            }
        };
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(as, context.bundleContext());
        req.setMethod(method);
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        if ("POST".equals(method)) {
            servlet.doPost(req, res);
        } else {
            servlet.doGet(req, res);
        }
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }
}
