package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationException;
import com.sun.net.httpserver.HttpServer;
import io.sensible.studio.translation.api.CloudConfig;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.TranslationRequest;
import io.sensible.studio.translation.api.TranslationResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Translation cloud configurations in /conf, secret references and the configuration servlet. */
@ExtendWith(SlingContextExtension.class)
class CloudConfigTest {

    private static final String SITE = "/content/dam/wknd-shared";
    private static final String CONF = "/conf/wknd-shared";
    private static final String SECRET_VALUE = "sk-ant-test-secret-value";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private HttpServer server;
    private final List<Map<String, String>> headers = new ArrayList<>();
    private final List<Map<String, Object>> bodies = new ArrayList<>();
    private Secrets secrets;
    private TranslatorImpl translator;
    private AiTranslationServiceFactory ai;
    private int status;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) Json.parse(body);
            Map<String, String> h = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            synchronized (headers) {
                headers.add(h);
                bodies.add(parsed);
            }
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> s : TranslationTest.segmentsOf(parsed)) {
                out.add(TranslationTest.seg((String) s.get("id"), TranslationTest.upper((String) s.get("text"))));
            }
            byte[] bytes = TranslationTest.anthropic(Json.write(Collections.singletonMap("segments", out)), "end_turn").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Map<String, String> env = new HashMap<>();
        env.put("FROM_ENV", "env-key");
        secrets = new Secrets().environment(env);
        SecretEntry entry = new SecretEntry();
        entry.activate(secret("CLAUDE_KEY", SECRET_VALUE));
        secrets.bindEntry(entry);

        ai = new AiTranslationServiceFactory();
        ai.secrets = secrets;
        ai.activate(TranslationTest.config("anthropic", url(), ""));
        translator = new TranslatorImpl();
        translator.bindFactory(ai);
        translator.bindFactory(new PseudoTranslationServiceFactory());

        context.create().resource(SITE, "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource(SITE + "/jcr:content", "cq:conf", CONF);
        context.create().resource(SITE + "/en/article", "jcr:primaryType", "dam:Asset");
        context.create().resource(CONF, "jcr:primaryType", "sling:Folder");
        context.create().resource("/conf/global", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/other/en/x", "jcr:primaryType", "dam:Asset");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    private static SecretEntry.Config secret(String name, String value) {
        return new SecretEntry.Config() {
            @Override public Class<? extends Annotation> annotationType() { return SecretEntry.Config.class; }
            @Override public String name() { return name; }
            @Override public String value() { return value; }
            @Override public String webconsole_configurationFactory_nameHint() { return "{name}"; }
        };
    }

    private CloudConfig save(String conf, String name, String apiKey) throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("api", "anthropic");
        settings.put("endpoint", url());
        settings.put("model", "claude-haiku-5-5");
        if (apiKey != null) settings.put("apiKey", apiKey);
        settings.put("instructions", "Short and friendly.");
        settings.put("doNotTranslate", new String[]{"WKND"});
        return CloudConfigs.save(context.resourceResolver(), conf, name, "Claude " + name, "studio-ai", settings);
    }

    private static TranslationRequest request() {
        return new TranslationRequest("en", "fr", Collections.singletonList(new Segment("title", "Hello", false, "Title")));
    }

    @Test
    void contentUsesItsSiteConfigurationWithTheSecretKey() throws Exception {
        save("/conf/global", "global", "$[env:FROM_ENV]");
        CloudConfig site = save(CONF, "claude", "$[secret:CLAUDE_KEY]");
        assertEquals(CONF + "/settings/cloudconfigs/translation/claude", site.getPath());
        assertEquals(CONF, site.getConf());
        assertEquals("cq:Page", context.resourceResolver().getResource(site.getPath()).getValueMap().get("jcr:primaryType", String.class));

        ResourceResolver rr = context.resourceResolver();
        TranslationResponse r = translator.translate(rr, SITE + "/en/article", request());
        assertEquals("HELLO", r.get("title").getText());
        assertEquals(site.getPath(), r.getProvider(), "the copy records which configuration translated it");
        assertEquals(SECRET_VALUE, headers.get(0).get("x-api-key"));
        assertEquals("claude-haiku-5-5", bodies.get(0).get("model"));
        assertTrue(String.valueOf(bodies.get(0).get("system")).contains("Short and friendly."));
        assertTrue(String.valueOf(bodies.get(0).get("system")).contains("WKND"));

        // content of another site falls back to /conf/global
        translator.translate(rr, "/content/dam/other/en/x", request());
        assertEquals("env-key", headers.get(1).get("x-api-key"));

        List<Map<String, Object>> providers = translator.providers(rr, SITE + "/en/article");
        assertEquals(site.getPath(), providers.get(0).get("name"));
        assertEquals(Boolean.TRUE, providers.get(0).get("default"));
        assertEquals(Boolean.TRUE, providers.get(0).get("available"));
        assertEquals("/conf/global/settings/cloudconfigs/translation/global", providers.get(1).get("name"));
        assertTrue(providers.stream().filter(p -> "studio-pseudo".equals(p.get("name"))).allMatch(p -> Boolean.FALSE.equals(p.get("default"))));
    }

    @Test
    void theTranslationMemoryAnswersTextsTranslatedBeforeAndPrefersApprovedOnes() throws Exception {
        save(CONF, "claude", "$[secret:CLAUDE_KEY]");
        translator.resolverFactory = context.getService(org.apache.sling.api.resource.ResourceResolverFactory.class);
        ResourceResolver rr = context.resourceResolver();
        java.util.function.Supplier<TranslationRequest> twoTexts = () -> new TranslationRequest("en", "fr", java.util.Arrays.asList(
                new Segment("a", "Hello", false, null), new Segment("b", "<p>Ride <b>on</b></p>", true, null)));

        TranslationResponse first = translator.translate(rr, SITE + "/en/article", twoTexts.get());
        assertEquals(1, bodies.size());
        assertEquals(0, first.getFromMemory());
        // the same texts again, e.g. in another page or fragment: no request to the model
        TranslationResponse again = translator.translate(rr, SITE + "/en/article", twoTexts.get());
        assertEquals(1, bodies.size());
        assertEquals(2, again.getFromMemory());
        assertEquals("<p>RIDE <b>ON</b></p>", again.get("b").getText());
        // "translate again" skips the memory
        translator.translate(rr, SITE + "/en/article", twoTexts.get().useMemory(false));
        assertEquals(2, bodies.size());

        // an approved translation wins, and a later machine translation does not replace it
        translator.remember("en", "fr", java.util.Collections.singletonMap("Hello", "Bonjour"));
        assertEquals("Bonjour", translator.translate(rr, SITE + "/en/article", twoTexts.get()).get("a").getText());
        translator.translate(rr, SITE + "/en/article", twoTexts.get().useMemory(false));
        assertEquals("Bonjour", translator.translate(rr, SITE + "/en/article", twoTexts.get()).get("a").getText());
        // other language pairs are separate
        TranslationRequest de = new TranslationRequest("en", "de", java.util.Collections.singletonList(new Segment("a", "Hello", false, null)));
        assertEquals(0, translator.translate(rr, SITE + "/en/article", de).getFromMemory());
    }

    @Test
    void keysNeverComeFromTheRepository() throws Exception {
        // a literal key written into the repository by hand is refused
        CloudConfig plain = save(CONF, "plain", null);
        context.resourceResolver().getResource(plain.getPath() + "/jcr:content").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("apiKey", "sk-literal");
        context.resourceResolver().commit();
        TranslationException e = assertThrows(TranslationException.class,
                () -> translator.translate(context.resourceResolver(), SITE + "/en/article", request().provider(plain.getPath())));
        assertEquals(TranslationException.ErrorCode.MISSING_CREDENTIALS, e.getErrorCode());
        assertFalse(e.getMessage().contains("sk-literal"));
        assertTrue(headers.isEmpty(), "nothing was sent");

        // a secret that is not set on this instance
        CloudConfig missing = save(CONF, "missing", "$[secret:NOT_SET]");
        e = assertThrows(TranslationException.class,
                () -> translator.translate(context.resourceResolver(), SITE + "/en/article", request().provider(missing.getPath())));
        assertEquals(TranslationException.ErrorCode.MISSING_CREDENTIALS, e.getErrorCode());
        assertTrue(e.getMessage().contains("$[secret:NOT_SET]"));
    }

    @Test
    void aKeyTypedInTheConsoleIsStoredEncrypted() throws Exception {
        TranslationConfigServlet servlet = new TranslationConfigServlet();
        servlet.translator = translator;
        servlet.secrets = secrets;
        servlet.bindFactory(ai);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("endpoint", url());
        settings.put("apiKeyValue", "sk-test-typed-in-console");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "save");
        body.put("conf", CONF);
        body.put("title", "Typed key");
        body.put("settings", settings);
        String answer = post(servlet, body);
        assertEquals(200, status, answer);
        assertFalse(answer.contains("sk-test-typed-in-console"), "the key is not answered");
        String stored = context.resourceResolver().getResource(CONF + "/settings/cloudconfigs/translation/typed-key/jcr:content")
                .getValueMap().get("apiKey", String.class);
        assertTrue(Secrets.isEncrypted(stored), stored);
        assertFalse(stored.contains("sk-test"));
        assertTrue(answer.contains("\"encrypted\":true"), answer);
        assertEquals("sk-test-typed-in-console", secrets.resolve(stored));
        // saved again as it was answered: the encrypted key stays
        settings.remove("apiKeyValue");
        settings.put("apiKey", stored);
        assertEquals(200, status);
        // another instance (another master key) cannot read it; a damaged value neither
        Secrets other = new Secrets().masterKey(new byte[32]);
        assertNull(other.resolve(stored));
        assertNull(secrets.resolve(stored.replace("enc:v1:", "enc:v1:A")));
        // what is not a key is refused
        settings.put("apiKeyValue", "two words");
        post(servlet, body);
        assertEquals(422, status);
    }

    @Test
    void servletRefusesPlainKeysAndAnswersOnlyTheirStatus() throws Exception {
        TranslationConfigServlet servlet = new TranslationConfigServlet();
        servlet.translator = translator;
        servlet.secrets = secrets;
        servlet.bindFactory(ai);
        servlet.bindFactory(new PseudoTranslationServiceFactory());

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("endpoint", url());
        settings.put("apiKey", "sk-ant-pasted-by-mistake");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "save");
        body.put("conf", CONF);
        body.put("title", "Claude for WKND");
        body.put("settings", settings);
        String answer = post(servlet, body);
        assertEquals(422, status);
        assertTrue(answer.contains("settings.apiKey"));
        assertFalse(answer.contains("sk-ant-pasted"), "the key is not echoed");
        assertNull(context.resourceResolver().getResource(CONF + "/settings/cloudconfigs"), "nothing was written");

        // a credential header with a literal value: refused and not echoed
        settings.put("apiKey", "$[secret:CLAUDE_KEY]");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", "api-key");
        header.put("value", "sk-header-literal");
        settings.put("headers", java.util.Arrays.asList(header));
        answer = post(servlet, body);
        assertEquals(422, status);
        assertTrue(answer.contains("settings.headers.0"));
        assertFalse(answer.contains("sk-header-literal"));
        header.put("value", "$[secret:CLAUDE_KEY]");
        settings.put("headers", java.util.Arrays.asList(header, "X-Team: content"));

        settings.put("apiKey", "$[secret:CLAUDE_KEY]");
        settings.put("doNotTranslate", java.util.Arrays.asList("WKND", " ", "Adobe"));
        settings.put("maxTokens", "8000");
        answer = post(servlet, body);
        assertEquals(200, status, answer);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) Json.parse(answer);
        assertEquals(CONF + "/settings/cloudconfigs/translation/claude-for-wknd", saved.get("path"));
        assertEquals("{status=found, name=CLAUDE_KEY}", String.valueOf(saved.get("key")));
        assertFalse(answer.contains(SECRET_VALUE));
        @SuppressWarnings("unchecked")
        Map<String, Object> savedSettings = (Map<String, Object>) saved.get("settings");
        assertEquals(java.util.Arrays.asList("WKND", "Adobe"), savedSettings.get("doNotTranslate"));
        assertEquals(8000L, ((Number) savedSettings.get("maxTokens")).longValue());
        assertEquals(java.util.Arrays.asList("api-key: $[secret:CLAUDE_KEY]", "X-Team: content"), savedSettings.get("headers"));

        // the same name again is refused; testing the saved configuration works
        assertTrue(post(servlet, body).contains("exists already"));
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("op", "test");
        test.put("path", saved.get("path"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Json.parse(post(servlet, test));
        assertEquals(Boolean.TRUE, result.get("ok"), String.valueOf(result));
        assertEquals(TranslatorImpl.TEST_TEXT.toUpperCase(), result.get("sample"));

        // testing unsaved settings with a missing secret explains it
        Map<String, Object> unsaved = new LinkedHashMap<>();
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("apiKey", "$[secret:NOPE]");
        unsaved.put("settings", s2);
        test.remove("path");
        test.put("config", unsaved);
        @SuppressWarnings("unchecked")
        Map<String, Object> failed = (Map<String, Object>) Json.parse(post(servlet, test));
        assertEquals(Boolean.FALSE, failed.get("ok"));
        assertEquals("MISSING_CREDENTIALS", failed.get("code"));
    }

    private String post(TranslationConfigServlet servlet, Map<String, Object> body) throws Exception {
        org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest request =
                new org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        request.setMethod("POST");
        request.setContent(Json.write(body).getBytes(StandardCharsets.UTF_8));
        org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletResponse response =
                new org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletResponse();
        servlet.doPost(request, response);
        status = response.getStatus();
        return response.getOutputAsString();
    }
}
