package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationConstants;
import com.adobe.granite.translation.api.TranslationException;
import com.adobe.granite.translation.api.TranslationResult;
import com.adobe.granite.translation.api.TranslationService;
import com.adobe.granite.translation.api.TranslationServiceFactory;
import io.sensible.studio.translation.api.CloudConfig;
import io.sensible.studio.translation.api.CloudConfigurable;
import io.sensible.studio.translation.api.DocumentTranslator;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;
import io.sensible.studio.translation.api.TranslationResponse;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** See {@link Translator}. */
@Component(service = Translator.class)
@Designate(ocd = TranslatorImpl.Config.class)
public class TranslatorImpl implements Translator {

    @ObjectClassDefinition(name = "Sensible Studio Translator", description = "Which translation connector Sensible Studio uses by default.")
    public @interface Config {
        @AttributeDefinition(name = "Default connector", description = "Factory name: studio-ai (language model), or another installed connector's name.")
        String defaultProvider() default AiTranslationServiceFactory.NAME;
    }

    private final List<TranslationServiceFactory> factories = new CopyOnWriteArrayList<>();
    private volatile String defaultProvider = AiTranslationServiceFactory.NAME;

    @Activate
    @Modified
    void activate(Config config) {
        defaultProvider = config.defaultProvider();
    }

