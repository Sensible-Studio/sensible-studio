/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.translation.api;

import java.util.Map;

/** A translation connector bound to one configuration. Language codes are ISO codes such as {@code fr} or {@code de_ch}. */
public interface TranslationService {

    TranslationServiceInfo getTranslationServiceInfo();

    /** Languages the connector translates, by code, with their names. */
    Map<String, String> supportedLanguages();

    boolean isDirectionSupported(String sourceLanguage, String targetLanguage) throws TranslationException;

    /** The language of a text, as a code; null when it cannot tell. */
    String detectLanguage(String detectSource, TranslationConstants.ContentType contentType) throws TranslationException;

    TranslationResult translateString(String sourceString, String sourceLanguage, String targetLanguage,
                                      TranslationConstants.ContentType contentType, String contentCategory)
            throws TranslationException;

    TranslationResult[] translateArray(String[] sourceStringArr, String sourceLanguage, String targetLanguage,
                                       TranslationConstants.ContentType contentType, String contentCategory)
            throws TranslationException;

    /** Default category (domain) sent with translations, e.g. {@code general}. */
    String getDefaultCategory();

    void setDefaultCategory(String defaultCategory);
}
