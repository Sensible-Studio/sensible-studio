/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

/** DRM: the preview of a licensed file carries the system watermark. */
@ExtendWith(SlingContextExtension.class)
class DrmPreviewTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private static BufferedImage plain(int w, int h, Color c) {
        BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = i.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return i;
    }

    private static byte[] bytes(BufferedImage i, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(i, format, out);
        return out.toByteArray();
    }

    @Test
    void drawsTheMarkWhereItIsSet() {
        BufferedImage picture = plain(200, 100, Color.WHITE);
        BufferedImage mark = plain(10, 10, Color.BLACK);
        BufferedImage centre = DamWatermark.draw(picture, mark, "center", 100, 20);
        assertEquals(0xff000000, centre.getRGB(100, 50));
        assertEquals(0xffffffff, centre.getRGB(5, 5));
        BufferedImage corner = DamWatermark.draw(picture, mark, "bottom-right", 100, 20);
        assertEquals(0xff000000, corner.getRGB(180, 80));
        assertEquals(0xffffffff, corner.getRGB(100, 50));
        BufferedImage tiled = DamWatermark.draw(picture, mark, "tile", 50, 10);
        int grey = tiled.getRGB(10, 10) & 0xff;
        // half opacity: neither the picture nor the mark
        assertEquals(true, grey > 100 && grey < 160, String.valueOf(grey));
        assertNotEquals(tiled.getRGB(10, 10), tiled.getRGB(1, 1));
        // the picture given is left as it was
        assertEquals(0xffffffff, picture.getRGB(100, 50));
    }

    @Test
    void servesTheMarkedPreviewOnlyForLicensedFilesWhileDrmIsOn() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        ResourceResolver resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        am.createAsset("/content/dam/mark.png", new ByteArrayInputStream(bytes(plain(10, 10, Color.BLACK), "png")), "image/png", true);
        for (String name : new String[] {"licensed.jpg", "free.jpg"}) {
            am.createAsset("/content/dam/" + name, new ByteArrayInputStream(bytes(plain(200, 100, Color.WHITE), "jpg")), "image/jpeg", true);
            context.load().binaryFile(new ByteArrayInputStream(bytes(plain(200, 100, Color.WHITE), "jpg")),
                    "/content/dam/" + name + "/jcr:content/renditions/cq5dam.web.1280.1280.jpeg", "image/jpeg");
        }
        context.create().resource("/content/dam/licensed.jpg/jcr:content/metadata", DamAssetsConfig.RESTRICTIONS, "<p>Web only.</p>");
        context.create().resource("/content/dam/mark.png/jcr:content/metadata", "dam:MIMEtype", "image/png");
        resolver.commit();

        // DRM off
        assertEquals(404, get("/content/dam/licensed.jpg").getStatus());
        context.create().resource(DamAssetsConfig.ROOT + "/drm", "enable_drm", true);
        // on, but no watermark set: the console keeps the usual preview
        assertEquals(404, get("/content/dam/licensed.jpg").getStatus());
        assertNull(DrmPreviewServlet.render(resolver.getResource("/content/dam/licensed.jpg")));
        context.create().resource(DamWatermark.PATH, "image", "/content/dam/mark.png", "position", "center", "opacity", 100L, "size", 20L);
        resolver.commit();

        MockSlingHttpServletResponse marked = get("/content/dam/licensed.jpg");
        assertEquals(200, marked.getStatus());
        assertEquals("image/jpeg", marked.getContentType());
        BufferedImage shown = ImageIO.read(new ByteArrayInputStream(marked.getOutput()));
        assertNotNull(shown);
        assertEquals(200, shown.getWidth());
        assertEquals(true, (shown.getRGB(100, 50) & 0xff) < 60, "the mark in the centre");
        assertEquals(true, (shown.getRGB(5, 5) & 0xff) > 200, "the picture elsewhere");

        assertEquals(404, get("/content/dam/free.jpg").getStatus());
        assertEquals(404, get("/content/elsewhere").getStatus());
    }

    private MockSlingHttpServletResponse get(String path) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        req.addRequestParameter("path", path);
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        new DrmPreviewServlet().doGet(req, res);
        return res;
    }
}
