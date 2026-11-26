/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Smart Tag training, as AEM's Smart Tags training: Auto tags learn tags of your own taxonomy from the pictures you
 * tagged with them. A tag is learned from the pictures that have it (or a tag below it): the average of their
 * embeddings (the same SigLIP model, {@link DamSimilar}), and how close a picture has to be to it. A new picture
 * that close gets the tag as an Auto tag, beside what the model reads from the tags' names.
 *
 * <pre>
 * /conf/global/settings/dam/smarttags/training/&lt;name&gt;   tag, centroid (768 floats), threshold, examples,
 *                                                         recall, others, model, trained, trainedBy
 * </pre>
 *
 * How close is enough comes from the examples themselves: each is compared with the average of the others (so it
 * does not vote for itself); four in five of them are close enough, and at most one in fifty of the pictures without
 * the tag. {@code recall} is the share of the examples found that way; {@code others}, the pictures without the tag
 * it would be given to: a tag that pictures cannot tell apart shows it there.
 */
final class DamSmartTagTraining {

    static final String STORE = DamSmartTags.CONF + "/training";
    /** Pictures a tag needs to be learned; AEM asks for ten or more, which is better. */
    static final int MIN_EXAMPLES = 5;
    static final int GOOD_EXAMPLES = 10;
    /** Pictures read at most for one training. */
    static final int MAX_SCAN = 20_000;
    static final int MAX_TAGS = 50;

    private static final Pattern TAG_ID = Pattern.compile("[\\w.-]+:[\\w.-]+(/[\\w.-]+)*");

    private DamSmartTagTraining() {
    }

    static final class Model {
        String path;
        String tag;
        float[] centroid;
        double threshold;
        int examples;
        double recall;
        int others;
        Calendar trained;
        String trainedBy;
    }

    /** One picture that was looked at: its tags and its embedding (normalised). */
    private static final class Example {
        final String[] tags;
        final float[] e;

        Example(String[] tags, float[] e) {
            this.tags = tags;
            this.e = e;
        }

