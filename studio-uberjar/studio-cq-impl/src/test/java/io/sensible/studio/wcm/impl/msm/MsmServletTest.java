/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.BLUEPRINT;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.CA;
import static io.sensible.studio.wcm.impl.msm.MsmTestSupport.US;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class MsmServletTest {

    private final SlingContext context = MsmTestSupport.newContext();
    private MsmServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        MsmTestSupport.setUp(context);
        servlet = context.registerInjectActivateService(new MsmServlet());
    }

    private MockSlingHttpServletResponse call(String method, Object... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.setMethod(method);
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            p.put((String) params[i], params[i + 1]);
        }
        req.setParameterMap(p);
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.service(req, res);
        return res;
    }

    @Test
    void statusOfALiveCopyPageAndItsBlueprint() throws Exception {
        String body = call("GET", "op", "status", "path", US + "/adventures").getOutputAsString();
        assertTrue(body.contains("\"inLiveCopy\":true"), body);
        assertTrue(body.contains("\"sourcePath\":\"" + BLUEPRINT + "/adventures\""), body);
        assertTrue(body.contains("\"status\":\"synced\""), body);
        assertTrue(body.contains("\"pageExclusions\":[\"jcr:.*\""), body);
        assertTrue(body.contains("\"title\":\"Standard rollout\""), body);

        String bp = call("GET", "op", "status", "path", BLUEPRINT).getOutputAsString();
        assertTrue(bp.contains("\"isSource\":true"), bp);
        assertTrue(bp.contains("\"targetPath\":\"" + US + "\"") && bp.contains("\"targetPath\":\"" + CA + "\""), bp);

        String overview = call("GET", "op", "overview", "path", BLUEPRINT).getOutputAsString();
        assertTrue(overview.contains("\"targetPath\":\"" + US + "/adventures\""), overview);
        assertEquals(404, call("GET", "op", "status", "path", "/content/nope").getStatus());
        assertTrue(call("GET", "op", "configs").getOutputAsString().contains("pushonmodify"));
        assertTrue(call("GET", "op", "liveCopies").getOutputAsString().contains(US));
    }

    @Test
    void componentStateForTheEditor() {
        String comp = US + "/jcr:content/root/container";
        assertEquals(MsmState.INHERITED, MsmState.of(context.resourceResolver().getResource(comp)));
        assertNull(MsmState.of(context.resourceResolver().getResource(BLUEPRINT + "/jcr:content/root")));
        assertTrue(MsmState.isLiveCopyRoot(context.resourceResolver().getResource(US)));
        assertFalse(MsmState.isLiveCopyRoot(context.resourceResolver().getResource(US + "/adventures")));
        String teaser = "/content/experience-fragments/wknd/us/en/featured/camping-western-australia/master/jcr:content/root/container/teaser";
        assertEquals(MsmState.CANCELLED, MsmState.of(context.resourceResolver().getResource(teaser)));
    }

    @Test
    void cancelReenableAndPropertiesThroughPosts() throws Exception {
        String comp = US + "/jcr:content/root/container";
        MockSlingHttpServletResponse r = call("POST", "op", "cancel", "path", comp);
        assertEquals(200, r.getStatus(), r.getOutputAsString());
        assertEquals(MsmState.CANCELLED, MsmState.of(context.resourceResolver().getResource(comp)));
        r = call("POST", "op", "reenable", "path", comp);
        assertEquals(200, r.getStatus(), r.getOutputAsString());
        assertTrue(r.getOutputAsString().contains("\"result\""));
        assertEquals(MsmState.INHERITED, MsmState.of(context.resourceResolver().getResource(comp)));

        r = call("POST", "op", "cancelProperty", "path", US + "/jcr:content", "name", new String[] {"./navTitle", "/cq:featuredimage"});
        assertTrue(r.getOutputAsString().contains("\"cancelledProperties\":[\"navTitle\",\"cq:featuredimage\"]"), r.getOutputAsString());
        r = call("POST", "op", "reenableProperty", "path", US + "/jcr:content", "name", "navTitle");
        assertTrue(r.getOutputAsString().contains("\"cancelledProperties\":[\"cq:featuredimage\"]"), r.getOutputAsString());

        r = call("POST", "op", "suspend", "path", US + "/adventures");
        assertTrue(r.getOutputAsString().contains("\"status\":\"suspended\""), r.getOutputAsString());
        r = call("POST", "op", "resume", "path", US + "/adventures");
        assertTrue(r.getOutputAsString().contains("\"status\":\"synced\""), r.getOutputAsString());

        assertEquals(409, call("POST", "op", "cancel", "path", BLUEPRINT).getStatus());
    }

    @Test
    void rolloutAndCreateThroughPosts() throws Exception {
        MockSlingHttpServletResponse r = call("POST", "op", "rollout", "path", BLUEPRINT + "/adventures", "deep", "false",
                "target", US + "/adventures");
        assertEquals(200, r.getStatus(), r.getOutputAsString());
        assertTrue(r.getOutputAsString().contains("\"updated\":[\"" + US + "/adventures\"]"), r.getOutputAsString());

        context.create().resource("/content/wknd/gb", "jcr:primaryType", "cq:Page");
        r = call("POST", "op", "create", "source", BLUEPRINT, "parent", "/content/wknd/gb", "name", "en", "title", "UK",
                "deep", "false");
        assertEquals(200, r.getStatus(), r.getOutputAsString());
        assertTrue(r.getOutputAsString().contains("\"path\":\"/content/wknd/gb/en\""));
        assertEquals(MsmState.INHERITED, MsmState.of(context.resourceResolver().getResource("/content/wknd/gb/en")));

        r = call("POST", "op", "detach", "path", "/content/wknd/gb/en");
        assertEquals(200, r.getStatus(), r.getOutputAsString());
        assertTrue(r.getOutputAsString().contains("\"inLiveCopy\":false"), r.getOutputAsString());
    }
}
