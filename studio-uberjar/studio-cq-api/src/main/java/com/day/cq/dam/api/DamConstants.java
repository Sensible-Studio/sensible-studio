/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.api;

/**
 * Names of the DAM content model (as stored in {@code /content/dam}).
 */
public final class DamConstants {

    public static final String NT_DAM_ASSET = "dam:Asset";
    public static final String NT_DAM_ASSETCONTENT = "dam:AssetContent";
    public static final String RENDITIONS_FOLDER = "renditions";
    public static final String METADATA_FOLDER = "metadata";
    public static final String ORIGINAL_FILE = "original";
    public static final String PREFIX_ASSET_WEB = "cq5dam.web";
    public static final String PREFIX_ASSET_THUMBNAIL = "cq5dam.thumbnail";
    public static final String MOUNTPOINT_ASSETS = "/content/dam";
    public static final String DC_TITLE = "dc:title";
    public static final String DC_DESCRIPTION = "dc:description";
    public static final String DC_FORMAT = "dc:format";
    public static final String TIFF_IMAGEWIDTH = "tiff:ImageWidth";
    public static final String TIFF_IMAGELENGTH = "tiff:ImageLength";

    private DamConstants() {
    }
}
