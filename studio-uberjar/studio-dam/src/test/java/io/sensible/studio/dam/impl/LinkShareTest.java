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
class LinkShareTest {

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

    @Test
    void sharesOnlyWhatWasSharedUntilItExpires() throws Exception {
        MockSlingHttpServletResponse made = call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "days", "7");
        assertEquals(200, made.getStatus(), made.getOutputAsString());
        Map<String, Object> link = Json.parseObject(made.getOutputAsString());
        String token = (String) link.get("token");
        assertEquals(43, token.length());
        assertNull(resolver.getResource(DamLinkShare.ROOT + "/" + token), "only the hash is stored");

        MockSlingHttpServletResponse page = call("GET", "/linkshare", "html", "sh", token);
        assertEquals(200, page.getStatus());
        assertTrue(page.getOutputAsString().contains("surf.jpg"));
        assertFalse(page.getOutputAsString().contains("secret"));
        assertEquals("no-referrer", page.getHeader("Referrer-Policy"));

        MockSlingHttpServletResponse file = call("GET", "/bin/studio/dam/linkshare", "file", "sh", token, "path", "/content/dam/wknd/surf.jpg", "r", "original");
        assertEquals("SURF", new String(file.getOutput(), StandardCharsets.UTF_8));
        assertEquals("application/octet-stream", file.getContentType().replaceAll(";.*", ""));
        assertEquals(404, call("GET", "/bin/studio/dam/linkshare", "file", "sh", token, "path", "/content/dam/private/secret.jpg").getStatus());
        assertEquals(404, call("GET", "/bin/studio/dam/linkshare", "file", "sh", token, "path", "/content/dam/wknd/../private/secret.jpg").getStatus());
        assertEquals(404, call("GET", "/linkshare", "html", "sh", "x" + token.substring(1)).getStatus());

        MockSlingHttpServletResponse zip = call("GET", "/bin/studio/dam/linkshare", "zip", "sh", token);
        assertTrue(zip.getOutput().length > 20 && zip.getOutput()[0] == 'P');

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mine = (List<Map<String, Object>>) Json.parseObject(call("GET", "/bin/studio/dam/linkshare", "json").getOutputAsString()).get("links");
        assertEquals(1, mine.size());
        assertFalse(mine.get(0).containsKey("token"));

        // expired
        Calendar past = Calendar.getInstance();
        past.add(Calendar.DAY_OF_MONTH, -1);
        resolver.getResource(DamLinkShare.ROOT + "/" + DamLinkShare.id(token)).adaptTo(ModifiableValueMap.class).put("studio:expires", past);
        resolver.commit();
        assertEquals(404, call("GET", "/linkshare", "html", "sh", token).getStatus());

        assertEquals(400, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd", "days", "365").getStatus());
        assertEquals(404, call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/apps").getStatus());
    }

    @Test
    void revokes() throws Exception {
        Map<String, Object> link = Json.parseObject(
                call("POST", "/bin/studio/dam/linkshare", "json", "op", "create", "path", "/content/dam/wknd/surf.jpg", "days", "1").getOutputAsString());
        assertEquals(200, call("POST", "/bin/studio/dam/linkshare", "json", "op", "revoke", "id", (String) link.get("id")).getStatus());
        assertEquals(404, call("GET", "/linkshare", "html", "sh", (String) link.get("token")).getStatus());
    }
}
