package io.sensible.studio.translation.api;

import com.adobe.granite.translation.api.TranslationException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.annotation.versioning.ConsumerType;

import java.util.List;
import java.util.Map;

/**
 * Translates content that pages refer to and that is not a page, e.g. content fragments. When a page is translated
 * with its references, Sensible Studio asks the registered translators for each path the page refers to; the first that
 * accepts it makes or updates the language copy, so the page's copy can point to it.
 */
@ConsumerType
public interface ReferenceTranslator {

    /** Whether this translator translates the content at {@code path}. */
    boolean accepts(ResourceResolver resolver, String path);

    /**
     * Creates or updates the copy of {@code path} (and what it refers to, if the translator does that) in
     * {@code target}. Results like a page's: {@code source}, {@code path} (the copy), {@code language},
     * {@code created}, {@code translated}, {@code unchanged}, {@code failed}, {@code status}.
     */
    List<Map<String, Object>> translate(ResourceResolver resolver, String path, String target, String provider, boolean force)
            throws TranslationException;

    /**
     * Approves the copy at {@code path} (a review workflow's decision) on behalf of {@code by}, feeding the
     * translation memory; false when this translator does not handle it.
     */
    default boolean approve(ResourceResolver resolver, String path, String by) throws Exception {
        return false;
    }

    /**
     * The texts of a copy this translator made, next to its source's (for review and XLIFF export): {@code path},
     * {@code title}, {@code sourceLanguage}, {@code targetLanguage} and {@code segments}, each with {@code id},
     * {@code label}, {@code html}, {@code source}, {@code translation}; null when it does not handle the path.
     */
    default Map<String, Object> review(ResourceResolver resolver, String path) {
        return null;
    }

    /**
     * Saves corrected texts of a copy (by segment id, already checked by the caller) on behalf of the session's
     * user; they count as reviewed.
     */
    default void saveReview(ResourceResolver resolver, String path, Map<String, String> translations) throws Exception {
        throw new UnsupportedOperationException(path + " cannot be corrected here");
    }
}
