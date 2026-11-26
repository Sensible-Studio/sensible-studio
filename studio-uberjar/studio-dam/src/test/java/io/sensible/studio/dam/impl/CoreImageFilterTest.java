/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;

@ExtendWith(SlingContextExtension.class)
class CoreImageFilterTest {

    static final String BLOB = DeliveryTest.BLOB;
    static final String COMPONENT = "/content/wknd/us/en/jcr:content/root/image";
    static final long ASSET_MODIFIED = 1660323801921L;

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private Map<String, Object> policy;
    private CoreImageFilter filter;

    @BeforeEach
    void setUp() {
        context.create().resource("/apps/wknd/components/image", "sling:resourceSuperType", "core/wcm/components/image");
        context.create().resource("/content/dam/wknd/surf.jpg", "jcr:primaryType", "dam:Asset");
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content", "jcr:primaryType", "dam:AssetContent",
                "dam:assetState", "processed", "studio:blobId", BLOB);
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/metadata",
                "tiff:ImageWidth", 1600L, "tiff:ImageLength", 900L);
        context.create().resource(COMPONENT, "sling:resourceType", "wknd/components/image",
                "fileReference", "/content/dam/wknd/surf.jpg", "jcr:lastModified", at(1601510286000L));
        java.util.function.Function<Resource, Asset> toAsset = r -> r.getPath().startsWith("/content/dam/") ? asset("image/jpeg") : null;
        context.registerAdapter(Resource.class, Asset.class, toAsset);

        policy = new HashMap<>();
        policy.put("allowedRenditionWidths", new String[] {"100", "200", "400", "1600"});
        policy.put("jpegQuality", 60);
        filter = new CoreImageFilter(dam, false) {
            @Override
            ValueMap policy(Resource component, ResourceResolver resolver, SlingHttpServletRequest request) {
                return policy == null ? null : new ValueMapDecorator(policy);
            }
        };
    }

    private static Calendar at(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c;
    }

    /** EXIF orientation of the test asset ({@code null}: none). */
    private static String orientation;

    /** A 1600×900 asset whose web rendition is 1280 wide. */
    private static Asset asset(String mimeType) {
        Rendition web = (Rendition) Proxy.newProxyInstance(Rendition.class.getClassLoader(), new Class<?>[] {Rendition.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getName":
                    return "cq5dam.web.1280.1280.jpeg";
                case "getProperties":
                    return new ValueMapDecorator(Map.of("tiff:ImageWidth", "1280"));
                default:
                    return null;
            }
        });
        return (Asset) Proxy.newProxyInstance(Asset.class.getClassLoader(), new Class<?>[] {Asset.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getLastModified":
                    return ASSET_MODIFIED;
                case "getMimeType":
                    return mimeType;
                case "getMetadataValue":
                    return "tiff:ImageWidth".equals(a[0]) ? "1600" : "tiff:ImageLength".equals(a[0]) ? "900"
                            : "tiff:Orientation".equals(a[0]) ? orientation : null;
                case "getRendition":
                    return web;
                default:
                    return null;
            }
        });
    }

    private MockSlingHttpServletRequest request(String selectors, String ext, String suffix) {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.setResource(context.resourceResolver().getResource(COMPONENT));
        MockRequestPathInfo info = (MockRequestPathInfo) req.getRequestPathInfo();
        info.setResourcePath(COMPONENT);
        info.setSelectorString(selectors);
        info.setExtension(ext);
        info.setSuffix(suffix);
        return req;
    }

    private static String suffix(long ts) {
        return "/" + ts + "/surf.jpeg";
    }

    @Test
    void servesAllowedWidthsFromStudioDam() throws Exception {
        MockSlingHttpServletRequest req = request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED));
        MockSlingHttpServletResponse res = context.response();
        boolean[] passed = {false};
        filter.doFilter(req, res, (q, r) -> passed[0] = true);
        assertFalse(passed[0], "answered by the filter");
        assertEquals(List.of(BLOB + " 400 jpeg 60"), dam.delivered);
        assertEquals("image/jpeg", res.getContentType());
        assertEquals("inline; filename=surf.jpeg", res.getHeader("Content-Disposition"));
        assertTrue(res.getOutput().length > 0);
    }

    @Test
    void takesTheDefaultQualityWithoutAQualitySelector() {
        CoreImageFilter.Plan plan = filter.plan(request("coreimg.200", "jpeg", suffix(ASSET_MODIFIED)));
        assertEquals(200, plan.width);
        assertEquals(CoreImageFilter.DEFAULT_QUALITY, plan.quality);
    }

    @Test
    void leavesEverythingElseToTheServlet() {
        // Width or quality the policy does not allow: the servlet answers 404.
        assertNull(filter.plan(request("coreimg.60.450", "jpeg", suffix(ASSET_MODIFIED))));
        assertNull(filter.plan(request("coreimg.80.400", "jpeg", suffix(ASSET_MODIFIED))));
        // Stale timestamp: the servlet redirects.
        assertNull(filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED - 1))));
        // No width: the servlet streams the web rendition as it is.
        assertNull(filter.plan(request("coreimg", "jpeg", suffix(ASSET_MODIFIED))));
        // Format conversion and mismatched suffix.
        assertNull(filter.plan(request("coreimg.60.400", "png", "/" + ASSET_MODIFIED + "/surf.png")));
        assertNull(filter.plan(request("coreimg.60.400", "jpeg", "/" + ASSET_MODIFIED + "/surf.png")));
        // No policy: Core Components would ask the Designer.
        policy = null;
        assertNull(filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))));
    }

    @Test
    void appliesTheComponentsCropTurnAndFlipsInStudioDam() {
        org.apache.sling.api.resource.ModifiableValueMap c = context.resourceResolver().getResource(COMPONENT)
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        assertEquals("", filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))).transform);
        // Drawn on the 1280-wide web rendition, scaled ×1.25 to the 1600-wide original.
        c.put("imageCrop", "200,100,840,460/0.0");
        c.put("imageRotate", "90");
        c.put("imageFlipHorizontal", true);
        c.put("imageFlipVertical", true);
        assertEquals("c250,125,800,450-r90-h-v", filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))).transform);
        c.put("imageRotate", "-90");
        c.remove("imageFlipVertical");
        assertEquals("c250,125,800,450-r270-h", filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))).transform);
        c.put("imageCrop", "not,a,crop");
        assertEquals("r270-h", filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))).transform,
                "an unreadable crop is ignored, as by the servlet");
    }

    @Test
    void leavesOddTurnsTurnedPhotosAndUnprocessedAssetsToTheServlet() {
        org.apache.sling.api.resource.ModifiableValueMap c = context.resourceResolver().getResource(COMPONENT)
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        c.put("imageRotate", "45");
        assertNull(filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))));
        c.put("imageRotate", "90");
        orientation = "6";
        try {
            assertNull(filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))), "EXIF-turned original");
            c.remove("imageRotate");
            assertEquals("", filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))).transform,
                    "a plain resize is fine either way");
        } finally {
            orientation = null;
        }

        context.resourceResolver().getResource("/content/dam/wknd/surf.jpg/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("dam:assetState", "processing");
        assertNull(filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))));
    }

    @Test
    void usesTheComponentTimeWhenItIsNewer() {
        long later = ASSET_MODIFIED + 60_000;
        context.resourceResolver().getResource(COMPONENT).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("jcr:lastModified", at(later));
        assertNull(filter.plan(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED))));
        assertEquals(later, filter.plan(request("coreimg.60.400", "jpeg", suffix(later))).lastModified);
    }

    @Test
    void answersIfModifiedSinceAndFallsBackWhenStudioDamFails() throws Exception {
        MockSlingHttpServletRequest req = request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED));
        req.setHeader("If-Modified-Since", "Fri, 12 Aug 2022 17:03:22 GMT");
        MockSlingHttpServletResponse res = context.response();
        filter.doFilter(req, res, (q, r) -> {
            throw new AssertionError("not passed on");
        });
        assertEquals(304, res.getStatus());

        dam.failWith = new java.io.IOException("studio-dam is down");
        boolean[] passed = {false};
        filter.doFilter(request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED)), new MockSlingHttpServletResponse(),
                (q, r) -> passed[0] = true);
        assertTrue(passed[0], "the Java servlet answers when studio-dam cannot");
    }

    @Test
    void optionalWebpForBrowsersThatAcceptIt() {
        CoreImageFilter webp = new CoreImageFilter(dam, true) {
            @Override
            ValueMap policy(Resource component, ResourceResolver resolver, SlingHttpServletRequest request) {
                return new ValueMapDecorator(policy);
            }
        };
        MockSlingHttpServletRequest req = request("coreimg.60.400", "jpeg", suffix(ASSET_MODIFIED));
        assertEquals("jpeg", webp.plan(req).format);
        req.setHeader("Accept", "image/avif,image/webp,*/*");
        assertEquals("webp", webp.plan(req).format);
    }

    @Test
    void servesImportedAssetsFromTheirUploadedOriginal() throws Exception {
        // Content packages bring assets with renditions but no studio:blobId.
        context.resourceResolver().getResource("/content/dam/wknd/surf.jpg/jcr:content")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).remove("studio:blobId");
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/renditions/original", "jcr:primaryType", "nt:file");
        byte[] original = "jpeg bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/renditions/original/jcr:content",
                "jcr:primaryType", "nt:resource", "jcr:data", new java.io.ByteArrayInputStream(original));
        String blob = dam.store(original);
        for (String w : new String[] {"400", "200"}) {
            filter.doFilter(request("coreimg.60." + w, "jpeg", suffix(ASSET_MODIFIED)), new MockSlingHttpServletResponse(),
                    (q, r) -> {
                        throw new AssertionError("not passed on");
                    });
        }
        assertEquals(List.of(blob + " 400 jpeg 60", blob + " 200 jpeg 60"), dam.delivered);
    }

    @Test
    void readsPolicyWidthsLikeCoreComponents() {
        Map<String, Object> p = new HashMap<>();
        assertEquals(List.of(1280), CoreImageFilter.allowedWidths(new ValueMapDecorator(p)));
        p.put("resizeWidth", 900);
        assertEquals(List.of(900), CoreImageFilter.allowedWidths(new ValueMapDecorator(p)));
        p.put("allowedRenditionWidths", new String[] {"100", "x", "300"});
        assertEquals(List.of(100), CoreImageFilter.allowedWidths(new ValueMapDecorator(p)));
    }
}
