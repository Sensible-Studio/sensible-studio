/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.translation.api;

/** Describes a translation connector. */
public interface TranslationServiceInfo {

    String getTranslationServiceName();

    String getTranslationServiceLabel();

    /** The text to show for machine translated content, e.g. "Translated by ...". */
    String getTranslationServiceAttribution();

    TranslationConstants.TranslationMethod getSupportedTranslationMethod();

    /** Where this connector's cloud configurations live below {@code /conf/<site>/settings/cloudconfigs}. */
    String getServiceCloudConfigRootPath();
}
