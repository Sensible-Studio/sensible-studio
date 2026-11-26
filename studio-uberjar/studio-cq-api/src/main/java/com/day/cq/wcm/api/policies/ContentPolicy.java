/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.policies;

import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.ValueMap;

/**
 * A content policy: the design configuration of a component, stored under
 * {@code /conf/<site>/settings/wcm/policies}.
 */
public interface ContentPolicy extends Adaptable {

    /** @return absolute path of the policy resource */
    String getPath();

    /** @return policy name */
    String getName();

    /** @return {@code jcr:title} or {@code null} */
    String getTitle();

    /** @return {@code jcr:description} or {@code null} */
    String getDescription();

    /** @return the policy properties (what Core Components read as their style) */
    ValueMap getProperties();
}
