/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

/**
 * Java → real studio-dam binary → repository. Runs only when STUDIO_DAM_BINARY points to a build of
 * dam-service ({@code cargo build --release}); skipped otherwise.
 */
@ExtendWith(SlingContextExtension.class)
class EndToEndTest {

    private static final String PATH = "/content/dam/wknd/freeride.jpg";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private DamServiceManager manager;

    @TempDir
    Path home;

    static DamServiceManager.Config config(Map<String, Object> values) {
        return (DamServiceManager.Config) Proxy.newProxyInstance(EndToEndTest.class.getClassLoader(),
                new Class<?>[] {DamServiceManager.Config.class}, (proxy, method, args) ->
                        values.containsKey(method.getName()) ? values.get(method.getName()) : method.getDefaultValue());
    }

    @BeforeEach
    void startService() throws Exception {
        String binary = System.getenv("STUDIO_DAM_BINARY");
        assumeTrue(binary != null && !binary.isEmpty() && new File(binary).canExecute(), "STUDIO_DAM_BINARY not set");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("binary", binary);
        cfg.put("url", "http://127.0.0.1:" + port);
        manager = new DamServiceManager();
        manager.start(config(cfg), home.toFile());
        waitFor(() -> manager.isRunning(), 20_000);
    }

    @AfterEach
    void stopService() {
        if (manager != null) {
            manager.deactivate();
        }
    }

    @Test
    void processesAWkndImageThroughTheRealService() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        ResourceResolver resolver = context.resourceResolver();
        byte[] jpeg;
        try (InputStream in = getClass().getResourceAsStream("/wknd/freeride.jpg")) {
            jpeg = in.readAllBytes();
        }
        new AssetManagerImpl(resolver).createAsset(PATH, new ByteArrayInputStream(jpeg), "image/jpeg", true);

        long started = System.nanoTime();
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(manager).process(resolver, PATH));
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("EndToEndTest: freeride.jpg processed in " + ms + " ms");

        Asset asset = resolver.getResource(PATH).adaptTo(Asset.class);
        assertEquals(6, asset.getRenditions().size(), "original + 5 renditions");
        // Same sizes AEM produced for this WKND asset (720x1080, never upscaled).
        assertSize(asset.getRendition("cq5dam.thumbnail.48.48.png"), 32, 48);
        assertSize(asset.getRendition("cq5dam.thumbnail.319.319.png"), 213, 319);
        assertSize(asset.getRendition("cq5dam.web.1280.1280.jpeg"), 720, 1080);

        ValueMap md = resolver.getResource(PATH + "/jcr:content/metadata").getValueMap();
        assertEquals("479b58c3531bd476dc73943f58cd5286e1a37952", md.get("dam:sha1", String.class));
        assertEquals(102189L, md.get("dam:size", Long.class));
        assertEquals(300L, md.get("dam:Physicalwidthindpi", Long.class));
        assertEquals("Freeride", md.get("dc:title", String.class));
        assertEquals("processed", resolver.getResource(PATH + "/jcr:content").getValueMap().get("dam:assetState"));
    }

    @Test
    void deliversThroughStudioWithStableUrls() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        ResourceResolver resolver = context.resourceResolver();
        try (InputStream in = getClass().getResourceAsStream("/wknd/freeride.jpg")) {
            new AssetManagerImpl(resolver).createAsset(PATH, in, "image/jpeg", true);
        }
        new AssetProcessor(manager).process(resolver, PATH);

        Map<String, Object> params = DeliveryTest.ccParams("400");
        params.put("preferwebp", "false");
        String url = new AssetDeliveryImpl(manager).getDeliveryURL(resolver.getResource(PATH), params);
        assertNotNull(url);
        byte[] jpeg = deliver(url);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(400, img.getWidth());
        assertEquals(600, img.getHeight(), "720x1080 scaled to width 400");

        String webpUrl = new AssetDeliveryImpl(manager).getDeliveryURL(resolver.getResource(PATH), DeliveryTest.ccParams("1200"));
        byte[] webp = deliver(webpUrl);
        assertEquals("RIFF", new String(webp, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(webp, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));

        // Restarting Sensible Studio keeps the token (dam/token), so URLs already in cached pages stay valid.
        manager.deactivate();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("binary", System.getenv("STUDIO_DAM_BINARY"));
        cfg.put("url", "http://127.0.0.1:" + freePort());
        manager = new DamServiceManager();
        manager.start(config(cfg), home.toFile());
        waitFor(() -> manager.isRunning(), 20_000);
        assertEquals(url, new AssetDeliveryImpl(manager).getDeliveryURL(resolver.getResource(PATH), params));
        assertEquals(jpeg.length, deliver(url).length);
    }

    private byte[] deliver(String url) throws Exception {
        org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest req =
                new org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        ((org.apache.sling.testing.mock.sling.servlet.MockRequestPathInfo) req.getRequestPathInfo())
                .setSuffix(url.substring(DeliveryUrls.PREFIX.length()));
        org.apache.sling.servlethelpers.MockSlingHttpServletResponse res = new org.apache.sling.servlethelpers.MockSlingHttpServletResponse();
        new DamDeliveryServlet(manager).doGet(req, res);
        assertEquals(200, res.getStatus(), url);
        return res.getOutput();
    }

    private static int freePort() throws java.io.IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void ignoresAnotherProgramOnThePort() throws Exception {
        // Something else answers {"status":"ok"} on the port Sensible Studio wants: it must not get any work.
        manager.deactivate();
        com.sun.net.httpserver.HttpServer squatter = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        squatter.createContext("/", ex -> {
            byte[] b = "{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        squatter.start();
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("binary", System.getenv("STUDIO_DAM_BINARY"));
            cfg.put("url", "http://127.0.0.1:" + squatter.getAddress().getPort());
            cfg.put("startupTimeoutSeconds", 2);
            manager = new DamServiceManager();
            manager.start(config(cfg), home.toFile());
            Thread.sleep(3000);
            assertFalse(manager.isRunning(), "a health answer without the token proof is not our service");
        } finally {
            squatter.stop(0);
        }
    }

    @Test
    void restartsTheServiceWhenItDies() throws Exception {
        Process first = manager.currentProcess();
        assertNotNull(first);
        first.destroyForcibly();
        waitFor(() -> !manager.isRunning(), 5_000);
        waitFor(() -> manager.isRunning() && manager.currentProcess() != first, 20_000);
        assertNotEquals(first.pid(), manager.currentProcess().pid());

        Process second = manager.currentProcess();
        manager.deactivate();
        assertFalse(second.isAlive(), "stopped with Sensible Studio");
        assertTrue(Files.exists(home.resolve("logs/dam-service.log")));
        manager = null;
    }

    private static void assertSize(Rendition r, int w, int h) throws Exception {
        assertNotNull(r);
        try (InputStream in = r.getStream()) {
            BufferedImage img = ImageIO.read(in);
            assertEquals(w, img.getWidth(), r.getName());
            assertEquals(h, img.getHeight(), r.getName());
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(50);
        }
    }
}
