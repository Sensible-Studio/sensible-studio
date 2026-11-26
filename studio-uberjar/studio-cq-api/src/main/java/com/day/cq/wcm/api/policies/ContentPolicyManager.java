/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.policies;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;

/**
 * Resolves the content policy that applies to a component resource. Obtain one with
 * {@code resourceResolver.adaptTo(ContentPolicyManager.class)}.
 */
public interface ContentPolicyManager {

    /**
     * @param contentResource a component resource inside a page's {@code jcr:content}
     * @return the applicable policy, or {@code null}
     */
    ContentPolicy getPolicy(Resource contentResource);

    /**
     * Same as {@link #getPolicy(Resource)}; the request is accepted for API compatibility.
     */
    ContentPolicy getPolicy(Resource contentResource, SlingHttpServletRequest request);

    /**
     * @param contentResource a component resource inside a page's {@code jcr:content}
     * @return the mapping that applies, or {@code null}
     */
    ContentPolicyMapping getPolicyMapping(Resource contentResource);
}