    @Reference(service = TranslationServiceFactory.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindFactory(TranslationServiceFactory factory) {
        factories.add(factory);
    }

    void unbindFactory(TranslationServiceFactory factory) {
        factories.remove(factory);
    }

    @Override
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TranslationServiceFactory f : factories) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.getServiceFactoryName());
            try {
                TranslationService s = f.createTranslationService(TranslationConstants.TranslationMethod.MACHINE_TRANSLATION, null);
                m.put("label", s.getTranslationServiceInfo().getTranslationServiceLabel());
                m.put("available", true);
            } catch (TranslationException e) {
                m.put("label", f.getServiceFactoryName());
                m.put("available", false);
                m.put("problem", e.getMessage());
                m.put("code", e.getErrorCode() == null ? null : e.getErrorCode().name());
            }
            m.put("default", f.getServiceFactoryName().equals(defaultProvider));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> providers(ResourceResolver resolver, String contentPath) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<CloudConfig> configs = resolver == null ? new ArrayList<>() : CloudConfigs.forContent(resolver, contentPath);
        for (CloudConfig c : configs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getPath());
            m.put("cloudConfig", true);
            m.put("conf", c.getConf());
            m.put("connector", c.getConnector());
            try {
                m.put("label", service(c).getTranslationServiceInfo().getTranslationServiceLabel());
                m.put("available", true);
            } catch (TranslationException e) {
                m.put("label", c.getTitle());
                m.put("available", false);
                m.put("problem", e.getMessage());
                m.put("code", e.getErrorCode() == null ? null : e.getErrorCode().name());
            }
            m.put("default", out.isEmpty());
            out.add(m);
        }
        boolean configured = !out.isEmpty();
        for (Map<String, Object> m : providers()) {
            if (configured) m.put("default", false);
            out.add(m);
        }
        return out;
    }

    @Override
    public TranslationResponse translate(TranslationRequest request) throws TranslationException {
        return translate(null, null, request);
    }

    @Override
    public TranslationResponse translate(ResourceResolver resolver, String contentPath, TranslationRequest request) throws TranslationException {
        String provider = request.getProvider();
        if (provider != null && provider.startsWith("/")) {
            if (resolver == null) throw new TranslationException("Cloud configurations need a session", TranslationException.ErrorCode.NO_ENGINE);
            CloudConfig c = CloudConfigs.read(resolver.getResource(provider));
            if (c == null) throw new TranslationException("No translation cloud configuration at " + provider, TranslationException.ErrorCode.NO_ENGINE);
            return run(c.getPath(), service(c), request);
        }
        if ((provider == null || provider.isEmpty()) && resolver != null) {
            List<CloudConfig> configs = CloudConfigs.forContent(resolver, contentPath);
            if (!configs.isEmpty()) return run(configs.get(0).getPath(), service(configs.get(0)), request);
        }
        String name = provider == null || provider.isEmpty() ? defaultProvider : provider;
        TranslationServiceFactory factory = factory(name);
        return run(factory.getServiceFactoryName(),
                factory.createTranslationService(TranslationConstants.TranslationMethod.MACHINE_TRANSLATION, null), request);
    }

    @Override
    public Map<String, Object> test(CloudConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            TranslationService service = service(config);
            out.put("label", service.getTranslationServiceInfo().getTranslationServiceLabel());
            List<Segment> one = new ArrayList<>();
            one.add(new Segment("test", TEST_TEXT, false, "Connection test"));
            TranslationResponse r = run(config.getPath(), service, new TranslationRequest("en", "fr", one).title("Connection test").useMemory(false));
            SegmentResult result = r.getResults().get(0);
            out.put("ms", System.currentTimeMillis() - start);
            if (result.isTranslated()) {
                out.put("ok", true);
                out.put("sample", result.getText());
            } else {
                out.put("ok", false);
                out.put("error", result.getError());
                out.put("code", TranslationException.ErrorCode.INVALID_RESPONSE.name());
            }
        } catch (TranslationException e) {
            out.put("ok", false);
            out.put("ms", System.currentTimeMillis() - start);
            out.put("error", e.getMessage());
            out.put("code", e.getErrorCode() == null ? null : e.getErrorCode().name());
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("ms", System.currentTimeMillis() - start);
            out.put("error", String.valueOf(e.getMessage()));
            out.put("code", TranslationException.ErrorCode.GENERAL.name());
        }
        return out;
    }

    static final String TEST_TEXT = "Hello! This sentence checks the translation connection.";

    private TranslationServiceFactory factory(String name) throws TranslationException {
        TranslationServiceFactory factory = null;
        for (TranslationServiceFactory f : factories) if (f.getServiceFactoryName().equals(name)) factory = f;
        if (factory == null) throw new TranslationException("No translation connector " + name, TranslationException.ErrorCode.NO_ENGINE);
        return factory;
    }

    /** A cloud configuration's service: from its settings when the connector takes them, from its path otherwise (as in AEM). */
    private TranslationService service(CloudConfig config) throws TranslationException {
        String connector = config.getConnector() == null || config.getConnector().isEmpty() ? AiTranslationServiceFactory.NAME : config.getConnector();
        TranslationServiceFactory f = factory(connector);
        if (f instanceof CloudConfigurable) return ((CloudConfigurable) f).createTranslationService(config);
        return f.createTranslationService(TranslationConstants.TranslationMethod.MACHINE_TRANSLATION, config.getPath());
    }

    private TranslationResponse run(String providerName, TranslationService service, TranslationRequest request) throws TranslationException {
        if (!service.isDirectionSupported(request.getSourceLanguage(), request.getTargetLanguage())) {
            throw new TranslationException("Cannot translate from " + request.getSourceLanguage() + " to " + request.getTargetLanguage(),
                    TranslationException.ErrorCode.NOT_SUPPORTED_LANG_DIRECTION);
        }

        // only what has text goes to the connector; the rest is kept as it is
        Map<String, SegmentResult> results = new LinkedHashMap<>();
        List<Segment> todo = new ArrayList<>();
        for (Segment s : request.getSegments()) {
            if (Markup.hasText(s.getText(), s.isHtml())) todo.add(s);
            else results.put(s.getId(), SegmentResult.translated(s.getId(), s.getText()));
        }
        // texts translated before into this language: the same translation, without asking the connector
        ResourceResolver memoryResolver = request.isUseMemory() ? memoryResolver() : null;
        TranslationMemory memory = memoryResolver == null ? null : new TranslationMemory(memoryResolver);
        int fromMemory = 0;
        Map<String, String> learned = new LinkedHashMap<>();
        try {
            if (memory != null && !todo.isEmpty()) {
                List<String> texts = new ArrayList<>();
                for (Segment s : todo) texts.add(s.getText());
                Map<String, String> known = memory.lookup(request.getSourceLanguage(), request.getTargetLanguage(), texts);
                List<Segment> rest = new ArrayList<>();
                for (Segment s : todo) {
                    String t = known.get(s.getText());
                    if (t != null && Markup.damage(s.getText(), t, s.isHtml()) == null) {
                        results.put(s.getId(), SegmentResult.translated(s.getId(), t));
                        fromMemory++;
                    } else {
                        rest.add(s);
                    }
                }
                if (fromMemory > 0) memory.used(request.getSourceLanguage(), request.getTargetLanguage(), known.keySet());
                todo = rest;
            }
            translateRest(service, request, todo, results, learned);
            if (memory != null && !learned.isEmpty()) memory.store(request.getSourceLanguage(), request.getTargetLanguage(), learned, providerName);
        } catch (org.apache.sling.api.resource.PersistenceException e) {
            // the memory is a help, not a condition
        } finally {
            if (memoryResolver != null) memoryResolver.close();
        }
        List<SegmentResult> ordered = new ArrayList<>();
        for (Segment s : request.getSegments()) ordered.add(results.get(s.getId()));
        return new TranslationResponse(providerName, service.getTranslationServiceInfo().getTranslationServiceAttribution(), ordered, fromMemory);
    }

    private void translateRest(TranslationService service, TranslationRequest request, List<Segment> todo, Map<String, SegmentResult> results,
                               Map<String, String> learned) throws TranslationException {
        if (!todo.isEmpty()) {
            List<SegmentResult> first = segments(service, copy(request, todo));
            List<Segment> retry = new ArrayList<>();
            for (int i = 0; i < todo.size(); i++) {
                Segment s = todo.get(i);
                SegmentResult r = first.get(i);
                String damage = r.isTranslated() ? Markup.damage(s.getText(), r.getText(), s.isHtml()) : r.getError();
                if (damage == null) {
                    results.put(s.getId(), r);
                    learned.put(s.getText(), r.getText());
                } else retry.add(s);
            }
            // one more try for each damaged or missing segment, on its own
            for (Segment s : retry) {
                String damage;
                SegmentResult r;
                try {
                    List<Segment> one = new ArrayList<>();
                    one.add(s);
                    r = segments(service, copy(request, one)).get(0);
                    damage = r.isTranslated() ? Markup.damage(s.getText(), r.getText(), s.isHtml()) : r.getError();
                } catch (TranslationException e) {
                    if (e.getErrorCode() == TranslationException.ErrorCode.MISSING_CREDENTIALS) throw e;
                    r = null;
                    damage = e.getMessage();
                }
                results.put(s.getId(), damage == null ? r : SegmentResult.failed(s.getId(), s.getText(), damage));
                if (damage == null) learned.put(s.getText(), r.getText());
            }
        }
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    volatile org.apache.sling.api.resource.ResourceResolverFactory resolverFactory;

    /** The memory's session (studio-translation-service); null without one (then there is no memory). */
    private ResourceResolver memoryResolver() {
        org.apache.sling.api.resource.ResourceResolverFactory f = resolverFactory;
        if (f == null) return null;
        try {
            return f.getServiceResourceResolver(java.util.Collections.singletonMap(org.apache.sling.api.resource.ResourceResolverFactory.SUBSERVICE, (Object) "translation"));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void remember(String sourceLanguage, String targetLanguage, Map<String, String> translations) {
        ResourceResolver r = memoryResolver();
        if (r == null) return;
        try {
            new TranslationMemory(r).store(sourceLanguage, targetLanguage, translations, TranslationMemory.APPROVED);
        } catch (Exception ignored) {
            // the memory is a help, not a condition
        } finally {
            r.close();
        }
    }

    /** A connector without {@link DocumentTranslator} translates string by string (HTML and plain apart). */
    private static List<SegmentResult> segments(TranslationService service, TranslationRequest request) throws TranslationException {
        if (service instanceof DocumentTranslator) {
            List<SegmentResult> r = ((DocumentTranslator) service).translateDocument(request);
            if (r.size() != request.getSegments().size()) {
                throw new TranslationException("The connector answered " + r.size() + " results for " + request.getSegments().size()
                        + " segments", TranslationException.ErrorCode.INVALID_RESPONSE);
            }
            return r;
        }
        List<SegmentResult> out = new ArrayList<>();
        for (Segment s : request.getSegments()) {
            TranslationResult t = service.translateString(s.getText(), request.getSourceLanguage(), request.getTargetLanguage(),
                    s.isHtml() ? TranslationConstants.ContentType.HTML : TranslationConstants.ContentType.PLAIN, null);
            out.add(SegmentResult.translated(s.getId(), t.getTranslation()));
        }
        return out;
    }

    private static TranslationRequest copy(TranslationRequest r, List<Segment> segments) {
        TranslationRequest c = new TranslationRequest(r.getSourceLanguage(), r.getTargetLanguage(), segments)
                .provider(r.getProvider()).title(r.getTitle()).kind(r.getKind()).instructions(r.getInstructions()).useMemory(r.isUseMemory());
        c.getGlossary().putAll(r.getGlossary());
        c.getDoNotTranslate().addAll(r.getDoNotTranslate());
        return c;
    }
}
