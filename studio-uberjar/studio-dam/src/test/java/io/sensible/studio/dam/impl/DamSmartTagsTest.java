/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.JobManager;
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
class DamSmartTagsTest {

    private static final String SURF = "/content/dam/wknd/surf.jpg";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private final List<String> jobs = new ArrayList<>();
    private ResourceResolver resolver;
    private DamApiServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        context.create().resource("/content/cq:tags/wknd", "jcr:primaryType", "cq:Tag", "jcr:title", "WKND");
        context.create().resource("/content/cq:tags/wknd/activity", "jcr:primaryType", "cq:Tag", "jcr:title", "Activity");
        context.create().resource("/content/cq:tags/wknd/activity/surfing", "jcr:primaryType", "cq:Tag", "jcr:title", "Surfing");
        context.create().resource("/content/cq:tags/wknd/activity/hunting-fishing", "jcr:primaryType", "cq:Tag", "jcr:title",
                "Hunting & Fishing");
        context.create().resource("/content/cq:tags/wknd/region", "jcr:primaryType", "cq:Tag", "jcr:title", "Region");
        context.create().resource("/content/cq:tags/wknd/region/italy", "jcr:primaryType", "cq:Tag", "jcr:title", "Italy");
        context.create().resource("/conf/global/settings/dam", "jcr:primaryType", "sling:Folder");
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:Folder");
        new AssetManagerImpl(resolver).createAsset(SURF, new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/jpeg", true);
        new AssetManagerImpl(resolver).createAsset("/content/dam/wknd/notes.txt", new ByteArrayInputStream(new byte[] {1}), "text/plain",
                true);
        context.load().binaryFile(new ByteArrayInputStream(new byte[] {9, 9}), SURF + "/jcr:content/renditions/cq5dam.web.1280.1280.jpeg",
                "image/jpeg");
        resolver.getResource(SURF + "/jcr:content/metadata").adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put("cq:tags", new String[] {"wknd:region/italy"});
        resolver.commit();
        JobManager jm = (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class},
                (p, m, a) -> {
                    if ("addJob".equals(m.getName())) {
                        jobs.add(a[0] + " " + ((Map<?, ?>) a[1]).get(AssetProcessingJob.PROP_PATH));
                    }
                    return null;
                });
        servlet = new DamApiServlet(jm, dam);
    }

    private static Map<String, Object> p(String id, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("confidence", confidence);
        m.put("score", 0.01);
        return m;
    }

    private DamSmartTags.Settings settings(String... roots) {
        DamSmartTags.Settings s = new DamSmartTags.Settings();
        s.enabled = true;
        s.tagRoots = List.of(roots);
        return s;
    }

    @Test
    void labelsAreTheSubTagsOfTheRootsAndTheKeywords() {
        List<Map<String, String>> labels = DamSmartTags.labels(resolver, settings("wknd:activity", "wknd:nope"));
        assertEquals("wknd:activity/surfing", labels.get(0).get("id"));
        assertEquals("Surfing", labels.get(0).get("text"));
        assertEquals("Hunting and Fishing", labels.get(1).get("text"));
        assertEquals("keyword:person", labels.get(2).get("id"));
        assertEquals(2 + DamSmartTags.KEYWORDS.size(), labels.size());
        DamSmartTags.Settings s = settings("wknd:");
        s.keywords = false;
        assertEquals(5, DamSmartTags.labels(resolver, s).size(), "a namespace: every tag in it");
        assertNull(DamSmartTags.tagResource(resolver, "wknd:../../etc"));
    }

    @Test
    void tagsAnAssetFromItsWebRendition() throws Exception {
        dam.predictions = List.of(p("wknd:activity/surfing", 0.81), p("keyword:beach", 0.1), p("wknd:region/italy", 0.05),
                p("keyword:ocean", 0.04));
        DamSmartTags.Settings s = settings("wknd:");
        s.max = 3;
        assertEquals(DamSmartTags.Outcome.TAGGED, DamSmartTags.tag(resolver, dam, resolver.getResource(SURF), s));
        resolver.commit();
        ValueMap md = resolver.getResource(SURF + "/jcr:content/metadata").getValueMap();
        assertArrayEquals(new String[] {"wknd:activity/surfing"}, md.get(DamSmartTags.PN_TAGS, String[].class));
        assertArrayEquals(new Double[] {0.81}, md.get(DamSmartTags.PN_CONFIDENCE, Double[].class));
        assertArrayEquals(new String[] {"beach", "ocean"}, md.get(DamSmartTags.PN_KEYWORDS, String[].class), "italy is a tag already");
        assertTrue(dam.blobs.values().stream().anyMatch(b -> b.length == 2 && b[0] == 9), "the web rendition was sent");
        assertEquals(DamSmartTags.Outcome.NO_IMAGE,
                DamSmartTags.tag(resolver, dam, resolver.getResource("/content/dam/wknd/notes.txt"), s));
    }

    private Map<String, Object> call(String user, String method, String... params) throws Exception {
        ResourceResolver as = new org.apache.sling.api.wrappers.ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return user;
            }
        };
        MockSlingHttpServletRequest req = new MockSlingHttpServletRequest(as, context.bundleContext());
        req.setMethod(method);
        req.addHeader(DamApiServlet.CSRF_HEADER, "1");
        for (int i = 0; i < params.length; i += 2) {
            req.addRequestParameter(params[i], params[i + 1]);
        }
        MockSlingHttpServletResponse res = new MockSlingHttpServletResponse();
        if ("POST".equals(method)) {
            servlet.doPost(req, res);
        } else {
            servlet.doGet(req, res);
        }
        Map<String, Object> body = Json.parseObject(res.getOutputAsString());
        body.put("_status", (long) res.getStatus());
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminSetsItUpAndAuthorsCorrectIt() throws Exception {
        assertEquals(403L, call("ana", "POST", "op", "smartTagsSettings", "enabled", "true").get("_status"));
        assertEquals(400L, call("admin", "POST", "op", "smartTagsSettings", "enabled", "true", "tagRoot", "wknd:nope").get("_status"));
        Map<String, Object> saved = call("admin", "POST", "op", "smartTagsSettings", "enabled", "true", "tagRoot", "wknd:activity",
                "max", "4", "minConfidence", "0.1");
        assertEquals(200L, saved.get("_status"), String.valueOf(saved));
        Map<String, Object> status = call("ana", "GET", "op", "smartTags");
        Map<String, Object> st = (Map<String, Object>) status.get("settings");
        assertEquals(true, st.get("enabled"));
        assertEquals(List.of("wknd:activity"), st.get("tagRoots"));
        assertEquals(4L, st.get("max"));
        assertEquals(true, ((Map<String, Object>) status.get("model")).get("installed"));

        Map<String, Object> q = call("ana", "POST", "op", "smartTag", "path", "/content/dam/wknd");
        assertEquals(2L, q.get("queued"));
        assertTrue(jobs.contains(DamSmartTagJob.TOPIC + " " + SURF), jobs.toString());

        dam.predictions = List.of(p("wknd:activity/surfing", 0.9), p("wknd:activity/hunting-fishing", 0.06), p("keyword:beach", 0.05));
        DamSmartTags.tag(resolver, dam, resolver.getResource(SURF), DamSmartTags.settings(resolver));
        resolver.commit();
        Map<String, Object> asset = call("ana", "GET", "op", "asset", "path", SURF);
        Map<String, Object> smart = (Map<String, Object>) asset.get("smartTags");
        List<Map<String, Object>> tags = (List<Map<String, Object>>) smart.get("tags");
        assertEquals("Surfing", tags.get(0).get("title"));
        assertEquals(List.of("beach"), smart.get("keywords"));

        // apply one, remove another: they stay that way when the asset is tagged again
        assertEquals(200L, call("ana", "POST", "op", "smartTagApply", "path", SURF, "tag", "wknd:activity/surfing").get("_status"));
        assertEquals(200L, call("ana", "POST", "op", "smartTagRemove", "path", SURF, "tag", "wknd:activity/hunting-fishing").get("_status"));
        assertEquals(400L, call("ana", "POST", "op", "smartTagApply", "path", SURF, "tag", "keyword:beach").get("_status"));
        assertEquals(200L, call("ana", "POST", "op", "smartTagRemove", "path", SURF, "tag", "keyword:beach").get("_status"));
        assertEquals(404L, call("ana", "POST", "op", "smartTagRemove", "path", SURF, "tag", "wknd:activity/x").get("_status"));
        ValueMap md = resolver.getResource(SURF + "/jcr:content/metadata").getValueMap();
        assertArrayEquals(new String[] {"wknd:region/italy", "wknd:activity/surfing"}, md.get("cq:tags", String[].class));
        DamSmartTags.tag(resolver, dam, resolver.getResource(SURF), DamSmartTags.settings(resolver));
        resolver.commit();
        md = resolver.getResource(SURF + "/jcr:content/metadata").getValueMap();
        assertArrayEquals(new String[0], md.get(DamSmartTags.PN_TAGS, String[].class));
        assertArrayEquals(new String[] {"wknd:activity/hunting-fishing", "keyword:beach"}, md.get(DamSmartTags.PN_REMOVED, String[].class));
    }

    @Test
    void processingTagsWhenSwitchedOnAndNeverFailsBecauseOfIt() throws Exception {
        dam.nextResult = new ProcessResult("blob", "image/jpeg", new LinkedHashMap<>(), null, List.of(), List.of());
        dam.predictions = List.of(p("wknd:activity/surfing", 0.9));
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, SURF));
        assertNull(resolver.getResource(SURF + "/jcr:content/metadata").getValueMap().get(DamSmartTags.PN_TAGS), "off by default");

        DamSmartTags.save(resolver, settings("wknd:activity"));
        resolver.commit();
        dam.failPredictWith = new DamClient.DamProcessingException(409, "the Smart Tags model is not installed");
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, SURF));
        dam.failPredictWith = null;
        assertEquals(AssetProcessor.Outcome.PROCESSED, new AssetProcessor(dam).process(resolver, SURF));
        assertArrayEquals(new String[] {"wknd:activity/surfing"},
                resolver.getResource(SURF + "/jcr:content/metadata").getValueMap().get(DamSmartTags.PN_TAGS, String[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void foldersSwitchItOnOrOffForThemselvesAndBelow() throws Exception {
        context.create().resource("/content/dam/wknd/archive", "jcr:primaryType", "sling:Folder");
        resolver.commit();
        DamSmartTags.Settings off = settings("wknd:activity");
        off.enabled = false;
        assertEquals(false, DamSmartTags.enabledIn(resolver.getResource("/content/dam/wknd/archive"), off));
        assertEquals(200L, call("ana", "POST", "op", "folderSmartTags", "path", "/content/dam/wknd", "value", "on").get("_status"));
        assertEquals(200L, call("ana", "POST", "op", "folderSmartTags", "path", "/content/dam/wknd/archive", "value", "off").get("_status"));
        assertEquals(400L, call("ana", "POST", "op", "folderSmartTags", "path", "/content/dam/wknd", "value", "maybe").get("_status"));
        assertEquals(true, DamSmartTags.enabledIn(resolver.getResource("/content/dam/wknd"), off), "the folder says on");
        assertEquals(false, DamSmartTags.enabledIn(resolver.getResource("/content/dam/wknd/archive"), settings("wknd:activity")));
        Map<String, Object> list = call("ana", "GET", "op", "list", "path", "/content/dam/wknd");
        Map<String, Object> archive = ((List<Map<String, Object>>) list.get("items")).stream()
                .filter(i -> "/content/dam/wknd/archive".equals(i.get("path"))).findFirst().get();
        assertEquals("off", archive.get("smartTagging"));
        assertEquals(true, call("ana", "GET", "op", "smartTags", "path", "/content/dam/wknd").get("effective"));
        assertEquals(200L, call("ana", "POST", "op", "folderSmartTags", "path", "/content/dam/wknd/archive", "value", "").get("_status"));
        assertEquals(true, DamSmartTags.enabledIn(resolver.getResource("/content/dam/wknd/archive"), off), "inherits on again");
    }
}
