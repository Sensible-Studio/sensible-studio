/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import org.apache.sling.api.adapter.Adaptable;

import com.day.cq.commons.LabeledResource;

/**
 * A page template: either a static template ({@code /apps/.../templates/x}) or an editable
 * template ({@code /conf/.../settings/wcm/templates/x}).
 */
public interface Template extends Adaptable, LabeledResource {

    /** @return absolute path of the template */
    String getPath();

    /** @return the template name */
    String getName();

    /** @return title, or {@code null} */
    String getTitle();

    /** @return short title, or {@code null} */
    String getShortTitle();

    /** @return description, or {@code null} */
    String getDescription();

    /** @return path of the icon image, or {@code null} */
    String getIconPath();

    /** @return path of the thumbnail image, or {@code null} */
    String getThumbnailPath();

    /** @return ranking used to order templates, {@code Long.MAX_VALUE} if unset */
    Long getRanking();

    /** @return regular expressions of the paths where the template may be used */
    String[] getAllowedPaths();

    /** @return regular expressions of the templates allowed as parent */
    String[] getAllowedParents();

    /** @return regular expressions of the templates allowed as children */
    String[] getAllowedChildren();

    /**
     * @param parentPath path of the prospective parent page
     * @return {@code true} if a page with this template may be created below that path
     */
    boolean isAllowed(String parentPath);

    /** @return {@code true} for editable templates, which have a {@code structure} child */
    boolean hasStructureSupport();

    /** @return path of the initial content resource, or {@code null} */
    String getInitialContentPath();

    /** @return path of the structure content resource, or {@code null} */
    String getStructurePath();

    /** @return path of the policy mapping resource, or {@code null} */
    String getPoliciesPath();
}
