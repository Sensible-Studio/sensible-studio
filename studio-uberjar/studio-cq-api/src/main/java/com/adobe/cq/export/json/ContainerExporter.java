/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.export.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A {@link ComponentExporter} with child items ({@code :items} and {@code :itemsOrder}).
 */
public interface ContainerExporter extends ComponentExporter {

    /** @return child models by name */
    @JsonProperty(ExporterConstants.PN_ITEMS)
    Map<String, ? extends ComponentExporter> getExportedItems();

    /** @return child names in display order */
    @JsonProperty(ExporterConstants.PN_ITEMS_ORDER)
    String[] getExportedItemsOrder();
}
