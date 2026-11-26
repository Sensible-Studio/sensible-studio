/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.components;

import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.ValueMap;

/**
 * A component definition: the {@code cq:Component} resource a resource type points to
 * (for example {@code /apps/wknd/components/title}).
 */
public interface Component extends Adaptable {

    /** @return absolute path of the component definition */
    String getPath();

    /** @return component name (last path segment) */
    String getName();

    /** @return {@code jcr:title}, or {@code null} */
    String getTitle();

    /** @return {@code jcr:description}, or {@code null} */
    String getDescription();

    /** @return properties of the component definition */
    ValueMap getProperties();

    /** @return the resource type that addresses this component, e.g. {@code wknd/components/title} */
    String getResourceType();

    /** @return the component named by {@code sling:resourceSuperType}, or {@code null} */
    Component getSuperComponent();

    /** @return {@code componentGroup}, or {@code null} */
    String getComponentGroup();

    /** @return {@code true} if the current user may read the definition */
    boolean isAccessible();

    /** @return value of {@code cq:isContainer} */
    boolean isContainer();

    /** @return {@code true} if the component has a dialog and can be edited */
    boolean isEditable();
}
