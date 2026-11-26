/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.translation.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.adobe.granite.translation.api.TranslationException;

/** Generate Variations: copy written by the model of a translation provider, kept per person. */
@ExtendWith(SlingContextExtension.class)
class VariationsTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final List<String[]> asked = new ArrayList<>();
    private String answer;
    private String who = "alice";
    private VariationsServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new VariationsServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            String user(SlingHttpServletRequest request) {
                return who;
            }
        };
        servlet.generator = (provider, system, user, temperature) -> {
            asked.add(new String[] {provider.getPath(), system, user, String.valueOf(temperature)});
            return new String[] {answer, "OpenRouter (test/model)", "false"};
        };
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("api", "openrouter");
        settings.put("model", "test/model");
        settings.put("apiKey", "$[secret:OPENROUTER]");
        CloudConfigs.save(context.resourceResolver(), "/conf/global", "global", "Global", AiTranslationServiceFactory.NAME, settings);
        context.create().resource("/content/wknd/en/surf", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/wknd/en/surf/jcr:content", "jcr:title", "Surf Camp in Costa Rica",
                "jcr:description", "Two weeks of waves in Santa Teresa, all levels.");
        context.resourceResolver().commit();
        answer = "```json\n{\"variations\": [{\"title\": \"Ride Costa Rica\", \"subtitle\": \"Two weeks of waves.\", \"cta\": \"Book now\"},"
                + " {\"title\": \"Learn to surf\", \"subtitle\": \"All levels welcome.\", \"cta\": [\"Join\", \"us\"]},"
                + " {\"other\": \"nothing\"}]}\n```";
    }

    private Map<String, Object> call(boolean post, String json, String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(context.resourceResolver(), context.bundleContext());
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        if (post) {
            req.setMethod("POST");
            req.setContent(json.getBytes(StandardCharsets.UTF_8));
            servlet.doPost(req, res);
        } else {
            servlet.doGet(req, res);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) Json.parse(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void offersTheTemplatesAndTheModels() throws Exception {
        Map<String, Object> s = call(false, null, "op", "setup");
        assertEquals(200L, s.get("_status"));
        List<Map<String, Object>> templates = (List<Map<String, Object>>) s.get("templates");
        assertEquals("hero", templates.get(0).get("id"));
        assertEquals(3, ((List<Object>) templates.get(0).get("fields")).size());
        List<Map<String, Object>> providers = (List<Map<String, Object>>) s.get("providers");
        assertEquals("/conf/global/settings/cloudconfigs/translation/global", providers.get(0).get("path"));
        assertEquals("OpenRouter (test/model)", providers.get(0).get("engine"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesVariationsFromTheRequestAndThePage() throws Exception {
        Map<String, Object> r = call(true, "{\"op\":\"generate\",\"template\":\"hero\",\"request\":\"Our surf camp\",\"tone\":\"Playful\","
                + "\"audience\":\"Young travellers\",\"page\":\"/content/wknd/en/surf\",\"count\":3,\"creativity\":0.9}");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        List<Map<String, Object>> variations = (List<Map<String, Object>>) r.get("variations");
        // the one without the template's keys is left out; a list is read as lines
        assertEquals(2, variations.size());
        assertEquals("Ride Costa Rica", variations.get(0).get("title"));
        assertEquals("Join\nus", variations.get(1).get("cta"));
        assertEquals("OpenRouter (test/model)", r.get("engine"));

        String[] q = asked.get(0);
        assertEquals("/conf/global/settings/cloudconfigs/translation/global", q[0]);
        assertTrue(q[1].contains("3 different variations of a hero banner"));
        assertTrue(q[1].contains("Tone of voice: Playful."));
        assertTrue(q[1].contains("Written for: Young travellers."));
        assertTrue(q[1].contains("\"title\": \"...\""));
        assertTrue(q[2].contains("Our surf camp"));
        assertTrue(q[2].contains("Two weeks of waves in Santa Teresa"));
        assertEquals("0.9", q[3]);

        // kept: in my history, with its favorites
        String id = (String) r.get("id");
        List<Map<String, Object>> history = (List<Map<String, Object>>) call(false, null, "op", "history").get("items");
        assertEquals(1, history.size());
        assertEquals(2L, history.get(0).get("count"));
        assertNull(history.get(0).get("variations"));
        Map<String, Object> fav = call(true, "{\"op\":\"favorite\",\"id\":\"" + id + "\",\"index\":1,\"on\":true}");
        assertEquals(List.of(1L), fav.get("favorites"));

        // not someone else's
        who = "bob";
        assertTrue(((List<Object>) call(false, null, "op", "history").get("items")).isEmpty());
        assertEquals(404L, call(false, null, "op", "generation", "id", id).get("_status"));
        assertEquals(404L, call(true, "{\"op\":\"delete\",\"id\":\"" + id + "\"}").get("_status"));
        who = "alice";
        assertEquals(200L, call(true, "{\"op\":\"delete\",\"id\":\"" + id + "\"}").get("_status"));
        assertTrue(((List<Object>) call(false, null, "op", "history").get("items")).isEmpty());
    }

    @Test
    void saysWhatIsWrong() throws Exception {
        assertEquals(400L, call(true, "{\"op\":\"generate\",\"template\":\"hero\",\"request\":\" \"}").get("_status"));
        assertEquals(400L, call(true, "{\"op\":\"generate\",\"template\":\"nonsense\",\"request\":\"x\"}").get("_status"));
        assertEquals(400L, call(true, "{\"op\":\"generate\",\"request\":\"x\",\"count\":11}").get("_status"));
        assertEquals(400L, call(true, "{\"op\":\"generate\",\"request\":\"x\",\"page\":\"/etc/passwd\"}").get("_status"));
        assertEquals(400L, call(true, "{\"op\":\"generate\",\"request\":\"x\",\"page\":\"/content/nothing\"}").get("_status"));
        assertEquals(400L, call(true, "{\"op\":\"generate\",\"request\":\"x\",\"provider\":\"/conf/other\"}").get("_status"));
        answer = "I cannot help with that.";
        Map<String, Object> r = call(true, "{\"op\":\"generate\",\"request\":\"x\"}");
        assertEquals(502L, r.get("_status"));
        assertEquals("The model did not answer with variations", r.get("error"));
        servlet.generator = (p, s, u, t) -> {
            throw new TranslationException("The model API wants payment", TranslationException.ErrorCode.SERVICE_NOT_AVAILABLE);
        };
        assertEquals(502L, call(true, "{\"op\":\"generate\",\"request\":\"x\"}").get("_status"));
        who = null;
        assertEquals(401L, call(false, null, "op", "setup").get("_status"));
    }

    @Test
    void theInstructionsAskForJsonAndNoInventedFacts() {
        Variations.Input in = new Variations.Input();
        in.request = "x";
        in.count = 1;
        String s = Variations.system(Variations.template("faq"), in);
        assertTrue(s.contains("Write 1 variation of a frequently asked question"));
        assertFalse(s.contains("Make the variations differ"));
        assertTrue(s.contains("Do not invent prices"));
        assertTrue(s.contains("{\"variations\": [{\"question\": \"...\", \"answer\": \"...\"}, ...]}"));
        assertThrows(IllegalStateException.class, () -> Variations.parse(Variations.template("faq"), "{\"variations\": []}"));
        assertEquals("Hi & bye", Variations.plain("<p>Hi &amp; <b>bye</b></p>"));
    }
}
