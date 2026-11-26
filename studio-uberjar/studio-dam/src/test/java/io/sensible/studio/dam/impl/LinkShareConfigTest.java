/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;
import io.sensible.studio.wcm.impl.WcmAdapterFactory;

@ExtendWith(SlingContextExtension.class)
/** Assets Configurations › Link Share: what a link may give, for how long, and who may make one. */
class LinkShareConfigTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private ResourceResolver resolver;
    private LinkShareServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/private", "jcr:primaryType", "sling:Folder");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        am.createAsset("/content/dam/wknd/surf.jpg", new ByteArrayInputStream("SURF".getBytes(StandardCharsets.UTF_8)), "image/jpeg", true);
        am.createAsset("/content/dam/private/secret.jpg", new ByteArrayInputStream("SECRET".getBytes(StandardCharsets.UTF_8)), "image/jpeg", true);
        resolver.commit();
        servlet = new LinkShareServlet(context.getService(ResourceResolverFactory.class));
    }

    private MockSlingHttpServletResponse call(String method, String resourcePath, String ext, String... params) throws Exception {
        ResourceResolver author = new org.apache.sling.api.wrappers.ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return "author";
            }
        };
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(author, context.bundleContext());
        req.setMethod(method);
        req.setResource(context.create().resource(resourcePath + "-" + System.nanoTime()));
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) req.getRequestPathInfo()).setResourcePath(resourcePath);
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) req.getRequestPathInfo()).setExtension(ext);
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
        return res;
    }

    private void configure(String... props) {
        Object[] p = new Object[props.length + 2];
        p[0] = "jcr:primaryType";
        p[1] = "nt:unstructured";
        for (int i = 0; i < props.length; i += 2) {
            p[i + 2] = props[i];
            String v = props[i + 1];
            p[i + 3] = v.matches("true|false") ? (Object) Boolean.valueOf(v) : v.matches("\\d+") ? (Object) Long.valueOf(v) : v.startsWith("[") ? v.substring(1, v.length() - 1).split(",") : v;
        }
        context.create().resource(DamAssetsConfig.ROOT + "/linkshare", p);
    }

    @Test
    void readOnlyAndHiddenChoicesAreTheSettings() throws Exception {
        configure("includeOriginals", "false", "originalsMode", "hidden", "includeRenditions", "true", "renditionsMode", "readonly",
                "expirationDurationAmount", "6", "expirationDurationUnit", "hours");
        // the asker asks for the originals and no renditions: the settings decide
        MockSlingHttpServletResponse made = call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd",
                "originals", "true", "renditions", "false");
        assertEquals(200, made.getStatus(), made.getOutputAsString());
        String token = (String) Json.parseObject(made.getOutputAsString()).get("token");
        org.apache.sling.api.resource.ValueMap link = resolver.getResource(DamLinkShare.ROOT + "/" + DamLinkShare.id(token)).getValueMap();
        assertEquals(false, link.get("studio:originals", true));
        assertEquals(true, link.get("studio:renditions", false));
        // the default validity: 6 hours
        long left = link.get("studio:expires", Calendar.class).getTimeInMillis() - System.currentTimeMillis();
        assertTrue(left > 5 * 3_600_000L && left <= 6 * 3_600_000L + 1000, String.valueOf(left));
        // no original through the link: there is no rendition here either
        assertEquals(404, call("GET", "/bin/studio/dam/linkshare", "file", "sh", token, "path", "/content/dam/wknd/surf.jpg", "r", "original").getStatus());
    }

    @Test
    void validityInAnyUnitUpToTheLongerOf90DaysAndTheSetting() throws Exception {
        configure("expirationDurationAmount", "1", "expirationDurationUnit", "years");
        assertEquals(200, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "amount", "11", "unit", "months").getStatus());
        assertEquals(400, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "amount", "13", "unit", "months").getStatus());
        assertEquals(400, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "amount", "0", "unit", "hours").getStatus());
        assertEquals(400, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "amount", "2", "unit", "weeks").getStatus());
        assertEquals(200, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "days", "30").getStatus());
    }

    @Test
    void onlyTheGroupsAllowedShare() throws Exception {
        configure("groupsAllowOrDeny", "allow", "groups", "[brand-managers]");
        MockSlingHttpServletResponse refused = call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd");
        assertEquals(403, refused.getStatus());
        assertTrue(refused.getOutputAsString().contains("Link Share"));
    }
}
