/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.dam.scene7.api.constants;

/**
 * Asset types of the Dynamic Media (Scene7) content model.
 */
public enum Scene7AssetType {
    IMAGE("Image"), IMAGE_SET("ImageSet"), VIDEO("Video"), SPIN_SET("SpinSet"), MIXED_MEDIA_SET("MixedMediaSet");

    private final String value;

    Scene7AssetType(String value) {
        this.value = value;
    }

    /** @return the stored value */
    public String getValue() {
        return value;
    }
}
