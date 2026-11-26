/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.export.json;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A Sling Model that can be serialized to the JSON model format ({@code .model.json}).
 */
public interface ComponentExporter {

    /** @return the resource type reported as {@code :type} */
    @JsonProperty(ExporterConstants.PN_TYPE)
    String getExportedType();
}
