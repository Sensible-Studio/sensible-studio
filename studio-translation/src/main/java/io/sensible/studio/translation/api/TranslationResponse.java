package io.sensible.studio.translation.api;

import java.util.Collections;
import java.util.List;

/** The result of a {@link Translator#translate}: one result per segment, in the request's order. */
public final class TranslationResponse {

    private final String provider;
    private final String attribution;
    private final List<SegmentResult> results;

    private final int fromMemory;

    public TranslationResponse(String provider, String attribution, List<SegmentResult> results) {
        this(provider, attribution, results, 0);
    }

    public TranslationResponse(String provider, String attribution, List<SegmentResult> results, int fromMemory) {
        this.provider = provider;
        this.attribution = attribution;
        this.results = Collections.unmodifiableList(results);
        this.fromMemory = fromMemory;
    }

    /** How many results came from the translation memory instead of the connector. */
    public int getFromMemory() {
        return fromMemory;
    }

    /** The connector that translated. */
    public String getProvider() {
        return provider;
    }

    /** e.g. "Translated by Claude (claude-sonnet-5-5)". */
    public String getAttribution() {
        return attribution;
    }

    public List<SegmentResult> getResults() {
        return results;
    }

    public SegmentResult get(String id) {
        for (SegmentResult r : results) if (r.getId().equals(id)) return r;
        return null;
    }

    public boolean isComplete() {
        for (SegmentResult r : results) if (!r.isTranslated()) return false;
        return true;
    }
}
