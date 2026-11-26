package io.sensible.studio.translation.api;

/** The translation of one segment, or why there is none (then {@link #getText()} is the source text). */
public final class SegmentResult {

    private final String id;
    private final String text;
    private final String error;

    private SegmentResult(String id, String text, String error) {
        this.id = id;
        this.text = text;
        this.error = error;
    }

    public static SegmentResult translated(String id, String text) {
        return new SegmentResult(id, text, null);
    }

    public static SegmentResult failed(String id, String sourceText, String error) {
        return new SegmentResult(id, sourceText, error == null ? "Not translated" : error);
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public boolean isTranslated() {
        return error == null;
    }

    public String getError() {
        return error;
    }
}
