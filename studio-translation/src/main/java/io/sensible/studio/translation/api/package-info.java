/**
 * Sensible Studio's translation API: {@link io.sensible.studio.translation.api.Translator} translates documents (the
 * translatable texts of a fragment or page, with their context) with the configured connector.
 * {@link io.sensible.studio.translation.api.DocumentTranslator} is the extension a connector implements, next to
 * {@code TranslationService}, to translate a whole document at once (consistent terms, context) instead of
 * string by string.
 */
@Version("1.5.0")
@Export
package io.sensible.studio.translation.api;

import org.osgi.annotation.bundle.Export;
import org.osgi.annotation.versioning.Version;
