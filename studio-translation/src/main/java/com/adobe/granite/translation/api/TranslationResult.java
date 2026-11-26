/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.translation.api;

/** One translated string. */
public interface TranslationResult {

    String getTranslation();

    String getSourceString();

    String getSourceLanguage();

    String getTargetLanguage();

    TranslationConstants.ContentType getContentType();

    String getCategory();

    /** A connector's quality estimate, 0 when it has none. */
    int getRating();

    String getUserId();
}
