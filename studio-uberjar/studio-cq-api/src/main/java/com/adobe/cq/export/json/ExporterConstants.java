/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.export.json;

/**
 * Names used by the JSON model export. The values are the public {@code .model.json} format.
 */
public final class ExporterConstants {

    public static final String SLING_MODEL_EXPORTER_NAME = "jackson";
    public static final String SLING_MODEL_EXTENSION = "json";
    public static final String SLING_MODEL_SELECTOR = "model";
    public static final String PN_TYPE = ":type";
    public static final String PN_ITEMS = ":items";
    public static final String PN_ITEMS_ORDER = ":itemsOrder";

    private ExporterConstants() {
    }
}
