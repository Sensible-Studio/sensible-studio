package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.ContentFragment;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fragment versions on a real Oak repository (JCR versioning), with the WKND fragments. */
@ExtendWith(SlingContextExtension.class)
class FragmentVersionsTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private FragmentVersions versions;
    private FragmentStore store;

    @BeforeEach
    void setUp() {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", "/conf/wknd-shared/settings/dam/cfm/models");
        Map<String, Object> props = new HashMap<>();
        props.put(AdapterFactory.ADAPTABLE_CLASSES, new String[]{Resource.class.getName()});
        props.put(AdapterFactory.ADAPTER_CLASSES, new String[]{ContentFragment.class.getName()});
        context.registerService(AdapterFactory.class, new ContentFragmentAdapterFactory(), props);
        versions = new FragmentVersions(context.resourceResolver());
        store = new FragmentStore(context.resourceResolver());
    }

    private void setTrip(String value) throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("tripLength", value);
        store.save(BALI, "master", values, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Object master(Map<String, Object> fragment, String element) {
        return ((Map<String, Object>) ((Map<String, Object>) fragment.get("values")).get("master")).get(element);
    }

    @Test
    void keepsLabelledCommentedVersionsNewestFirst() throws Exception {
        assertTrue(versions.list(BALI).isEmpty(), "no versions before the first");
        Map<String, Object> v1 = versions.create(BALI, "Launch", "As published in June");
        setTrip("7 Days");
        Map<String, Object> v2 = versions.create(BALI, null, null);

        List<Map<String, Object>> list = versions.list(BALI);
        assertEquals(Arrays.asList(v2.get("name"), v1.get("name")), Arrays.asList(list.get(0).get("name"), list.get(1).get("name")));
        assertEquals(Arrays.asList("Launch"), list.get(1).get("labels"));
        assertEquals("As published in June", list.get(1).get("comment"));
        assertEquals("Bali Surf Camp", list.get(1).get("title"));
        assertNull(list.get(0).get("comment"), "a comment belongs to its version only");
        assertEquals(context.resourceResolver().getUserID(), list.get(1).get("createdBy"));
        assertFalse(context.resourceResolver().getResource(BALI + "/jcr:content").getValueMap().containsKey(FragmentVersions.COMMENT));

        // the old version's content, in the editor's shape
        Map<String, Object> old = versions.content(BALI, (String) v1.get("name"));
        assertEquals(BALI, old.get("path"));
        assertEquals("6 Days", master(old, "tripLength"));
        assertEquals("7 Days", master(store.fragment(BALI), "tripLength"));
    }

    @Test
    void restoresAfterSavingTheCurrentState() throws Exception {
        Map<String, Object> v1 = versions.create(BALI, "Original", null);
        setTrip("9 Days");

        long before = System.currentTimeMillis() - 1000;
        Map<String, Object> safety = versions.restore(BALI, (String) v1.get("name"));
        assertTrue(java.time.Instant.parse((String) store.fragment(BALI).get("modified")).toEpochMilli() >= before, "restoring counts as a save");
        assertEquals("Before restoring Original", safety.get("comment"));
        assertEquals("6 Days", master(store.fragment(BALI), "tripLength"));
        assertEquals("9 Days", master(versions.content(BALI, (String) safety.get("name")), "tripLength"), "the replaced state is kept");

        // still a working fragment: it adapts and saves after the restore
        assertEquals("Bali Surf Camp", context.resourceResolver().getResource(BALI).adaptTo(ContentFragment.class).getTitle());
        setTrip("10 Days");
        assertEquals("10 Days", master(store.fragment(BALI), "tripLength"));
        assertEquals(2, versions.list(BALI).size(), "v1 and the safety version");
    }

    @Test
    void refusesDuplicateAndInvalidLabelsAndUnknownVersions() throws Exception {
        versions.create(BALI, "Launch", null);
        FragmentStore.ValidationException dup = assertThrows(FragmentStore.ValidationException.class, () -> versions.create(BALI, "Launch", null));
        assertEquals("label", dup.errors.get(0).field);
        assertThrows(FragmentStore.ValidationException.class, () -> versions.create(BALI, "a/b", null));
        assertEquals(1, versions.list(BALI).size(), "nothing created by the refused calls");
        assertThrows(IllegalArgumentException.class, () -> versions.content(BALI, "9.9"));
        assertThrows(IllegalArgumentException.class, () -> versions.restore(BALI, "jcr:rootVersion"));
        assertThrows(IllegalArgumentException.class, () -> versions.list(DAM + "/en"));
    }

    @Test
    void translatesAVersionedFragmentOnOakAndVersionsTheCopyBeforeAnUpdate() throws Exception {
        versions.create(BALI, "Launch", null); // the source is mix:versionable now
        FragmentTranslationTest.MarkingTranslator t = new FragmentTranslationTest.MarkingTranslator();
        FragmentTranslation translation = new FragmentTranslation(context.resourceResolver(), t);
        String fr = BALI.replace("/en/", "/fr/");
        translation.translate(java.util.Collections.singletonList(BALI), java.util.Collections.singletonList("fr"), null, false, false);
        assertEquals("[fr] Bali Surf Camp", master(store.fragment(fr), "title"));
        assertTrue(versions.list(fr).isEmpty(), "a new copy has no versions of the source");
        assertEquals("Bali Surf Camp", context.resourceResolver().getResource(BALI).adaptTo(ContentFragment.class).getTitle());

        setTrip("8 Days");
        Map<String, Object> values = new HashMap<>();
        values.put("itinerary", "<p>New</p>");
        store.save(BALI, "master", values, null, null, null, null);
        translation.translate(java.util.Collections.singletonList(BALI), java.util.Collections.singletonList("fr"), null, false, false);
        assertEquals("<p>[fr] New</p>", master(store.fragment(fr), "itinerary"));
        assertEquals("8 Days", master(store.fragment(fr), "tripLength"));
        List<Map<String, Object>> v = versions.list(fr);
        assertEquals(1, v.size());
        assertEquals("Before translation update from en", v.get(0).get("comment"));
    }
}
