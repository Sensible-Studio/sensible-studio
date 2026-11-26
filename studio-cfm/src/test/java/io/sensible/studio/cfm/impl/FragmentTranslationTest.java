package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;
import io.sensible.studio.translation.api.TranslationResponse;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Language copies of the WKND fragments, with a translator that marks texts "[fr] ...". */
@ExtendWith(SlingContextExtension.class)
class FragmentTranslationTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String ALASKA = DAM + "/en/magazine/alaska-adventure/alaskan-adventures";
    private static final String ALASKA_FR = DAM + "/fr/magazine/alaska-adventure/alaskan-adventures";
    private static final String IAN = DAM + "/en/contributors/ian-provo";
    private static final String IAN_FR = DAM + "/fr/contributors/ian-provo";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private MarkingTranslator translator;
    private FragmentTranslation translation;

    /** Prefixes texts (outside tags) with the target language; can fail chosen segments. */
    static final class MarkingTranslator implements Translator {
        final List<TranslationRequest> requests = new ArrayList<>();
        final Set<String> failing = new HashSet<>();

        @Override
        public List<Map<String, Object>> providers() {
            return Collections.emptyList();
        }

        @Override
        public TranslationResponse translate(TranslationRequest request) throws TranslationException {
            requests.add(request);
            List<SegmentResult> out = new ArrayList<>();
            String prefix = "[" + request.getTargetLanguage() + "] ";
            for (Segment s : request.getSegments()) {
                if (failing.contains(s.getId())) out.add(SegmentResult.failed(s.getId(), s.getText(), "markup changed"));
                else out.add(SegmentResult.translated(s.getId(), s.isHtml() ? s.getText().replaceAll(">([^<\\s][^<]*)<", ">" + prefix + "$1<") : prefix + s.getText()));
            }
            return new TranslationResponse("test", "Translated by test", out);
        }

        @Override
        public List<Map<String, Object>> providers(org.apache.sling.api.resource.ResourceResolver resolver, String contentPath) {
            return providers();
        }

        @Override
        public TranslationResponse translate(org.apache.sling.api.resource.ResourceResolver resolver, String contentPath,
                                             TranslationRequest request) throws TranslationException {
            return translate(request);
        }

        final List<Map<String, String>> remembered = new ArrayList<>();

        @Override
        public void remember(String sourceLanguage, String targetLanguage, Map<String, String> translations) {
            remembered.add(translations);
        }

        @Override
        public Map<String, Object> test(io.sensible.studio.translation.api.CloudConfig config) {
            return Collections.emptyMap();
        }
    }

    @BeforeEach
    void setUp() {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", "/conf/wknd-shared/settings/dam/cfm/models");
        Map<String, Object> props = new HashMap<>();
        props.put(AdapterFactory.ADAPTABLE_CLASSES, new String[]{Resource.class.getName()});
        props.put(AdapterFactory.ADAPTER_CLASSES, new String[]{ContentFragment.class.getName()});
        context.registerService(AdapterFactory.class, new ContentFragmentAdapterFactory(), props);
        translator = new MarkingTranslator();
        translation = new FragmentTranslation(context.resourceResolver(), translator);
    }

    private ValueMap master(String path) {
        return context.resourceResolver().getResource(path + "/jcr:content/data/master").getValueMap();
    }

    private ValueMap record(String path) {
        return context.resourceResolver().getResource(path + "/jcr:content/translation").getValueMap();
    }

    @Test
    void findsLanguageRootsAsAem() {
        FragmentTranslation.LanguageRoot lr = FragmentTranslation.languageRoot(ALASKA);
        assertEquals(DAM + "/en", lr.root);
        assertEquals("en", lr.language);
        assertEquals(ALASKA_FR, lr.pathIn("fr"));
        assertEquals(DAM + "/de_ch/magazine/alaska-adventure/alaskan-adventures", lr.pathIn("de_ch"));
        assertNull(FragmentTranslation.languageRoot("/content/dam/wknd-shared/adventures/x"), "no language folder");
        assertFalse(FragmentTranslation.isLanguage("adventures"));
        assertFalse(FragmentTranslation.isLanguage("xx"), "not an ISO language");
        assertTrue(FragmentTranslation.isLanguage("pt_br"));
    }

    @Test
    void createsALanguageCopyWithTheTranslatableElementsTranslated() throws Exception {
        List<Map<String, Object>> results = translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("fr"), null, false, false);
        assertEquals(1, results.size());
        assertEquals(true, results.get(0).get("created"));
        assertEquals("TRANSLATED", results.get(0).get("status"));

        ValueMap fr = master(ALASKA_FR);
        assertEquals("[fr] Alaskan Adventure", fr.get("title", String.class));
        assertTrue(fr.get("main", String.class).startsWith("<p>[fr] My brother Neil"), fr.get("main", String.class));
        assertEquals("alaskan-adventures", fr.get("slug", String.class), "not translatable: copied");
        assertEquals(IAN, fr.get("authorFragment", String.class), "the author has no French copy: the reference stays");
        assertEquals("text/html", fr.get("main@ContentType", String.class));
        assertEquals("[fr] Alaskan Adventures", context.resourceResolver().getResource(ALASKA_FR + "/jcr:content").getValueMap().get("jcr:title"));
        assertEquals("/conf/wknd-shared/settings/dam/cfm/models/article",
                context.resourceResolver().getResource(ALASKA_FR + "/jcr:content/data").getValueMap().get("cq:model"));
        // the copy is a working fragment
        assertEquals("[fr] Alaskan Adventures", context.resourceResolver().getResource(ALASKA_FR).adaptTo(ContentFragment.class).getTitle());
        // folders titled like the source's, the language root by the language
        assertEquals("French", context.resourceResolver().getResource(DAM + "/fr/jcr:content").getValueMap().get("jcr:title"));
        assertEquals(context.resourceResolver().getResource(DAM + "/en/magazine/jcr:content") == null ? null
                        : context.resourceResolver().getResource(DAM + "/en/magazine/jcr:content").getValueMap().get("jcr:title"),
                context.resourceResolver().getResource(DAM + "/fr/magazine/jcr:content") == null ? null
                        : context.resourceResolver().getResource(DAM + "/fr/magazine/jcr:content").getValueMap().get("jcr:title"));

        ValueMap rec = record(ALASKA_FR);
        assertEquals(ALASKA, rec.get("sourcePath"));
        assertEquals("en", rec.get("sourceLanguage"));
        assertEquals("fr", rec.get("targetLanguage"));
        assertEquals("TRANSLATED", rec.get("status"));
        assertEquals("Translated by test", rec.get("attribution"));

        // the request carried the context and only translatable texts
        TranslationRequest req = translator.requests.get(0);
        assertEquals("en", req.getSourceLanguage());
        assertEquals("Alaskan Adventures", req.getTitle());
        assertTrue(req.getKind().contains("Article"), req.getKind());
        List<String> ids = new ArrayList<>();
        for (Segment s : req.getSegments()) ids.add(s.getId());
        assertEquals(Arrays.asList("_title", "master/title", "master/main"), ids);
        assertTrue(req.getSegments().get(2).isHtml());
    }

    @Test
    void approvingACopyFeedsTheTranslationMemory() throws Exception {
        translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("fr"), null, false, false);
        translation.approve(ALASKA_FR);
        Map<String, String> pairs = translator.remembered.get(0);
        assertEquals("[fr] Alaskan Adventures", pairs.get("Alaskan Adventures"));
    }

    @Test
    void aCopysTextsCanBeReviewedAndCorrected() throws Exception {
        translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("fr"), null, false, false);
        Map<String, Object> review = translation.review(ALASKA_FR);
        assertEquals("fr", review.get("targetLanguage"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segs = (List<Map<String, Object>>) review.get("segments");
        Map<String, Object> title = segs.stream().filter(m -> "_title".equals(m.get("id"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Alaskan Adventures", title.get("source"));
        assertEquals("[fr] Alaskan Adventures", title.get("translation"));
        translation.saveReview(ALASKA_FR, Collections.singletonMap("_title", "Aventures en Alaska"));
        assertEquals("Aventures en Alaska", context.resourceResolver().getResource(ALASKA_FR + "/jcr:content").getValueMap().get("jcr:title"));
        assertNotNull(context.resourceResolver().getResource(ALASKA_FR + "/jcr:content/translation").getValueMap().get("reviewedBy"));
        assertThrows(IllegalArgumentException.class, () -> translation.saveReview(ALASKA_FR, Collections.singletonMap("nope", "x")));
        assertNull(translation.review(ALASKA), "the source is not a copy");
    }

    @Test
    void translatesTheFragmentsAPageRefersTo() throws Exception {
        FragmentReferenceTranslator refs = new FragmentReferenceTranslator();
        java.lang.reflect.Field f = FragmentReferenceTranslator.class.getDeclaredField("translator");
        f.setAccessible(true);
        f.set(refs, translator);
        assertTrue(refs.accepts(context.resourceResolver(), ALASKA));
        assertFalse(refs.accepts(context.resourceResolver(), DAM + "/en"), "a folder is not a fragment");
        List<Map<String, Object>> results = refs.translate(context.resourceResolver(), ALASKA, "fr", "studio-pseudo", false);
        // the fragment and the author it refers to
        assertEquals(java.util.Arrays.asList(ALASKA_FR, IAN_FR), results.stream().map(r -> r.get("path")).collect(java.util.stream.Collectors.toList()));
        assertThrows(IllegalArgumentException.class, () -> refs.translate(context.resourceResolver(), ALASKA, "zz-invalid", null, false));
    }

    @Test
    void translatesReferencedFragmentsAndPointsToThem() throws Exception {
        List<Map<String, Object>> results = translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("fr"), null, true, false);
        assertEquals(2, results.size(), "the article and its author");
        assertEquals(IAN_FR, master(ALASKA_FR).get("authorFragment", String.class));
        assertTrue(master(IAN_FR).get("biography", String.class) == null || master(IAN_FR).get("biography", String.class).isEmpty()
                || master(IAN_FR).get("biography", String.class).contains("[fr]"));
        assertEquals("Ian", master(IAN_FR).get("firstName", String.class), "names are not translatable");
        // assets are not copied: the image keeps pointing to the English folder
        assertEquals(master(ALASKA).get("featuredImage", String.class), master(ALASKA_FR).get("featuredImage", String.class));
    }

    @Test
    void updatesOnlyWhatChangedAndKeepsReviewedTexts() throws Exception {
        translation.translate(Collections.singletonList(BALI), Collections.singletonList("fr"), null, false, false);
        String bali = BALI.replace("/en/", "/fr/");
        translation.approve(bali);
        assertEquals("APPROVED", record(bali).get("status"));
        assertTrue(((List<?>) translation.info(context.resourceResolver().getResource(bali)).get("outdated")).isEmpty());

        // a reviewer improves the French title; the source changes its trip length and itinerary
        context.resourceResolver().getResource(bali + "/jcr:content/data/master").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("title", "Camp de surf à Bali");
        Map<String, Object> values = new HashMap<>();
        values.put("itinerary", "<p>New plan</p>");
        values.put("tripLength", "7 Days");
        new FragmentStore(context.resourceResolver()).save(BALI, "master", values, null, null, null, null);
        assertEquals(Collections.singletonList("master/itinerary"), translation.info(context.resourceResolver().getResource(bali)).get("outdated"));

        translator.requests.clear();
        Map<String, Object> r = translation.translate(Collections.singletonList(BALI), Collections.singletonList("fr"), null, false, false).get(0);
        assertEquals(false, r.get("created"));
        assertEquals(1, r.get("translated"));
        assertEquals(1, translator.requests.get(0).getSegments().size(), "only the changed text was sent");
        ValueMap fr = master(bali);
        assertEquals("<p>[fr] New plan</p>", fr.get("itinerary", String.class));
        assertEquals("Camp de surf à Bali", fr.get("title", String.class), "unchanged source: the reviewed text stays");
        assertEquals("7 Days", fr.get("tripLength", String.class), "not translatable: follows the source");
        assertEquals("TRANSLATED", record(bali).get("status"), "back to review after an update");

        // force: everything again
        Map<String, Object> forced = translation.translate(Collections.singletonList(BALI), Collections.singletonList("fr"), null, false, true).get(0);
        assertTrue((Integer) forced.get("translated") > 1);
        assertEquals("[fr] Bali Surf Camp", master(bali).get("title", String.class));
    }

    @Test
    void reportsSegmentsThatFailedAndRefusesBadRequests() throws Exception {
        translator.failing.add("master/main");
        Map<String, Object> r = translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("de"), null, false, false).get(0);
        assertEquals("ERROR_UPDATE", r.get("status"));
        String de = ALASKA.replace("/en/", "/de/");
        assertEquals(master(ALASKA).get("main", String.class), master(de).get("main", String.class), "the source stands in");
        assertEquals("[de] Alaskan Adventure", master(de).get("title", String.class));
        String[] failed = record(de).get("failed", String[].class);
        assertNotNull(failed);
        assertTrue(failed[0].startsWith("master/main"));

        FragmentStore.ValidationException e = assertThrows(FragmentStore.ValidationException.class,
                () -> translation.translate(Collections.singletonList(DAM + "/en"), Collections.<String>emptyList(), null, false, false));
        assertEquals(2, e.errors.size(), "not a fragment, no language");
        assertThrows(FragmentStore.ValidationException.class,
                () -> translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("klingon"), null, false, false));
        assertTrue(translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("en"), null, false, false).isEmpty(),
                "the source language is skipped");
        assertThrows(UnsupportedOperationException.class,
                () -> new FragmentTranslation(context.resourceResolver(), null).translate(Collections.singletonList(ALASKA), Collections.singletonList("fr"), null, false, false));
    }

    @Test
    void listsLanguagesAndCopies() throws Exception {
        translation.translate(Collections.singletonList(ALASKA), Collections.singletonList("fr"), null, false, false);
        Map<String, Object> l = translation.languages(ALASKA);
        assertEquals("en", l.get("language"));
        List<?> languages = (List<?>) l.get("languages");
        assertEquals(2, languages.size(), "en and fr folders");
        List<?> copies = (List<?>) l.get("copies");
        assertEquals(1, copies.size());
        assertEquals("fr", ((Map<?, ?>) copies.get(0)).get("language"));
        assertEquals("TRANSLATED", ((Map<?, ?>) copies.get(0)).get("status"));
        Map<String, Object> fr = translation.languages(ALASKA_FR);
        assertEquals(ALASKA, ((Map<?, ?>) fr.get("translation")).get("sourcePath"));
    }
}
