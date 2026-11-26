/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.tagging.Tag;

/**
 * Read-only tag backed by a resource below a tag root such as {@code /content/cq:tags}.
 */
final class TagImpl extends SlingAdaptable implements Tag {

    private final Resource resource;
    private final String root;
    private final ValueMap props;

    TagImpl(Resource resource, String root) {
        this.resource = resource;
        this.root = root;
        this.props = resource.getValueMap();
    }

    /** Path relative to the tag root, e.g. {@code wknd/activity/surfing}. */
    private String relative() {
        return resource.getPath().substring(root.length() + 1);
    }

    @Override
    public String getTagID() {
        String rel = relative();
        int slash = rel.indexOf('/');
        return slash < 0 ? rel + ":" : rel.substring(0, slash) + ":" + rel.substring(slash + 1);
    }

    @Override
    public String getLocalTagID() {
        String id = getTagID();
        return id.substring(id.indexOf(':') + 1);
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
    public String getTitle() {
        return props.get("jcr:title", resource.getName());
    }

    @Override
    public String getTitle(Locale locale) {
        if (locale != null) {
            String full = props.get("jcr:title." + locale.toString().toLowerCase(Locale.ROOT), String.class);
            if (full != null) {
                return full;
            }
            String lang = props.get("jcr:title." + locale.getLanguage(), String.class);
            if (lang != null) {
                return lang;
            }
        }
        return getTitle();
    }

    @Override
    public String getDescription() {
        return props.get("jcr:description", String.class);
    }

    @Override
    public Tag getNamespace() {
        String rel = relative();
        int slash = rel.indexOf('/');
        if (slash < 0) {
            return this;
        }
        Resource ns = resource.getResourceResolver().getResource(root + "/" + rel.substring(0, slash));
        return ns == null ? null : new TagImpl(ns, root);
    }

    @Override
    public Tag getParent() {
        if (relative().indexOf('/') < 0) {
            return null;
        }
        Resource parent = resource.getParent();
        return parent == null ? null : new TagImpl(parent, root);
    }

    @Override
    public Iterator<Tag> listChildren() {
        List<Tag> children = new ArrayList<>();
        for (Resource child : resource.getChildren()) {
            if (!child.getName().startsWith("jcr:") && !child.getName().startsWith("rep:")) {
                children.add(new TagImpl(child, root));
            }
        }
        return children.iterator();
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
        return "Tag{" + getTagID() + "}";
    }
}
