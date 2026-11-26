/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.translation.api;

/** Constants of the translation framework. */
public final class TranslationConstants {

    private TranslationConstants() {
    }

    /** How a connector translates. */
    public enum TranslationMethod {
        MACHINE_TRANSLATION,
        HUMAN_TRANSLATION
    }

    /** The kind of text sent for translation. */
    public enum ContentType {
        PLAIN,
        HTML
    }

    /** State of a translated item, as stored in {@code cq:translationStatus}. */
    public enum TranslationStatus {
        DRAFT,
        SUBMITTED,
        SCOPE_REQUESTED,
        SCOPE_COMPLETED,
        COMMITTED_FOR_TRANSLATION,
        TRANSLATION_IN_PROGRESS,
        TRANSLATED,
        READY_FOR_REVIEW,
        APPROVED,
        COMPLETE,
        CANCEL,
        ARCHIVE,
        REJECTED,
        ERROR_UPDATE,
        UNKNOWN_STATE
    }
}
