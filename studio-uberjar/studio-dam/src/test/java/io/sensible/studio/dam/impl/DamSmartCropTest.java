/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
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
class DamSmartCropTest {

    private static final String SURF = "/content/dam/wknd/en/surf.jpg";
    private static final String BLOB = "613269c22d868b76da4f3926548b0cd8068cd5f307f4b9bbb75244e4c4b519b1";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private final List<String> jobs = new ArrayList<>();
    private ResourceResolver resolver;
    private DamApiServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/wknd/en", "jcr:primaryType", "sling:Folder");
        new AssetManagerImpl(resolver).createAsset(SURF, new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        ModifiableValueMap c = resolver.getResource(SURF + "/jcr:content").adaptTo(ModifiableValueMap.class);
        c.put(AssetProcessor.PN_ASSET_STATE, AssetProcessor.STATE_PROCESSED);
        c.put(AssetProcessor.PN_BLOB_ID, BLOB);
        ModifiableValueMap md = resolver.getResource(SURF + "/jcr:content/metadata").adaptTo(ModifiableValueMap.class);
        md.put("tiff:ImageWidth", 1000L);
        md.put("tiff:ImageLength", 500L);
        resolver.commit();
        JobManager jm = (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class},
                (p, m, a) -> {
                    if ("addJob".equals(m.getName())) {
                        jobs.add(a[0] + " " + ((Map<?, ?>) a[1]).get(AssetProcessingJob.PROP_PATH));
                    }
                    return null;
                });
        servlet = new DamApiServlet(jm, dam);
    }

    private Map<String, Object> call(String user, String method, String... params) throws Exception {
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

    @Test
    @SuppressWarnings("unchecked")
    void profilesAreAssignedToFoldersAndCropsFollow() throws Exception {
        assertEquals(403L, call("ana", "POST", "op", "saveImageProfile", "name", "web", "ratios", "[]").get("_status"));
        assertEquals(400L, call("admin", "POST", "op", "saveImageProfile", "name", "web", "ratios", "[]").get("_status"));
        Map<String, Object> saved = call("admin", "POST", "op", "saveImageProfile", "name", "web", "title", "Web",
                "ratios", "[{\"name\":\"Square\",\"width\":1,\"height\":1},{\"name\":\"Banner\",\"width\":4,\"height\":1}]");
        assertEquals(200L, saved.get("_status"));
        assertEquals(2, ((List<?>) saved.get("ratios")).size());

        // assigning queues the folder's images
        Map<String, Object> assigned = call("admin", "POST", "op", "folderImageProfile", "path", "/content/dam/wknd",
                "profile", DamSmartCrop.ROOT + "/web");
        assertEquals(200L, assigned.get("_status"));
        assertEquals(1L, assigned.get("queued"));
        assertEquals(List.of(DamSmartCropJob.TOPIC + " " + SURF), jobs);
        Map<String, Object> listed = call("ana", "GET", "op", "imageProfiles", "path", "/content/dam/wknd/en");
        assertEquals(DamSmartCrop.ROOT + "/web", listed.get("effective"), "inherited");

        // the job computes and stores fractions of the image
        Resource asset = resolver.getResource(SURF);
        assertTrue(DamSmartCrop.compute(resolver, dam, asset, false));
        resolver.commit();
        assertEquals(List.of(BLOB + " Square,Banner"), dam.cropped);
        List<Map<String, Object>> crops = DamSmartCrop.crops(resolver.getResource(SURF));
        assertEquals(2, crops.size());
        Map<String, Object> square = crops.get(0);
        assertEquals("Square", square.get("name"));
        assertEquals(0.25, square.get("left"));
        assertEquals(0.5, square.get("width"));
        assertEquals(1.0, square.get("height"));
        assertEquals("/is/image/wknd/en/surf:Square", square.get("url"));
        assertEquals("c250,0,500,500", DamSmartCrop.cropStep(asset, "Square"));
        assertEquals("c0,125,1000,250", DamSmartCrop.cropStep(asset, "Banner"));
        assertNull(DamSmartCrop.cropStep(asset, "nope"));

        // the author moves the square to the left edge: it stays manual on the next compute
        Map<String, Object> moved = call("admin", "POST", "op", "smartCrop", "path", SURF, "name", "Square", "left", "0", "top", "0", "width", "0.5");
        assertEquals(200L, moved.get("_status"));
        assertEquals(true, moved.get("manual"));
        assertEquals(0.0, moved.get("left"));
        assertEquals("c0,0,500,500", DamSmartCrop.cropStep(resolver.getResource(SURF), "Square"));
        // too wide for the ratio: clipped to what fits
        Map<String, Object> clipped = call("admin", "POST", "op", "smartCrop", "path", SURF, "name", "Banner", "left", "0.9", "top", "0.9", "width", "1");
        assertEquals(0.0, clipped.get("left"));
        assertEquals(0.5, clipped.get("top"), "4:1 of 1000 wide is 250 high: half the image");
        assertEquals(400L, call("admin", "POST", "op", "smartCrop", "path", SURF, "name", "Banner", "left", "x", "top", "0", "width", "1").get("_status"));
        assertEquals(404L, call("admin", "POST", "op", "smartCrop", "path", SURF, "name", "nope", "left", "0", "top", "0", "width", "1").get("_status"));

        dam.cropped.clear();
        assertTrue(DamSmartCrop.compute(resolver, dam, resolver.getResource(SURF), false));
        assertTrue(dam.cropped.isEmpty(), "both manual: nothing asked");
        assertEquals("c0,0,500,500", DamSmartCrop.cropStep(resolver.getResource(SURF), "Square"));
        // a reset asks again and the computed crops win
        Map<String, Object> reset = call("admin", "POST", "op", "smartCropReset", "path", SURF);
        assertEquals(200L, reset.get("_status"));
        assertEquals(false, ((List<Map<String, Object>>) reset.get("smartCrops")).get(0).get("manual"));
        assertEquals("c250,0,500,500", DamSmartCrop.cropStep(resolver.getResource(SURF), "Square"));

        // the profile loses Banner: its crop goes; no profile: all go
        call("admin", "POST", "op", "saveImageProfile", "name", "web", "ratios", "[{\"name\":\"Square\",\"width\":1,\"height\":1}]");
        DamSmartCrop.compute(resolver, dam, resolver.getResource(SURF), false);
        assertEquals(1, DamSmartCrop.crops(resolver.getResource(SURF)).size());
        call("admin", "POST", "op", "folderImageProfile", "path", "/content/dam/wknd", "profile", "");
        assertTrue(DamSmartCrop.compute(resolver, dam, resolver.getResource(SURF), false));
        assertTrue(DamSmartCrop.crops(resolver.getResource(SURF)).isEmpty());
        assertFalse(DamSmartCrop.compute(resolver, dam, resolver.getResource(SURF), false), "nothing to do");
        assertEquals(200L, call("admin", "POST", "op", "deleteImageProfile", "name", "web").get("_status"));
    }

    @Test
    void theUrlServesACrop() throws Exception {
        call("admin", "POST", "op", "saveImageProfile", "name", "web", "ratios", "[{\"name\":\"Square\",\"width\":1,\"height\":1}]");
        call("admin", "POST", "op", "folderImageProfile", "path", "/content/dam/wknd/en", "profile", DamSmartCrop.ROOT + "/web");
        DamSmartCrop.compute(resolver, dam, resolver.getResource(SURF), false);
        resolver.commit();
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("GET");
        req.setResource(new org.apache.sling.api.resource.SyntheticResource(resolver, DynamicMedia.PREFIX + "/wknd/en/surf:Square",
                DynamicMediaResourceProvider.TYPE));
        req.addRequestParameter("wid", "200");
        req.addRequestParameter("rotate", "90");
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new DynamicMediaServlet(dam).doGet(req, res);
        assertEquals(200, res.getStatus());
        assertEquals(BLOB + " 200x4096 jpeg 80 c250,0,500,500-r90", dam.delivered.get(0), "the crop comes first");
        req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("GET");
        req.setResource(new org.apache.sling.api.resource.SyntheticResource(resolver, DynamicMedia.PREFIX + "/wknd/en/surf:Nope",
                DynamicMediaResourceProvider.TYPE));
        res = new MockSlingHttpServletResponse();
        new DynamicMediaServlet(dam).doGet(req, res);
        assertEquals(404, res.getStatus());
    }
}
