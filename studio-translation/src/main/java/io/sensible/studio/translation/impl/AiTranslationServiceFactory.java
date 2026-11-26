package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationConstants;
import com.adobe.granite.translation.api.TranslationException;
import com.adobe.granite.translation.api.TranslationService;
import com.adobe.granite.translation.api.TranslationServiceFactory;
import io.sensible.studio.translation.api.CloudConfig;
import io.sensible.studio.translation.api.CloudConfigurable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.List;

/**
 * Machine translation with a language model: Anthropic's Claude (default) or any OpenAI-compatible chat API,
 * including local models (Ollama, vLLM, LM Studio). Settings come from this OSGi configuration (the instance's
 * {@code studio-ai} connector) or from translation cloud configurations in {@code /conf}. API keys never live in the
 * repository: cloud configurations refer to a secret ({@code $[secret:NAME]}: a "Sensible Studio Secret" configuration or an
 * environment variable); the default is the {@code ANTHROPIC_API_KEY} / {@code OPENAI_API_KEY} environment variable.
 */
@Component(service = TranslationServiceFactory.class, configurationPolicy = ConfigurationPolicy.OPTIONAL,
        property = "translationFactory=" + AiTranslationServiceFactory.NAME)
@Designate(ocd = AiTranslationServiceFactory.Config.class)
public class AiTranslationServiceFactory implements TranslationServiceFactory, CloudConfigurable {

    static final String NAME = "studio-ai";
    static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    @ObjectClassDefinition(name = "Sensible Studio AI Translation",
            description = "Machine translation with a language model (Anthropic Claude, or an OpenAI-compatible API such as a local Ollama).")
    public @interface Config {
        @AttributeDefinition(name = "API", options = {
                @Option(label = "Anthropic (Claude)", value = "anthropic"),
                @Option(label = "OpenAI-compatible (OpenAI, Azure, Ollama, vLLM ...)", value = "openai"),
                @Option(label = "OpenRouter (models of many providers)", value = "openrouter")})
        String api() default "anthropic";

        @AttributeDefinition(name = "Endpoint", description = "Empty for the API's default. Ollama: http://localhost:11434/v1/chat/completions")
        String endpoint() default "";

        @AttributeDefinition(name = "Model", description = "e.g. claude-sonnet-5-5, claude-haiku-5-5, gpt-4.1, llama3.1; OpenRouter: openai/gpt-4o, anthropic/claude-sonnet-4.6 (empty: the account's default)")
        String model() default DEFAULT_MODEL;

        @AttributeDefinition(name = "API key", type = AttributeType.PASSWORD,
                description = "A secret reference ($[secret:NAME], $[env:NAME]) or the key. Empty: the ANTHROPIC_API_KEY or OPENAI_API_KEY environment variable. Not needed for local servers.")
        String apiKey() default "";

        @AttributeDefinition(name = "Maximum output tokens per request")
        int maxTokens() default 16000;

        @AttributeDefinition(name = "Maximum source characters per request")
        int maxChars() default 12000;

        @AttributeDefinition(name = "Timeout (seconds)")
        int timeoutSeconds() default 180;

        @AttributeDefinition(name = "Guidance", description = "Tone and style for all translations, e.g. 'Friendly, informal; address readers directly.'")
        String instructions() default "";

        @AttributeDefinition(name = "Do not translate", description = "Brand and product names kept as they are.")
        String[] doNotTranslate() default {};

        @AttributeDefinition(name = "Extra headers", description = "Name: value, e.g. api-key: $[secret:AZURE_OPENAI_API_KEY] for Azure OpenAI.")
        String[] headers() default {};
    }

    private volatile Config config;

    @Reference
    volatile Secrets secrets;

    @Activate
    @Modified
    void activate(Config config) {
        this.config = config;
    }

    /** The instance-wide connector (OSGi configuration "Sensible Studio AI Translation"); a cloud configuration path is read by Sensible Studio, see {@link CloudConfigurable}. */
    @Override
    public TranslationService createTranslationService(TranslationConstants.TranslationMethod method, String cloudConfigPath)
            throws TranslationException {
        Config c = config;
        if (c == null) throw new TranslationException("Sensible Studio AI Translation is not configured", TranslationException.ErrorCode.NO_ENGINE);
        return create(c.api(), c.endpoint(), c.model(), c.apiKey(), c.headers(), true, c.maxTokens(), c.maxChars(), c.timeoutSeconds(),
                c.instructions(), c.doNotTranslate(), null, "the OSGi configuration \"Sensible Studio AI Translation\"");
    }

