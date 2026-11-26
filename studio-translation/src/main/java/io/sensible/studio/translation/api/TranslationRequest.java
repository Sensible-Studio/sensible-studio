package io.sensible.studio.translation.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A document to translate: its segments, the languages and what the translator should know about it. */
public final class TranslationRequest {

    private final String sourceLanguage;
    private final String targetLanguage;
    private final List<Segment> segments;
    private String provider;
    private String title;
    private String kind;
    private final Map<String, String> glossary = new LinkedHashMap<>();
    private final List<String> doNotTranslate = new ArrayList<>();
    private String instructions;
    private boolean useMemory = true;

    /**
     * @param sourceLanguage code such as {@code en}; null to let the translator detect it
     * @param targetLanguage code such as {@code fr} or {@code de_ch}
     */
    public TranslationRequest(String sourceLanguage, String targetLanguage, List<Segment> segments) {
        if (targetLanguage == null || targetLanguage.isEmpty()) throw new IllegalArgumentException("A target language is needed");
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    /** The connector (factory name, or cloud configuration path); null for the default one. */
    public String getProvider() {
        return provider;
    }

    public TranslationRequest provider(String provider) {
        this.provider = provider;
        return this;
    }

    /** The document's title, for context. */
    public String getTitle() {
        return title;
    }

    public TranslationRequest title(String title) {
        this.title = title;
        return this;
    }

    /** What the document is, e.g. "content fragment (model Adventure)". */
    public String getKind() {
        return kind;
    }

    public TranslationRequest kind(String kind) {
        this.kind = kind;
        return this;
    }

    /** Required translations of terms, source → target. */
    public Map<String, String> getGlossary() {
        return glossary;
    }

    /** Terms kept as they are (brand and product names). */
    public List<String> getDoNotTranslate() {
        return doNotTranslate;
    }

    /** Extra guidance, e.g. tone of voice. */
    public String getInstructions() {
        return instructions;
    }

    public TranslationRequest instructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /** Whether texts translated before into the language are taken from the translation memory (default true). */
    public boolean isUseMemory() {
        return useMemory;
    }

    public TranslationRequest useMemory(boolean useMemory) {
        this.useMemory = useMemory;
        return this;
    }
}
