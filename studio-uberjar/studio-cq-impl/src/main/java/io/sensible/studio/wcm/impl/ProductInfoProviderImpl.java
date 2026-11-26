/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.osgi.framework.Version;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import com.adobe.granite.license.ProductInfo;
import com.adobe.granite.license.ProductInfoProvider;

/**
 * Identifies the runtime as Sensible Studio. Components use the version only for feature detection
 * ("is the platform at least X?"), so it is configurable as the API compatibility level Sensible Studio
 * offers, independent of Sensible Studio's own release number.
 */
@Component(service = ProductInfoProvider.class)
@Designate(ocd = ProductInfoProviderImpl.Config.class)
public class ProductInfoProviderImpl implements ProductInfoProvider {

    @ObjectClassDefinition(name = "Sensible Studio Product Info")
    public @interface Config {
        @AttributeDefinition(name = "API compatibility level",
                description = "Version reported to components for feature detection")
        String compatibilityVersion() default "6.5.0";
    }

    private volatile ProductInfo info;

    @Activate
    @Modified
    void activate(Config config) {
        Version v = Version.parseVersion(config.compatibilityVersion());
        info = new ProductInfo() {
            @Override
            public String getName() {
                return "Sensible Studio";
            }

            @Override
            public Version getVersion() {
                return v;
            }

            @Override
            public String getShortName() {
                return "Sensible Studio";
            }
        };
    }

    @Override
    public ProductInfo getProductInfo() {
        return info;
    }
}
