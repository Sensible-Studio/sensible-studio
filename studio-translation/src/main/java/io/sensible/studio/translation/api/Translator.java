package io.sensible.studio.translation.api;

import com.adobe.granite.translation.api.TranslationException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.annotation.versioning.ProviderType;

import java.util.List;
import java.util.Map;

/**
 * Translates documents with a translation connector ({@code TranslationServiceFactory}). Uses a connector's
 * {@link DocumentTranslator} when it has one, its {@code translateArray} otherwise; checks that HTML segments
 * keep their markup, retries the ones that do not, and reports what could not be translated.
 *
 * <p>A request's provider is a connector name ({@code studio-ai} ...) or the path of a
 * {@link CloudConfig}. Without one, content uses the first translation cloud configuration of its site's
 * {@code cq:conf} (then {@code /conf/global}), and the instance's default connector when there is none.
 */
@ProviderType
public interface Translator {

    /** The connectors: {@code name}, {@code label}, {@code available} (configured), {@code default}. */
    List<Map<String, Object>> providers();

    /**
     * The cloud configurations that apply to content (its {@code cq:conf}, then {@code /conf/global}), followed by
     * the connectors: {@code name} (configuration path or connector name), {@code label}, {@code available},
     * {@code problem}, {@code default}, and for configurations {@code cloudConfig: true}, {@code conf}, {@code connector}.
     */
    List<Map<String, Object>> providers(ResourceResolver resolver, String contentPath);

    /** Translates with a connector given by name (no cloud configuration). */
    TranslationResponse translate(TranslationRequest request) throws TranslationException;

    /** Translates content at {@code contentPath}, resolving cloud configurations with {@code resolver}. */
    TranslationResponse translate(ResourceResolver resolver, String contentPath, TranslationRequest request) throws TranslationException;

    /**
     * Remembers translations people approved (source text → translation), so the translation memory prefers them
     * over machine translations from then on.
     */
    void remember(String sourceLanguage, String targetLanguage, Map<String, String> translations);

    /** Translates a short text with a configuration (saved or not) to check it works: {@code ok}, {@code ms}, {@code sample}, {@code label} or {@code error}, {@code code}. */
    Map<String, Object> test(CloudConfig config);
}
