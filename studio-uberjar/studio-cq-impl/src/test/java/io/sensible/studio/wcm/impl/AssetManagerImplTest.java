/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.jcr.Node;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.dam.api.Rendition;

@ExtendWith(SlingContextExtension.class)
class AssetManagerImplTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private ResourceResolver resolver;
    private AssetManager manager;

    @BeforeEach
    void setUp() {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        manager = resolver.adaptTo(AssetManager.class);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createsTheDamStructureAndParentFolders() throws IOException {
        assertNotNull(manager);
        Asset asset = manager.createAsset("/content/dam/wknd/new/photo.jpg", bytes("JPEG"), "image/jpeg", true);
        assertNotNull(asset);
        assertEquals("/content/dam/wknd/new/photo.jpg", asset.getPath());

        Resource folder = resolver.getResource("/content/dam/wknd/new");
        assertEquals("sling:OrderedFolder", folder.getValueMap().get("jcr:primaryType", String.class));
        Resource content = resolver.getResource("/content/dam/wknd/new/photo.jpg/jcr:content");
        assertEquals("dam:AssetContent", content.getValueMap().get("jcr:primaryType", String.class));
        assertEquals("processing", content.getValueMap().get("dam:assetState", String.class));
        assertNotNull(content.getChild("metadata"));

        Rendition original = asset.getOriginal();
        assertNotNull(original);
        assertEquals("image/jpeg", original.getMimeType());
        try (InputStream in = original.getStream()) {
            assertArrayEquals("JPEG".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertTrue(manager.assetExists("/content/dam/wknd/new/photo.jpg"));
        assertFalse(manager.assetExists("/content/dam/wknd/new"));
    }

    @Test
    void replacingTheOriginalKeepsMetadataAndRestartsProcessing() throws Exception {
        manager.createAsset("/content/dam/a.png", bytes("v1"), "image/png", true);
        Resource content = resolver.getResource("/content/dam/a.png/jcr:content");
        content.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("dam:assetState", "processed");
        resolver.getResource("/content/dam/a.png/jcr:content/metadata")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("dc:title", "Kept");
        resolver.commit();

        Asset asset = manager.createAsset("/content/dam/a.png", bytes("v2"), "image/png", true);
        try (InputStream in = asset.getOriginal().getStream()) {
            assertEquals("v2", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("Kept", asset.getMetadataValue("dc:title"));
        assertEquals("processing", resolver.getResource("/content/dam/a.png/jcr:content")
                .getValueMap().get("dam:assetState", String.class));
    }

    @Test
    void refusesBadPathsAndNonAssetNodes() throws Exception {
        assertNull(manager.createAsset("relative.jpg", bytes("x"), "image/jpeg", true));
        assertNull(manager.createAsset("/content/dam/", bytes("x"), "image/jpeg", true));
        assertNull(manager.createAsset("/content/dam/x.jpg", null, "image/jpeg", true));
        context.create().resource("/content/dam/folder", "jcr:primaryType", "sling:OrderedFolder");
        resolver.commit();
        assertNull(manager.createAsset("/content/dam/folder", bytes("x"), "image/jpeg", true));
    }

    @Test
    void findsAssetsByIdentifier() throws Exception {
        manager.createAsset("/content/dam/id.jpg", bytes("x"), "image/jpeg", true);
        Node node = resolver.getResource("/content/dam/id.jpg").adaptTo(Node.class);
        Asset byId = manager.getAssetByIdentifier(node.getIdentifier());
        assertNotNull(byId);
        assertEquals("/content/dam/id.jpg", byId.getPath());
        assertNull(manager.getAssetByIdentifier("does-not-exist"));
    }
}
