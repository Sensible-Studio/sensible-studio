/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.dam;

import java.io.InputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.dam.api.DamConstants;

/**
 * Writes the DAM content model:
 * <pre>
 * &lt;asset&gt;                         dam:Asset
 *   jcr:content                   dam:AssetContent   dam:assetState = "processing"
 *     metadata                    nt:unstructured
 *     renditions                  nt:folder
 *       original                  nt:file
 *         jcr:content             oak:Resource       jcr:data, jcr:mimeType, jcr:lastModified
 * </pre>
 * Missing parent folders are created as {@code sling:OrderedFolder}. Renditions and extracted
 * metadata are added later by the Sensible Studio DAM processing job, which reacts to the new original.
 */
public final class AssetManagerImpl implements AssetManager {

    /** Set while renditions are being produced; the processing job switches it to {@code processed}. */
    public static final String PN_ASSET_STATE = "dam:assetState";
    public static final String STATE_PROCESSING = "processing";

    private static final Logger LOG = LoggerFactory.getLogger(AssetManagerImpl.class);

    private final ResourceResolver resolver;

    public AssetManagerImpl(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Asset createAsset(String path, InputStream is, String mimeType, boolean doSave) {
        if (path == null || !path.startsWith("/") || path.endsWith("/") || is == null) {
            return null;
        }
        String name = ResourceUtil.getName(path);
        if (name.isEmpty() || name.startsWith(".") || name.contains("[") || name.contains("]")) {
            return null;
        }
        try {
            Resource parent = ResourceUtil.getOrCreateResource(resolver, ResourceUtil.getParent(path),
                    "sling:OrderedFolder", "sling:OrderedFolder", false);
            Resource asset = parent.getChild(name);
            if (asset == null) {
                asset = resolver.create(parent, name, props("jcr:primaryType", DamConstants.NT_DAM_ASSET));
            } else if (!AssetImpl.isAsset(asset)) {
                LOG.warn("Cannot create asset at {}: a non-asset node is already there", path);
                return null;
            }
            Resource content = child(asset, "jcr:content", DamConstants.NT_DAM_ASSETCONTENT);
            child(content, DamConstants.METADATA_FOLDER, "nt:unstructured");
            Resource renditions = child(content, DamConstants.RENDITIONS_FOLDER, "nt:folder");

            Resource previous = renditions.getChild(DamConstants.ORIGINAL_FILE);
            if (previous != null) {
                resolver.delete(previous);
            }
            Resource original = resolver.create(renditions, DamConstants.ORIGINAL_FILE, props("jcr:primaryType", "nt:file"));
            Map<String, Object> binary = props("jcr:primaryType", "oak:Resource");
            binary.put("jcr:data", is);
            binary.put("jcr:mimeType", mimeType == null ? "application/octet-stream" : mimeType);
            binary.put("jcr:lastModified", Calendar.getInstance());
            resolver.create(original, "jcr:content", binary);

            ModifiableValueMap state = content.adaptTo(ModifiableValueMap.class);
            if (state != null) {
                state.put(PN_ASSET_STATE, STATE_PROCESSING);
                state.put("jcr:lastModified", Calendar.getInstance());
                state.put("jcr:lastModifiedBy", resolver.getUserID());
            }
            if (doSave) {
                resolver.commit();
            }
            return new AssetImpl(asset);
        } catch (PersistenceException | RuntimeException e) {
            LOG.warn("Cannot create asset at {}", path, e);
            resolver.revert();
            return null;
        }
    }

    @Override
    public Asset getAssetByIdentifier(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return null;
        }
        try {
            Node node = session.getNodeByIdentifier(id);
            Resource r = resolver.getResource(node.getPath());
            return AssetImpl.isAsset(r) ? new AssetImpl(r) : null;
        } catch (RepositoryException e) {
            return null;
        }
    }

    @Override
    public boolean assetExists(String assetPath) {
        return assetPath != null && AssetImpl.isAsset(resolver.getResource(assetPath));
    }

    private Resource child(Resource parent, String name, String type) throws PersistenceException {
        Resource existing = parent.getChild(name);
        return existing != null ? existing : resolver.create(parent, name, props("jcr:primaryType", type));
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}
