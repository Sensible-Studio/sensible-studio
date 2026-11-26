/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

/** Color tags: the colors a picture is mostly made of. */
@ExtendWith(SlingContextExtension.class)
class DamColorsTest {

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
        am.createAsset("/content/dam/wknd/sea.jpg", new ByteArrayInputStream(new byte[] {1}), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/sunset.jpg", new ByteArrayInputStream(new byte[] {2}), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/notes.txt", new ByteArrayInputStream(new byte[] {3}), "text/plain", true);
        // the sea: three quarters blue, a quarter white sky; the sunset: orange over black
        thumbnail("/content/dam/wknd/sea.jpg", new Color(25, 90, 200), new Color(250, 250, 250), 0.75);
        thumbnail("/content/dam/wknd/sunset.jpg", new Color(240, 130, 20), new Color(10, 10, 10), 0.6);
        resolver.commit();
    }

    /** A picture of two colors, the first taking {@code share} of it from the bottom. */
    private static BufferedImage picture(Color main, Color rest, double share) {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(rest);
        g.fillRect(0, 0, 200, 100);
        g.setColor(main);
        int h = (int) Math.round(100 * share);
        g.fillRect(0, 100 - h, 200, h);
        g.dispose();
        return img;
    }

    private void thumbnail(String asset, Color main, Color rest, double share) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(picture(main, rest, share), "png", png);
        context.load().binaryFile(new ByteArrayInputStream(png.toByteArray()),
                asset + "/jcr:content/renditions/" + DamItems.THUMBNAIL, "image/png");
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

    @Test
    void namesColors() {
        assertEquals("red", DamColors.name(200, 30, 30));
        assertEquals("orange", DamColors.name(240, 130, 20));
        assertEquals("yellow", DamColors.name(240, 220, 40));
        assertEquals("green", DamColors.name(40, 160, 60));
        assertEquals("teal", DamColors.name(20, 150, 150));
        assertEquals("blue", DamColors.name(25, 90, 200));
        assertEquals("purple", DamColors.name(120, 40, 180));
        assertEquals("pink", DamColors.name(240, 120, 190));
        assertEquals("brown", DamColors.name(110, 70, 30));
        assertEquals("beige", DamColors.name(230, 215, 185));
        assertEquals("white", DamColors.name(250, 250, 250));
        assertEquals("gray", DamColors.name(128, 128, 128));
        assertEquals("black", DamColors.name(10, 10, 10));
        assertTrue(DamColors.NAMES.containsAll(List.of("red", "white", "black")));
    }

    @Test
    void findsTheColorsAPictureIsMostlyMadeOf() {
        List<Object[]> colors = DamColors.of(picture(new Color(25, 90, 200), new Color(250, 250, 250), 0.75));
        assertEquals(List.of("blue", "white"), colors.stream().map(c -> c[0]).collect(Collectors.toList()));
        assertEquals(0.75, (Double) colors.get(0)[1], 0.03);
        // a sliver under 5% is not named
        assertEquals(List.of("green"), DamColors.of(picture(new Color(40, 160, 60), new Color(200, 30, 30), 0.97)).stream()
                .map(c -> c[0]).collect(Collectors.toList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void colorsTheFilesOfAFolderAndTheSearchFindsThem() throws Exception {
        assertNull(call(false, "op", "asset", "path", "/content/dam/wknd/sea.jpg").get("colors"));
        Map<String, Object> r = call(true, "op", "colorTag", "path", "/content/dam/wknd");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        assertEquals(2L, r.get("tagged"));
        assertEquals(1L, r.get("skipped")); // the text file has no picture
        assertEquals(false, r.get("more"));

        List<Map<String, Object>> colors = (List<Map<String, Object>>) call(false, "op", "asset", "path", "/content/dam/wknd/sea.jpg").get("colors");
        assertEquals("blue", colors.get(0).get("name"));
        assertTrue(((Number) colors.get(0).get("share")).doubleValue() > 0.7);
        assertEquals(Arrays.asList("orange", "black"), Arrays.asList(resolver.getResource("/content/dam/wknd/sunset.jpg/jcr:content/metadata")
                .getValueMap().get(DamColors.PN_COLORS, String[].class)));

        // done once: a second run has nothing to do, unless asked again
        assertEquals(0L, call(true, "op", "colorTag", "path", "/content/dam/wknd").get("tagged"));
        assertEquals(1L, call(true, "op", "colorTag", "path", "/content/dam/wknd/sea.jpg", "again", "true").get("tagged"));
        assertEquals(404L, call(true, "op", "colorTag", "path", "/content/dam/nothing").get("_status"));

        Map<String, Object> found = call(false, "op", "search", "path", "/content/dam/wknd", "meta", DamColors.PN_COLORS + "=blue");
        List<Map<String, Object>> items = (List<Map<String, Object>>) found.get("items");
        assertEquals(List.of("sea.jpg"), items.stream().map(i -> i.get("name")).collect(Collectors.toList()));
    }

    @Test
    void theSearchFormHasAColorFilter() {
        assertTrue(DamSearchForms.BUILT_IN.contains("colors"));
        assertEquals("Color", DamSearchForms.defaultLabel("colors"));
        assertFalse(DamSearchForms.defaults().isEmpty());
    }
}
