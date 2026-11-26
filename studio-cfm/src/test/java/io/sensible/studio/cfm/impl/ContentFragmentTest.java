package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.ContentVariation;
import com.adobe.cq.dam.cfm.VariationDef;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs against the real WKND shared content (30 fragments, 3 models; MIT licensed, converted from the
 * wknd-shared content package to sling-mock JSON in src/test/resources).
 */
@ExtendWith(SlingContextExtension.class)
class ContentFragmentTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String MODELS = "/conf/wknd-shared/settings/dam/cfm/models";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp() {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", MODELS);
        Map<String, Object> props = new HashMap<>();
        props.put(AdapterFactory.ADAPTABLE_CLASSES, new String[]{Resource.class.getName()});
        props.put(AdapterFactory.ADAPTER_CLASSES, new String[]{ContentFragment.class.getName()});
        context.registerService(AdapterFactory.class, new ContentFragmentAdapterFactory(), props);
    }

    private ContentFragment fragment(String path) {
        Resource r = context.resourceResolver().getResource(path);
        assertNotNull(r, path);
        return r.adaptTo(ContentFragment.class);
    }

    private static List<String> names(Iterator<ContentElement> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(e -> out.add(e.getName()));
        return out;
    }

    // ------------------------------------------------------------------------------------------- API

    @Test
    void adaptsAssetsAndTheirContentNode() {
        ContentFragment cf = fragment(BALI);
        assertNotNull(cf);
        assertEquals("Bali Surf Camp", cf.getTitle());
        assertEquals("bali-surf-camp", cf.getName());
        assertNotNull(context.resourceResolver().getResource(BALI + "/jcr:content").adaptTo(ContentFragment.class));
        assertNull(context.resourceResolver().getResource(DAM + "/en").adaptTo(ContentFragment.class), "a folder is no fragment");
        assertEquals(BALI, cf.adaptTo(Resource.class).getPath());
    }

    @Test
    void elementsFollowTheModelOrderAndTypes() {
        ContentFragment cf = fragment(BALI);
        List<String> elements = names(cf.getElements());
        assertEquals(Arrays.asList("title", "slug", "description", "adventureType", "tripLength", "activity"), elements.subList(0, 6));
        assertTrue(cf.hasElement("groupSize"));
        assertFalse(cf.hasElement("nope"));

        assertEquals(6L, cf.getElement("groupSize").getValue().getValue());
        assertEquals("long", cf.getElement("groupSize").getValue().getDataType().getTypeString());
        assertEquals("6", cf.getElement("groupSize").getValue().getValue(String.class));
        assertEquals(5000.0, cf.getElement("price").getValue().getValue(Double.class));
        assertEquals("text/html", cf.getElement("description").getContentType());
        assertTrue(cf.getElement("description").getValue().getValue(String.class).startsWith("<p>Surfing in Bali"));
        assertArrayEquals(new String[]{"Surfing"}, cf.getElement("activity").getValue().getValue(String[].class));
        assertEquals("Bali Surf Camp", cf.getElement(null).getValue().getValue(String.class), "null name = first element");
    }

    @Test
    void templateIsTheModel() {
        ContentFragment cf = fragment(BALI);
        assertEquals("Adventure", cf.getTemplate().getTitle());
        assertEquals(MODELS + "/adventure/jcr:content/model", cf.getTemplate().adaptTo(Resource.class).getPath());
    }

    @Test
    void everyWkndFragmentOpens() {
        List<Map<String, Object>> all = castList(new FragmentStore(context.resourceResolver()).search(DAM, "", null, 0, 500).get("fragments"));
        assertEquals(30, all.size());
        for (Map<String, Object> s : all) {
            ContentFragment cf = fragment((String) s.get("path"));
            assertNotNull(cf.getTemplate(), "model resolved for " + s.get("path"));
            for (Iterator<ContentElement> it = cf.getElements(); it.hasNext(); ) {
                ContentElement e = it.next();
                e.getValue().getValue(); // never throws
                e.getContentType();
            }
        }
    }

    @Test
    void calendarsAndReferences() {
        String author = firstOfModel("author");
        ContentFragment cf = fragment(author);
        Object birthDay = cf.getElement("birthDay").getValue().getValue();
        assertTrue(birthDay == null || birthDay instanceof Calendar);
        assertEquals("calendar", cf.getElement("birthDay").getValue().getDataType().getTypeString());
        String article = firstOfModel("article");
        ContentFragment a = fragment(article);
        assertEquals("string/content-fragment", a.getElement("authorFragment").getValue().getDataType().getValueType());
    }

    // ------------------------------------------------------------------------------------------- editor API

    @Test
    void modelJsonDescribesFieldsForTheEditor() {
        Map<String, Object> model = ModelJson.model(context.resourceResolver(), MODELS + "/adventure");
        assertEquals("Adventure", model.get("title"));
        List<Map<String, Object>> fields = castList(model.get("fields"));
        Map<String, Object> slug = byName(fields, "slug");
        assertEquals("text", slug.get("kind"));
        assertEquals(Boolean.TRUE, slug.get("required"));
        assertNotNull(((Map<?, ?>) slug.get("validation")).get("regex"));
        Map<String, Object> type = byName(fields, "adventureType");
        assertEquals("enumeration", type.get("kind"));
        assertEquals(3, castList(type.get("options")).size());
        assertEquals("number", byName(fields, "groupSize").get("kind"));
        assertEquals("content-reference", byName(fields, "primaryImage").get("kind"));
        assertEquals("text/html", byName(fields, "description").get("mimeType"));

        Map<String, Object> authorModel = ModelJson.model(context.resourceResolver(), MODELS + "/author");
        Map<String, Object> occupations = byName(castList(authorModel.get("fields")), "occupations");
        assertEquals(Boolean.TRUE, occupations.get("multiple"));
        assertEquals(3, ModelJson.listModels(context.resourceResolver()).size());
    }

    @Test
    void savingWritesTypedValuesInTheAemFormat() throws Exception {
        FragmentStore store = new FragmentStore(context.resourceResolver());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("groupSize", "8");
        values.put("price", 4200);
        values.put("tripLength", "");
        values.put("description", "<p>New</p>");
        Map<String, Object> types = Collections.singletonMap("description", "text/html");
        store.save(BALI, null, values, types, "Bali Surf Camp 2", null, Arrays.asList("wknd-shared:activity/surfing"));

        Resource master = context.resourceResolver().getResource(BALI + "/jcr:content/data/master");
        assertEquals(8L, master.getValueMap().get("groupSize"));
        assertEquals(4200.0, master.getValueMap().get("price"));
        assertNull(master.getValueMap().get("tripLength"), "blank removes the value");
        assertNotNull(master.getValueMap().get("groupSize@LastModified", Calendar.class));
        assertEquals("text/html", master.getValueMap().get("description@ContentType"));
        assertEquals("Bali Surf Camp 2", fragment(BALI).getTitle());
        assertArrayEquals(new String[]{"wknd-shared:activity/surfing"},
                context.resourceResolver().getResource(BALI + "/jcr:content/metadata").getValueMap().get("cq:tags", String[].class));
    }

    @Test
    void savingValidatesAgainstTheModel() {
        FragmentStore store = new FragmentStore(context.resourceResolver());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("slug", "Not A Slug");
        values.put("title", "");
        values.put("groupSize", 0);
        values.put("unknown", "x");
        FragmentStore.ValidationException e = assertThrows(FragmentStore.ValidationException.class,
                () -> store.save(BALI, null, values, null, null, null, null));
        List<String> fields = new ArrayList<>();
        for (FragmentStore.FieldError fe : e.errors) fields.add(fe.field);
        assertTrue(fields.containsAll(Arrays.asList("slug", "title", "groupSize", "unknown")), fields.toString());
    }

    @Test
    void createVariationsAndDelete() throws Exception {
        FragmentStore store = new FragmentStore(context.resourceResolver());
        String path = store.create(DAM + "/en/adventures", null, "Danube Kayak Trip", MODELS + "/adventure", "test");
        assertEquals(DAM + "/en/adventures/danube-kayak-trip", path);
        ContentFragment cf = fragment(path);
        assertEquals("Danube Kayak Trip", cf.getTitle());
        assertEquals(100.0, cf.getElement("price").getValue().getValue(Double.class), "model default applied");

        store.save(path, null, Collections.singletonMap("tripLength", "3 Days"), null, null, null, null);
        assertEquals("summer", store.createVariation(path, "summer", "Summer", null));
        store.save(path, "summer", Collections.singletonMap("tripLength", "4 Days"), null, null, null, null);

        ContentFragment reread = fragment(path);
        List<String> variations = new ArrayList<>();
        for (Iterator<VariationDef> it = reread.listAllVariations(); it.hasNext(); ) variations.add(it.next().getName());
        assertEquals(Collections.singletonList("summer"), variations);
        ContentVariation summer = reread.getElement("tripLength").getVariation("summer");
        assertEquals("4 Days", summer.getValue().getValue(String.class));
        assertEquals("3 Days", reread.getElement("tripLength").getValue().getValue(String.class));

        Map<String, Object> json = store.fragment(path);
        assertEquals(2, castList(json.get("variations")).size());

        store.deleteVariation(path, "summer");
        assertFalse(fragment(path).listAllVariations().hasNext());
        assertThrows(IllegalArgumentException.class, () -> store.deleteVariation(path, "master"));
        assertThrows(IllegalArgumentException.class, () -> store.create(DAM + "/en/adventures", "danube-kayak-trip", "x", MODELS + "/adventure", null));
        store.delete(path);
        assertNull(context.resourceResolver().getResource(path));
    }

    @Test
    void foldersAndSearch() {
        FragmentStore store = new FragmentStore(context.resourceResolver());
        Map<String, Object> en = store.folder(DAM + "/en");
        assertFalse(castList(en.get("folders")).isEmpty());
        Map<String, Object> surf = store.search(DAM, "surf", null, 0, 50);
        assertTrue(castList(surf.get("fragments")).size() >= 2);
        Map<String, Object> authors = store.search(DAM, "", MODELS + "/author", 0, 50);
        assertEquals(7, castList(authors.get("fragments")).size());
    }

    @Test
    void jsonRoundTrip() {
        String json = "{\"a\":[1,2.5,true,null,\"x\\n\\u00e9\"],\"b\":{\"c\":-3}}";
        assertEquals(json.replace("\\u00e9", "é"), Json.write(Json.parse(json)));
    }

    // ------------------------------------------------------------------------------------------- fixtures for the UI

    /** With {@code -Dstudio.cf.dump=<dir>} writes the API responses the editor's tests and mock mode use. */
    @Test
    void dumpApiResponses() throws Exception {
        String dump = System.getProperty("studio.cf.dump");
        if (dump == null) return;
        Path dir = Paths.get(dump);
        Files.createDirectories(dir);
        FragmentStore store = new FragmentStore(context.resourceResolver());
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("models", ModelJson.listModels(context.resourceResolver()));
        Map<String, Object> models = new LinkedHashMap<>();
        for (String m : new String[]{"adventure", "article", "author"}) models.put(MODELS + "/" + m, ModelJson.model(context.resourceResolver(), MODELS + "/" + m));
        all.put("model", models);
        Map<String, Object> folders = new LinkedHashMap<>();
        collectFolders(store, "/content/dam", folders);
        all.put("folder", folders);
        Map<String, Object> fragments = new LinkedHashMap<>();
        for (Map<String, Object> s : castList(store.search(DAM, "", null, 0, 500).get("fragments"))) {
            fragments.put((String) s.get("path"), store.fragment((String) s.get("path")));
        }
        all.put("fragment", fragments);
        Files.write(dir.resolve("wknd-shared.json"), Json.write(all).getBytes(StandardCharsets.UTF_8));
    }

    private void collectFolders(FragmentStore store, String path, Map<String, Object> out) {
        Map<String, Object> f = store.folder(path);
        if (f == null) {
            // /content and /content/dam are not in the fixture; synthesize the way down to wknd-shared
            Map<String, Object> synthetic = new LinkedHashMap<>();
            synthetic.put("path", path);
            synthetic.put("title", path.substring(path.lastIndexOf('/') + 1));
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("name", "wknd-shared");
            child.put("path", DAM);
            child.put("title", "WKND Shared");
            synthetic.put("folders", Collections.singletonList(child));
            synthetic.put("fragments", Collections.emptyList());
            out.put(path, synthetic);
            collectFolders(store, DAM, out);
            return;
        }
        out.put(path, f);
        for (Map<String, Object> sub : castList(f.get("folders"))) collectFolders(store, (String) sub.get("path"), out);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private String firstOfModel(String model) {
        List<Map<String, Object>> hits = castList(new FragmentStore(context.resourceResolver())
                .search(DAM, "", MODELS + "/" + model, 0, 1).get("fragments"));
        assertFalse(hits.isEmpty());
        return (String) hits.get(0).get("path");
    }

    private static Map<String, Object> byName(List<Map<String, Object>> fields, String name) {
        for (Map<String, Object> f : fields) if (name.equals(f.get("name"))) return f;
        fail("no field " + name);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
