/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

/** Search Forms: the filters of the Media search, and the metadata filters they add. */
@ExtendWith(SlingContextExtension.class)
class DamSearchFormsTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private DamApiServlet servlet;
    private ResourceResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        JobManager jm = (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class}, (p, m, a) -> null);
        servlet = new DamApiServlet(jm);
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        am.createAsset("/content/dam/wknd/surf.jpg", new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/beach.jpg", new ByteArrayInputStream(new byte[] {4, 5}), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/hike.jpg", new ByteArrayInputStream(new byte[] {6}), "image/jpeg", true);
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/metadata", "dc:creator", new String[] {"Jane Doe", "Ann"},
                "dam:status", "approved", "dc:description", "Surfers at dawn in Bali");
        context.create().resource("/content/dam/wknd/beach.jpg/jcr:content/metadata", "dc:creator", "Jane Doe", "dam:status", "rejected");
        context.create().resource("/content/dam/wknd/hike.jpg/jcr:content/metadata", "dc:creator", "Bob");
        resolver.commit();
    }

    private Map<String, Object> call(boolean post, String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        if (post) {
            req.setMethod("POST");
            req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        }
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        if (post) {
            servlet.doPost(req, res);
        } else {
            servlet.doGet(req, res);
        }
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<String> types(Map<String, Object> form) {
        return ((List<Map<String, Object>>) form.get("facets")).stream().map(f -> (String) f.get("type")).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> found(String... params) throws Exception {
        String[] all = new String[params.length + 4];
        all[0] = "op";
        all[1] = "search";
        all[2] = "path";
        all[3] = "/content/dam/wknd";
        System.arraycopy(params, 0, all, 4, params.length);
        Map<String, Object> r = call(false, all);
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        return ((List<Map<String, Object>>) r.get("items")).stream().map(i -> (String) i.get("name")).sorted().collect(Collectors.toList());
    }

    @Test
    void withoutAFormOfItsOwnTheSearchShowsTheDefault() throws Exception {
        Map<String, Object> form = call(false, "op", "searchForm");
        assertEquals(DamSearchForms.BUILT_IN, types(form));
        assertEquals(false, form.get("custom"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAdminReordersTheFiltersAndAddsMetadataOnes() throws Exception {
        Map<String, Object> saved = call(true, "op", "saveSearchForm", "facets", "["
                + "{\"type\":\"options\",\"label\":\"Status\",\"property\":\"dam:status\",\"options\":[{\"value\":\"approved\",\"label\":\"Approved\"},{\"value\":\"rejected\"}]},"
                + "{\"type\":\"tags\",\"label\":\"Topics\",\"root\":\"wknd:\"},"
                + "{\"type\":\"text\",\"label\":\"Photographer\",\"property\":\"dc:creator\"},"
                + "{\"type\":\"types\"}]");
        assertEquals(200L, saved.get("_status"), String.valueOf(saved));
        Map<String, Object> form = call(false, "op", "searchForm");
        assertEquals(List.of("options", "tags", "text", "types"), types(form));
        assertEquals(true, form.get("custom"));
        List<Map<String, Object>> facets = (List<Map<String, Object>>) form.get("facets");
        assertEquals("wknd:", facets.get(1).get("root"));
        assertEquals("File type", facets.get(3).get("label"));
        List<Map<String, Object>> options = (List<Map<String, Object>>) facets.get(0).get("options");
        assertEquals("Approved", options.get(0).get("label"));
        assertEquals("rejected", options.get(1).get("label"));

        // what is checked
        assertEquals(400L, call(true, "op", "saveSearchForm", "facets", "[{\"type\":\"types\"},{\"type\":\"types\"}]").get("_status"));
        assertEquals(400L, call(true, "op", "saveSearchForm", "facets", "[{\"type\":\"text\",\"label\":\"x\"}]").get("_status"));
        assertEquals(400L, call(true, "op", "saveSearchForm", "facets", "[{\"type\":\"text\",\"property\":\"jcr:content/../x\"}]").get("_status"));
        assertEquals(400L, call(true, "op", "saveSearchForm", "facets", "[{\"type\":\"options\",\"property\":\"dam:status\",\"options\":[]}]").get("_status"));
        assertEquals(400L, call(true, "op", "saveSearchForm", "facets", "[{\"type\":\"nonsense\"}]").get("_status"));
        assertEquals(400L, call(true, "op", "saveSearchForm", "facets", "{}").get("_status"));
        assertEquals(List.of("options", "tags", "text", "types"), types(call(false, "op", "searchForm")));

        Map<String, Object> reset = call(true, "op", "resetSearchForm");
        assertEquals(200L, reset.get("_status"));
        assertEquals(DamSearchForms.BUILT_IN, types(reset));
        assertFalse(resolver.getResource(DamSearchForms.STORE) != null);
    }

    @Test
    void metadataFiltersFindTheFiles() throws Exception {
        assertEquals(List.of("beach.jpg", "hike.jpg", "surf.jpg"), found());
        // one of the values; any case; any value of a multi-value property
        assertEquals(List.of("beach.jpg", "surf.jpg"), found("meta", "dam:status=Approved", "meta", "dam:status=rejected"));
        assertEquals(List.of("surf.jpg"), found("meta", "dc:creator=ann"));
        // properties are ANDed
        assertEquals(List.of("beach.jpg"), found("meta", "dc:creator=Jane Doe", "meta", "dam:status=rejected"));
        // a text the property contains
        assertEquals(List.of("surf.jpg"), found("metaText", "dc:description=bali"));
        assertEquals(List.of("beach.jpg", "surf.jpg"), found("metaText", "dc:creator=jane"));
        // a bad property is left out, not an error
        assertEquals(List.of("beach.jpg", "hike.jpg", "surf.jpg"), found("meta", "../x=1"));
    }

    @Test
    void smartCollectionsKeepTheMetadataFilters() {
        DamSearch.Query q = DamSearch.fromJson(Json.parse("{\"meta\":{\"dam:status\":[\"approved\"]},\"metaText\":{\"dc:description\":\"dawn\"}}"));
        assertEquals("approved", q.meta.get("dam:status").iterator().next());
        assertEquals("dawn", q.metaText.get("dc:description"));
        assertTrue(q.assetFiltersOnly());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsTheValuesOfAPropertyMostUsedFirst() throws Exception {
        Map<String, Object> r = call(false, "op", "metadataValues", "path", "/content/dam", "property", "dc:creator");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        List<Map<String, Object>> values = (List<Map<String, Object>>) r.get("values");
        assertEquals("Jane Doe", values.get(0).get("value"));
        assertEquals(2L, values.get(0).get("count"));
        assertEquals(3, values.size());
        assertEquals(400L, call(false, "op", "metadataValues", "path", "/content/dam", "property", "a/../b").get("_status"));
    }
}
