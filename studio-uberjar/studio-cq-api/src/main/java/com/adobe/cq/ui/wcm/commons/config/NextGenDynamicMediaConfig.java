/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.ui.wcm.commons.config;

/**
 * Configuration of remote (next generation) dynamic media. Sensible Studio reports it as disabled.
 */
public interface NextGenDynamicMediaConfig {

    boolean enabled();

    String getRepositoryId();

    String getAssetMetadataPath();

    String getImageDeliveryBasePath();

    String getVideoDeliveryPath();

    String getAssetOriginalBinaryDeliveryPath();
}
