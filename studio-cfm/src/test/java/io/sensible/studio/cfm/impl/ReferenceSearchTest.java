package io.sensible.studio.cfm.impl;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** "Used in" and "references" over the WKND fragments plus pages that use them. */
@ExtendWith(SlingContextExtension.class)
class ReferenceSearchTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String MODELS = "/conf/wknd-shared/settings/dam/cfm/models";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";
    private static final String COSTA_RICA = DAM + "/en/adventures/surf-camp-in-costa-rica/surf-camp-costa-rica";
    private static final String IAN = DAM + "/en/contributors/ian-provo";
    private static final String ALASKA = DAM + "/en/magazine/alaska-adventure/alaskan-adventures";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp() {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", MODELS);
        // a page with a Content Fragment component, and one linking to the fragment from a text component
        page("/content/wknd/us/en/adventures/bali-surf-camp", "Bali Surf Camp");
        context.create().resource("/content/wknd/us/en/adventures/bali-surf-camp/jcr:content/root/container/contentfragment",
                "sling:resourceType", "wknd/components/contentfragment", "fragmentPath", BALI);
        page("/content/wknd/us/en/magazine/surfing", "Surfing");
        context.create().resource("/content/wknd/us/en/magazine/surfing/jcr:content/root/text",
                "sling:resourceType", "wknd/components/text", "text", "<p>See <a href=\"" + BALI + ".html\">Bali</a></p>");
        // a look-alike path that must not count
        page("/content/wknd/us/en/other", "Other");
        context.create().resource("/content/wknd/us/en/other/jcr:content/root/x", "fragmentPath", BALI + "-2");
        page("/content/experience-fragments/wknd/promo", "Promo");
        context.create().resource("/content/experience-fragments/wknd/promo/jcr:content/root/cf",
                "fragmentPath", new String[]{IAN, BALI});
    }

    private void page(String path, String title) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        context.create().resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", title);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> usedIn(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("usedIn");
    }

    @Test
    void findsPagesAndExperienceFragmentsUsingAFragment() {
        Map<String, Object> r = ReferenceSearch.usedIn(context.resourceResolver(), BALI);
        assertEquals(Boolean.FALSE, r.get("truncated"));
        List<Map<String, Object>> used = usedIn(r);
        Map<String, Map<String, Object>> byPath = used.stream().collect(Collectors.toMap(m -> (String) m.get("path"), m -> m));
        assertEquals(3, used.size(), () -> used.toString());
        assertEquals("page", byPath.get("/content/wknd/us/en/adventures/bali-surf-camp").get("type"));
        assertEquals("Bali Surf Camp", byPath.get("/content/wknd/us/en/adventures/bali-surf-camp").get("title"));
        Map<String, Object> via = ((List<Map<String, Object>>) byPath.get("/content/wknd/us/en/adventures/bali-surf-camp").get("via")).get(0);
        assertEquals("jcr:content/root/container/contentfragment", via.get("node"));
        assertEquals("fragmentPath", via.get("property"));
        assertEquals("wknd/components/contentfragment", via.get("resourceType"));
        assertTrue(byPath.containsKey("/content/wknd/us/en/magazine/surfing"), "link in rich text");
        assertEquals("experience-fragment", byPath.get("/content/experience-fragments/wknd/promo").get("type"));
        assertFalse(byPath.containsKey("/content/wknd/us/en/other"), "a longer path is another fragment");
    }

    @Test
    void findsFragmentsReferencingAFragment() {
        Map<String, Object> byElement = usedIn(ReferenceSearch.usedIn(context.resourceResolver(), IAN)).stream()
                .filter(m -> ALASKA.equals(m.get("path"))).findFirst().orElseThrow();
        assertEquals("fragment", byElement.get("type"));
        assertEquals("Article", byElement.get("model"));
        assertTrue(usedIn(ReferenceSearch.usedIn(context.resourceResolver(), COSTA_RICA)).stream()
                .anyMatch(m -> BALI.equals(m.get("path"))), "Bali's description links to Costa Rica");
    }

    @Test
    void listsWhatAFragmentReferences() {
        List<Map<String, Object>> refs = ReferenceSearch.referencesOf(context.resourceResolver(), ALASKA);
        Map<String, Object> ian = refs.stream().filter(m -> IAN.equals(m.get("path"))).findFirst().orElseThrow();
        assertEquals("fragment", ian.get("type"));
        assertEquals(List.of("authorFragment"), ian.get("elements"));
        Map<String, Object> image = refs.stream().filter(m -> ((String) m.get("path")).endsWith("alaskan-grizzly.jpg")).findFirst().orElseThrow();
        assertEquals("missing", image.get("type"), "the fixture has no images");
        List<Map<String, Object>> bali = ReferenceSearch.referencesOf(context.resourceResolver(), BALI);
        assertTrue(bali.stream().anyMatch(m -> COSTA_RICA.equals(m.get("path")) && ((List<?>) m.get("elements")).contains("description")));
    }

    @Test
    void stopsAtTheBudget() {
        Map<String, Object> r = new ReferenceSearch(context.resourceResolver(), 50, 10_000).usedIn(BALI, "/content");
        assertEquals(Boolean.TRUE, r.get("truncated"));
    }
}