    /** A translation cloud configuration: its settings, with the API key as a secret reference. */
    @Override
    public TranslationService createTranslationService(CloudConfig cc) throws TranslationException {
        Config c = config;
        return create(first(cc.get("api"), c == null ? null : c.api()), cc.get("endpoint"), cc.get("model"), cc.get("apiKey"),
                cc.getArray("headers"), false,
                cc.getInt("maxTokens", c == null ? 16000 : c.maxTokens()), cc.getInt("maxChars", c == null ? 12000 : c.maxChars()),
                cc.getInt("timeoutSeconds", c == null ? 180 : c.timeoutSeconds()), cc.get("instructions"), cc.getArray("doNotTranslate"),
                cc.getTitle(), "the cloud configuration \"" + cc.getTitle() + "\"");
    }

    private TranslationService create(String api, String endpoint, String model, String apiKey, String[] headers, boolean literalKeyAllowed, int maxTokens,
                                      int maxChars, int timeoutSeconds, String instructions, String[] doNotTranslate, String title,
                                      String where) throws TranslationException {
        Built b = build(api, endpoint, model, apiKey, headers, literalKeyAllowed, maxTokens, timeoutSeconds, title, where);
        return new AiTranslationService(DocumentService.info(NAME, b.label, "Translated by " + b.engine), b.client, maxChars > 0 ? maxChars : 12000,
                instructions, doNotTranslate == null ? new String[0] : doNotTranslate);
    }

    /** A model client and how it is named ("OpenRouter (anthropic/claude-sonnet-4.6)"). */
    static final class Built {
        final LlmClient client;
        final String engine;
        final String label;

        Built(LlmClient client, String engine, String label) {
            this.client = client;
            this.engine = engine;
            this.label = label;
        }
    }

    /**
     * The model of a translation cloud configuration, for other work with the same connector (Generate Variations):
     * its API, model and key, checked as for translating.
     */
    Built build(CloudConfig cc) throws TranslationException {
        Config c = config;
        return build(first(cc.get("api"), c == null ? null : c.api()), cc.get("endpoint"), cc.get("model"), cc.get("apiKey"),
                cc.getArray("headers"), false, cc.getInt("maxTokens", c == null ? 16000 : c.maxTokens()),
                cc.getInt("timeoutSeconds", c == null ? 180 : c.timeoutSeconds()), cc.getTitle(), "the cloud configuration \"" + cc.getTitle() + "\"");
    }

