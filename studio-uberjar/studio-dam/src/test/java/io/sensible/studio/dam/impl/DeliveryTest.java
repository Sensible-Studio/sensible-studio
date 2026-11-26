/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class DeliveryTest {

    static final String BLOB = "613269c22d868b76da4f3926548b0cd8068cd5f307f4b9bbb75244e4c4b519b1";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private Resource asset;

    @BeforeEach
    void setUp() {
        context.create().resource("/content/dam/wknd/surf.jpg", "jcr:primaryType", "dam:Asset");
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content", "jcr:primaryType", "dam:AssetContent",
                "dam:assetState", "processed", "studio:blobId", BLOB);
        asset = context.resourceResolver().getResource("/content/dam/wknd/surf.jpg");
    }

    /** The parameters Core Components' AssetDeliveryHelper passes. */
    static Map<String, Object> ccParams(String width) {
        Map<String, Object> p = new HashMap<>();
        p.put("path", "/content/dam/wknd/surf.jpg");
        p.put("seoname", "surf");
        p.put("format", "jpeg");
        p.put("preferwebp", "true");
        p.put("quality", "60");
        if (width != null) {
            p.put("width", width);
        }
        return p;
    }

    @Test
    void buildsSignedWebpUrls() throws IOException {
        String url = new AssetDeliveryImpl(dam).getDeliveryURL(asset, ccParams("1200"));
        String sig = dam.signUrl(DeliveryUrls.canonical(BLOB, "webp", 60));
        assertEquals("/bin/studio/dam/deliver/" + sig + "/" + BLOB + "/1200/60/surf.webp", url);
    }

    @Test
    void keepsTheWidthPlaceholderOfUriTemplates() {
        // Core Components pass Integer.MAX_VALUE and then replace it with {.width} in the string.
        String url = new AssetDeliveryImpl(dam).getDeliveryURL(asset, ccParams(String.valueOf(Integer.MAX_VALUE)));
        assertTrue(url.contains("/" + Integer.MAX_VALUE + "/60/"), url);
    }

    @Test
    void fallsBackWhenStudioDamCannotServeIt() {
        AssetDeliveryImpl delivery = new AssetDeliveryImpl(dam);
        Map<String, Object> crop = ccParams("800");
        crop.put("c", "10,10,200,200");
        assertNull(delivery.getDeliveryURL(asset, crop), "crop is done by Core Components' servlet");

        Map<String, Object> gif = ccParams("800");
        gif.put("format", "gif");
        gif.remove("preferwebp");
        assertNull(delivery.getDeliveryURL(asset, gif));

        dam.available = false;
        assertNull(delivery.getDeliveryURL(asset, ccParams("800")), "service down: keep pages working");
        dam.available = true;

        context.create().resource("/content/dam/new.jpg", "jcr:primaryType", "dam:Asset");
        context.create().resource("/content/dam/new.jpg/jcr:content", "dam:assetState", "processing", "studio:blobId", BLOB);
        assertNull(delivery.getDeliveryURL(context.resourceResolver().getResource("/content/dam/new.jpg"), ccParams("800")),
                "stale blob while the new original is processed");
    }

    private MockSlingHttpServletResponse get(String url) throws IOException {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.setMethod("GET");
        ((org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo) req.getRequestPathInfo())
                .setSuffix(url.substring(DeliveryUrls.PREFIX.length()));
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new DamDeliveryServlet(dam).doGet(req, res);
        return res;
    }

    @Test
    void servletServesSignedUrlsWithSnappedWidths() throws IOException {
        AssetDeliveryImpl delivery = new AssetDeliveryImpl(dam);
        MockSlingHttpServletResponse ok = get(delivery.getDeliveryURL(asset, ccParams("1200")));
        assertEquals(200, ok.getStatus());
        assertEquals("image/webp", ok.getContentType());
        assertEquals("public, max-age=31536000, immutable", ok.getHeader("Cache-Control"));
        assertEquals(BLOB + " 1200 webp 60", dam.delivered.get(0));

        get(delivery.getDeliveryURL(asset, ccParams("1201")));
        get(delivery.getDeliveryURL(asset, ccParams("99999")));
        get(delivery.getDeliveryURL(asset, ccParams("1")));
        assertEquals(BLOB + " 1216 webp 60", dam.delivered.get(1));
        assertEquals(BLOB + " 4096 webp 60", dam.delivered.get(2));
        assertEquals(BLOB + " 16 webp 60", dam.delivered.get(3));
    }

    @Test
    void anAssetPublishedFromAnotherInstanceIsDeliveredFromItsOriginal() throws IOException {
        // the URL names the author's blob; this instance's studio-dam never saw it
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/renditions/original", "jcr:primaryType", "nt:file");
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/renditions/original/jcr:content",
                "jcr:primaryType", "nt:resource", "jcr:data", new java.io.ByteArrayInputStream("the picture".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "jcr:mimeType", "image/jpeg");
        dam.unknownBlobs.add(BLOB);
        String url = new AssetDeliveryImpl(dam).getDeliveryURL(asset, ccParams("640"));
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.setMethod("GET");
        ((org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo) req.getRequestPathInfo())
                .setSuffix(url.substring(DeliveryUrls.PREFIX.length()));
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        java.util.List<String> asked = new java.util.ArrayList<>();
        new DamDeliveryServlet(dam) {
            private static final long serialVersionUID = 1L;

            @Override
            java.util.List<String> assetsWithBlob(org.apache.sling.api.resource.ResourceResolver resolver, String blobId) {
                asked.add(blobId);
                return java.util.List.of("/content/dam/wknd/surf.jpg");
            }
        }.doGet(req, res);
        assertEquals(200, res.getStatus());
        assertEquals(java.util.List.of(BLOB), asked);
        String uploaded = dam.store("the picture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(uploaded + " 640 webp 60", dam.delivered.get(dam.delivered.size() - 1));

        // nothing names the blob (or the reader may not read it): still a 404
        dam.unknownBlobs.add(uploaded);
        assertEquals(404, get(url).getStatus());
    }

    @Test
    void servletRejectsForgedOrMalformedUrls() throws IOException {
        String good = new AssetDeliveryImpl(dam).getDeliveryURL(asset, ccParams("800"));
        // Changing the quality or format invalidates the signature.
        assertEquals(404, get(good.replace("/800/60/", "/800/100/")).getStatus());
        assertEquals(404, get(good.replace(".webp", ".png")).getStatus());
        assertEquals(404, get(DeliveryUrls.PREFIX + "/../../etc/passwd").getStatus());
        assertEquals(404, get(DeliveryUrls.PREFIX + "/" + "0".repeat(32) + "/" + BLOB + "/800/60/x.webp").getStatus());
        assertTrue(dam.delivered.isEmpty());
    }

    @Test
    void servletReports503WhenTheServiceIsDown() throws IOException {
        String url = new AssetDeliveryImpl(dam).getDeliveryURL(asset, ccParams("800"));
        dam.failWith = new IOException("connection refused");
        MockSlingHttpServletResponse res = get(url);
        assertEquals(503, res.getStatus());
        assertNotNull(res.getHeader("Retry-After"));
    }

    @Test
    void seoNamesAreSafe() {
        assertEquals("surf", DeliveryUrls.seoName("surf.jpg"));
        assertEquals("Ski-touring-Mont-Blanc", DeliveryUrls.seoName("Ski touring / Mont Blanc"));
        assertEquals("image", DeliveryUrls.seoName("../"));
        assertEquals("image", DeliveryUrls.seoName(null));
    }

    @Test
    void pngUrlsUseQualityZeroAndWork() throws IOException {
        Map<String, Object> png = ccParams("640");
        png.put("format", "png");
        png.remove("preferwebp");
        String url = new AssetDeliveryImpl(dam).getDeliveryURL(asset, png);
        assertTrue(url.endsWith("/640/0/surf.png"), url);
        assertEquals(200, get(url).getStatus());
        assertEquals(BLOB + " 640 png 0", dam.delivered.get(0));
        assertEquals(404, get(url.replace("/640/0/", "/640/80/")).getStatus());
    }
}
