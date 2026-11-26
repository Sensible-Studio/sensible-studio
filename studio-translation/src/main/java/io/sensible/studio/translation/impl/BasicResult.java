package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationConstants;
import com.adobe.granite.translation.api.TranslationResult;

final class BasicResult implements TranslationResult {

    private final String translation;
    private final String source;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final TranslationConstants.ContentType contentType;
    private final String category;

    BasicResult(String translation, String source, String sourceLanguage, String targetLanguage,
                TranslationConstants.ContentType contentType, String category) {
        this.translation = translation;
        this.source = source;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.contentType = contentType;
        this.category = category;
    }

    @Override public String getTranslation() { return translation; }
    @Override public String getSourceString() { return source; }
    @Override public String getSourceLanguage() { return sourceLanguage; }
    @Override public String getTargetLanguage() { return targetLanguage; }
    @Override public TranslationConstants.ContentType getContentType() { return contentType; }
    @Override public String getCategory() { return category; }
    @Override public int getRating() { return 0; }
    @Override public String getUserId() { return null; }
}
