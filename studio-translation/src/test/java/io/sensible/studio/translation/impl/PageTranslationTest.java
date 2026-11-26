package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.CloudConfig;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;
import io.sensible.studio.translation.api.TranslationResponse;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Language copies of pages: creation, updates, references, records and the MSM guard. */
@ExtendWith(SlingContextExtension.class)
class PageTranslationTest {

    private static final String EN = "/content/site/language-masters/en";
    private static final String FR = "/content/site/language-masters/fr";
    private static final String ABOUT = EN + "/about";
    private static final String ABOUT_FR = FR + "/about";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private Marking translator;
    private PageTranslation pt;

    /** Prefixes texts (outside tags) with the target language; can fail chosen segments. */
    static final class Marking implements Translator {
        final List<TranslationRequest> requests = new ArrayList<>();
        final Set<String> failing = new HashSet<>();

        @Override
        public List<Map<String, Object>> providers() {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> providers(ResourceResolver resolver, String contentPath) {
            return Collections.emptyList();
        }

        @Override
        public TranslationResponse translate(TranslationRequest request) {
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
        public TranslationResponse translate(ResourceResolver resolver, String contentPath, TranslationRequest request) {
            return translate(request);
        }

        final List<Map<String, String>> remembered = new ArrayList<>();

        @Override
        public void remember(String sourceLanguage, String targetLanguage, Map<String, String> translations) {
            remembered.add(translations);
        }

        @Override
        public Map<String, Object> test(CloudConfig config) {
            return Collections.emptyMap();
        }

        List<String> ids(int request) {
            return requests.get(request).getSegments().stream().map(Segment::getId).collect(Collectors.toList());
        }
    }

    @BeforeEach
    void setUp() {
        context.load().json("/page-site.json", "/content/site");
        context.create().resource("/content/dam/site/en/surf.jpg", "jcr:primaryType", "dam:Asset");
        context.create().resource("/content/dam/site/fr/surf.jpg", "jcr:primaryType", "dam:Asset");
        context.create().resource("/var/studio", "jcr:primaryType", "sling:Folder");
        translator = new Marking();
        pt = new PageTranslation(context.resourceResolver(), translator, new PageTranslationRules());
    }

    private ValueMap at(String path) {
        Resource r = context.resourceResolver().getResource(path);
        assertNotNull(r, path);
        return r.getValueMap();
    }

    @Test
    void createsACopyWithTranslatedTextsAndReferencesInTheTargetLanguage() throws Exception {
        List<String> batch = Arrays.asList(ABOUT, EN + "/contact");
        List<Map<String, Object>> results = pt.translate(ABOUT, "fr", "studio-pseudo", false, batch);
        assertEquals(1, results.size());
        Map<String, Object> r = results.get(0);
        assertEquals(ABOUT_FR, r.get("path"));
        assertEquals(Boolean.TRUE, r.get("created"));
        assertEquals(7, r.get("translated"));
        assertEquals("studio-pseudo", translator.requests.get(0).getProvider());
        assertEquals("About us", translator.requests.get(0).getTitle());
        assertTrue(translator.ids(0).containsAll(Arrays.asList("@jcr:title", "@jcr:description", "root/title@jcr:title", "root/text@text",
                "root/image@alt", "root/teaser@jcr:title", "root/teaser/actions/item0@text")));
        assertFalse(translator.ids(0).contains("root/embed@text"), "excluded component");
        assertFalse(translator.ids(0).stream().anyMatch(id -> id.contains("fileReference") || id.contains("layout") || id.contains("cq:")),
                "only text properties: " + translator.ids(0));

        ValueMap content = at(ABOUT_FR + "/jcr:content");
        assertEquals("[fr] About us", content.get("jcr:title", String.class));
        assertEquals("/conf/site/settings/wcm/templates/page", content.get("cq:template", String.class));
        assertNotNull(content.get(PageTranslation.LAST_UPDATE));
        // a plain page: no MSM, replication or version state copied
        assertNull(content.get("cq:lastReplicated"));
        assertNull(content.get("jcr:uuid"));
        assertFalse(Arrays.asList(content.get("jcr:mixinTypes", new String[0])).contains("cq:LiveRelationship"));
        assertNull(context.resourceResolver().getResource(ABOUT_FR + "/jcr:content/cq:LiveSyncConfig"));
        assertNotNull(context.resourceResolver().getResource(ABOUT_FR + "/jcr:content/cq:responsive/default"), "layout kept");
        assertNull(context.resourceResolver().getResource(ABOUT_FR + "/team"), "child pages only when deep");

        // references: the French asset exists, the contact page exists in French, jobs does not
        assertEquals("/content/dam/site/fr/surf.jpg", at(ABOUT_FR + "/jcr:content/root/image").get("fileReference", String.class));
        assertEquals(FR + "/contact", at(ABOUT_FR + "/jcr:content/root/teaser/actions/item0").get("link", String.class));
        assertEquals(FR, at(ABOUT_FR + "/jcr:content/root/list").get("parentPage", String.class));
        String text = at(ABOUT_FR + "/jcr:content/root/text").get("text", String.class);
        assertTrue(text.contains("href=\"" + FR + "/contact.html\""), text);
        assertTrue(text.contains("href=\"" + EN + "/jobs.html\""), "no French jobs page: the link stays");
        assertTrue(text.startsWith("<p>[fr] We ride <b>[fr] waves</b>"), text);
        assertEquals("Not to be translated", at(ABOUT_FR + "/jcr:content/root/embed").get("text", String.class));

        // the record, outside the page
        ValueMap rec = at(PageTranslation.RECORDS + ABOUT_FR);
        assertEquals(ABOUT, rec.get("sourcePath", String.class));
        assertEquals("TRANSLATED", rec.get("status", String.class));
        assertEquals("test", rec.get("provider", String.class));
        Map<String, Object> langs = pt.languages(ABOUT_FR);
        @SuppressWarnings("unchecked")
        Map<String, Object> own = (Map<String, Object>) langs.get("translation");
        assertEquals(Boolean.FALSE, own.get("outdated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFileInMediaHasItsLanguagesLikeAPage() throws Exception {
        for (String lang : new String[] {"en", "fr", "de"}) {
            context.create().resource("/content/dam/site/" + lang + "x", "jcr:primaryType", "sling:Folder"); // not a language
        }
        org.apache.sling.api.resource.ModifiableValueMap en = context.resourceResolver().getResource("/content/dam/site/en")
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        en.put("jcr:primaryType", "sling:Folder");
        context.resourceResolver().getResource("/content/dam/site/fr").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/site/de", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/site/en/surf.jpg/jcr:content/metadata", "dc:title", "Surf");
        Map<String, Object> out = pt.languages("/content/dam/site/en/surf.jpg");
        assertEquals("en", out.get("language"));
        assertEquals("Surf", out.get("title"));
        List<Map<String, Object>> copies = (List<Map<String, Object>>) out.get("copies");
        assertEquals(Arrays.asList("de", "fr"), copies.stream().map(c -> c.get("language")).collect(Collectors.toList()));
        assertEquals(Boolean.FALSE, copies.get(0).get("exists"));
        assertEquals(Boolean.TRUE, copies.get(1).get("exists"));
        assertEquals("/content/dam/site/fr/surf.jpg", copies.get(1).get("path"));
    }

    @Test
    void aCopyMadeAgainAfterItWasDeletedStartsWithoutTheOldReview() throws Exception {
        pt.translate(ABOUT, "fr", null, false, null);
        pt.saveReview(ABOUT_FR, Collections.singletonMap("@jcr:title", "À propos de nous"));
        pt.approve(ABOUT_FR);
        assertNotNull(pt.record(ABOUT_FR).get("approvedAt"));
        assertNotNull(pt.record(ABOUT_FR).get("reviewedAt"));

        // the copy is deleted: its record stays (a restored copy has it again) but shows nowhere
        context.resourceResolver().delete(context.resourceResolver().getResource(ABOUT_FR));
        context.resourceResolver().commit();
        assertNull(pt.record(ABOUT_FR));
        assertTrue(pt.queue("fr", null, null, 10).isEmpty());

        // translated again, it is a new copy: nobody reviewed or approved it
        Map<String, Object> r = pt.translate(ABOUT, "fr", null, false, null).get(0);
        assertEquals(Boolean.TRUE, r.get("created"));
        Map<String, Object> rec = pt.record(ABOUT_FR);
        assertNull(rec.get("reviewedAt"));
        assertNull(rec.get("approvedAt"));
        assertEquals("review", pt.queue("fr", null, null, 10).get(0).get("state"));
    }

    @Test
    void updatesOnlyChangedAndNewTextsAndKeepsLocalSettings() throws Exception {
        pt.translate(ABOUT, "fr", null, false, null);
        // the source changes a text and gets a component; the copy changes a setting locally
        at(ABOUT + "/jcr:content/root/title").size();
        context.resourceResolver().getResource(ABOUT + "/jcr:content/root/title").adaptTo(ModifiableValueMap.class).put("jcr:title", "Our long story");
        context.create().resource(ABOUT + "/jcr:content/root/quote", "sling:resourceType", "site/components/text", "text", "Ride on");
        context.resourceResolver().getResource(ABOUT_FR + "/jcr:content/root/title").adaptTo(ModifiableValueMap.class).put("type", "h2");
        context.resourceResolver().commit();

        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) pt.languages(ABOUT_FR).get("translation");
        assertEquals(Boolean.TRUE, before.get("outdated"));

        Map<String, Object> r = pt.translate(ABOUT, "fr", null, false, null).get(0);
        assertEquals(Boolean.FALSE, r.get("created"));
        assertEquals(Arrays.asList("root/title@jcr:title", "root/quote@text"), translator.ids(1));
        assertEquals(2, r.get("translated"));
        assertEquals(6, r.get("unchanged"));
        assertEquals("[fr] Our long story", at(ABOUT_FR + "/jcr:content/root/title").get("jcr:title", String.class));
        assertEquals("h2", at(ABOUT_FR + "/jcr:content/root/title").get("type", String.class), "local setting kept");
        assertEquals("[fr] Ride on", at(ABOUT_FR + "/jcr:content/root/quote").get("text", String.class));

        // nothing changed: nothing sent; force translates everything again
        pt.translate(ABOUT, "fr", null, false, null);
        assertEquals(2, translator.requests.size());
        pt.translate(ABOUT, "fr", null, true, null);
        assertEquals(8, translator.ids(2).size());
    }

    @Test
    void aCopyMadeByPeopleKeepsItsTextsUnlessForced() throws Exception {
        // fr/contact exists without a record (a human translation); the source gets a new component
        context.create().resource(EN + "/contact/jcr:content/root/text", "sling:resourceType", "site/components/text", "text", "Write to us");
        Map<String, Object> r = pt.translate(EN + "/contact", "fr", null, false, null).get(0);
        assertEquals(Boolean.FALSE, r.get("created"));
        assertEquals(1, r.get("kept"));
        assertEquals(Collections.singletonList("root/text@text"), translator.ids(0), "only what the copy lacks");
        assertEquals("Contactez-nous", at(FR + "/contact/jcr:content").get("jcr:title", String.class));
        assertEquals("[fr] Write to us", at(FR + "/contact/jcr:content/root/text").get("text", String.class));

        pt.translate(EN + "/contact", "fr", null, true, null);
        assertEquals("[fr] Contact", at(FR + "/contact/jcr:content").get("jcr:title", String.class));
    }

    @Test
    void createsMissingLanguageRootsAndPagesAboveAndTranslatesTrees() throws Exception {
        List<String> pages = pt.pages(ABOUT, true);
        assertEquals(Arrays.asList(ABOUT, ABOUT + "/team"), pages);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String p : pages) results.addAll(pt.translate(p, "de", null, false, pages));
        List<Object> paths = results.stream().map(r -> r.get("path")).collect(Collectors.toList());
        assertEquals(Arrays.asList("/content/site/language-masters/de", "/content/site/language-masters/de/about",
                "/content/site/language-masters/de/about/team"), paths);
        assertEquals(Boolean.TRUE, results.get(0).get("above"));
        assertEquals("[de] English", at("/content/site/language-masters/de/jcr:content").get("jcr:title", String.class));
        assertEquals("[de] The team", at("/content/site/language-masters/de/about/team/jcr:content").get("jcr:title", String.class));
        // German has no asset: the English one stays
        assertEquals("/content/dam/site/en/surf.jpg", at("/content/site/language-masters/de/about/jcr:content/root/image").get("fileReference", String.class));

        Map<String, Object> langs = pt.languages(ABOUT);
        assertEquals("en", langs.get("language"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> copies = (List<Map<String, Object>>) langs.get("copies");
        assertEquals(Arrays.asList("de", "fr"), copies.stream().map(c -> c.get("language")).collect(Collectors.toList()));
        assertEquals(Boolean.TRUE, copies.get(0).get("exists"));
        assertNotNull(copies.get(0).get("translation"));
        assertEquals(Boolean.FALSE, copies.get(1).get("exists"));
    }

    @Test
    void neverWritesALiveCopyAndRefusesWrongRequests() throws Exception {
        Map<String, Object> r = pt.translate("/content/site/us/en", "es", null, false, null).get(0);
        assertEquals("SKIPPED", r.get("status"));
        assertTrue(String.valueOf(r.get("error")).contains("live copy"));
        assertTrue(translator.requests.isEmpty());
        assertEquals("Español", at("/content/site/us/es/jcr:content").get("jcr:title", String.class));

        assertThrows(IllegalArgumentException.class, () -> pt.translate(ABOUT, "en", null, false, null));
        assertThrows(IllegalArgumentException.class, () -> pt.translate(ABOUT, "xx", null, false, null));
        assertThrows(IllegalArgumentException.class, () -> pt.translate("/content/site/language-masters", "fr", null, false, null));
        assertEquals(Boolean.TRUE, pt.languages("/content/site/us/en").get("liveCopy"));
    }

    @Test
    void failedTextsKeepTheSourceAndAreRetriedNextTime() throws Exception {
        translator.failing.add("root/text@text");
        Map<String, Object> r = pt.translate(ABOUT, "fr", null, false, null).get(0);
        assertEquals(Collections.singletonList("root/text@text"), r.get("failed"));
        assertTrue(at(ABOUT_FR + "/jcr:content/root/text").get("text", String.class).startsWith("<p>We ride"));
        translator.failing.clear();
        pt.translate(ABOUT, "fr", null, false, null);
        assertEquals(Collections.singletonList("root/text@text"), translator.ids(1));
        pt.approve(ABOUT_FR);
        assertEquals("APPROVED", at(PageTranslation.RECORDS + ABOUT_FR).get("status", String.class));
    }

    @Test
    void translatesReferencedExperienceAndContentFragmentsFirst() throws Exception {
        String xf = "/content/experience-fragments/site/language-masters";
        context.create().resource("/content/experience-fragments/site", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource(xf, "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource(xf + "/en", "jcr:primaryType", "sling:OrderedFolder", "jcr:title", "English");
        context.create().resource(xf + "/fr", "jcr:primaryType", "sling:OrderedFolder", "jcr:title", "French");
        context.create().resource(xf + "/en/people", "jcr:primaryType", "sling:OrderedFolder", "jcr:title", "People");
        context.create().resource(xf + "/en/people/anna", "jcr:primaryType", "cq:Page");
        context.create().resource(xf + "/en/people/anna/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Anna");
        context.create().resource(xf + "/en/people/anna/byline", "jcr:primaryType", "cq:Page");
        context.create().resource(xf + "/en/people/anna/byline/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Byline");
        context.create().resource(xf + "/en/people/anna/byline/jcr:content/root/text", "sling:resourceType", "site/components/text",
                "text", "Photographer and surfer", "fileReference", "/content/dam/site/en/surf.jpg");
        context.create().resource(ABOUT + "/jcr:content/root/xf", "sling:resourceType", "site/components/experiencefragment",
                "fragmentVariationPath", xf + "/en/people/anna/byline");
        context.create().resource(ABOUT + "/jcr:content/root/cf", "sling:resourceType", "site/components/contentfragment",
                "fragmentPath", "/content/dam/site/en/story");
        context.create().resource("/content/dam/site/en/story", "jcr:primaryType", "dam:Asset");
        List<String> cfCalls = new ArrayList<>();
        io.sensible.studio.translation.api.ReferenceTranslator fragments = new io.sensible.studio.translation.api.ReferenceTranslator() {
            @Override
            public boolean accepts(ResourceResolver resolver, String path) {
                return path.equals("/content/dam/site/en/story");
            }

            @Override
            public List<Map<String, Object>> translate(ResourceResolver resolver, String path, String target, String provider, boolean force) {
                cfCalls.add(path + " -> " + target + " by " + provider);
                context.create().resource("/content/dam/site/" + target + "/story", "jcr:primaryType", "dam:Asset");
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("source", path);
                r.put("path", "/content/dam/site/" + target + "/story");
                r.put("status", "TRANSLATED");
                return Collections.singletonList(r);
            }
        };
        PageTranslation withRefs = new PageTranslation(context.resourceResolver(), translator, new PageTranslationRules(), Collections.singletonList(fragments));

        List<Map<String, Object>> results = withRefs.translate(ABOUT, "fr", "studio-pseudo", false, null, true);
        List<Object> paths = results.stream().map(r -> r.get("path")).collect(Collectors.toList());
        assertEquals(Arrays.asList(xf + "/fr/people/anna", xf + "/fr/people/anna/byline", "/content/dam/site/fr/story", ABOUT_FR), paths);
        assertEquals(Boolean.TRUE, results.get(1).get("reference"));
        assertEquals(Collections.singletonList("/content/dam/site/en/story -> fr by studio-pseudo"), cfCalls);
        assertEquals("sling:OrderedFolder", at(xf + "/fr/people").get("jcr:primaryType", String.class), "the folder on the way is copied");
        assertEquals("[fr] Photographer and surfer", at(xf + "/fr/people/anna/byline/jcr:content/root/text").get("text", String.class));
        assertEquals("/content/dam/site/fr/surf.jpg", at(xf + "/fr/people/anna/byline/jcr:content/root/text").get("fileReference", String.class));
        // the page's copy points to the copies
        assertEquals(xf + "/fr/people/anna/byline", at(ABOUT_FR + "/jcr:content/root/xf").get("fragmentVariationPath", String.class));
        assertEquals("/content/dam/site/fr/story", at(ABOUT_FR + "/jcr:content/root/cf").get("fragmentPath", String.class));

        // a later page of the same batch skips what earlier ones translated
        List<Map<String, Object>> again = new PageTranslation(context.resourceResolver(), translator, new PageTranslationRules(),
                Collections.singletonList(fragments)).skipReferences(Arrays.asList(xf + "/en/people/anna/byline", "/content/dam/site/en/story"))
                .translate(ABOUT, "fr", null, false, null, true);
        assertEquals(1, again.size());
        assertEquals(1, cfCalls.size());
    }

    @Test
    void reviewCorrectsRetranslatesAndApprovesIntoTheMemory() throws Exception {
        translator.failing.add("root/image@alt");
        pt.translate(ABOUT, "fr", null, false, null);
        Map<String, Object> review = pt.review(ABOUT_FR);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segs = (List<Map<String, Object>>) review.get("segments");
        Map<String, Object> title = segs.stream().filter(m -> "@jcr:title".equals(m.get("id"))).findFirst().get();
        assertEquals("About us", title.get("source"));
        assertEquals("[fr] About us", title.get("translation"));
        assertEquals(Boolean.TRUE, segs.stream().filter(m -> "root/image@alt".equals(m.get("id"))).findFirst().get().get("failed"));
        assertEquals("failed", pt.queue(null, null, null, 10).get(0).get("state"));

        // a correction that breaks the markup is refused, one that keeps it is saved
        Map<String, String> broken = new java.util.LinkedHashMap<>();
        broken.put("root/text@text", "<p>Nous surfons des vagues.</p>");
        PageTranslation.ReviewException e = assertThrows(PageTranslation.ReviewException.class, () -> pt.saveReview(ABOUT_FR, broken));
        assertTrue(e.errors.containsKey("root/text@text"));
        Map<String, String> fixes = new java.util.LinkedHashMap<>();
        fixes.put("@jcr:title", "À propos de nous");
        fixes.put("root/image@alt", "Surfeurs à l'aube");
        pt.saveReview(ABOUT_FR, fixes);
        assertEquals("À propos de nous", at(ABOUT_FR + "/jcr:content").get("jcr:title", String.class));
        assertEquals("review", pt.queue("fr", null, null, 10).get(0).get("state"), "no failed texts any more");

        // one text translated again, without the memory
        Map<String, Object> again = pt.retranslate(ABOUT_FR, Collections.singletonList("root/title@jcr:title"), null);
        assertEquals(1, again.get("retranslated"));
        TranslationRequest last = translator.requests.get(translator.requests.size() - 1);
        assertEquals(Collections.singletonList("root/title@jcr:title"), translator.ids(translator.requests.size() - 1));
        assertFalse(last.isUseMemory());

        pt.approve(ABOUT_FR);
        Map<String, String> remembered = translator.remembered.get(0);
        assertEquals("À propos de nous", remembered.get("About us"), "approved corrections feed the memory");
        assertEquals("approved", pt.queue(null, "/content/site", "approved", 10).get(0).get("state"));
        assertTrue(pt.queue("de", null, null, 10).isEmpty());

        context.resourceResolver().getResource(ABOUT + "/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:title", "About");
        context.resourceResolver().commit();
        assertEquals("outdated", pt.queue(null, null, null, 10).get(0).get("state"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = (List<Map<String, Object>>) pt.review(ABOUT_FR).get("segments");
        assertEquals(Boolean.TRUE, after.stream().filter(m -> "@jcr:title".equals(m.get("id"))).findFirst().get().get("changed"));
    }

    @Test
    void assetsAPageShowsGetCopiesWithTheirMetadataTranslated() throws Exception {
        String beach = "/content/dam/site/en/gallery/beach.jpg";
        String beachFr = "/content/dam/site/fr/gallery/beach.jpg";
        context.create().resource("/content/dam/site/en/gallery", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/site/en/gallery/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "Gallery");
        context.create().resource(beach, "jcr:primaryType", "dam:Asset");
        context.create().resource(beach + "/jcr:content", "jcr:primaryType", "dam:AssetContent", "cq:parentPath", "/content/dam/site/en/gallery");
        context.create().resource(beach + "/jcr:content/metadata", "jcr:primaryType", "nt:unstructured",
                "dc:title", "Beach", "dc:description", "A wide beach at dawn", "dc:format", "image/jpeg", "tiff:Make", "Canon");
        context.create().resource(beach + "/jcr:content/renditions/original", "jcr:primaryType", "nt:file");
        context.create().resource("/content/dam/site/en/plain.jpg", "jcr:primaryType", "dam:Asset");
        context.create().resource("/content/dam/site/en/plain.jpg/jcr:content/metadata", "jcr:primaryType", "nt:unstructured", "dc:format", "image/jpeg");
        context.create().resource(ABOUT + "/jcr:content/beach", "sling:resourceType", "core/wcm/components/image/v3/image",
                "fileReference", beach, "altValueFromDAM", "true", "accessibilityLabel", "Open the beach gallery");
        context.create().resource(ABOUT + "/jcr:content/plain", "sling:resourceType", "core/wcm/components/image/v3/image",
                "fileReference", "/content/dam/site/en/plain.jpg");

        List<Map<String, Object>> results = pt.translate(ABOUT, "fr", "studio-pseudo", false, Collections.singletonList(ABOUT), true);
        Map<String, Object> asset = results.stream().filter(r -> beach.equals(r.get("source"))).findFirst().orElse(null);
        assertNotNull(asset, String.valueOf(results));
        assertEquals("asset", asset.get("kind"));
        assertEquals(true, asset.get("created"));
        assertEquals(2, asset.get("translated"));
        // an asset without texts stays shared: no copy, no result
        assertTrue(results.stream().noneMatch(r -> String.valueOf(r.get("source")).endsWith("plain.jpg")), String.valueOf(results));
        assertNull(context.resourceResolver().getResource("/content/dam/site/fr/plain.jpg"));

        ValueMap meta = at(beachFr + "/jcr:content/metadata");
        assertEquals("[fr] Beach", meta.get("dc:title"));
        assertEquals("[fr] A wide beach at dawn", meta.get("dc:description"));
        assertEquals("Canon", meta.get("tiff:Make"));
        assertEquals("/content/dam/site/fr/gallery", at(beachFr + "/jcr:content").get("cq:parentPath"));
        assertNotNull(context.resourceResolver().getResource(beachFr + "/jcr:content/renditions/original"));
        assertEquals("Gallery", at("/content/dam/site/fr/gallery/jcr:content").get("jcr:title"));
        // the page's copy shows the asset's copy, and its own label is translated (a ...Label property)
        assertEquals(beachFr, at(ABOUT_FR + "/jcr:content/beach").get("fileReference"));
        assertEquals("/content/dam/site/en/plain.jpg", at(ABOUT_FR + "/jcr:content/plain").get("fileReference"));
        assertEquals("[fr] Open the beach gallery", at(ABOUT_FR + "/jcr:content/beach").get("accessibilityLabel"));

        // reviewed like a page
        assertTrue(pt.queue("fr", null, null, 50).stream().anyMatch(m -> beachFr.equals(m.get("path")) && "asset".equals(m.get("kind"))));
        Map<String, Object> review = pt.review(beachFr);
        assertEquals("[fr] Beach", review.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segs = (List<Map<String, Object>>) review.get("segments");
        assertEquals(Arrays.asList("Asset · Title", "Asset · Description (alternative text)"), segs.stream().map(m -> m.get("label")).collect(Collectors.toList()));
        pt.saveReview(beachFr, Collections.singletonMap("@dc:title", "Plage"));
        assertEquals("Plage", at(beachFr + "/jcr:content/metadata").get("dc:title"));
        pt.approve(beachFr, "reviewer");
        assertEquals("Plage", translator.remembered.get(translator.remembered.size() - 1).get("Beach"));

        // an update translates what changed only
        context.resourceResolver().getResource(beach + "/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dc:description", "A quiet beach");
        context.resourceResolver().commit();
        Map<String, Object> again = pt.translateAsset(beach, "fr", "studio-pseudo", false);
        assertEquals(false, again.get("created"));
        assertEquals(1, again.get("translated"));
        assertEquals(1, again.get("unchanged"));
        assertEquals("Plage", at(beachFr + "/jcr:content/metadata").get("dc:title"));
        assertEquals("[fr] A quiet beach", at(beachFr + "/jcr:content/metadata").get("dc:description"));
        assertEquals("NO_TEXT", pt.translateAsset("/content/dam/site/en/plain.jpg", "fr", null, false).get("status"));
    }

    @Test
    void manyCopiesAreApprovedAtOnceButNotTheUnfinishedOnes() throws Exception {
        String teamFr = FR + "/about/team";
        pt.translate(ABOUT, "fr", null, false, null);
        translator.failing.add("@jcr:title");
        pt.translate(EN + "/about/team", "fr", null, false, null);
        translator.failing.clear();
        PageTranslationServlet servlet = new PageTranslationServlet();
        Map<String, Object> r = servlet.approveAll(context.resourceResolver(), pt, Arrays.asList(ABOUT_FR, teamFr, "/content/nowhere"));
        assertEquals(Collections.singletonList(ABOUT_FR), r.get("approved"));
        assertEquals("APPROVED", at(PageTranslation.RECORDS + ABOUT_FR).get("status", String.class));
        String skipped = String.valueOf(r.get("skipped"));
        assertTrue(skipped.contains(teamFr) || String.valueOf(r).contains(teamFr), String.valueOf(r));
        assertTrue(skipped.contains("/content/nowhere"), skipped);
        // approved already: left as it is
        Map<String, Object> again = servlet.approveAll(context.resourceResolver(), pt, Collections.singletonList(ABOUT_FR));
        assertTrue(String.valueOf(again.get("skipped")).contains("Approved already"), String.valueOf(again));
        assertThrows(IllegalArgumentException.class, () -> servlet.approveAll(context.resourceResolver(), pt, Collections.emptyList()));
    }

    @Test
    void languageRootsLikeAem() {
        assertEquals("/content/wknd/us/en", PageTranslation.languageRoot("/content/wknd/us/en/magazine/a").root);
        assertEquals("/content/wknd/language-masters/fr/x", PageTranslation.languageRoot("/content/wknd/language-masters/en/x").pathIn("fr"));
        assertEquals("de_ch", PageTranslation.languageRoot("/content/site/de_ch/x").language);
        assertNull(PageTranslation.languageRoot("/content/site/home"));
        assertNull(PageTranslation.languageRoot("/conf/site/en"));
        assertTrue(PageTranslationRules.isText("Hello world"));
        assertFalse(PageTranslationRules.isText("/content/x"));
        assertFalse(PageTranslationRules.isText("https://x.y"));
        assertFalse(PageTranslationRules.isText("42"));
        assertFalse(PageTranslationRules.isText("true"));
    }
}
