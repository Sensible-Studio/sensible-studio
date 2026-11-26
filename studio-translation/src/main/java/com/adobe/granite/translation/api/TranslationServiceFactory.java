/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.translation.api;

import java.util.List;

/** A translation connector, registered as an OSGi service. */
public interface TranslationServiceFactory {

    /** A service for a cloud configuration ({@code /conf/<site>/settings/cloudconfigs/translation/...}), or the default one when null. */
    TranslationService createTranslationService(TranslationConstants.TranslationMethod translationMethod, String cloudConfigPath)
            throws TranslationException;

    List<TranslationConstants.TranslationMethod> getSupportedTranslationMethods();

    /** The class of this connector's cloud configuration, or null. */
    Class<?> getServiceCloudConfigClass();

    /** The connector's name, as cloud configurations refer to it. */
    String getServiceFactoryName();
}
