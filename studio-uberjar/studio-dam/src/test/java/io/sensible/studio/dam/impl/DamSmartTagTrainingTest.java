/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sling.api.resource.ModifiableValueMap;
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

/** Smart Tag training: Auto tags learn your tags from the pictures you tagged with them. */
@ExtendWith(SlingContextExtension.class)
class DamSmartTagTrainingTest {

    private static final String SURFING = "wknd:activity/surfing";
    private static final String WINTER = "wknd:season/winter";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private final Random random = new Random(42);
    private DamApiServlet servlet;
    private ResourceResolver resolver;
    private AssetManagerImpl am;

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        JobManager jm = (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class}, (p, m, a) -> null);
        servlet = new DamApiServlet(jm);
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/cq:tags/wknd/activity/surfing", "jcr:primaryType", "cq:Tag", "jcr:title", "Surfing");
        // no tags to read from names, no keywords: only what is learned
        context.create().resource(DamSmartTags.CONF, "enabled", true, "keywords", false);
        am = new AssetManagerImpl(resolver);
        // six surf pictures (one tagged only below the tag), two winter ones, ten others
        for (int i = 0; i < 6; i++) {
            picture("surf" + i, near(0), i == 5 ? new String[] {SURFING + "/big-waves"} : new String[] {SURFING});
        }
        for (int i = 0; i < 2; i++) {
            picture("snow" + i, near(2), new String[] {WINTER});
        }
        for (int i = 0; i < 10; i++) {
            picture("city" + i, near(1), new String[0]);
        }
        resolver.commit();
    }

    /** The embedding right on axis {@code axis}: a picture of the very thing. */
    private static float[] exactly(int axis) {
        float[] e = new float[DamSimilar.DIMENSIONS];
        e[axis] = 1;
        return e;
    }

    /** An embedding around axis {@code axis}. */
    private float[] near(int axis) {
        float[] e = new float[DamSimilar.DIMENSIONS];
        for (int i = 0; i < e.length; i++) {
            e[i] = (float) (random.nextGaussian() * 0.02);
        }
        e[axis] += 1;
        return DamSmartTagTraining.normalize(e);
    }

    private void picture(String name, float[] e, String[] tags) throws Exception {
        String path = "/content/dam/wknd/" + name + ".jpg";
        am.createAsset(path, new ByteArrayInputStream(new byte[] {1}), "image/jpeg", true);
        ModifiableValueMap c = resolver.getResource(path + "/jcr:content").adaptTo(ModifiableValueMap.class);
        if (e != null) {
            c.put(DamSimilar.PN_EMBEDDING, new ByteArrayInputStream(DamSimilar.encode(e)));
            c.put(DamSimilar.PN_MODEL, DamSimilar.MODEL);
        }
        context.create().resource(path + "/jcr:content/metadata", "cq:tags", tags);
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

    @Test
    @SuppressWarnings("unchecked")
    void saysHowManyPicturesCouldTeachATag() throws Exception {
        Map<String, Object> s = call(false, "op", "smartTagTraining", "tag", SURFING, "tag", WINTER);
        assertEquals(200L, s.get("_status"), String.valueOf(s));
        List<Map<String, Object>> ready = (List<Map<String, Object>>) s.get("ready");
        assertEquals("Surfing", ready.get(0).get("title"));
        assertEquals(6L, ready.get(0).get("examples"));
        assertEquals(2L, ready.get(1).get("examples"));
        assertEquals(5L, s.get("minExamples"));
        assertTrue(((List<Object>) s.get("models")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void learnsATagFromItsPicturesAndGivesItToNewOnes() throws Exception {
        Map<String, Object> r = call(true, "op", "trainSmartTags", "tag", SURFING, "tag", WINTER);
        assertEquals(200L, r.get("_status"), String.valueOf(r));
        List<Map<String, Object>> results = (List<Map<String, Object>>) r.get("results");
        Map<String, Object> surf = results.get(0);
        assertEquals(6L, surf.get("examples"));
        assertTrue(((Number) surf.get("recall")).doubleValue() >= 0.8, String.valueOf(surf));
        assertEquals(0L, surf.get("others"));
        assertTrue(String.valueOf(results.get(1).get("error")).contains("Only 2 pictures"));
        assertEquals(1, ((List<Object>) call(false, "op", "smartTagTraining").get("models")).size());

        // a new surf picture gets the tag; a city one does not
        List<DamSmartTagTraining.Model> models = DamSmartTagTraining.models(resolver);
        assertEquals(SURFING, DamSmartTagTraining.predict(models, exactly(0)).get(0).get("id"));
        assertTrue(DamSmartTagTraining.predict(models, exactly(1)).isEmpty());

        // and through Auto tags, without any tag to read from names
        picture("new-surf", exactly(0), new String[0]);
        assertEquals(DamSmartTags.Outcome.TAGGED, DamSmartTags.tag(resolver, null,
                resolver.getResource("/content/dam/wknd/new-surf.jpg"), DamSmartTags.settings(resolver)));
        assertEquals(Arrays.asList(SURFING), Arrays.asList(resolver.getResource("/content/dam/wknd/new-surf.jpg/jcr:content/metadata")
                .getValueMap().get(DamSmartTags.PN_TAGS, String[].class)));

        assertEquals(200L, call(true, "op", "forgetSmartTag", "tag", SURFING).get("_status"));
        assertTrue(DamSmartTagTraining.models(resolver).isEmpty());
        assertEquals(404L, call(true, "op", "forgetSmartTag", "tag", SURFING).get("_status"));
        assertEquals(400L, call(true, "op", "trainSmartTags", "tag", "not a tag").get("_status"));
    }

    @Test
    void howCloseIsEnoughComesFromTheExamples() {
        List<float[]> pos = Arrays.asList(near(0), near(0), near(0), near(0), near(0));
        List<float[]> neg = Arrays.asList(near(1), near(1), near(0)); // one look-alike without the tag
        DamSmartTagTraining.Model m = DamSmartTagTraining.learn(SURFING, pos, neg);
        assertEquals(5, m.examples);
        assertTrue(m.recall >= 0.8);
        // the two others far away never pass; the look-alike may, and is then counted
        assertTrue(m.others <= 1);
        assertTrue(m.threshold > 0.5);
        assertEquals(0.5, DamSmartTagTraining.percentile(new double[] {0.9, 0.1, 0.5}, 0.5));
    }
}
