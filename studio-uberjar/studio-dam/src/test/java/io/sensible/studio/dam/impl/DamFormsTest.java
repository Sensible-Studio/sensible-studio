/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
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

/** Folder metadata schemas and metadata profiles, through the DAM API. */
@ExtendWith(SlingContextExtension.class)
class DamFormsTest {

    private static final String PROFILES = "/conf/global/settings/dam/adminui-extension/metadataprofile";
    private static final String FOLDER_SCHEMAS = "/conf/global/settings/dam/adminui-extension/foldermetadataschema";

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
        context.create().resource("/content/dam/wknd/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "WKND Site");
        context.create().resource("/content/dam/wknd/summer", "jcr:primaryType", "sling:OrderedFolder");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        am.createAsset("/content/dam/wknd/surf.jpg", new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        am.createAsset("/content/dam/wknd/summer/beach.jpg", new ByteArrayInputStream(new byte[] {4, 5}), "image/jpeg", true);
        context.create().resource("/content/dam/wknd/surf.jpg/jcr:content/metadata", "dc:title", "Surf in Bali");
        resolver.commit();
    }

    private Map<String, Object> get(String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doGet(req, res);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    private Map<String, Object> post(String... params) throws Exception {
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(resolver, context.bundleContext());
        req.setMethod("POST");
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        servlet.doPost(req, res);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    private static final String PROFILE_TABS = "\"tabs\":[{\"title\":\"Basic\",\"fields\":["
            + "{\"label\":\"Title\",\"property\":\"dc:title\",\"type\":\"text\"},"
            + "{\"label\":\"Copyright\",\"property\":\"dc:rights\",\"type\":\"text\"},"
            + "{\"label\":\"Keywords\",\"property\":\"dc:subject\",\"type\":\"multi\"},"
            + "{\"label\":\"Campaign\",\"property\":\"wknd:campaign\",\"type\":\"select\",\"options\":[{\"value\":\"summer\"}]}]}]";

    @Test
    @SuppressWarnings("unchecked")
    void profileFillsWhatAFileDidNotBring() throws Exception {
        Map<String, Object> saved = post("op", "saveForm", "kind", "profile", "name", "wknd", "title", "WKND defaults", "definition",
                "{" + PROFILE_TABS + ",\"values\":{\"dc:title\":\"Untitled WKND\",\"dc:rights\":\"© WKND\",\"dc:subject\":[\"wknd\",\"travel\"],\"wknd:campaign\":\"summer\"}}");
        assertEquals(200L, saved.get("_status"), String.valueOf(saved));
        assertEquals("© WKND", ((Map<String, Object>) saved.get("values")).get("dc:rights"));
        // values are checked against the fields
        assertEquals(400L, post("op", "saveForm", "kind", "profile", "name", "bad", "definition",
                "{" + PROFILE_TABS + ",\"values\":{\"dc:creator\":\"x\"}}").get("_status"));
        assertEquals(400L, post("op", "saveForm", "kind", "profile", "name", "bad", "definition",
                "{" + PROFILE_TABS + ",\"values\":{\"wknd:campaign\":\"winter\"}}").get("_status"));
        assertEquals(400L, post("op", "saveForm", "kind", "nonsense", "name", "bad", "definition", "{" + PROFILE_TABS + "}").get("_status"));

        assertEquals(200L, post("op", "folderForm", "kind", "profile", "path", "/content/dam/wknd", "form", PROFILES + "/wknd").get("_status"));
        assertEquals(PROFILES + "/wknd", get("op", "forms", "kind", "profile", "path", "/content/dam/wknd/summer").get("effective"));
        assertEquals(PROFILES + "/wknd", ((List<Map<String, Object>>) get("op", "list", "path", "/content/dam").get("items")).stream()
                .filter(i -> "/content/dam/wknd".equals(i.get("path"))).findFirst().get().get("metadataProfile"));

        // a new file of a subfolder: everything it lacks
        assertEquals(4, DamForms.fill(resolver.getResource("/content/dam/wknd/summer/beach.jpg")));
        ValueMap beach = resolver.getResource("/content/dam/wknd/summer/beach.jpg/jcr:content/metadata").getValueMap();
        assertEquals("Untitled WKND", beach.get("dc:title", String.class));
        assertEquals(2, beach.get("dc:subject", String[].class).length);
        // the files already there, with apply: a title it has stays
        Map<String, Object> applied = post("op", "applyProfile", "path", "/content/dam/wknd");
        assertEquals(2L, applied.get("files"), String.valueOf(applied));
        assertEquals(1L, applied.get("changed"));
        ValueMap surf = resolver.getResource("/content/dam/wknd/surf.jpg/jcr:content/metadata").getValueMap();
        assertEquals("Surf in Bali", surf.get("dc:title", String.class));
        assertEquals("© WKND", surf.get("dc:rights", String.class));

        assertEquals(200L, post("op", "folderForm", "kind", "profile", "path", "/content/dam/wknd", "form", "").get("_status"));
        assertNull(get("op", "forms", "kind", "profile", "path", "/content/dam/wknd").get("effective"));
        assertEquals(200L, post("op", "deleteForm", "kind", "profile", "name", "wknd").get("_status"));
        assertNull(resolver.getResource(PROFILES + "/wknd"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void folderSchemaAddsFieldsToAFolder() throws Exception {
        String def = "{\"tabs\":[{\"title\":\"Campaign\",\"fields\":["
                + "{\"label\":\"Owner\",\"property\":\"wknd:owner\",\"type\":\"text\"},"
                + "{\"label\":\"Ends\",\"property\":\"wknd:ends\",\"type\":\"date\"},"
                + "{\"label\":\"Budget\",\"property\":\"wknd:budget\",\"type\":\"number\"}]}]}";
        assertEquals(200L, post("op", "saveForm", "kind", "folderSchema", "name", "campaign", "title", "Campaign", "definition", def).get("_status"));
        assertEquals(1, ((List<Object>) get("op", "forms", "kind", "folderSchema").get("forms")).size());
        // without a schema, a folder has no fields to write
        assertEquals(400L, post("op", "folderMetadataFields", "path", "/content/dam/wknd", "fields", "{\"wknd:owner\":\"Ana\"}").get("_status"));

        assertEquals(200L, post("op", "folderForm", "kind", "folderSchema", "path", "/content/dam/wknd", "form", FOLDER_SCHEMAS + "/campaign").get("_status"));
        Map<String, Object> w = post("op", "folderMetadataFields", "path", "/content/dam/wknd/summer", "fields",
                "{\"wknd:owner\":\"Ana\",\"wknd:ends\":{\"date\":\"2027-03-01\"},\"wknd:budget\":1500}");
        assertEquals(200L, w.get("_status"), String.valueOf(w));
        assertEquals(400L, post("op", "folderMetadataFields", "path", "/content/dam/wknd/summer", "fields", "{\"dc:title\":\"x\"}").get("_status"));

        Map<String, Object> md = get("op", "folderMetadata", "path", "/content/dam/wknd/summer");
        assertEquals("Campaign", ((Map<String, Object>) md.get("schema")).get("title"));
        Map<String, Object> values = (Map<String, Object>) md.get("values");
        assertEquals("Ana", values.get("wknd:owner"));
        assertEquals(1500L, values.get("wknd:budget"));
        assertEquals("2027-03-01", String.valueOf(values.get("wknd:ends")).substring(0, 10));
        assertEquals(400L, get("op", "folderMetadata", "path", "/content/dam/wknd/surf.jpg").get("_status"));
    }
}
