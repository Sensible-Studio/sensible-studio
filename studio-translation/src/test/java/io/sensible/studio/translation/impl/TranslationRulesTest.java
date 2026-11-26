/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.translation.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Translation rules per context, as AEM's Translation Configuration. */
@ExtendWith(SlingContextExtension.class)
class TranslationRulesTest {

    private final SlingContext context = new SlingContext();
    private final PageTranslationRules rules = new PageTranslationRules();
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = context.resourceResolver();
        context.create().resource("/content/wknd/en/home", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/wknd/en/home/jcr:content", "jcr:title", "Home", "quoteAuthor", "Ana", "slogan", "Go further");
        context.create().resource("/content/wknd/en/home/jcr:content/root/quote", "sling:resourceType", "wknd/components/quote",
                "text", "Life is short", "source", "A book");
        context.create().resource("/content/other/en/home", "jcr:primaryType", "cq:Page");
        context.create().resource("/content/other/en/home/jcr:content", "jcr:title", "Other", "slogan", "Stay");
    }

    private static Map<String, Object> json(String path, List<String> properties, Map<String, List<String>> types) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("properties", properties);
        m.put("resourceTypes", types.entrySet().stream().map(e -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("resourceType", e.getKey());
            t.put("properties", e.getValue());
            return t;
        }).collect(Collectors.toList()));
        m.put("propertySuffixes", Collections.singletonList("Author"));
        m.put("translateAssets", false);
        return m;
    }

    private List<String> texts(String page) {
        return new PageTranslation(resolver, null, rules).segments(resolver.getResource(page + "/jcr:content")).stream()
                .map(s -> (s.node.isEmpty() ? "" : s.node + "@") + s.prop).collect(Collectors.toList());
    }

    @Test
    void theMostSpecificContextApplies() throws Exception {
        // the built-in /content: the default properties
        assertEquals(Arrays.asList("jcr:title", "root/quote@text"), texts("/content/wknd/en/home"));
        assertEquals(1, rules.contexts(resolver).size());
        assertTrue(rules.contexts(resolver).get(0).builtIn);

        Map<String, List<String>> types = new LinkedHashMap<>();
        types.put("wknd/components/quote", Arrays.asList("source"));
        rules.save(resolver, PageTranslationRules.Rules.fromJson(json("/content/wknd", Arrays.asList("jcr:title", "slogan", "text"), types)));
        resolver.commit();

        assertEquals("/content/wknd", rules.at(resolver, "/content/wknd/en/home/jcr:content").path);
        assertEquals("/content", rules.at(resolver, "/content/other/en/home").path);
        assertEquals(Arrays.asList("jcr:title", "quoteAuthor", "root/quote@source", "root/quote@text", "slogan"),
                texts("/content/wknd/en/home").stream().sorted().collect(Collectors.toList()));
        assertEquals(Collections.singletonList("jcr:title"), texts("/content/other/en/home"));
        assertFalse(rules.at(resolver, "/content/wknd/en/home").translateAssets);

        Map<String, Object> preview = TranslationRulesServlet.preview(resolver, rules, resolver.getResource("/content/wknd/en/home/jcr:content"));
        assertEquals("/content/wknd", preview.get("context"));
        assertEquals(5, preview.get("total"));

        assertEquals(2, rules.contexts(resolver).size());
        assertTrue(rules.delete(resolver, "/content/wknd"));
        resolver.commit();
        assertEquals("/content", rules.at(resolver, "/content/wknd/en/home").path);
        assertFalse(rules.delete(resolver, "/content/wknd"));
    }

    @Test
    void aStoredContentContextReplacesTheBuiltInOne() throws Exception {
        rules.save(resolver, PageTranslationRules.Rules.fromJson(json("/content", Collections.singletonList("slogan"), Collections.emptyMap())));
        resolver.commit();
        assertEquals(1, rules.contexts(resolver).size());
        assertFalse(rules.contexts(resolver).get(0).builtIn);
        assertEquals(Collections.singletonList("slogan"), texts("/content/other/en/home"));
    }

    @Test
    void refusesWhatIsNotARule() {
        assertThrows(IllegalArgumentException.class, () -> PageTranslationRules.Rules.fromJson(json("/etc/x", Collections.emptyList(), Collections.emptyMap())));
        assertThrows(IllegalArgumentException.class, () -> PageTranslationRules.Rules.fromJson(json("/content/a", Collections.singletonList("bad name"), Collections.emptyMap())));
        Map<String, List<String>> types = new LinkedHashMap<>();
        types.put("../evil", Collections.singletonList("x"));
        assertThrows(IllegalArgumentException.class, () -> PageTranslationRules.Rules.fromJson(json("/content/a", Collections.emptyList(), types)));
    }
}
