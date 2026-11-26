package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model editor's writes, against the real WKND models. */
@ExtendWith(SlingContextExtension.class)
class ModelStoreTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String MODELS = "/conf/wknd-shared/settings/dam/cfm/models";
    private static final String ADVENTURE = MODELS + "/adventure";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private ModelStore store;

    @BeforeEach
    void setUp() {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", MODELS);
        Map<String, Object> props = new HashMap<>();
        props.put(AdapterFactory.ADAPTABLE_CLASSES, new String[]{Resource.class.getName()});
        props.put(AdapterFactory.ADAPTER_CLASSES, new String[]{ContentFragment.class.getName()});
        context.registerService(AdapterFactory.class, new ContentFragmentAdapterFactory(), props);
        store = new ModelStore(context.resourceResolver());
    }

    private Map<String, Object> model(String path) {
        Map<String, Object> m = ModelJson.model(context.resourceResolver(), path);
        assertNotNull(m, path);
        // as the editor gets it: through JSON
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(Json.write(m));
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> fields(Map<String, Object> model) {
        return new ArrayList<>((List<Object>) model.get("fields"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(List<Object> fields, String name) {
        return fields.stream().map(o -> (Map<String, Object>) o).filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
    }

    private static List<String> names(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Object o : fields(model)) out.add((String) ((Map<?, ?>) o).get("name"));
        return out;
    }

    private ValueMap item(String modelPath, String elementName) {
        for (Resource item : context.resourceResolver().getResource(modelPath + "/" + ModelStore.ITEMS).getChildren()) {
            if (elementName.equals(item.getValueMap().get("name", String.class))) return item.getValueMap();
        }
        throw new AssertionError("no item " + elementName);
    }

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------------------------------- round trip

    @Test
    void savingAnUnchangedModelChangesNothing() throws Exception {
        for (String name : new String[]{"adventure", "article", "author"}) {
            String path = MODELS + "/" + name;
            Map<String, Object> before = model(path);
            store.save(path, (String) before.get("title"), (String) before.get("description"), fields(before));
            assertEquals(before.get("fields"), model(path).get("fields"), name);
        }
        // what the editor does not show survives, with its stored type
        ValueMap description = item(ADVENTURE, "description");
        assertEquals("true", description.get("checked", String.class));
        assertArrayEquals(new String[]{MODELS + "/adventure", MODELS + "/article"}, description.get("fragmentmodelreference", String[].class));
        assertEquals("true", item(MODELS + "/adventure", "title").get("translatable", String.class));
        assertEquals("true", item(ADVENTURE, "slug").get("unique", String.class));
        assertArrayEquals(new String[]{"YYYY-MM-DD HH:mm", "YYYY-MM-DD HH:mm"}, item(MODELS + "/author", "birthDay").get("emptyText", String[].class));
        assertEquals("100.00", item(ADVENTURE, "price").get("value", String.class));
        // and the fragments still open the same way
        ContentFragment bali = context.resourceResolver().getResource(BALI).adaptTo(ContentFragment.class);
        assertEquals("Surfing", bali.getElement("activity").getValue().getValue(String.class));
    }

    // ------------------------------------------------------------------------------------------- create

    @Test
    void createsAModelThatFragmentsAndTheEditorUse() throws Exception {
        String path = store.create("/conf/wknd-shared", null, "Event", "Something that happens");
        assertEquals(MODELS + "/event", path);
        assertEquals(0, fields(model(path)).size());
        assertTrue(ModelJson.listModels(context.resourceResolver()).stream().anyMatch(m -> path.equals(m.get("path"))));

        List<Object> fields = new ArrayList<>(Arrays.asList(
                f("kind", "text", "name", "title", "label", "Title", "required", true, "maxLength", 120),
                f("kind", "longtext", "name", "body", "label", "Body", "mimeType", "text/x-markdown"),
                f("kind", "tab", "label", "Details"),
                f("kind", "number", "name", "seats", "label", "Seats", "valueType", "long",
                        "validation", f("min", 1, "max", 500, "message", "Between 1 and 500")),
                f("kind", "number", "name", "fee", "label", "Fee", "valueType", "double", "defaultValue", 9.5),
                f("kind", "boolean", "name", "online", "label", "Online", "defaultValue", true),
                f("kind", "date", "name", "day", "label", "Day", "dateType", "date"),
                f("kind", "enumeration", "name", "format", "label", "Format", "multiple", true,
                        "options", Arrays.asList(f("value", "talk", "label", "Talk"), f("value", "workshop"))),
                f("kind", "tags", "name", "topics", "label", "Topics"),
                f("kind", "content-reference", "name", "poster", "label", "Poster", "rootPath", "/content/dam/wknd-shared",
                        "validation", f("type", "cfm.validation.contenttype.image")),
                f("kind", "fragment-reference", "name", "host", "label", "Host", "allowedModels", Arrays.asList(MODELS + "/author")),
                f("kind", "json", "name", "extra", "label", "Extra")));
        store.save(path, "Event", "Something that happens", fields);

        Map<String, Object> m = model(path);
        assertEquals(Arrays.asList("title", "body", null, "seats", "fee", "online", "day", "format", "topics", "poster", "host", "extra"),
                names(m).stream().map(n -> n != null && n.matches("\\d+") ? null : n).collect(Collectors.toList()));
        List<Object> got = fields(m);
        assertEquals("tab", ((Map<?, ?>) got.get(2)).get("kind"));
        assertEquals(true, field(got, "title").get("required"));
        assertEquals(120L, ((Number) field(got, "title").get("maxLength")).longValue());
        assertEquals("text/x-markdown", field(got, "body").get("mimeType"));
        assertEquals("long", field(got, "seats").get("valueType"));
        assertEquals("Between 1 and 500", ((Map<?, ?>) field(got, "seats").get("validation")).get("message"));
        assertEquals("double", field(got, "fee").get("valueType"));
        assertEquals("calendar/date", field(got, "day").get("valueType"));
        assertEquals(true, field(got, "format").get("multiple"));
        assertEquals(Arrays.asList(f("value", "talk", "label", "Talk"), f("value", "workshop", "label", "workshop")), field(got, "format").get("options"));
        assertEquals("content-reference", field(got, "poster").get("kind"));
        assertEquals(Arrays.asList(MODELS + "/author"), field(got, "host").get("allowedModels"));
        assertEquals("json", field(got, "extra").get("kind"));

        // the fragment side: a new fragment takes the defaults, validation follows the model
        FragmentStore fragments = new FragmentStore(context.resourceResolver());
        String event = fragments.create(DAM + "/en", "summit", "Summit", path, null);
        Map<String, Object> created = fragments.fragment(event);
        assertEquals(9.5, ((Number) ((Map<?, ?>) ((Map<?, ?>) created.get("values")).get("master")).get("fee")).doubleValue());
        Map<String, Object> values = new HashMap<>();
        values.put("seats", 0);
        FragmentStore.ValidationException e = assertThrows(FragmentStore.ValidationException.class,
                () -> fragments.save(event, "master", values, null, null, null, null));
        assertEquals("Between 1 and 500", e.errors.get(0).message);
        context.resourceResolver().revert();
        values.put("seats", 40);
        values.put("title", "Summit");
        fragments.save(event, "master", values, null, null, null, null);
        assertEquals(40L, context.resourceResolver().getResource(event + "/jcr:content/data/master").getValueMap().get("seats"));
    }

    // ------------------------------------------------------------------------------------------- edit

    @Test
    void reordersRemovesAndRetypesElements() throws Exception {
        Map<String, Object> m = model(ADVENTURE);
        List<Object> fields = fields(m);
        Map<String, Object> price = field(fields, "price");
        fields.remove(price);
        fields.add(0, price);
        fields.remove(field(fields, "gearList"));
        Map<String, Object> trip = field(fields, "tripLength");
        trip.put("kind", "number");
        trip.put("valueType", "long");
        store.save(ADVENTURE, "Adventure", null, fields);

        Map<String, Object> after = model(ADVENTURE);
        assertEquals("price", names(after).get(0));
        assertFalse(names(after).contains("gearList"));
        ValueMap tripItem = item(ADVENTURE, "tripLength");
        assertEquals("number", tripItem.get("metaType", String.class));
        assertEquals("long", tripItem.get("valueType", String.class));
        assertNull(tripItem.get("maxlength"), "nothing of the text field left");

        // fragments keep their stored values and show the new order
        ContentFragment bali = context.resourceResolver().getResource(BALI).adaptTo(ContentFragment.class);
        Iterator<ContentElement> it = bali.getElements();
        assertEquals("price", it.next().getName());
        assertEquals("<ul>", context.resourceResolver().getResource(BALI + "/jcr:content/data/master").getValueMap()
                .get("gearList", "").trim().substring(0, 4));
    }

    @Test
    void refusesInvalidModels() {
        List<Object> fields = new ArrayList<>(Arrays.asList(
                f("kind", "text", "name", "title", "label", "Title"),
                f("kind", "text", "name", "title", "label", "Again"),
                f("kind", "text", "name", "1st", "label", "Bad name"),
                f("kind", "text", "name", "_path", "label", "Reserved"),
                f("kind", "enumeration", "name", "e", "label", "No options"),
                f("kind", "text", "name", "r", "label", "Bad regex", "validation", f("regex", "([a-z")),
                f("kind", "number", "name", "n", "label", "Range", "validation", f("min", 5, "max", 1)),
                f("kind", "text", "name", "nolabel", "label", " "),
                f("kind", "rocket", "name", "x", "label", "Unknown")));
        FragmentStore.ValidationException e = assertThrows(FragmentStore.ValidationException.class,
                () -> store.save(ADVENTURE, "Adventure", null, fields));
        assertEquals(Arrays.asList("fields.1.name", "fields.2.name", "fields.3.name", "fields.4.options",
                "fields.5.validation.regex", "fields.6.validation.max", "fields.7.label", "fields.8.kind"),
                e.errors.stream().map(x -> x.field).collect(Collectors.toList()));
        assertThrows(FragmentStore.ValidationException.class, () -> store.create("/conf/wknd-shared", "adventure", "Adventure", null));
        assertThrows(IllegalArgumentException.class, () -> store.create("/content/dam", "x", "X", null));
    }

    @Test
    void deletesOnlyUnusedModelsAndDisables() throws Exception {
        FragmentStore.ValidationException used = assertThrows(FragmentStore.ValidationException.class, () -> store.delete(ADVENTURE, false));
        assertTrue(used.errors.get(0).message.contains("/content/dam/wknd-shared/en/adventures/"), used.errors.get(0).message);
        assertEquals(16, store.usage(ADVENTURE, 100).size());

        store.setEnabled(ADVENTURE, false);
        assertEquals(false, ModelJson.listModels(context.resourceResolver()).stream()
                .filter(m -> ADVENTURE.equals(m.get("path"))).findFirst().orElseThrow().get("enabled"));

        String path = store.create("/conf/wknd-shared", "scratch", "Scratch", null);
        store.delete(path, false);
        assertNull(context.resourceResolver().getResource(path));
        assertTrue(store.confs().stream().anyMatch(c -> "/conf/wknd-shared".equals(c.get("path"))));
    }
}
