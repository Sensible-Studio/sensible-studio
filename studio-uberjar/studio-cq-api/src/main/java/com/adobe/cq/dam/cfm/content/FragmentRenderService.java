/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm.content;

import org.apache.sling.api.resource.Resource;

/**
 * Renders a content fragment component to HTML.
 */
public interface FragmentRenderService {

    /** @return the rendered HTML, or {@code null} */
    String render(Resource resource);
}