        boolean has(String tag) {
            for (String t : tags) {
                if (t.equals(tag) || t.startsWith(tag + "/")) {
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------------------ models

    static List<Model> models(ResourceResolver resolver) {
        List<Model> out = new ArrayList<>();
        Resource store = resolver.getResource(STORE);
        if (store == null) {
            return out;
        }
        for (Resource r : store.getChildren()) {
            ValueMap v = r.getValueMap();
            if (!DamSimilar.MODEL.equals(v.get("model", String.class))) {
                continue; // learned with another model: its numbers mean nothing to this one
            }
            Model m = new Model();
            m.path = r.getPath();
            m.tag = v.get("tag", String.class);
            try (InputStream in = v.get("centroid", InputStream.class)) {
                byte[] bytes = in == null ? new byte[0] : in.readNBytes(DamSimilar.DIMENSIONS * 4 + 1);
                m.centroid = bytes.length == DamSimilar.DIMENSIONS * 4 ? DamSimilar.decode(bytes) : null;
            } catch (IOException e) {
                m.centroid = null;
            }
            m.threshold = v.get("threshold", 1.0);
            m.examples = v.get("examples", 0);
            m.recall = v.get("recall", 0.0);
            m.others = v.get("others", 0);
            m.trained = v.get("trained", Calendar.class);
            m.trainedBy = v.get("trainedBy", String.class);
            if (m.tag != null && m.centroid != null) {
                out.add(m);
            }
        }
        out.sort((a, b) -> a.tag.compareTo(b.tag));
        return out;
    }

    static Map<String, Object> describe(ResourceResolver resolver, Model m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tag", m.tag);
        out.put("title", title(resolver, m.tag));
        out.put("examples", m.examples);
        out.put("recall", m.recall);
        out.put("others", m.others);
        out.put("threshold", Math.round(m.threshold * 1000) / 1000.0);
        if (m.trained != null) {
            out.put("trained", m.trained.toInstant().toString());
        }
        if (m.trainedBy != null) {
            out.put("trainedBy", m.trainedBy);
        }
        return out;
    }

    static String title(ResourceResolver resolver, String tagId) {
        Resource t = DamSmartTags.tagResource(resolver, tagId);
        String title = t == null ? null : t.getValueMap().get("jcr:title", String.class);
        return title != null ? title : tagId.substring(tagId.lastIndexOf('/') + 1).replaceFirst("^[^:]*:", "");
    }

    /** What the console shows: the learned tags, and for the tags asked about, how many pictures could teach them. */
    static Map<String, Object> status(ResourceResolver resolver, List<String> asked) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> models = new ArrayList<>();
        for (Model m : models(resolver)) {
            models.add(describe(resolver, m));
        }
        out.put("models", models);
        out.put("minExamples", MIN_EXAMPLES);
        out.put("goodExamples", GOOD_EXAMPLES);
        if (!asked.isEmpty()) {
            Map<String, int[]> counts = new LinkedHashMap<>(); // tag -> {tagged, looked at}
            for (String t : asked) {
                counts.put(t, new int[2]);
            }
            walk(resolver, (tags, asset) -> {
                boolean looked = DamSimilar.stored(asset) != null;
                for (Map.Entry<String, int[]> c : counts.entrySet()) {
                    if (new Example(tags, null).has(c.getKey())) {
                        c.getValue()[0]++;
                        if (looked) {
                            c.getValue()[1]++;
                        }
                    }
                }
            });
            List<Object> ready = new ArrayList<>();
            for (Map.Entry<String, int[]> c : counts.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tag", c.getKey());
                m.put("title", title(resolver, c.getKey()));
                m.put("tagged", c.getValue()[0]);
                m.put("examples", c.getValue()[1]);
                ready.add(m);
            }
            out.put("ready", ready);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ training

    /** Learns the tags (not saved: the caller commits); one result per tag, {@code error} when it cannot be learned. */
    static List<Map<String, Object>> train(ResourceResolver resolver, List<String> tags) throws DamApiServlet.ApiException, PersistenceException {
        if (tags.isEmpty()) {
            throw new DamApiServlet.ApiException(400, "choose the tags to learn");
        }
        if (tags.size() > MAX_TAGS) {
            throw new DamApiServlet.ApiException(400, "at most " + MAX_TAGS + " tags at once");
        }
        for (String t : tags) {
            if (!TAG_ID.matcher(t).matches()) {
                throw new DamApiServlet.ApiException(400, "not a tag: " + t);
            }
        }
        List<Example> all = new ArrayList<>();
        walk(resolver, (tagIds, asset) -> {
            float[] e = DamSimilar.stored(asset);
            if (e != null) {
                all.add(new Example(tagIds, normalize(e)));
            }
        });
        List<Map<String, Object>> results = new ArrayList<>();
        for (String tag : tags) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tag", tag);
            r.put("title", title(resolver, tag));
            List<float[]> pos = new ArrayList<>();
            List<float[]> neg = new ArrayList<>();
            for (Example x : all) {
                (x.has(tag) ? pos : neg).add(x.e);
            }
            r.put("examples", pos.size());
            if (pos.size() < MIN_EXAMPLES) {
                r.put("error", pos.size() == 0
                        ? "No picture that was looked at has this tag yet"
                        : "Only " + pos.size() + " picture" + (pos.size() == 1 ? "" : "s") + " with this tag were looked at; it takes "
                                + MIN_EXAMPLES + " or more");
                results.add(r);
                continue;
            }
            Model m = learn(tag, pos, neg);
            store(resolver, m);
            r.putAll(describe(resolver, m));
            results.add(r);
        }
        return results;
    }

    /** A tag learned from its pictures ({@code pos}) and the others ({@code neg}); all normalised. */
    static Model learn(String tag, List<float[]> pos, List<float[]> neg) {
        float[] sum = new float[pos.get(0).length];
        for (float[] e : pos) {
            for (int i = 0; i < sum.length; i++) {
                sum[i] += e[i];
            }
        }
        // each example against the average of the others, so it does not vote for itself
        double[] own = new double[pos.size()];
        for (int k = 0; k < pos.size(); k++) {
            float[] e = pos.get(k);
            float[] rest = new float[sum.length];
            for (int i = 0; i < sum.length; i++) {
                rest[i] = sum[i] - e[i];
            }
            own[k] = DamSimilar.dot(e, normalize(rest));
        }
        float[] centroid = normalize(sum);
        double[] other = new double[neg.size()];
        for (int k = 0; k < neg.size(); k++) {
            other[k] = DamSimilar.dot(neg.get(k), centroid);
        }
        double threshold = Math.max(percentile(own, 0.2), other.length == 0 ? -1 : percentile(other, 0.98));
        Model m = new Model();
        m.tag = tag;
        m.centroid = centroid;
        m.threshold = threshold;
        m.examples = pos.size();
        int found = 0;
        for (double s : own) {
            found += s >= threshold ? 1 : 0;
        }
        m.recall = Math.round(found * 100.0 / own.length) / 100.0;
        for (double s : other) {
            m.others += s >= threshold ? 1 : 0;
        }
        return m;
    }

    private static void store(ResourceResolver resolver, Model m) throws PersistenceException {
        Resource store = ensure(resolver, STORE);
        String name = slug(m.tag);
        Resource node = store.getChild(name);
        if (node == null) {
            node = resolver.create(store, name, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap v = node.adaptTo(ModifiableValueMap.class);
        v.put("tag", m.tag);
        v.put("centroid", new ByteArrayInputStream(DamSimilar.encode(m.centroid)));
        v.put("threshold", m.threshold);
        v.put("examples", m.examples);
        v.put("recall", m.recall);
        v.put("others", m.others);
        v.put("model", DamSimilar.MODEL);
        m.trained = Calendar.getInstance();
        v.put("trained", m.trained);
        if (resolver.getUserID() != null) {
            m.trainedBy = resolver.getUserID();
            v.put("trainedBy", m.trainedBy);
        }
        m.path = node.getPath();
    }

    /** Forgets a learned tag (not saved: the caller commits). */
    static void forget(ResourceResolver resolver, String tag) throws DamApiServlet.ApiException, PersistenceException {
        for (Model m : models(resolver)) {
            if (m.tag.equals(tag)) {
                resolver.delete(resolver.getResource(m.path));
                return;
            }
        }
        throw new DamApiServlet.ApiException(404, "this tag was not learned: " + tag);
    }

    // ------------------------------------------------------------------------------------ tagging

    /** The learned tags a picture is close enough to, the closest first: {id, confidence} as the model gives them. */
    static List<Map<String, Object>> predict(List<Model> models, float[] embedding) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (embedding == null) {
            return out;
        }
        float[] e = normalize(embedding);
        for (Model m : models) {
            double s = DamSimilar.dot(e, m.centroid);
            if (s >= m.threshold) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", m.tag);
                // at the threshold 50%, identical 100%
                double confidence = m.threshold >= 1 ? 1 : 0.5 + 0.5 * (s - m.threshold) / (1 - m.threshold);
                f.put("confidence", Math.round(Math.min(1, confidence) * 1000) / 1000.0);
                f.put("learned", true);
                out.add(f);
            }
        }
        out.sort((a, b) -> Double.compare((Double) b.get("confidence"), (Double) a.get("confidence")));
        return out;
    }

    // ------------------------------------------------------------------------------------ helpers

    private interface Visitor {
        void visit(String[] tags, Resource asset);
    }

    private static void walk(ResourceResolver resolver, Visitor visitor) {
        Resource root = resolver.getResource(DamItems.ROOT);
        if (root == null) {
            return;
        }
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty()) {
            for (Resource c : queue.poll().getChildren()) {
                if (DamItems.isAsset(c)) {
                    if (++seen > MAX_SCAN) {
                        return;
                    }
                    Resource md = c.getChild("jcr:content/metadata");
                    visitor.visit(md == null ? new String[0] : md.getValueMap().get("cq:tags", new String[0]), c);
                } else if (DamItems.isFolder(c)) {
                    queue.add(c);
                }
            }
        }
    }

    static float[] normalize(float[] e) {
        double n = 0;
        for (float x : e) {
            n += x * x;
        }
        n = Math.sqrt(n);
        float[] out = new float[e.length];
        for (int i = 0; i < e.length; i++) {
            out[i] = n == 0 ? 0 : (float) (e[i] / n);
        }
        return out;
    }

    /** The value below which {@code p} of the values lie. */
    static double percentile(double[] values, double p) {
        double[] v = values.clone();
        Arrays.sort(v);
        return v[(int) Math.floor(p * (v.length - 1))];
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : path.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        return r;
    }

    /** Node names for tags: {@code wknd-shared:season/summer} → {@code wknd-shared-season-summer}. */
    static String slug(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
