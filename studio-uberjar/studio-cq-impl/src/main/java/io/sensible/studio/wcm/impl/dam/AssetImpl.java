/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.dam;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.RenditionPicker;

/**
 * {@link Asset} over the DAM content model:
 * {@code <asset>/jcr:content/renditions/<name>} (nt:file) and {@code <asset>/jcr:content/metadata}.
 */
public final class AssetImpl extends SlingAdaptable implements Asset {

    private final Resource resource;

    public AssetImpl(Resource resource) {
        this.resource = resource;
    }

    public static boolean isAsset(Resource r) {
        return r != null && DamConstants.NT_DAM_ASSET.equals(r.getValueMap().get("jcr:primaryType", String.class));
    }

    private Resource content() {
        return resource.getChild("jcr:content");
    }

    private ValueMap metadata() {
        Resource md = resource.getChild("jcr:content/" + DamConstants.METADATA_FOLDER);
        return md == null ? ValueMap.EMPTY : md.getValueMap();
    }

    @Override
    public String getPath() {
        return resource.getPath();
    }

    @Override
    public String getName() {
        return resource.getName();
    }

    @Override
    public String getID() {
        String uuid = resource.getValueMap().get("jcr:uuid", String.class);
        return uuid != null ? uuid : resource.getPath();
    }

    @Override
    public long getLastModified() {
        Resource c = content();
        Calendar cal = c == null ? null : c.getValueMap().get("jcr:lastModified", Calendar.class);
        if (cal == null && c != null) {
            cal = c.getValueMap().get("cq:lastModified", Calendar.class);
        }
        return cal == null ? 0 : cal.getTimeInMillis();
    }

    @Override
    public String getMimeType() {
        String format = metadata().get(DamConstants.DC_FORMAT, String.class);
        if (format != null) {
            return format;
        }
        Rendition original = getOriginal();
        return original == null ? null : original.getMimeType();
    }

    @Override
    public Object getMetadata(String name) {
        return metadata().get(name);
    }

    @Override
    public String getMetadataValue(String name) {
        Object v = metadata().get(name);
        if (v instanceof Object[]) {
            Object[] arr = (Object[]) v;
            return arr.length == 0 ? null : String.valueOf(arr[0]);
        }
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public String getMetadataValueFromJcr(String name) {
        return getMetadataValue(name);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata()));
    }

    @Override
    public Rendition getOriginal() {
        return getRendition(DamConstants.ORIGINAL_FILE);
    }

    @Override
    public Rendition getRendition(String name) {
        Resource r = resource.getChild("jcr:content/" + DamConstants.RENDITIONS_FOLDER + "/" + name);
        return r == null ? null : new RenditionImpl(r, this);
    }

    @Override
    public Rendition getRendition(RenditionPicker picker) {
        return picker == null ? getOriginal() : picker.getRendition(this);
    }

    @Override
    public List<Rendition> getRenditions() {
        List<Rendition> out = new ArrayList<>();
        Resource folder = resource.getChild("jcr:content/" + DamConstants.RENDITIONS_FOLDER);
        if (folder != null) {
            for (Resource r : folder.getChildren()) {
                out.add(new RenditionImpl(r, this));
            }
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if (type == Resource.class) {
            return (AdapterType) resource;
        }
        return super.adaptTo(type);
    }

    @Override
    public String toString() {
        return "Asset{" + getPath() + "}";
    }
}
