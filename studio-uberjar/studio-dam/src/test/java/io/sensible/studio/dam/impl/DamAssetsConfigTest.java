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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
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

/** Assets Configurations: the settings, and what each one changes in Media. */
@ExtendWith(SlingContextExtension.class)
class DamAssetsConfigTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private DamApiServlet servlet;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        JobManager jm = (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class}, (p, m, a) -> null);
        servlet = new DamApiServlet(jm);
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        am.createAsset("/content/dam/wknd/free.jpg", new ByteArrayInputStream("FREE".getBytes(StandardCharsets.UTF_8)), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/licensed.jpg", new ByteArrayInputStream("LIC".getBytes(StandardCharsets.UTF_8)), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/linked.jpg", new ByteArrayInputStream("LINK".getBytes(StandardCharsets.UTF_8)), "image/jpeg", true);
        context.create().resource("/content/dam/wknd/licensed.jpg/jcr:content/metadata", DamAssetsConfig.RESTRICTIONS,
                "<p>Only on <b>wknd.site</b> &amp; its newsletter.</p><script>alert(1)</script><p>Until 2027.</p>");
        context.create().resource("/content/dam/wknd/linked.jpg/jcr:content/metadata", DamAssetsConfig.WEB_STATEMENT, "https://example.com/license");
        resolver.commit();
    }

    private Map<String, Object> call(boolean post, String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        if (post) {
            req.setMethod("POST");
            req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        }
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        if (post) {
            servlet.doPost(req, res);
        } else {
            servlet.doGet(req, res);
        }
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    private Map<String, Object> save(String name, String json) throws Exception {
        return call(true, "op", "saveAssetsConfiguration", "name", name, "config", json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsDefaultsAndSavesCheckedValues() throws Exception {
        Map<String, Object> c = call(false, "op", "assetsConfigurations");
        assertEquals(200L, c.get("_status"));
        assertEquals(Collections.emptyList(), ((Map<String, Object>) c.get("uploadRestrictions")).get("allowedMimeTypes"));
        assertEquals(false, ((Map<String, Object>) c.get("drm")).get("enabled"));
        assertEquals(1000L, ((Map<String, Object>) c.get("omnisearch")).get("guessTotal"));
        assertEquals(true, ((Map<String, Object>) c.get("duplicates")).get("enabled"));
        Map<String, Object> ls = (Map<String, Object>) c.get("linkShare");
        assertEquals(true, ls.get("includeOriginals"));
        assertEquals("editable", ls.get("originalsMode"));
        assertEquals(7L, ls.get("validityAmount"));
        assertEquals("days", ls.get("validityUnit"));
        assertTrue(c.containsKey("watermark"));

        assertEquals(400L, save("uploadrestrictions", "{\"allowedMimeTypes\":[\"jpeg\"]}").get("_status"));
        Map<String, Object> up = save("uploadrestrictions", "{\"allowedMimeTypes\":[\"Image/*\",\"application/pdf\",\"image/*\"]}");
        assertEquals(Arrays.asList("image/*", "application/pdf"), ((Map<String, Object>) up.get("uploadRestrictions")).get("allowedMimeTypes"));

        assertEquals(400L, save("drm", "{\"enabled\":true,\"usageTermsUrl\":\"javascript:alert(1)\"}").get("_status"));
        assertEquals(true, ((Map<String, Object>) save("drm", "{\"enabled\":true,\"usageTermsUrl\":\"/content/wknd/terms.html\"}").get("drm")).get("enabled"));

        assertEquals(400L, save("omnisearch", "{\"guessTotal\":300}").get("_status"));
        Map<String, Object> o = (Map<String, Object>) save("omnisearch", "{\"guessTotal\":200,\"currentFolder\":false}").get("omnisearch");
        assertEquals(200L, o.get("guessTotal"));
        assertEquals(false, o.get("currentFolder"));
        // where AEM keeps it
        assertEquals(200L, resolver.getResource(DamAssetsConfig.OMNISEARCH).getValueMap().get("guessTotal", 0L));

        assertEquals(false, ((Map<String, Object>) save("duplicates", "{\"enabled\":false}").get("duplicates")).get("enabled"));

        assertEquals(400L, save("linkshare", "{\"includeOriginals\":false,\"includeRenditions\":false,\"originalsMode\":\"editable\","
                + "\"renditionsMode\":\"editable\",\"validityAmount\":7,\"validityUnit\":\"days\"}").get("_status"));
        assertEquals(400L, save("linkshare", "{\"includeOriginals\":true,\"includeRenditions\":false,\"originalsMode\":\"editable\","
                + "\"renditionsMode\":\"editable\",\"validityAmount\":3,\"validityUnit\":\"years\"}").get("_status"));
        Map<String, Object> l = (Map<String, Object>) save("linkshare", "{\"includeOriginals\":false,\"includeRenditions\":true,\"originalsMode\":\"hidden\","
                + "\"renditionsMode\":\"readonly\",\"validityAmount\":12,\"validityUnit\":\"hours\",\"groupsMode\":\"deny\",\"groups\":[\"contributors\"]}").get("linkShare");
        assertEquals("hidden", l.get("originalsMode"));
        assertEquals(12L, l.get("validityAmount"));
        assertEquals("hours", l.get("validityUnit"));
        assertEquals(Collections.singletonList("contributors"), l.get("groups"));
        assertEquals(400L, save("nonsense", "{}").get("_status"));
    }

    @Test
    void uploadRestrictionsByTypeAndFamily() {
        assertTrue(DamAssetsConfig.mimeAllowed(Collections.emptyList(), "video/mp4"));
        List<String> allowed = Arrays.asList("image/*", "application/pdf");
        assertTrue(DamAssetsConfig.mimeAllowed(allowed, "image/jpeg"));
        assertTrue(DamAssetsConfig.mimeAllowed(allowed, "Application/PDF; charset=binary"));
        assertFalse(DamAssetsConfig.mimeAllowed(allowed, "video/mp4"));
        assertFalse(DamAssetsConfig.mimeAllowed(allowed, "imagex/png"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void drmListsLicencesAsTextAndDownloadsLeaveOutWhatWasNotAgreedTo() throws Exception {
        Resource licensed = resolver.getResource("/content/dam/wknd/licensed.jpg");
        Map<String, Object> l = DamAssetsConfig.license(licensed);
        assertEquals("Only on wknd.site & its newsletter.\n\nUntil 2027.", l.get("text"));
        assertNull(DamAssetsConfig.license(resolver.getResource("/content/dam/wknd/free.jpg")));
        assertEquals("https://example.com/license", DamAssetsConfig.license(resolver.getResource("/content/dam/wknd/linked.jpg")).get("url"));

        // off: nothing to agree to
        Map<String, Object> off = call(false, "op", "licenses", "item", "/content/dam/wknd");
        assertEquals(false, off.get("enabled"));
        assertEquals(Collections.emptyList(), off.get("licenses"));
        assertEquals(3, zip("/content/dam/wknd").size());

        save("drm", "{\"enabled\":true,\"usageTermsUrl\":\"https://example.com/terms\"}");
        resolver.commit();
        Map<String, Object> on = call(false, "op", "licenses", "item", "/content/dam/wknd");
        assertEquals(2, ((List<Object>) on.get("licenses")).size());
        assertEquals("https://example.com/terms", on.get("usageTermsUrl"));

        assertEquals(Collections.singletonList("free.jpg"), zip("/content/dam/wknd"));
        assertEquals(Arrays.asList("free.jpg", "licensed.jpg"), zip("/content/dam/wknd", "/content/dam/wknd/licensed.jpg"));
        MockSlingHttpServletResponse refused = download(new String[] {"/content/dam/wknd/linked.jpg"});
        assertEquals(403, refused.getStatus());
    }

    private List<String> zip(String path, String... agree) throws Exception {
        MockSlingHttpServletResponse res = download(new String[] {path}, agree);
        assertEquals(200, res.getStatus(), res.getOutputAsString());
        List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(res.getOutput()))) {
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null;) {
                names.add(e.getName().replaceAll("^.*/", ""));
            }
        }
        Collections.sort(names);
        return names;
    }

    private MockSlingHttpServletResponse download(String[] paths, String... agree) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        for (String p : paths) {
            req.addRequestParameter("path", p);
        }
        for (String a : agree) {
            req.addRequestParameter("agree", a);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new AssetDownloadServlet().doGet(req, res);
        return res;
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateDetectionCanBeTurnedOff() throws Exception {
        String sha1 = "0123456789abcdef0123456789abcdef01234567";
        assertTrue(call(false, "op", "duplicates", "sha1", sha1).containsKey("duplicates"));
        save("duplicates", "{\"enabled\":false}");
        resolver.commit();
        Map<String, Object> off = call(false, "op", "duplicates", "sha1", sha1);
        assertEquals(false, off.get("enabled"));
        assertEquals(Collections.emptyList(), off.get("duplicates"));
    }

    @Test
    void licenceTextNeverKeepsMarkup() {
        assertEquals("a < b \"c\"", DamAssetsConfig.textOf("<i>a</i> &lt; b &quot;c&quot;<img src=x onerror=alert(1)>"));
        assertEquals("one\n\ntwo", DamAssetsConfig.textOf("one<br>two"));
    }
}
