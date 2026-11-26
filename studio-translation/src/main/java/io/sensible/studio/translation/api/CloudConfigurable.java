package io.sensible.studio.translation.api;

import com.adobe.granite.translation.api.TranslationException;
import com.adobe.granite.translation.api.TranslationService;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Implemented by a {@code TranslationServiceFactory} that takes its settings from a {@link CloudConfig} Sensible Studio has
 * already read (with the user's session). A connector without it gets the configuration's path through
 * {@code createTranslationService(method, cloudConfigPath)} and reads it itself, as in AEM.
 */
@ConsumerType
public interface CloudConfigurable {

    TranslationService createTranslationService(CloudConfig config) throws TranslationException;
}
