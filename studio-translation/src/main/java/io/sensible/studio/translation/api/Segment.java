package io.sensible.studio.translation.api;

/** One text to translate: an element value, a component property, a metadata field. */
public final class Segment {

    private final String id;
    private final String text;
    private final boolean html;
    private final String label;

    /**
     * @param id    unique within the document, e.g. {@code master/description}
     * @param text  the source text
     * @param html  whether the text is HTML (markup is kept, only text and alt/title are translated)
     * @param label what the text is, for the translator's context (e.g. "Description"), may be null
     */
    public Segment(String id, String text, boolean html, String label) {
        if (id == null || text == null) throw new IllegalArgumentException("A segment needs an id and a text");
        this.id = id;
        this.text = text;
        this.html = html;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public boolean isHtml() {
        return html;
    }

    public String getLabel() {
        return label;
    }
}
