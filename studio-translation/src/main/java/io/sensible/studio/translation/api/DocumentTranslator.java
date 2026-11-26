package io.sensible.studio.translation.api;

import com.adobe.granite.translation.api.TranslationException;

import java.util.List;

/**
 * Implemented by a {@code TranslationService} that translates a whole document at once. Answers one result
 * per segment, in order; the caller checks HTML integrity and retries what failed.
 */
public interface DocumentTranslator {

    List<SegmentResult> translateDocument(TranslationRequest request) throws TranslationException;
}
