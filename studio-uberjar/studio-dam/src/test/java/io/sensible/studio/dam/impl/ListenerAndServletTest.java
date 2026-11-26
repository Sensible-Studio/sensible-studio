/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.dam.api.Asset;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;

@ExtendWith(SlingContextExtension.class)
class ListenerAndServletTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    /** JobManager that records addJob calls. */
    private static JobManager recordingJobManager(List<String> paths) {
        return (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class},
                (proxy, method, args) -> {
                    if ("addJob".equals(method.getName())) {
                        assertEquals(AssetProcessingJob.TOPIC, args[0]);
                        paths.add((String) ((Map<?, ?>) args[1]).get(AssetProcessingJob.PROP_PATH));
                    }
                    return null;
                });
    }

    @Test
    void queuesOneJobPerChangedOriginal() {
        List<String> queued = new ArrayList<>();
        AssetChangeListener listener = new AssetChangeListener(recordingJobManager(queued),
                context.getService(ResourceResolverFactory.class));
        listener.onChange(Arrays.asList(
                new ResourceChange(ChangeType.ADDED, "/content/dam/a.jpg/jcr:content/renditions/original", false),
                new ResourceChange(ChangeType.ADDED, "/content/dam/a.jpg/jcr:content/renditions/original/jcr:content", false),
                new ResourceChange(ChangeType.CHANGED, "/content/dam/b/c.png/jcr:content/renditions/original/jcr:content", false),
                // Written by processing itself: must not loop.
                new ResourceChange(ChangeType.ADDED, "/content/dam/a.jpg/jcr:content/renditions/cq5dam.web.1280.1280.jpeg", false),
                new ResourceChange(ChangeType.CHANGED, "/content/dam/a.jpg/jcr:content/metadata", false)));
        assertEquals(Arrays.asList("/content/dam/a.jpg", "/content/dam/b/c.png"), queued);
    }

    @Test
    void queuesNothingWhereFilesArriveProcessed() {
        List<String> queued = new ArrayList<>();
        AssetChangeListener listener = new AssetChangeListener(recordingJobManager(queued),
                context.getService(ResourceResolverFactory.class));
        listener.activate(java.util.Collections.singletonMap("enabled", (Object) Boolean.FALSE));
        listener.onChange(Arrays.asList(
                new ResourceChange(ChangeType.ADDED, "/content/dam/a.jpg/jcr:content/renditions/original", false)));
        assertEquals(java.util.Collections.emptyList(), queued);
        listener.activate(java.util.Collections.emptyMap());
        listener.onChange(Arrays.asList(
                new ResourceChange(ChangeType.ADDED, "/content/dam/a.jpg/jcr:content/renditions/original", false)));
        assertEquals(Arrays.asList("/content/dam/a.jpg"), queued);
    }

    @Test
    void uploadCreatesTheAsset() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        MockSlingHttpServletRequest req = context.request();
        req.setMethod("POST");
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        req.addRequestParameter("folder", "/content/dam/wknd");
        req.addRequestParameter("file", "JPEG".getBytes(StandardCharsets.UTF_8), "image/jpeg", "Pică Ski Day.jpg");
        MockSlingHttpServletResponse res = context.response();

        new AssetUploadServlet().doPost(req, res);
        assertEquals(201, res.getStatus(), res.getOutputAsString());
        assertTrue(res.getOutputAsString().contains("/content/dam/wknd/Pica-Ski-Day.jpg"));
        Asset asset = context.resourceResolver().getResource("/content/dam/wknd/Pica-Ski-Day.jpg").adaptTo(Asset.class);
        assertNotNull(asset);
        assertEquals("image/jpeg", asset.getOriginal().getMimeType());
    }

    @Test
    void uploadAsksBeforeReplacingAnExistingAsset() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        assertEquals(201, upload(null).getStatus());
        MockSlingHttpServletResponse again = upload(null);
        assertEquals(409, again.getStatus());
        assertTrue(again.getOutputAsString().contains("\"conflict\":true"), again.getOutputAsString());

        MockSlingHttpServletResponse both = upload("rename");
        assertEquals(201, both.getStatus());
        assertTrue(both.getOutputAsString().contains("/content/dam/wknd/day1.jpg"), both.getOutputAsString());
        assertEquals(201, upload("replace").getStatus());
        assertNotNull(context.resourceResolver().getResource("/content/dam/wknd/day.jpg"));
    }

    @Test
    void downloadsFoldersAndAssetsAsZip() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:Folder");
        assertEquals(201, upload(null).getStatus());
        assertEquals(201, upload("rename").getStatus());

        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.addRequestParameter("path", "/content/dam/wknd");
        req.addRequestParameter("path", "/content/dam/wknd/day.jpg");
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new AssetDownloadServlet().doGet(req, res);
        assertEquals("application/zip", res.getContentType(), res.getOutputAsString());
        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(res.getOutput()))) {
            for (java.util.zip.ZipEntry e; (e = zin.getNextEntry()) != null;) {
                names.add(e.getName());
                assertEquals("JPEG", new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(java.util.List.of("wknd/day.jpg", "wknd/day1.jpg", "day.jpg"), names);

        MockSlingHttpServletRequest bad = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        bad.addRequestParameter("path", "/apps/studio");
        MockSlingHttpServletResponse r = new MockSlingHttpServletResponse();
        new AssetDownloadServlet().doGet(bad, r);
        assertEquals(404, r.getStatus());
    }

    private MockSlingHttpServletResponse upload(String conflict) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.setMethod("POST");
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        req.addRequestParameter("folder", "/content/dam/wknd");
        req.addRequestParameter("file", "JPEG".getBytes(StandardCharsets.UTF_8), "image/jpeg", "day.jpg");
        if (conflict != null) {
            req.addRequestParameter("conflict", conflict);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new AssetUploadServlet().doPost(req, res);
        context.resourceResolver().commit();
        return res;
    }

    @Test
    void uploadRejectsMissingHeaderAndBadFolders() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        MockSlingHttpServletRequest noHeader = context.request();
        noHeader.addRequestParameter("folder", "/content/dam");
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new AssetUploadServlet().doPost(noHeader, res);
        assertEquals(403, res.getStatus());

        for (String folder : new String[] {"/apps", "/content/dam/../../apps", "/content/damage"}) {
            MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
            req.addHeader(DamApiServlet.CSRF_HEADER, "1");
            req.addRequestParameter("folder", folder);
            req.addRequestParameter("file", new byte[] {1}, "image/png", "x.png");
            MockSlingHttpServletResponse r = new MockSlingHttpServletResponse();
            new AssetUploadServlet().doPost(req, r);
            assertEquals(400, r.getStatus(), folder);
        }
    }

    @Test
    void sanitisesFileNames() {
        assertEquals("photo.jpg", AssetUploadServlet.safeName("C:\\Users\\me\\photo.jpg"));
        assertEquals("passwd", AssetUploadServlet.safeName("../../etc/passwd"));
        assertEquals("Sare-si-piper.png", AssetUploadServlet.safeName("Sare și piper.png"));
        assertEquals("htaccess", AssetUploadServlet.safeName(".htaccess"));
        assertNull(AssetUploadServlet.safeName("..."));
        assertNull(AssetUploadServlet.safeName(null));
    }

    @Test
    void uploadTypeComesFromTheExtensionNotTheClient() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        MockSlingHttpServletRequest req = context.request();
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        req.addRequestParameter("folder", "/content/dam");
        req.addRequestParameter("file", new byte[] {1, 2}, "text/html", "photo.JPG");
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new AssetUploadServlet().doPost(req, res);
        assertEquals(201, res.getStatus());
        assertEquals("image/jpeg", context.resourceResolver().getResource("/content/dam/photo.JPG").adaptTo(Asset.class)
                .getOriginal().getMimeType());

        for (String name : new String[] {"x.html", "x.xhtml", "x.js", "noextension"}) {
            MockSlingHttpServletRequest bad = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
            bad.addHeader(DamApiServlet.CSRF_HEADER, "1");
            bad.addRequestParameter("folder", "/content/dam");
            bad.addRequestParameter("file", new byte[] {1}, "image/png", name);
            MockSlingHttpServletResponse r = new MockSlingHttpServletResponse();
            new AssetUploadServlet().doPost(bad, r);
            assertEquals(400, r.getStatus(), name);
        }
    }

    @Test
    void activeTypesAreServedSandboxed() throws Exception {
        context.load().binaryFile(new java.io.ByteArrayInputStream("<svg/>".getBytes(StandardCharsets.UTF_8)),
                "/content/dam/logo.svg/jcr:content/renditions/original", "image/svg+xml");
        context.load().binaryFile(new java.io.ByteArrayInputStream(new byte[] {1}), "/content/dam/a.png/jcr:content/renditions/original", "image/png");
        for (String[] c : new String[][] {{"/content/dam/logo.svg/jcr:content/renditions/original", "sandbox"}, {"/content/dam/a.png/jcr:content/renditions/original", null}}) {
            context.currentResource(c[0]);
            MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
            boolean[] called = {false};
            new ActiveContentFilter().doFilter(context.request(), res, (q, r) -> called[0] = true);
            assertTrue(called[0]);
            String csp = res.getHeader("Content-Security-Policy");
            assertEquals(c[1], csp == null ? null : csp.substring(0, 7), c[0]);
        }
        assertEquals("image/svg+xml", AssetUploadServlet.mimeFor("Logo.SVG"));
    }

    @Test
    void tokenFileIsOwnerOnlyAndStable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File f = dir.resolve("dam/token").toFile();
        String t1 = DamServiceManager.persistentToken(f);
        assertEquals(t1, DamServiceManager.persistentToken(f));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(f.toPath())));
        java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(t1, DamServiceManager.persistentToken(f));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(f.toPath())), "repaired");
    }

    @Test
    void embeddedModeIsLoopbackOnly() {
        assertTrue(DamServiceManager.isLoopback("127.0.0.1"));
        assertTrue(DamServiceManager.isLoopback("localhost"));
        assertTrue(DamServiceManager.isLoopback("[::1]"));
        assertEquals(false, DamServiceManager.isLoopback("0.0.0.0"));
        assertEquals(false, DamServiceManager.isLoopback("10.0.0.5"));
    }
}
