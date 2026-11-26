/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.policies;

import org.apache.sling.api.adapter.Adaptable;

/**
 * The link between a position in a template and a {@link ContentPolicy}. Stored under
 * {@code <template>/policies/jcr:content} with a {@code cq:policy} property.
 */
public interface ContentPolicyMapping extends Adaptable {

    /** @return absolute path of the mapping resource */
    String getPath();

    /** @return the referenced policy, or {@code null} if it cannot be resolved */
    ContentPolicy getPolicy();
}
