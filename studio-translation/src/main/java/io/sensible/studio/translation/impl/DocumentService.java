package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationConstants;
import com.adobe.granite.translation.api.TranslationException;
import com.adobe.granite.translation.api.TranslationResult;
import com.adobe.granite.translation.api.TranslationService;
import com.adobe.granite.translation.api.TranslationServiceInfo;
import io.sensible.studio.translation.api.DocumentTranslator;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A connector that translates documents; AEM's string methods go through {@link #translateDocument}. */
abstract class DocumentService implements TranslationService, DocumentTranslator {

    private final TranslationServiceInfo info;
    private String category = "general";

    DocumentService(TranslationServiceInfo info) {
        this.info = info;
    }

    @Override
    public TranslationServiceInfo getTranslationServiceInfo() {
        return info;
    }

    @Override
    public Map<String, String> supportedLanguages() {
        return Languages.common();
    }

    @Override
    public boolean isDirectionSupported(String sourceLanguage, String targetLanguage) {
        return targetLanguage != null && !targetLanguage.isEmpty() && !targetLanguage.equalsIgnoreCase(sourceLanguage);
    }

    @Override
    public String detectLanguage(String detectSource, TranslationConstants.ContentType contentType) throws TranslationException {
        return null;
    }

    @Override
    public TranslationResult translateString(String sourceString, String sourceLanguage, String targetLanguage,
                                             TranslationConstants.ContentType contentType, String contentCategory)
            throws TranslationException {
        return translateArray(new String[]{sourceString}, sourceLanguage, targetLanguage, contentType, contentCategory)[0];
    }

    @Override
    public TranslationResult[] translateArray(String[] sources, String sourceLanguage, String targetLanguage,
                                              TranslationConstants.ContentType contentType, String contentCategory)
            throws TranslationException {
        boolean html = contentType == TranslationConstants.ContentType.HTML;
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) segments.add(new Segment("s" + i, sources[i] == null ? "" : sources[i], html, null));
        List<SegmentResult> results = translateDocument(new TranslationRequest(sourceLanguage, targetLanguage, segments));
        TranslationResult[] out = new TranslationResult[sources.length];
        for (int i = 0; i < sources.length; i++) {
            SegmentResult r = results.get(i);
            if (!r.isTranslated()) {
                throw new TranslationException("Segment " + i + ": " + r.getError(), TranslationException.ErrorCode.REQUEST_FAILED);
            }
            out[i] = new BasicResult(r.getText(), sources[i], sourceLanguage, targetLanguage, contentType,
                    contentCategory == null ? category : contentCategory);
        }
        return out;
    }

    @Override
    public String getDefaultCategory() {
        return category;
    }

    @Override
    public void setDefaultCategory(String defaultCategory) {
        this.category = defaultCategory;
    }

    static TranslationServiceInfo info(String name, String label, String attribution) {
        return new TranslationServiceInfo() {
            @Override public String getTranslationServiceName() { return name; }
            @Override public String getTranslationServiceLabel() { return label; }
            @Override public String getTranslationServiceAttribution() { return attribution; }
            @Override public TranslationConstants.TranslationMethod getSupportedTranslationMethod() {
                return TranslationConstants.TranslationMethod.MACHINE_TRANSLATION;
            }
            @Override public String getServiceCloudConfigRootPath() { return "translation/" + name; }
        };
    }
}
