/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;
import io.sensible.studio.wcm.impl.WcmAdapterFactory;

@ExtendWith(SlingContextExtension.class)
class DamSimilarTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);
    private final FakeDamClient dam = new FakeDamClient();
    private ResourceResolver resolver;

    /** A unit vector mostly along `axis`, leaning a little towards `towards`. */
    private static float[] vector(int axis, int towards, double lean) {
        float[] e = new float[DamSimilar.DIMENSIONS];
        e[axis] = (float) Math.sqrt(1 - lean * lean);
        e[towards] += (float) lean;
        return e;
    }

    private Resource asset(String name, int firstByte) throws Exception {
        String path = "/content/dam/wknd/" + name;
        new AssetManagerImpl(resolver).createAsset(path, new ByteArrayInputStream(new byte[] {(byte) firstByte, 2, 3}), "image/jpeg", true);
        resolver.commit();
        return resolver.getResource(path);
    }

    @BeforeEach
    void setUp() throws Exception {
        context.registerInjectActivateService(new WcmAdapterFactory());
        resolver = context.resourceResolver();
        context.create().resource("/content/dam", "jcr:primaryType", "sling:OrderedFolder");
        context.create().resource("/content/dam/wknd", "jcr:primaryType", "sling:OrderedFolder");
        dam.embeddings.put(1, vector(0, 1, 0.0));   // the picture asked about
        dam.embeddings.put(2, vector(0, 1, 0.2));   // nearly the same
        dam.embeddings.put(3, vector(0, 1, 0.8));   // somewhat alike
        dam.embeddings.put(4, vector(5, 6, 0.0));   // nothing in common
    }

    @Test
    void theEmbeddingIsKeptWithTheAssetAndReadBack() throws Exception {
        Resource a = asset("a.jpg", 2);
        assertNull(DamSimilar.stored(a));
        assertTrue(DamSimilar.index(dam, a));
        resolver.commit();
        assertArrayEquals(dam.embeddings.get(2), DamSimilar.stored(resolver.getResource(a.getPath())), 1e-6f);
        assertEquals(DamSimilar.MODEL, resolver.getResource(a.getPath() + "/jcr:content").getValueMap().get(DamSimilar.PN_MODEL, String.class));
        float[] e = dam.embeddings.get(3);
        assertArrayEquals(e, DamSimilar.decode(DamSimilar.encode(e)), 0f);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMostAlikeComeFirstAndPicturesWithoutAnEmbeddingAreCounted() throws Exception {
        Resource mine = asset("mine.jpg", 1);
        for (String[] other : new String[][] {{"near.jpg", "2"}, {"alike.jpg", "3"}, {"far.jpg", "4"}}) {
            assertTrue(DamSimilar.index(dam, asset(other[0], Integer.parseInt(other[1]))));
        }
        asset("later.jpg", 2); // has a picture, no embedding yet
        resolver.commit();

        Map<String, Object> res = DamSimilar.similar(resolver, dam, resolver.getResource(mine.getPath()), 2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        assertEquals(2, items.size(), "the limit");
        assertEquals("/content/dam/wknd/near.jpg", items.get(0).get("path"));
        assertEquals("/content/dam/wknd/alike.jpg", items.get(1).get("path"));
        assertEquals(0.98, (Double) items.get(0).get("score"), 0.001);
        assertEquals(3, res.get("compared"));
        assertEquals(1, res.get("notIndexed"));
        assertFalse(res.containsKey("noImage"));

        assertEquals(List.of("/content/dam/wknd/mine.jpg", "/content/dam/wknd/later.jpg"),
                DamSimilar.missing(resolver, List.of("/content/dam"), 100), "what Index now would queue");
    }

    @Test
    void anAssetWithoutAPictureHasNothingAlike() throws Exception {
        String path = "/content/dam/wknd/notes.txt";
        new AssetManagerImpl(resolver).createAsset(path, new ByteArrayInputStream(new byte[] {9}), "text/plain", true);
        resolver.commit();
        Map<String, Object> res = DamSimilar.similar(resolver, dam, resolver.getResource(path), 10);
        assertEquals(true, res.get("noImage"));
        assertFalse(DamSimilar.index(dam, resolver.getResource(path)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void picturesAreFoundByWhatAPhraseSays() throws Exception {
        for (String[] a : new String[][] {{"surf.jpg", "1"}, {"tent.jpg", "4"}}) {
            assertTrue(DamSimilar.index(dam, asset(a[0], Integer.parseInt(a[1]))));
        }
        resolver.commit();
        dam.phrases.put("a tent", vector(5, 6, 0.1)); // points where picture 4 does
        Map<String, Object> res = DamSimilar.pictures(resolver, dam, resolver.getResource("/content/dam"), "a tent", 10, 0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        assertEquals("/content/dam/wknd/tent.jpg", items.get(0).get("path"));
        assertEquals("/content/dam/wknd/surf.jpg", items.get(1).get("path"));
        // sigmoid(10 * 0.995 - 5) against sigmoid(-5)
        assertEquals(0.9929, (Double) items.get(0).get("score"), 0.001);
        assertEquals(0.0067, (Double) items.get(1).get("score"), 0.001);
        assertEquals(2, res.get("total"));
        assertEquals(2, res.get("compared"));
        assertEquals(0, res.get("weaker"));

        // with the usual cut the picture that does not fit is counted, not listed
        res = DamSimilar.pictures(resolver, dam, resolver.getResource("/content/dam"), "a tent", 10, DamSimilar.MIN_SCORE);
        items = (List<Map<String, Object>>) res.get("items");
        assertEquals(1, items.size());
        assertEquals("/content/dam/wknd/tent.jpg", items.get(0).get("path"));
        assertEquals(1, res.get("total"));
        assertEquals(1, res.get("weaker"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMultilingualModelKeepsItsOwnEmbeddings() throws Exception {
        Resource surf = asset("surf.jpg", 1);
        Resource tent = asset("tent.jpg", 4);
        assertTrue(DamSimilar.index(dam, surf));
        assertTrue(DamSimilar.index(dam, tent));
        resolver.commit();
        // not installed: nothing is computed, and nothing is said to be missing in that space
        assertThrows(DamClient.DamProcessingException.class, () -> DamSimilar.index(dam, surf, DamSimilar.Space.SEARCH));
        assertEquals(List.of(), DamSimilar.missing(resolver, List.of("/content/dam"), 10, false));

        dam.searchInstalled = true;
        dam.searchEmbeddings.put(1, vector(7, 8, 0.0));
        dam.searchEmbeddings.put(4, vector(9, 10, 0.0));
        assertEquals(List.of("/content/dam/wknd/surf.jpg", "/content/dam/wknd/tent.jpg"),
                DamSimilar.missing(resolver, List.of("/content/dam"), 10, true).stream().sorted().collect(java.util.stream.Collectors.toList()));
        assertTrue(DamSimilar.index(dam, resolver.getResource(surf.getPath()), DamSimilar.Space.SEARCH));
        assertTrue(DamSimilar.index(dam, resolver.getResource(tent.getPath()), DamSimilar.Space.SEARCH));
        resolver.commit();
        Resource read = resolver.getResource(tent.getPath());
        assertArrayEquals(dam.embeddings.get(4), DamSimilar.stored(read), "the Smart Tags embedding is untouched");
        assertArrayEquals(dam.searchEmbeddings.get(4), DamSimilar.stored(read, DamSimilar.Space.SEARCH));

        dam.searchPhrases.put("un cort", vector(9, 10, 0.1)); // Romanian for "a tent"
        Map<String, Object> res = DamSimilar.pictures(resolver, dam, null, resolver.getResource("/content/dam"), "un cort", 10,
                DamSimilar.MIN_SCORE, DamSimilar.Space.SEARCH);
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        assertEquals(1, items.size(), String.valueOf(res));
        assertEquals("/content/dam/wknd/tent.jpg", items.get(0).get("path"));
        assertEquals(2, res.get("compared"));

        DamSearchIndex index = new DamSearchIndex(context.getService(org.apache.sling.api.resource.ResourceResolverFactory.class));
        Map<String, Object> viaIndex = DamSimilar.pictures(resolver, dam, index, resolver.getResource("/content/dam"), "un cort", 10,
                DamSimilar.MIN_SCORE, DamSimilar.Space.SEARCH);
        assertEquals(res.get("items"), viaIndex.get("items"));
    }

    @Test
    void halfFloatsKeepWhatAScoreNeeds() {
        for (float f : new float[] {0f, 1f, -1f, 0.036f, -0.5123f, 1e-4f, 3.3e-6f, 0.99999f}) {
            float back = DamEmbeddingIndex.halfToFloat(DamEmbeddingIndex.floatToHalf(f));
            assertEquals(f, back, Math.max(Math.abs(f) / 1000, 1e-7f), "half float of " + f);
        }
        assertEquals(0f, DamEmbeddingIndex.halfToFloat(DamEmbeddingIndex.floatToHalf(1e-9f)), 0f);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theIndexAnswersAsTheWalkDoesAndFollowsChanges() throws Exception {
        for (String[] a : new String[][] {{"me.jpg", "1"}, {"near.jpg", "2"}, {"some.jpg", "3"}, {"tent.jpg", "4"}}) {
            assertTrue(DamSimilar.index(dam, asset(a[0], Integer.parseInt(a[1]))));
        }
        asset("new.jpg", 9); // no embedding yet
        resolver.commit();
        DamEmbeddingIndex index = new DamEmbeddingIndex(context.getService(org.apache.sling.api.resource.ResourceResolverFactory.class));
        Resource me = resolver.getResource("/content/dam/wknd/me.jpg");
        Map<String, Object> walked = DamSimilar.similar(resolver, dam, me, 10);
        Map<String, Object> indexed = DamSimilar.similar(resolver, dam, index, me, 10);
        List<Map<String, Object>> a = (List<Map<String, Object>>) walked.get("items");
        List<Map<String, Object>> b = (List<Map<String, Object>>) indexed.get("items");
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).get("path"), b.get(i).get("path"));
            assertEquals((Double) a.get(i).get("score"), (Double) b.get(i).get("score"), 0.002);
        }
        assertEquals(walked.get("compared"), indexed.get("compared"));
        assertEquals(1, indexed.get("notIndexed"));
        assertEquals(4, index.size());

        // a phrase, with the cut: the same list and the same count of what was left out
        dam.phrases.put("a tent", vector(5, 6, 0.1));
        Resource root = resolver.getResource("/content/dam");
        Map<String, Object> p1 = DamSimilar.pictures(resolver, dam, root, "a tent", 10, DamSimilar.MIN_SCORE);
        Map<String, Object> p2 = DamSimilar.pictures(resolver, dam, index, root, "a tent", 10, DamSimilar.MIN_SCORE);
        assertEquals(((List<Map<String, Object>>) p1.get("items")).get(0).get("path"), ((List<Map<String, Object>>) p2.get("items")).get(0).get("path"));
        assertEquals(p1.get("total"), p2.get("total"));
        assertEquals(p1.get("weaker"), p2.get("weaker"));

        // the new picture gets its embedding, another is deleted: the index follows
        dam.embeddings.put(9, vector(0, 1, 0.1));
        assertTrue(DamSimilar.index(dam, resolver.getResource("/content/dam/wknd/new.jpg")));
        resolver.delete(resolver.getResource("/content/dam/wknd/near.jpg"));
        resolver.commit();
        index.onChange(java.util.Arrays.asList(
                new org.apache.sling.api.resource.observation.ResourceChange(
                        org.apache.sling.api.resource.observation.ResourceChange.ChangeType.CHANGED, "/content/dam/wknd/new.jpg/jcr:content", false),
                new org.apache.sling.api.resource.observation.ResourceChange(
                        org.apache.sling.api.resource.observation.ResourceChange.ChangeType.REMOVED, "/content/dam/wknd/near.jpg", false)));
        indexed = DamSimilar.similar(resolver, dam, index, me, 10);
        b = (List<Map<String, Object>>) indexed.get("items");
        assertEquals("/content/dam/wknd/new.jpg", b.get(0).get("path"));
        assertEquals(3, b.size());
        assertEquals(0, indexed.get("notIndexed"));
    }
}
