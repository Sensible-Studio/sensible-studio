/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.WcmAdapterFactory;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;

@ExtendWith(SlingContextExtension.class)
class DamReportsTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private ResourceResolver resolver;
    private DamReportServlet servlet;

    private static Calendar day(int y, int m, int d) {
        return new GregorianCalendar(y, m - 1, d, 12, 0);
    }

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam/wknd/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:title", "WKND");
        context.create().resource("/content/dam/wknd/surf", "jcr:primaryType", "sling:Folder");
        AssetManagerImpl am = new AssetManagerImpl(resolver);
        asset(am, "/content/dam/wknd/old.jpg", 1000, day(2025, 1, 10), day(2025, 2, 1));
        asset(am, "/content/dam/wknd/surf/bali.jpg", 3000, day(2026, 10, 1), day(2026, 10, 15));
        asset(am, "/content/dam/wknd/surf/=cmd.jpg", 500, day(2026, 10, 5), day(2026, 10, 5));
        ModifiableValueMap md = resolver.getResource("/content/dam/wknd/old.jpg/jcr:content/metadata").adaptTo(ModifiableValueMap.class);
        md.put("prism:expirationDate", day(2026, 3, 1));
        md.put("dc:creator", "Ana");
        ModifiableValueMap c = resolver.getResource("/content/dam/wknd/surf/bali.jpg/jcr:content").adaptTo(ModifiableValueMap.class);
        c.put("cq:lastReplicationAction", "Activate");
        c.put("cq:lastReplicated", day(2026, 10, 16));
        c.put("cq:lastReplicatedBy", "admin");
        context.load().binaryFile(new ByteArrayInputStream(new byte[700]),
                "/content/dam/wknd/surf/bali.jpg/jcr:content/renditions/cq5dam.web.1280.1280.jpeg", "image/jpeg");
        context.create().resource("/content/dam/wknd/surf/bali.jpg/jcr:content/renditions/cq5dam.web.1280.1280.jpeg/jcr:content/metadata",
                "dam:size", 700L);
        resolver.commit();
        servlet = new DamReportServlet(context.getService(ResourceResolverFactory.class));
    }

    private void asset(AssetManagerImpl am, String path, long size, Calendar created, Calendar modified) throws Exception {
        am.createAsset(path, new ByteArrayInputStream(new byte[(int) size]), "image/jpeg", true);
        resolver.getResource(path).adaptTo(ModifiableValueMap.class).put("jcr:created", created);
        resolver.getResource(path).adaptTo(ModifiableValueMap.class).put("jcr:createdBy", "ana");
        resolver.getResource(path + "/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:lastModified", modified);
        if (resolver.getResource(path + "/jcr:content/metadata") == null) {
            context.create().resource(path + "/jcr:content/metadata", "dam:size", size);
        } else {
            resolver.getResource(path + "/jcr:content/metadata").adaptTo(ModifiableValueMap.class).put("dam:size", size);
        }
    }

    private MockSlingHttpServletResponse call(String user, String method, String ext, boolean csrf, String... params) throws Exception {
        ResourceResolver as = new org.apache.sling.api.wrappers.ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return user;
            }
        };
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(as, context.bundleContext());
        req.setMethod(method);
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) req.getRequestPathInfo()).setResourcePath("/bin/studio/dam/reports");
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) req.getRequestPathInfo()).setExtension(ext);
        if (csrf) {
            req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        }
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        if ("POST".equals(method)) {
            servlet.doPost(req, res);
        } else {
            servlet.doGet(req, res);
        }
        return res;
    }

    private Map<String, Object> create(String user, String... params) throws Exception {
        String[] all = new String[params.length + 2];
        all[0] = "op";
        all[1] = "create";
        System.arraycopy(params, 0, all, 2, params.length);
        MockSlingHttpServletResponse res = call(user, "POST", "json", true, all);
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> rows(String user, String id) throws Exception {
        MockSlingHttpServletResponse res = call(user, "GET", "json", false, "id", id, "limit", "100");
        assertEquals(200, res.getStatus(), res.getOutputAsString());
        return (List<List<String>>) (Object) Json.parseObject(res.getOutputAsString()).get("items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadReportListsWhatWasAddedInTheDates() throws Exception {
        Map<String, Object> r = create("ana", "type", "assetadditionreport", "title", "October uploads", "path", "/content/dam/wknd",
                "from", "2026-10-01", "to", "2026-10-31", "column", "path", "column", "size", "column", "addedBy", "property", "dc:creator");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        assertEquals("completed", r.get("status"));
        assertEquals(2L, r.get("rows"));
        assertEquals(3500L, ((Map<String, Object>) r.get("summary")).get("size"));
        Map<String, Object> page = Json.parseObject(call("ana", "GET", "json", false, "id", (String) r.get("id")).getOutputAsString());
        assertEquals(List.of("Path", "Size", "Added By", "dc:creator"), page.get("header"));
        List<List<String>> items = rows("ana", (String) r.get("id"));
        assertEquals(List.of("/content/dam/wknd/surf/bali.jpg", "3000", "ana", ""), items.get(0));

        MockSlingHttpServletResponse csv = call("ana", "GET", "csv", false, "id", (String) r.get("id"));
        assertEquals("text/csv;charset=UTF-8", csv.getContentType());
        assertTrue(csv.getHeader("Content-Disposition").contains("october-uploads.csv"));
        String text = csv.getOutputAsString();
        assertTrue(text.startsWith("Path,Size,Added By,dc:creator\r\n"), text);
        assertTrue(text.contains("/content/dam/wknd/surf/=cmd.jpg"), "a path is not a formula");
    }

    @Test
    void otherTypesFilterByTheirOwnDates() throws Exception {
        Map<String, Object> mod = create("ana", "type", "assetmodificationreport", "title", "m", "from", "2026-10-10");
        assertEquals(1L, mod.get("rows"), String.valueOf(mod));
        Map<String, Object> exp = create("ana", "type", "assetexpiryreport", "title", "e", "column", "path", "column", "expires");
        assertEquals(1L, exp.get("rows"), "expired by now, without dates");
        assertEquals("/content/dam/wknd/old.jpg", rows("ana", (String) exp.get("id")).get(0).get(0));
        Map<String, Object> pub = create("ana", "type", "assetpublishreport", "title", "p", "column", "path", "column", "published",
                "column", "publishedBy");
        assertEquals(1L, pub.get("rows"));
        assertEquals("admin", rows("ana", (String) pub.get("id")).get(0).get(2));
        Map<String, Object> files = create("ana", "type", "foldercontentreport", "title", "f", "path", "/content/dam/wknd/surf");
        assertEquals(2L, files.get("rows"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diskUsageAddsUpSubFolders() throws Exception {
        Map<String, Object> r = create("ana", "type", "foldersizeandstrengthreport", "title", "Disk", "path", "/content/dam/wknd",
                "includeRenditions", "true");
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        Map<String, Object> s = (Map<String, Object>) r.get("summary");
        assertEquals(3L, s.get("assets"));
        assertEquals(4500L, s.get("size"));
        assertEquals(700L, s.get("renditionsSize"));
        List<List<String>> items = rows("ana", (String) r.get("id"));
        assertEquals(List.of("/content/dam/wknd", "WKND", "3", "4500", "700"), items.get(0));
        assertEquals(List.of("/content/dam/wknd/surf", "surf", "2", "3500", "700"), items.get(1));

        Map<String, Object> only = create("ana", "type", "foldersizeandstrengthreport", "title", "Disk", "path", "/content/dam/wknd",
                "excludeSubfolders", "true");
        assertEquals(1L, only.get("rows"));
    }

    @Test
    void linkShareReportShowsTheUsersLinks() throws Exception {
        DamLinkShare.create(new org.apache.sling.api.wrappers.ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return "ana";
            }
        }, resolver, List.of("/content/dam/wknd/old.jpg"), 3, false);
        assertEquals(1L, create("ana", "type", "linksharereport", "title", "links").get("rows"));
        assertEquals(0L, create("bob", "type", "linksharereport", "title", "links").get("rows"));
        assertEquals(1L, create("admin", "type", "linksharereport", "title", "links").get("rows"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsBelongToTheirOwner() throws Exception {
        String id = (String) create("ana", "type", "foldercontentreport", "title", "Mine").get("id");
        assertEquals(1, ((List<Object>) Json.parseObject(call("ana", "GET", "json", false).getOutputAsString()).get("items")).size());
        assertEquals(0, ((List<Object>) Json.parseObject(call("bob", "GET", "json", false).getOutputAsString()).get("items")).size());
        assertEquals(404, call("bob", "GET", "csv", false, "id", id).getStatus());
        assertEquals(0L, Json.parseObject(call("bob", "POST", "json", true, "op", "delete", "id", id).getOutputAsString()).get("deleted"));
        assertEquals(200, call("admin", "GET", "json", false, "id", id).getStatus());
        assertEquals(1L, Json.parseObject(call("ana", "POST", "json", true, "op", "delete", "id", id).getOutputAsString()).get("deleted"));
        assertNull(resolver.getResource(DamReports.ROOT + "/" + id));
        assertEquals(404, call("ana", "GET", "json", false, "id", "../../content").getStatus());
    }

    @Test
    void refusesWhatItCannotRun() throws Exception {
        assertEquals(400L, create("ana", "type", "nope", "title", "x").get("_status"));
        assertEquals(400L, create("ana", "type", "foldercontentreport").get("_status"), "no title");
        assertEquals(400L, create("ana", "type", "foldercontentreport", "title", "x", "property", "jcr:data;drop").get("_status"));
        assertEquals(400L, create("ana", "type", "foldercontentreport", "title", "x", "column", "secret").get("_status"));
        assertEquals(400L, create("ana", "type", "assetadditionreport", "title", "x", "from", "yesterday").get("_status"));
        assertEquals(400L, create("ana", "type", "assetadditionreport", "title", "x", "from", "2026-10-10", "to", "2026-10-01").get("_status"));
        assertEquals(404L, create("ana", "type", "foldercontentreport", "title", "x", "path", "/content/dam/missing").get("_status"));
        assertEquals(404L, create("ana", "type", "foldercontentreport", "title", "x", "path", "/etc").get("_status"));
        assertEquals(401, call("anonymous", "POST", "json", true, "op", "create", "type", "foldercontentreport", "title", "x").getStatus());
        assertEquals(403, call("ana", "POST", "json", false, "op", "create", "type", "foldercontentreport", "title", "x").getStatus());
        assertEquals(401, call(null, "GET", "json", false).getStatus());
        assertTrue(Json.parseObject(call("ana", "GET", "json", false, "op", "catalog").getOutputAsString()).containsKey("types"));
        assertTrue(StandardCharsets.UTF_8.name().length() > 0);
    }
}
