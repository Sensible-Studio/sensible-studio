/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
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
class ImageEditTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private DamApiServlet servlet;
    private ResourceResolver resolver;

    private static final String ASSET = "/content/dam/wknd/surf.jpg";

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        servlet = new DamApiServlet(null, dam);
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        new AssetManagerImpl(resolver).createAsset(ASSET, new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        context.create().resource(ASSET + "/jcr:content/metadata", "tiff:ImageWidth", 1000L, "tiff:ImageLength", 500L);
        resolver.getResource(ASSET + "/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put(AssetProcessor.PN_BLOB_ID, "0".repeat(64));
        resolver.commit();
    }

    private Map<String, Object> post(String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("POST");
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doPost(req, res);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    private byte[] original() throws Exception {
        Resource r = resolver.getResource(ASSET + "/jcr:content/renditions/original/jcr:content");
        try (InputStream in = r.adaptTo(InputStream.class)) {
            return in.readAllBytes();
        }
    }

    @Test
    void editReplacesTheOriginalAndQueuesProcessing() throws Exception {
        Map<String, Object> res = post("op", "edit", "path", ASSET,
                "operations", "[{\"op\":\"rotate\",\"degrees\":-90},{\"op\":\"crop\",\"x\":0.1,\"y\":0,\"width\":0.5,\"height\":1}]");
        assertEquals(200L, res.get("_status"), String.valueOf(res));
        assertEquals(true, res.get("edited"));
        assertEquals(300L, res.get("width"));

        assertArrayEquals(dam.editedBytes, original());
        List<Map<String, Object>> ops = dam.edits.get(0);
        assertEquals(270, ops.get(0).get("degrees"), "left turn normalised");
        assertEquals("crop", ops.get(1).get("op"));
        // The original went to studio-dam first (it may never have been processed there).
        assertTrue(dam.blobs.values().stream().anyMatch(b -> b.length == 3));

        ValueMap content = resolver.getResource(ASSET + "/jcr:content").getValueMap();
        assertEquals("processing", content.get(AssetProcessor.PN_ASSET_STATE, String.class));
        assertNull(content.get(AssetProcessor.PN_BLOB_ID, String.class), "stale blob id dropped");
    }

    @Test
    void imageMapIsStoredInCoreComponentsNotation() throws Exception {
        String areas = "[{\"shape\":\"rect\",\"coords\":[0.5,0.5,0.1,0.2],\"href\":\"/content/wknd/us/en.html\",\"target\":\"_blank\",\"alt\":\"Home\"},"
                + "{\"shape\":\"circle\",\"coords\":[0.5,0.5,0.25],\"href\":\"https://wknd.site\",\"target\":\"\",\"alt\":\"Say \\\"hi\\\" | [x]\"}]";
        Map<String, Object> res = post("op", "edit", "path", ASSET, "imageMap", areas);
        assertEquals(200L, res.get("_status"), String.valueOf(res));
        assertEquals(false, res.get("edited"));
        String map = resolver.getResource(ASSET + "/jcr:content/metadata").getValueMap().get("imageMap", String.class);
        assertEquals("[rect(100,100,500,250)\"/content/wknd/us/en.html\"|\"_blank\"|\"Home\"|(0.1,0.2,0.5,0.5)]"
                + "[circle(500,250,250)\"https://wknd.site\"|\"\"|\"Say hi x\"|(0.5,0.5,0.25)]", map);
        assertTrue(dam.edits.isEmpty(), "a map alone does not touch the image");

        assertEquals(200L, post("op", "edit", "path", ASSET, "imageMap", "[]").get("_status"));
        assertNull(resolver.getResource(ASSET + "/jcr:content/metadata").getValueMap().get("imageMap", String.class));
    }

    @Test
    void polygonsAreStoredWithEveryCorner() throws Exception {
        String areas = "[{\"shape\":\"poly\",\"coords\":[0.1,0.2,0.9,0.2,0.5,1.4],\"href\":\"/content/wknd\",\"target\":\"\",\"alt\":\"Tent\"}]";
        assertEquals(200L, post("op", "edit", "path", ASSET, "imageMap", areas).get("_status"));
        assertEquals("[poly(100,100,900,100,500,500)\"/content/wknd\"|\"\"|\"Tent\"|(0.1,0.2,0.9,0.2,0.5,1.0)]",
                resolver.getResource(ASSET + "/jcr:content/metadata").getValueMap().get("imageMap", String.class));
        // two corners, or an odd number of coordinates, are no polygon
        assertEquals(400L, post("op", "edit", "path", ASSET, "imageMap",
                "[{\"shape\":\"poly\",\"coords\":[0.1,0.2,0.9,0.2],\"href\":\"\",\"target\":\"\",\"alt\":\"\"}]").get("_status"));
        assertEquals(400L, post("op", "edit", "path", ASSET, "imageMap",
                "[{\"shape\":\"poly\",\"coords\":[0.1,0.2,0.9,0.2,0.5,0.5,0.3],\"href\":\"\",\"target\":\"\",\"alt\":\"\"}]").get("_status"));
    }

    @Test
    void aMapStoredBeforeItMovedIsReadAndThenMoved() throws Exception {
        String old = "[rect(0,0,10,10)\"/content/a.html\"|\"\"|\"A\"|(0,0,0.1,0.1)]";
        resolver.getResource(ASSET + "/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("imageMap", old);
        resolver.commit();
        assertEquals(old, DamApiServlet.imageMap(resolver.getResource(ASSET)));
        post("op", "edit", "path", ASSET, "imageMap", "[{\"shape\":\"rect\",\"coords\":[0,0,1,1],\"href\":\"\",\"target\":\"\",\"alt\":\"\"}]");
        assertNull(resolver.getResource(ASSET + "/jcr:content").getValueMap().get("imageMap", String.class), "the old place is cleared");
        assertTrue(DamApiServlet.imageMap(resolver.getResource(ASSET)).startsWith("[rect(0,0,"));
    }

    @Test
    void mapAfterAnEditUsesTheNewSize() throws Exception {
        post("op", "edit", "path", ASSET, "operations", "[{\"op\":\"rotate\",\"degrees\":90}]",
                "imageMap", "[{\"shape\":\"rect\",\"coords\":[0,0,1,1],\"href\":\"\",\"target\":\"\",\"alt\":\"\"}]");
        String map = resolver.getResource(ASSET + "/jcr:content/metadata").getValueMap().get("imageMap", String.class);
        assertTrue(map.startsWith("[rect(0,0,300,200)"), map);
    }

    @Test
    void refusesBadInput() throws Exception {
        assertEquals(400L, post("op", "edit", "path", ASSET).get("_status"), "nothing to change");
        assertEquals(400L, post("op", "edit", "path", ASSET, "operations", "[{\"op\":\"rotate\",\"degrees\":45}]").get("_status"));
        assertEquals(400L, post("op", "edit", "path", ASSET, "operations", "[{\"op\":\"crop\",\"x\":0.8,\"y\":0,\"width\":0.5,\"height\":1}]").get("_status"));
        assertEquals(400L, post("op", "edit", "path", ASSET, "operations", "{\"op\":\"flip\"}").get("_status"));
        assertEquals(400L, post("op", "edit", "path", ASSET, "imageMap",
                "[{\"shape\":\"rect\",\"coords\":[0,0,1,1],\"href\":\"javascript:alert(1)\",\"target\":\"\",\"alt\":\"\"}]").get("_status"));
        assertEquals(400L, post("op", "edit", "path", ASSET, "imageMap",
                "[{\"shape\":\"poly\",\"coords\":[0,0,1,1],\"href\":\"\",\"target\":\"\",\"alt\":\"\"}]").get("_status"));
        assertEquals(400L, post("op", "edit", "path", ASSET, "imageMap",
                "[{\"shape\":\"rect\",\"coords\":[0,0,1,1],\"href\":\"\",\"target\":\"_evil\",\"alt\":\"\"}]").get("_status"));
        assertTrue(dam.edits.isEmpty());
        assertArrayEquals(new byte[] {1, 2, 3}, original());
    }

    @Test
    void serviceDownLeavesTheAssetAlone() throws Exception {
        dam.available = false;
        Map<String, Object> res = post("op", "edit", "path", ASSET, "operations", "[{\"op\":\"flip\",\"axis\":\"vertical\"}]");
        assertEquals(503L, res.get("_status"));
        assertArrayEquals(new byte[] {1, 2, 3}, original());
        dam.available = true;
        dam.failWith = new java.io.IOException("connection refused");
        assertEquals(503L, post("op", "edit", "path", ASSET, "operations", "[{\"op\":\"flip\",\"axis\":\"vertical\"}]").get("_status"));
        assertFalse(resolver.hasChanges());
    }

    @Test
    void hrefRules() {
        assertTrue(ImageEdit.safeHref(""));
        assertTrue(ImageEdit.safeHref("/content/wknd/us/en.html"));
        assertTrue(ImageEdit.safeHref("#top"));
        assertTrue(ImageEdit.safeHref("page.html"));
        assertTrue(ImageEdit.safeHref("HTTPS://example.com"));
        assertTrue(ImageEdit.safeHref("mailto:a@b.c"));
        assertFalse(ImageEdit.safeHref("javascript:alert(1)"));
        assertFalse(ImageEdit.safeHref(" data:text/html,x".trim()));
        assertFalse(ImageEdit.safeHref("//evil.example"));
        assertThrows(ImageEdit.InvalidEdit.class, () -> ImageEdit.operations("[" + "{\"op\":\"rotate\",\"degrees\":90},".repeat(64) + "{\"op\":\"rotate\",\"degrees\":90}]"));
    }
}