    private Built build(String api, String endpoint, String model, String apiKey, String[] headers, boolean literalKeyAllowed, int maxTokens,
                        int timeoutSeconds, String title, String where) throws TranslationException {
        Secrets s = secrets == null ? new Secrets() : secrets;
        LlmClient.Kind kind = kindOf(api);
        boolean anthropic = kind == LlmClient.Kind.ANTHROPIC;
        String envName = defaultKeyVariable(kind);
        boolean local = kind == LlmClient.Kind.OPENAI && isLocal(endpoint);
        for (String h : headers == null ? new String[0] : headers) {
            String problem = Headers.problem(h, literalKeyAllowed);
            if (problem != null) {
                throw new TranslationException(problem + " (" + where + ")", Headers.split(h) != null && Headers.carriesCredentials(Headers.split(h)[0])
                        ? TranslationException.ErrorCode.MISSING_CREDENTIALS : TranslationException.ErrorCode.NO_ENGINE);
            }
        }
        List<String> missing = new ArrayList<>();
        Map<String, String> extra = Headers.resolve(headers, s, missing);
        if (!missing.isEmpty()) {
            throw new TranslationException("The secret " + missing.get(0) + " of " + where + " has no value on this instance: add a \"Sensible Studio Secret\" "
                    + "configuration with that name, or set the environment variable", TranslationException.ErrorCode.MISSING_CREDENTIALS);
        }
        boolean authHeader = Headers.hasAuth(headers);
        String key;
        String raw = apiKey == null ? "" : apiKey.trim();
        if (raw.isEmpty()) {
            key = authHeader ? null : s.env(envName);
        } else if (Secrets.isEncrypted(raw)) {
            key = s.resolve(raw);
            if (key == null) {
                throw new TranslationException("The API key of " + where + " was encrypted on another instance (or its master key is gone): "
                        + "enter the key again in its cloud configuration", TranslationException.ErrorCode.MISSING_CREDENTIALS);
            }
        } else if (Secrets.isReference(raw)) {
            key = s.resolve(raw);
            if (key == null && !local) {
                throw new TranslationException("The secret " + raw + " of " + where + " has no value on this instance: add a \"Sensible Studio Secret\" "
                        + "configuration with that name, or set the environment variable", TranslationException.ErrorCode.MISSING_CREDENTIALS);
            }
        } else if (literalKeyAllowed) {
            key = raw;
        } else {
            throw new TranslationException(where + " holds an API key itself; keys stay out of the repository: use $[secret:NAME]",
                    TranslationException.ErrorCode.MISSING_CREDENTIALS);
        }
        if ((key == null || key.isEmpty()) && !local && !authHeader) {
            throw new TranslationException("No API key for " + where + ": refer to a secret ($[secret:NAME]) or set " + envName,
                    TranslationException.ErrorCode.MISSING_CREDENTIALS);
        }
        String m = model == null || model.trim().isEmpty() ? (kind == LlmClient.Kind.OPENROUTER ? null : anthropic ? DEFAULT_MODEL : null) : model.trim();
        if (m == null && kind == LlmClient.Kind.OPENAI) {
            throw new TranslationException("Choose a model for " + where, TranslationException.ErrorCode.NO_ENGINE);
        }
        LlmClient client = new LlmClient(kind, endpoint, m, key,
                maxTokens > 0 ? maxTokens : 16000, Duration.ofSeconds(Math.max(10, timeoutSeconds)), extra);
        String engine = engineName(kind, endpoint) + " (" + (m == null ? "default model" : m) + ")";
        String label = title == null ? engine : title + " \u00b7 " + engine;
        return new Built(client, engine, label);
    }

    /** Claude, OpenRouter, or the host of an OpenAI-compatible API (api.mistral.ai, localhost:11434 ...). */
    static String engineName(LlmClient.Kind kind, String endpoint) {
        if (kind == LlmClient.Kind.OPENROUTER) return "OpenRouter";
        String host = null;
        try {
            host = endpoint == null || endpoint.trim().isEmpty() ? null : java.net.URI.create(endpoint.trim()).getAuthority();
        } catch (IllegalArgumentException ignored) {
            // shown without the host
        }
        // another server speaking Anthropic's protocol (e.g. DeepSeek) is named by its host, not as Claude
        if (kind == LlmClient.Kind.ANTHROPIC) return host == null || "api.anthropic.com".equals(host) ? "Claude" : host;
        return host == null || "api.openai.com".equals(host) ? "OpenAI" : host;
    }

    static LlmClient.Kind kindOf(String api) {
        if ("openai".equalsIgnoreCase(api)) return LlmClient.Kind.OPENAI;
        if ("openrouter".equalsIgnoreCase(api)) return LlmClient.Kind.OPENROUTER;
        return LlmClient.Kind.ANTHROPIC;
    }

    /** The environment variable a connector uses when no key is configured. */
    static String defaultKeyVariable(LlmClient.Kind kind) {
        return kind == LlmClient.Kind.ANTHROPIC ? "ANTHROPIC_API_KEY" : kind == LlmClient.Kind.OPENROUTER ? "OPENROUTER_API_KEY" : "OPENAI_API_KEY";
    }

    static boolean isLocal(String endpoint) {
        return endpoint != null && endpoint.trim().matches("https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?(/.*)?");
    }

    private static String first(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    @Override
    public List<TranslationConstants.TranslationMethod> getSupportedTranslationMethods() {
        return Collections.singletonList(TranslationConstants.TranslationMethod.MACHINE_TRANSLATION);
    }

    @Override
    public Class<?> getServiceCloudConfigClass() {
        return CloudConfig.class;
    }

    @Override
    public String getServiceFactoryName() {
        return NAME;
    }
}
