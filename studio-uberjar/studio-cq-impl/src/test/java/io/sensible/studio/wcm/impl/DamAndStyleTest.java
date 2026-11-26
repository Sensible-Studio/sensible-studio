/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.adobe.cq.wcm.style.ComponentStyleInfo;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.foundation.WCMRenditionPicker;
import com.day.image.Layer;

import io.sensible.studio.wcm.impl.dam.AssetStoreImpl;

@ExtendWith(SlingContextExtension.class)
class DamAndStyleTest {

    private final SlingContext context = WcmTestSupport.newContext();
    private byte[] png;

    @BeforeEach
    void setUp() throws Exception {
        WcmTestSupport.setUp(context);
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        png = out.toByteArray();

        String asset = "/content/dam/wknd/surf.png";
        Map<String, Object> p = new HashMap<>();
        p.put("jcr:primaryType", "dam:Asset");
        context.create().resource(asset, p);
        context.create().resource(asset + "/jcr:content", "jcr:primaryType", "dam:AssetContent");
        context.create().resource(asset + "/jcr:content/metadata", "dc:format", "image/png", "dc:title", "Surf");
        context.create().resource(asset + "/jcr:content/renditions", "jcr:primaryType", "nt:folder");
        context.load().binaryFile(new ByteArrayInputStream(png), asset + "/jcr:content/renditions/original", "image/png");
        context.load().binaryFile(new ByteArrayInputStream(png), asset + "/jcr:content/renditions/cq5dam.web.1280.1280.png", "image/png");
    }

    @Test
    void assetRenditionsAndMetadata() throws Exception {
        Asset asset = context.resourceResolver().getResource("/content/dam/wknd/surf.png").adaptTo(Asset.class);
        assertNotNull(asset);
        assertEquals("image/png", asset.getMimeType());
        assertEquals("Surf", asset.getMetadataValue("dc:title"));
        assertEquals(2, asset.getRenditions().size());
        assertEquals("original", asset.getOriginal().getName());
        Rendition web = asset.getRendition(new WCMRenditionPicker());
        assertTrue(web.getName().startsWith("cq5dam.web."));
        assertEquals("image/png", web.getMimeType());
        BufferedImage img = new AssetStoreImpl().getAssetHandler("image/png").getImage(asset.getOriginal());
        assertEquals(40, img.getWidth());
        assertNull(new AssetStoreImpl().getAssetHandler("image/svg+xml"));
        Resource orig = context.resourceResolver().getResource("/content/dam/wknd/surf.png/jcr:content/renditions/original");
        assertEquals("/content/dam/wknd/surf.png", orig.adaptTo(Rendition.class).getAsset().getPath());
    }

    @Test
    void layerOperations() throws Exception {
        Layer layer = new Layer(new ByteArrayInputStream(png));
        layer.resize(20, 0);
        assertEquals(10, layer.getHeight());
        layer.rotate(90);
        assertEquals(10, layer.getWidth());
        layer.crop(new java.awt.Rectangle(0, 0, 5, 5));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(layer.write("image/jpeg", 0.8, out));
        assertTrue(out.size() > 0);
    }

    @Test
    void styleSystemClasses() {
        String policy = "/conf/wknd/settings/wcm/policies/wknd/components/title/policy_title";
        context.create().resource(policy + "/cq:styleGroups/item0/cq:styles/item0",
                "cq:styleId", "s-underline", "cq:styleClasses", "cmp-title--underline");
        context.create().resource(policy + "/cq:styleGroups/item0/cq:styles/item1",
                "cq:styleId", "s-large", "cq:styleClasses", "cmp-title--large bold");
        Resource title = context.resourceResolver().getResource(EN + "/jcr:content/root/container/title");
        title.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("cq:styleIds", new String[] {"s-large", "s-underline"});
        ComponentStyleInfo info = title.adaptTo(ComponentStyleInfo.class);
        assertEquals("cmp-title--underline cmp-title--large bold", info.getAppliedCssClasses());
    }

    @Test
    void tagManagerResolves() {
        TagManager tm = context.resourceResolver().adaptTo(TagManager.class);
        assertEquals("Surfing", tm.resolve("wknd:activity/surfing").getTitle());
        assertEquals(1, tm.getNamespaces().length);
        assertEquals(1, tm.getTags(context.resourceResolver().getResource(EN + "/adventures/jcr:content")).length);
    }
}
