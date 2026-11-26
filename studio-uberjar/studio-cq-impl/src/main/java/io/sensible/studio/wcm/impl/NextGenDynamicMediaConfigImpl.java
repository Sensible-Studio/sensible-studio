/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.osgi.service.component.annotations.Component;

import com.adobe.cq.ui.wcm.commons.config.NextGenDynamicMediaConfig;

/**
 * Remote dynamic media is not available on Sensible Studio; images are served by Sensible Studio's own DAM.
 */
@Component(service = NextGenDynamicMediaConfig.class)
public class NextGenDynamicMediaConfigImpl implements NextGenDynamicMediaConfig {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String getRepositoryId() {
        return null;
    }

    @Override
    public String getAssetMetadataPath() {
        return null;
    }

    @Override
    public String getImageDeliveryBasePath() {
        return null;
    }

    @Override
    public String getVideoDeliveryPath() {
        return null;
    }

    @Override
    public String getAssetOriginalBinaryDeliveryPath() {
        return null;
    }
}
