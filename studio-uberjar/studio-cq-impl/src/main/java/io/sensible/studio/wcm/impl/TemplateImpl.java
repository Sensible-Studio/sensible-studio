/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Template;

/**
 * {@link Template} for both static templates (properties on the template node) and editable
 * templates (properties on {@code jcr:content}, plus {@code structure}, {@code initial} and
 * {@code policies} children).
 */
final class TemplateImpl extends SlingAdaptable implements Template {

    static final String NN_STRUCTURE = "structure";
    static final String NN_INITIAL = "initial";
    static final String NN_POLICIES = "policies";

    private final Resource resource;

    TemplateImpl(Resource resource) {
        this.resource = resource;
    }

    /** Template node first, then its jcr:content, so both template styles read the same. */
    private <T> T prop(String name, Class<T> type) {
        T value = resource.getValueMap().get(name, type);
        if (value != null) {
            return value;
        }
        Resource content = resource.getChild(NameConstants.NN_CONTENT);
        return content == null ? null : content.getValueMap().get(name, type);
    }

    private String childPath(String name) {
        Resource child = resource.getChild(name);
        return child == null ? null : child.getPath();
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
        return prop(NameConstants.PN_TITLE, String.class);
    }

    @Override
    public String getShortTitle() {
        return prop(NameConstants.PN_SHORT_TITLE, String.class);
    }

    @Override
    public String getDescription() {
        return prop(NameConstants.PN_DESCRIPTION, String.class);
    }

    @Override
    public String getIconPath() {
        return childPath("icon.png");
    }

    @Override
    public String getThumbnailPath() {
        return childPath("thumbnail.png");
    }

    @Override
    public Long getRanking() {
        Long ranking = prop(NameConstants.PN_RANKING, Long.class);
        return ranking == null ? Long.MAX_VALUE : ranking;
    }

    @Override
    public String[] getAllowedPaths() {
        return array(NameConstants.PN_ALLOWED_PATHS);
    }

    @Override
    public String[] getAllowedParents() {
        return array(NameConstants.PN_ALLOWED_PARENTS);
    }

    @Override
    public String[] getAllowedChildren() {
        return array(NameConstants.PN_ALLOWED_CHILDREN);
    }

    private String[] array(String name) {
        String[] values = prop(name, String[].class);
        return values == null ? new String[0] : values;
    }

    /**
     * An empty {@code allowedPaths} allows every path. Editable templates are normally restricted
     * by {@code cq:allowedTemplates} on the site's pages, which is the page manager's concern.
     */
    @Override
    public boolean isAllowed(String parentPath) {
        String[] patterns = getAllowedPaths();
        if (patterns.length == 0) {
            return true;
        }
        for (String pattern : patterns) {
            try {
                if (Pattern.matches(pattern, parentPath)) {
                    return true;
                }
            } catch (PatternSyntaxException ignored) {
                // a broken pattern in content must not break page creation elsewhere
            }
        }
        return false;
    }

    @Override
    public boolean hasStructureSupport() {
        return resource.getChild(NN_STRUCTURE) != null;
    }

    @Override
    public String getInitialContentPath() {
        return childPath(NN_INITIAL);
    }

    @Override
    public String getStructurePath() {
        return childPath(NN_STRUCTURE);
    }

    @Override
    public String getPoliciesPath() {
        return childPath(NN_POLICIES);
    }

    /** {@code /conf/wknd} for {@code /conf/wknd/settings/wcm/templates/x}, else {@code null}. */
    String getConfRoot() {
        String path = getPath();
        int idx = path.indexOf("/settings/wcm/templates/");
        return idx < 0 ? null : path.substring(0, idx);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if (type == Resource.class) {
            return (AdapterType) resource;
        }
        if (type == ValueMap.class) {
            return (AdapterType) resource.getValueMap();
        }
        return super.adaptTo(type);
    }

    @Override
    public String toString() {
        return "Template{" + getPath() + "}";
    }
}
