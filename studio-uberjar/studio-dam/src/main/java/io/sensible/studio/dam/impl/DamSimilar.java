/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Find similar: every picture gets an embedding from the auto-tags model (768 numbers, length 1),
 * kept with the asset; two pictures are alike when the dot product of their embeddings is close
 * to 1. The embedding is stored on {@code jcr:content} (not in the metadata: it is not something
 * a person reads) as 768 little-endian floats.
 *
 * <p>Comparing is done over {@link DamEmbeddingIndex}, the embeddings held in memory, when it is
 * there (always, on a running instance); without it the library is walked and each asset read.
 * Either way a person only gets pictures they may read.</p>
 */
final class DamSimilar {

    static final String PN_EMBEDDING = "studio:embedding";
    static final String PN_MODEL = "studio:embeddingModel";
    static final String PN_AT = "studio:embedded";
    static final String MODEL = "siglip-base-patch16-224";
    static final int DIMENSIONS = 768;
    /** At most this many assets are compared in one search. */
    static final int MAX_CANDIDATES = 50_000;

    private DamSimilar() {
    }

    /**
     * Where a picture's embedding lives, per model: the Smart Tags model (find similar, English
     * descriptions) and the optional multilingual one (descriptions in any language). Each keeps
     * its own numbers with the asset; they are not comparable with each other.
     */
    enum Space {
        TAGS(PN_EMBEDDING, PN_MODEL, PN_AT, MODEL),
        SEARCH("studio:searchEmbedding", "studio:searchEmbeddingModel", "studio:searchEmbedded", "siglip-base-patch16-256-multilingual");

        final String property;
        final String modelProperty;
        final String atProperty;
        final String model;
        volatile long checkedAt;
        volatile boolean installed;

        Space(String property, String modelProperty, String atProperty, String model) {
            this.property = property;
            this.modelProperty = modelProperty;
            this.atProperty = atProperty;
            this.model = model;
        }

        Map<String, Object> status(DamClient client) throws IOException {
            return this == TAGS ? client.smartTagsStatus() : client.searchModelStatus();
        }

        float[] embed(DamClient client, String blob) throws IOException {
            return this == TAGS ? client.embed(blob) : client.searchEmbed(blob);
        }

        DamClient.TextEmbedding embedText(DamClient client, String text) throws IOException {
            return this == TAGS ? client.embedText(text) : client.searchEmbedText(text);
        }
    }

    /** Whether studio-dam has the model (asked at most once a minute, so processing never waits on it). */
    static boolean available(DamClient client) {
        return available(client, Space.TAGS);
    }

    /** {@link #available(DamClient)} for the model of {@code space}. */
    static boolean available(DamClient client, Space space) {
        long now = System.currentTimeMillis();
        if (now - space.checkedAt > 60_000) {
            try {
                space.installed = Boolean.TRUE.equals(space.status(client).get("installed"));
            } catch (IOException | RuntimeException e) {
                space.installed = false;
            }
            space.checkedAt = now;
        }
        return space.installed;
    }

    /** The embedding kept with the asset, or null (none, another model's, or unreadable). */
    static float[] stored(Resource asset) {
        return stored(asset, Space.TAGS);
    }

