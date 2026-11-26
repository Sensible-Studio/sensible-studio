/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import org.apache.sling.api.resource.Resource;

/**
 * A page content resource merged with its editable template's structure. Sensible Studio resolves template
 * structure in the editor; at render time resources are used as stored.
 */
public interface TemplatedResource extends Resource {
}
