/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.commons.policy;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.designer.Cell;
import com.day.cq.wcm.api.designer.Design;
import com.day.cq.wcm.api.designer.Style;
import com.day.cq.wcm.api.policies.ContentPolicy;

/**
 * A {@link Style} whose properties are those of a content policy. With no policy it behaves as an
 * empty style, so callers never need a null check.
 */
public class ContentPolicyStyle implements Style {

    private final ContentPolicy policy;
    private final Cell cell;
    private final ValueMap props;

    /**
     * @param policy the policy, may be {@code null}
     * @param cell   the cell, may be {@code null}
     */
    public ContentPolicyStyle(ContentPolicy policy, Cell cell) {
        this.policy = policy;
        this.cell = cell;
        ValueMap p = policy == null ? null : policy.getProperties();
        this.props = p == null ? ValueMap.EMPTY : p;
    }

    /** @return the backing policy, or {@code null} */
    public ContentPolicy getPolicy() {
        return policy;
    }

    @Override
    public Design getDesign() {
        return null;
    }

    @Override
    public String getPath() {
        return policy == null ? null : policy.getPath();
    }

    @Override
    public Cell getCell() {
        return cell;
    }

    @Override
    public Resource getDefiningResource(String relPath) {
        Resource r = policy == null ? null : policy.adaptTo(Resource.class);
        return r == null ? null : (relPath == null || relPath.isEmpty() ? r : r.getChild(relPath));
    }

    @Override
    public String getDefiningPath(String name) {
        return props.containsKey(name) ? getPath() : null;
    }

    @Override
    public Style getSubStyle(String relPath) {
        return new ContentPolicyStyle(null, cell);
    }

    // ------------------------------------------------------------- ValueMap

    @Override
    public <T> T get(String name, Class<T> type) {
        return props.get(name, type);
    }

    @Override
    public <T> T get(String name, T defaultValue) {
        return props.get(name, defaultValue);
    }

    @Override
    public int size() {
        return props.size();
    }

    @Override
    public boolean isEmpty() {
        return props.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return props.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return props.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return props.get(key);
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("styles are read-only");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("styles are read-only");
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        throw new UnsupportedOperationException("styles are read-only");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("styles are read-only");
    }

    @Override
    public Set<String> keySet() {
        return props.keySet();
    }

    @Override
    public Collection<Object> values() {
        return props.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return props.entrySet();
    }

    @Override
    public String toString() {
        return "ContentPolicyStyle{" + getPath() + "}";
    }
}