    /** {@link #stored(Resource)} in {@code space}. */
    static float[] stored(Resource asset, Space space) {
        Resource content = asset.getChild("jcr:content");
        if (content == null || !space.model.equals(content.getValueMap().get(space.modelProperty, String.class))) {
            return null;
        }
        try (InputStream in = content.getValueMap().get(space.property, InputStream.class)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readNBytes(DIMENSIONS * 4 + 1);
            return bytes.length == DIMENSIONS * 4 ? decode(bytes) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The embedding of the asset's picture, asked from studio-dam; null when it has no picture. */
    static float[] compute(DamClient client, Resource asset) throws IOException {
        return compute(client, asset, Space.TAGS);
    }

    /** {@link #compute(DamClient, Resource)} with the model of {@code space}. */
    static float[] compute(DamClient client, Resource asset, Space space) throws IOException {
        if (!DamItems.isAsset(asset) || DamItems.isContentFragment(asset)) {
            return null;
        }
        Resource src = DamSmartTags.source(asset);
        if (src == null) {
            return null;
        }
        InputStream in = src.adaptTo(InputStream.class);
        if (in == null && src.getChild("jcr:content") != null) {
            in = src.getChild("jcr:content").adaptTo(InputStream.class);
        }
        if (in == null) {
            return null;
        }
        String blob;
        try (InputStream body = in) {
            blob = client.putBlob(body);
        }
        float[] e = space.embed(client, blob);
        return e != null && e.length == DIMENSIONS ? e : null;
    }

    /** Computes and keeps the asset's embedding (not saved: the caller commits). False when it has no picture. */
    static boolean index(DamClient client, Resource asset) throws IOException {
        return index(client, asset, Space.TAGS);
    }

    /** {@link #index(DamClient, Resource)} with the model of {@code space}. */
    static boolean index(DamClient client, Resource asset, Space space) throws IOException {
        float[] e = compute(client, asset, space);
        Resource content = asset.getChild("jcr:content");
        ModifiableValueMap m = content == null ? null : content.adaptTo(ModifiableValueMap.class);
        if (e == null || m == null) {
            return false;
        }
        m.put(space.property, new ByteArrayInputStream(encode(e)));
        m.put(space.modelProperty, space.model);
        m.put(space.atProperty, Calendar.getInstance());
        return true;
    }

    /**
     * The assets most like {@code asset}, best first: {@code items} (the asset as the console
     * lists it, plus {@code score} 0..1), {@code compared} and {@code notIndexed} (pictures
     * without an embedding yet, which could not be compared).
     */
    static Map<String, Object> similar(ResourceResolver resolver, DamClient client, Resource asset, int limit) throws IOException {
        return similar(resolver, client, null, asset, limit);
    }

    /** As above; with an index the library is not walked. */
    static Map<String, Object> similar(ResourceResolver resolver, DamClient client, DamEmbeddingIndex index, Resource asset, int limit) throws IOException {
        float[] mine = stored(asset);
        if (mine == null) {
            mine = compute(client, asset);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", asset.getPath());
        if (mine == null) {
            out.put("items", Collections.emptyList());
            out.put("compared", 0);
            out.put("notIndexed", 0);
            out.put("noImage", true);
            return out;
        }
        DamEmbeddingIndex.Result found = index == null ? null
                : index.search(mine, DamItems.ROOT, asset.getPath(), candidates(limit), Double.NEGATIVE_INFINITY);
        if (found != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (DamEmbeddingIndex.Hit h : found.hits) {
                Resource r = items.size() < Math.max(1, limit) ? resolver.getResource(h.path) : null; // only what the asking user may read
                if (r != null && DamItems.isAsset(r)) {
                    Map<String, Object> item = new LinkedHashMap<>(DamItems.asset(r));
                    item.put("score", Math.round(Math.max(0, Math.min(1, h.dot)) * 1000) / 1000.0);
                    items.add(item);
                }
            }
            out.put("items", items);
            out.put("compared", found.compared);
            out.put("notIndexed", found.notIndexed);
            return out;
        }
        List<Scored> best = new ArrayList<>();
        int compared = 0;
        int notIndexed = 0;
        for (String path : DamSmartTags.assets(resolver, Collections.singletonList(DamItems.ROOT), MAX_CANDIDATES)) {
            if (path.equals(asset.getPath())) {
                continue;
            }
            Resource other = resolver.getResource(path);
            if (other == null) {
                continue;
            }
            float[] e = stored(other);
            if (e == null) {
                if (DamSmartTags.source(other) != null) {
                    notIndexed++;
                }
                continue;
            }
            compared++;
            best.add(new Scored(path, dot(mine, e)));
        }
        best.sort((a, b) -> Double.compare(b.score, a.score));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Scored s : best.subList(0, Math.min(Math.max(1, limit), best.size()))) {
            Resource r = resolver.getResource(s.path);
            if (r == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(DamItems.asset(r));
            item.put("score", Math.round(Math.max(0, Math.min(1, s.score)) * 1000) / 1000.0);
            items.add(item);
        }
        out.put("items", items);
        out.put("compared", compared);
        out.put("notIndexed", notIndexed);
        return out;
    }

    /** A phrase is at most this long (the model reads about 64 tokens). */
    static final int MAX_TEXT = 200;

    /**
     * Below this a picture fits a phrase only weakly and is left out unless asked for. The model
     * answers "does this picture fit" on its own scale: pictures that plainly fit are far above
     * (0.1 to 0.8), the rest of a library far below (0.001 and less).
     */
    static final double MIN_SCORE = 0.01;

    /**
     * The pictures below {@code root} that best fit a phrase ("a red tent on a mountain"), best
     * first, in the shape of a search result: {@code items} (each with {@code score}, how well it
     * fits, 0..1), {@code total}, {@code compared}, {@code notIndexed}, and {@code weaker}: the
     * compared pictures left out because they score below {@code min}. The model reads English.
     */
    static Map<String, Object> pictures(ResourceResolver resolver, DamClient client, Resource root, String text, int limit, double min) throws IOException {
        return pictures(resolver, client, null, root, text, limit, min);
    }

    /**
     * More hits than asked for are taken from the index, because some may be pictures the asking
     * user may not read: enough for the list to be full unless most of the best are hidden.
     */
    private static int candidates(int limit) {
        return Math.max(1, limit) * 4 + 100;
    }

    /** As above; with an index the library is not walked. */
    static Map<String, Object> pictures(ResourceResolver resolver, DamClient client, DamEmbeddingIndex index, Resource root, String text, int limit, double min) throws IOException {
        return pictures(resolver, client, index, root, text, limit, min, Space.TAGS);
    }

    /** As above, in {@code space}: with {@link Space#SEARCH} the phrase may be in any language. */
    static Map<String, Object> pictures(ResourceResolver resolver, DamClient client, DamEmbeddingIndex index, Resource root, String text, int limit,
            double min, Space space) throws IOException {
        DamClient.TextEmbedding q = space.embedText(client, text);
        // the score is sigmoid(scale · dot + bias): the same cut, said for the dot product
        double minDot = min <= 0 || q.scale <= 0 ? Double.NEGATIVE_INFINITY
                : min >= 1 ? Double.POSITIVE_INFINITY : (Math.log(min / (1 - min)) - q.bias) / q.scale;
        DamEmbeddingIndex.Result found = index == null ? null : index.search(q.embedding, root.getPath(), null, candidates(limit), minDot);
        if (found != null) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (DamEmbeddingIndex.Hit h : found.hits) {
                Resource r = list.size() < Math.max(1, limit) ? resolver.getResource(h.path) : null; // only what the asking user may read
                if (r != null && DamItems.isAsset(r)) {
                    Map<String, Object> item = new LinkedHashMap<>(DamItems.asset(r));
                    item.put("score", Math.round(1 / (1 + Math.exp(-(q.scale * h.dot + q.bias))) * 10000) / 10000.0);
                    list.add(item);
                }
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("path", root.getPath());
            res.put("total", list.size());
            res.put("offset", 0);
            res.put("items", list);
            res.put("compared", found.compared);
            res.put("notIndexed", found.notIndexed);
            res.put("weaker", found.below);
            return res;
        }
        List<Scored> best = new ArrayList<>();
        int compared = 0;
        int notIndexed = 0;
        for (String path : DamSmartTags.assets(resolver, Collections.singletonList(root.getPath()), MAX_CANDIDATES)) {
            Resource other = resolver.getResource(path);
            if (other == null) {
                continue;
            }
            float[] e = stored(other, space);
            if (e == null) {
                if (DamSmartTags.source(other) != null) {
                    notIndexed++;
                }
                continue;
            }
            compared++;
            double score = 1 / (1 + Math.exp(-(q.scale * dot(q.embedding, e) + q.bias)));
            if (score >= min) {
                best.add(new Scored(path, score));
            }
        }
        int weaker = compared - best.size();
        best.sort((a, b) -> Double.compare(b.score, a.score));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Scored s : best.subList(0, Math.min(Math.max(1, limit), best.size()))) {
            Resource r = resolver.getResource(s.path);
            if (r == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(DamItems.asset(r));
            item.put("score", Math.round(s.score * 10000) / 10000.0);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", root.getPath());
        out.put("total", items.size());
        out.put("offset", 0);
        out.put("items", items);
        out.put("compared", compared);
        out.put("notIndexed", notIndexed);
        out.put("weaker", weaker);
        return out;
    }

    /** The pictures below {@code paths} that have no embedding of the current model yet. */
    static List<String> missing(ResourceResolver resolver, List<String> paths, int max) {
        return missing(resolver, paths, max, false);
    }

    /** {@link #missing(ResourceResolver, List, int)}; with {@code search}, also those missing the multilingual embedding. */
    static List<String> missing(ResourceResolver resolver, List<String> paths, int max, boolean search) {
        List<String> out = new ArrayList<>();
        for (String path : DamSmartTags.assets(resolver, paths, MAX_CANDIDATES)) {
            Resource r = resolver.getResource(path);
            if (r != null && (stored(r) == null || search && stored(r, Space.SEARCH) == null) && DamSmartTags.source(r) != null) {
                out.add(path);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    static byte[] encode(float[] e) {
        ByteBuffer b = ByteBuffer.allocate(e.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : e) {
            b.putFloat(f);
        }
        return b.array();
    }

    static float[] decode(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] e = new float[bytes.length / 4];
        for (int i = 0; i < e.length; i++) {
            e[i] = b.getFloat();
        }
        return e;
    }

    private static final class Scored {
        final String path;
        final double score;

        Scored(String path, double score) {
            this.path = path;
            this.score = score;
        }
    }
}
