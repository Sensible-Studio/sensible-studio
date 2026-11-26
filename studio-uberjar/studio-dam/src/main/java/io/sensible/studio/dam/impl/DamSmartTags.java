/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smart Tags: tags of the taxonomy (and, optionally, plain keywords) that studio-dam's image model
 * (SigLIP, see {@code dam-service/src/smarttags.rs}) finds in an image.
 *
 * <pre>
 * /conf/global/settings/dam/smarttags   enabled, tagRoots (tag ids whose sub-tags are candidates),
 *                                        keywords, max, minConfidence
 * asset jcr:content/metadata             studio:smartTags (tag ids), studio:smartTagConfidence,
 *                                        studio:smartKeywords, studio:smartTagged, studio:smartTagModel,
 *                                        studio:smartTagsRemoved (what authors took away; never re-added)
 * </pre>
 *
 * Like AEM, smart tags are kept apart from {@code cq:tags}: an author can apply one as a tag or
 * remove it. Both kinds are indexed as text, so the search finds them.
 */
final class DamSmartTags {

    static final String CONF = "/conf/global/settings/dam/smarttags";
    static final String TAGS_ROOT = "/content/cq:tags";
    static final String PN_TAGS = "studio:smartTags";
    static final String PN_CONFIDENCE = "studio:smartTagConfidence";
    static final String PN_KEYWORDS = "studio:smartKeywords";
    static final String PN_TAGGED = "studio:smartTagged";
    static final String PN_MODEL = "studio:smartTagModel";
    static final String PN_REMOVED = "studio:smartTagsRemoved";
    static final String KEYWORD_PREFIX = "keyword:";
    /** On a folder's {@code jcr:content}: {@code on} or {@code off} for it and its sub-folders; absent = inherit. */
    static final String PN_FOLDER = "studio:smartTagging";
    static final int MAX_LABELS = 5000;

    /** Plain words for what is in a picture, used when there is no fitting tag. */
    static final List<String> KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "person", "people", "crowd", "child", "portrait", "selfie", "man", "woman", "family", "couple", "team",
            "dog", "cat", "horse", "bird", "fish", "animal", "wildlife",
            "beach", "ocean", "sea", "waves", "lake", "river", "waterfall", "island", "coast", "harbor", "boat",
            "mountain", "hill", "valley", "canyon", "desert", "forest", "tree", "jungle", "field", "meadow", "garden",
            "flower", "park", "trail", "road", "bridge", "city", "street", "skyline", "building", "house", "interior",
            "kitchen", "restaurant", "cafe", "shop", "office", "stadium", "festival", "concert", "party",
            "snow", "ice", "rain", "fog", "clouds", "sunset", "sunrise", "night", "sky", "stars",
            "food", "drink", "coffee", "beer", "wine", "fruit", "dessert", "pizza", "breakfast",
            "car", "van", "truck", "bicycle", "motorcycle", "train", "airplane", "tent", "campfire", "backpack",
            "surfboard", "skateboard", "skis", "snowboard", "kayak", "helmet", "rope",
            "sport", "running", "swimming", "yoga", "fitness", "music", "guitar", "camera", "phone", "laptop", "book",
            "map", "sign", "text", "logo", "illustration", "drawing", "pattern", "texture", "aerial view", "close-up",
            "landscape", "black and white"));

    private static final Logger LOG = LoggerFactory.getLogger(DamSmartTags.class);

    private DamSmartTags() {
    }

    // ------------------------------------------------------------------------------------ settings

    static final class Settings {
        boolean enabled;
        List<String> tagRoots = new ArrayList<>();
        boolean keywords = true;
        int max = 5;
        double minConfidence = 0.05;
        /** below this the model does not see the label at all, however it compares to the others */
        double minScore = 0.001;
    }

    static Settings settings(ResourceResolver resolver) {
        Settings s = new Settings();
        Resource r = resolver.getResource(CONF);
        if (r == null) {
            return s;
        }
        ValueMap v = r.getValueMap();
        s.enabled = v.get("enabled", false);
        s.tagRoots = new ArrayList<>(Arrays.asList(v.get("tagRoots", new String[0])));
        s.keywords = v.get("keywords", true);
        s.max = (int) Math.max(1, Math.min(20, v.get("max", 5L)));
        s.minConfidence = Math.max(0, Math.min(1, v.get("minConfidence", 0.05)));
        return s;
    }

    /**
     * Whether assets in {@code folder} are tagged when processed: the nearest folder (this one,
     * then its parents) that says {@code on} or {@code off}, else the global setting.
     */
    static boolean enabledIn(Resource folder, Settings s) {
        for (Resource f = folder; f != null && DamItems.inDam(f.getPath()); f = f.getParent()) {
            Resource c = f.getChild("jcr:content");
            String v = c == null ? null : c.getValueMap().get(PN_FOLDER, String.class);
            if ("on".equals(v) || "off".equals(v)) {
                return "on".equals(v);
            }
        }
        return s.enabled;
    }

    /** Sets a folder's own choice: {@code on}, {@code off}, or null / empty to inherit. */
    static void setFolder(ResourceResolver resolver, Resource folder, String value) throws DamApiServlet.ApiException, PersistenceException {
        if (value != null && !value.isEmpty() && !"on".equals(value) && !"off".equals(value)) {
            throw new DamApiServlet.ApiException(400, "smart tagging is on, off or inherited");
        }
        Resource c = folder.getChild("jcr:content");
        if (c == null) {
            c = resolver.create(folder, "jcr:content", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap m = c.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + folder.getPath());
        }
        if (value == null || value.isEmpty()) {
            m.remove(PN_FOLDER);
        } else {
            m.put(PN_FOLDER, value);
        }
    }

    static Map<String, Object> describe(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled);
        m.put("tagRoots", s.tagRoots);
        m.put("keywords", s.keywords);
        m.put("max", (long) s.max);
        m.put("minConfidence", s.minConfidence);
        return m;
    }

    static void save(ResourceResolver resolver, Settings s) throws DamApiServlet.ApiException, PersistenceException {
        for (String root : s.tagRoots) {
            if (tagResource(resolver, root) == null) {
                throw new DamApiServlet.ApiException(400, "no tag " + root);
            }
        }
        Resource r = resolver.getResource(CONF);
        if (r == null) {
            Resource parent = resolver.getResource(CONF.substring(0, CONF.lastIndexOf('/')));
            if (parent == null) {
                throw new DamApiServlet.ApiException(500, "missing " + CONF.substring(0, CONF.lastIndexOf('/')));
            }
            r = resolver.create(parent, "smarttags", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap m = r.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + CONF);
        }
        m.put("enabled", s.enabled);
        m.put("tagRoots", s.tagRoots.toArray(new String[0]));
        m.put("keywords", s.keywords);
        m.put("max", (long) s.max);
        m.put("minConfidence", s.minConfidence);
    }

    // ------------------------------------------------------------------------------------- labels

    /** {@code ns:a/b} → {@code /content/cq:tags/ns/a/b}, if it is a tag. */
    static Resource tagResource(ResourceResolver resolver, String tagId) {
        if (tagId == null || !tagId.matches("^[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]*$") || tagId.contains("..")) {
            return null;
        }
        String path = TAGS_ROOT + "/" + tagId.replaceFirst(":", "/").replaceAll("/+$", "");
        Resource r = resolver.getResource(path);
        return r != null && "cq:Tag".equals(r.getValueMap().get("jcr:primaryType", String.class)) ? r : null;
    }

    static String tagId(Resource tag) {
        String rel = tag.getPath().substring(TAGS_ROOT.length() + 1);
        int slash = rel.indexOf('/');
        return slash < 0 ? rel + ":" : rel.substring(0, slash) + ":" + rel.substring(slash + 1);
    }

    /** What the model compares the image with: the sub-tags of the roots, then the keywords. */
    static List<Map<String, String>> labels(ResourceResolver resolver, Settings s) {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String root : s.tagRoots) {
            Resource r = tagResource(resolver, root);
            if (r == null) {
                continue;
            }
            Deque<Resource> queue = new ArrayDeque<>();
            queue.add(r);
            while (!queue.isEmpty() && out.size() < MAX_LABELS) {
                for (Resource c : queue.poll().getChildren()) {
                    if (!"cq:Tag".equals(c.getValueMap().get("jcr:primaryType", String.class))) {
                        continue;
                    }
                    queue.add(c);
                    String id = tagId(c);
                    if (seen.add(id)) {
                        out.add(label(id, c.getValueMap().get("jcr:title", c.getName())));
                    }
                }
            }
        }
        if (s.keywords) {
            for (String k : KEYWORDS) {
                if (out.size() < MAX_LABELS && seen.add(KEYWORD_PREFIX + k)) {
                    out.add(label(KEYWORD_PREFIX + k, k));
                }
            }
        }
        return out;
    }

    private static Map<String, String> label(String id, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        // "Hunting & Fishing" reads better to the model as words
        String t = text.replace("&", "and").replaceAll("\\s+", " ").trim();
        m.put("text", t.length() > 200 ? t.substring(0, 200) : t);
        return m;
    }

    // ------------------------------------------------------------------------------------ tagging

    /** The image the model looks at: the web rendition, else the large thumbnail, else the original image. */
    static Resource source(Resource asset) {
        if (DamSets.isSet(asset)) {
            return null; // a set shows its first picture; that picture is looked at as itself, not twice
        }
        Resource web = DamItems.webRendition(asset);
        if (web != null) {
            return web;
        }
        Resource thumb = asset.getChild("jcr:content/renditions/" + DamItems.THUMBNAIL);
        if (thumb != null) {
            return thumb;
        }
        Resource original = asset.getChild("jcr:content/renditions/original");
        Object mime = DamItems.asset(asset).get("mimeType");
        return original != null && mime != null && String.valueOf(mime).matches("image/(jpeg|png|webp)") ? original : null;
    }

    /** What happened to one asset. */
    enum Outcome { TAGGED, NO_IMAGE, NO_LABELS, NOT_AN_ASSET }

    /**
     * Tags one asset (not saved: the caller commits). Throws when studio-dam cannot answer, e.g.
     * 409 while the model is not installed.
     */
    static Outcome tag(ResourceResolver resolver, DamClient client, Resource asset, Settings s) throws IOException {
        if (!DamItems.isAsset(asset) || DamItems.isContentFragment(asset)) {
            return Outcome.NOT_AN_ASSET;
        }
        Resource src = source(asset);
        if (src == null) {
            return Outcome.NO_IMAGE;
        }
        List<Map<String, String>> labels = labels(resolver, s);
        List<DamSmartTagTraining.Model> learned = DamSmartTagTraining.models(resolver);
        if (labels.isEmpty() && learned.isEmpty()) {
            return Outcome.NO_LABELS;
        }
        // the tags learned from your pictures first (Smart Tag training), then what the model reads from the names
        List<Map<String, Object>> found = new ArrayList<>();
        if (!learned.isEmpty()) {
            float[] e = DamSimilar.stored(asset);
            found.addAll(DamSmartTagTraining.predict(learned, e != null ? e : DamSimilar.compute(client, asset)));
        }
        if (!labels.isEmpty()) {
            String blob;
            try (InputStream in = stream(src)) {
                if (in == null) {
                    return Outcome.NO_IMAGE;
                }
                blob = client.putBlob(in);
            }
            java.util.Set<String> have = new java.util.HashSet<>();
            found.forEach(f -> have.add(String.valueOf(f.get("id"))));
            for (Map<String, Object> f : client.predictTags(blob, labels, s.max + 10, s.minConfidence, s.minScore)) {
                if (have.add(String.valueOf(f.get("id")))) {
                    found.add(f);
                }
            }
        }
        write(resolver, asset, found, s.max);
        return Outcome.TAGGED;
    }

    private static InputStream stream(Resource rendition) {
        InputStream in = rendition.adaptTo(InputStream.class);
        if (in == null && rendition.getChild("jcr:content") != null) {
            in = rendition.getChild("jcr:content").adaptTo(InputStream.class);
        }
        return in;
    }

    /** Stores what the model found, leaving out what authors removed or already tagged. */
    static void write(ResourceResolver resolver, Resource asset, List<Map<String, Object>> found, int max) throws PersistenceException {
        Resource content = asset.getChild("jcr:content");
        Resource mdRes = content == null ? null : content.getChild("metadata");
        if (mdRes == null && content != null) {
            mdRes = resolver.create(content, "metadata", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        ModifiableValueMap md = mdRes == null ? null : mdRes.adaptTo(ModifiableValueMap.class);
        if (md == null) {
            throw new PersistenceException("cannot write the metadata of " + asset.getPath());
        }
        Set<String> removed = new LinkedHashSet<>(Arrays.asList(md.get(PN_REMOVED, new String[0])));
        Set<String> applied = new LinkedHashSet<>(Arrays.asList(md.get("cq:tags", new String[0])));
        List<String> tags = new ArrayList<>();
        List<Double> confidence = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        for (Map<String, Object> f : found) {
            String id = String.valueOf(f.get("id"));
            if (removed.contains(id) || applied.contains(id) || tags.size() + keywords.size() >= max) {
                continue;
            }
            if (id.startsWith(KEYWORD_PREFIX)) {
                keywords.add(id.substring(KEYWORD_PREFIX.length()));
            } else {
                tags.add(id);
                Object c = f.get("confidence");
                confidence.add(c instanceof Number ? Math.round(((Number) c).doubleValue() * 1000) / 1000.0 : 0.0);
            }
        }
        md.put(PN_TAGS, tags.toArray(new String[0]));
        md.put(PN_CONFIDENCE, confidence.toArray(new Double[0]));
        md.put(PN_KEYWORDS, keywords.toArray(new String[0]));
        md.put(PN_TAGGED, Calendar.getInstance());
        md.put(PN_MODEL, "siglip-base-patch16-224");
    }

    /** Takes a smart tag (or keyword) away for good, or applies it as a {@code cq:tags} tag. */
    static void change(Resource asset, String id, boolean apply) throws DamApiServlet.ApiException {
        Resource mdRes = asset.getChild("jcr:content/metadata");
        ModifiableValueMap md = mdRes == null ? null : mdRes.adaptTo(ModifiableValueMap.class);
        if (md == null) {
            throw new DamApiServlet.ApiException(403, "cannot change the metadata of " + asset.getPath());
        }
        boolean keyword = id != null && id.startsWith(KEYWORD_PREFIX);
        List<String> tags = new ArrayList<>(Arrays.asList(md.get(PN_TAGS, new String[0])));
        List<Double> conf = new ArrayList<>(Arrays.asList(md.get(PN_CONFIDENCE, new Double[0])));
        List<String> keywords = new ArrayList<>(Arrays.asList(md.get(PN_KEYWORDS, new String[0])));
        int i = keyword ? keywords.indexOf(id.substring(KEYWORD_PREFIX.length())) : tags.indexOf(id);
        if (i < 0) {
            throw new DamApiServlet.ApiException(404, "no smart tag " + id + " on " + asset.getPath());
        }
        if (keyword) {
            if (apply) {
                throw new DamApiServlet.ApiException(400, "a keyword is not a tag; add it to the keywords instead");
            }
            keywords.remove(i);
        } else {
            tags.remove(i);
            if (i < conf.size()) {
                conf.remove(i);
            }
        }
        if (apply) {
            List<String> cq = new ArrayList<>(Arrays.asList(md.get("cq:tags", new String[0])));
            if (!cq.contains(id)) {
                cq.add(id);
            }
            md.put("cq:tags", cq.toArray(new String[0]));
        } else {
            Set<String> removed = new LinkedHashSet<>(Arrays.asList(md.get(PN_REMOVED, new String[0])));
            removed.add(id);
            md.put(PN_REMOVED, removed.toArray(new String[0]));
        }
        md.put(PN_TAGS, tags.toArray(new String[0]));
        md.put(PN_CONFIDENCE, conf.toArray(new Double[0]));
        md.put(PN_KEYWORDS, keywords.toArray(new String[0]));
    }

    /** The smart tags of an asset for the console: tag id, title, confidence; then the keywords. */
    static Map<String, Object> describe(ResourceResolver resolver, Resource asset) {
        Resource mdRes = asset.getChild("jcr:content/metadata");
        ValueMap md = mdRes == null ? ValueMap.EMPTY : mdRes.getValueMap();
        String[] tags = md.get(PN_TAGS, new String[0]);
        Double[] conf = md.get(PN_CONFIDENCE, new Double[0]);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < tags.length; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", tags[i]);
            Resource tr = tagResource(resolver, tags[i]);
            t.put("title", tr == null ? tags[i] : tr.getValueMap().get("jcr:title", tr.getName()));
            t.put("confidence", i < conf.length ? conf[i] : null);
            list.add(t);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tags", list);
        m.put("keywords", Arrays.asList(md.get(PN_KEYWORDS, new String[0])));
        m.put("tagged", md.get(PN_TAGGED, Calendar.class));
        return m;
    }

    /** The assets of {@code paths} (folders: everything below), at most {@code max}. */
    static List<String> assets(ResourceResolver resolver, List<String> paths, int max) {
        List<String> out = new ArrayList<>();
        Deque<Resource> queue = new ArrayDeque<>();
        for (String p : paths) {
            Resource r = DamItems.inDam(p) ? resolver.getResource(p) : null;
            if (r != null) {
                queue.add(r);
            }
        }
        while (!queue.isEmpty() && out.size() < max) {
            Resource r = queue.poll();
            if (DamItems.isAsset(r)) {
                if (!DamItems.isContentFragment(r)) {
                    out.add(r.getPath()); // fragments have no picture
                }
            } else if (DamItems.isFolder(r) && !r.getPath().startsWith(DamCollections.ROOT)) {
                r.getChildren().forEach(queue::add);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------- model download

    /** A model download (Hugging Face → studio-dam), one at a time per model, progress for the console. */
    static final class Install {
        /** what the logs call it */
        final String name;
        volatile String state = "idle"; // idle | downloading | done | failed
        volatile String file;
        volatile long bytes;
        volatile long total;
        volatile String error;

        Install(String name) {
            this.name = name;
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("file", file);
            m.put("bytes", bytes);
            m.put("total", total);
            m.put("error", error);
            return m;
        }
    }

    /** How one model's files are listed and stored in studio-dam. */
    interface ModelFiles {
        Map<String, Object> status(DamClient client) throws IOException;

        void install(DamClient client, String name, InputStream in) throws IOException;
    }

    static final Install INSTALL = new Install("Smart Tags model");
    /** The multilingual model: picture search in any language. */
    static final Install SEARCH_INSTALL = new Install("Multilingual search model");
    private static final ModelFiles TAGS_FILES = new ModelFiles() {
        @Override
        public Map<String, Object> status(DamClient client) throws IOException {
            return client.smartTagsStatus();
        }

        @Override
        public void install(DamClient client, String name, InputStream in) throws IOException {
            client.installSmartTagsFile(name, in);
        }
    };
    private static final ModelFiles SEARCH_FILES = new ModelFiles() {
        @Override
        public Map<String, Object> status(DamClient client) throws IOException {
            return client.searchModelStatus();
        }

        @Override
        public void install(DamClient client, String name, InputStream in) throws IOException {
            client.installSearchModelFile(name, in);
        }
    };
    private static final String SOURCE = "https://huggingface.co/";

    /** Starts downloading the files studio-dam is missing; false when a download is already running. */
    static boolean startInstall(DamClient client) throws IOException {
        return startInstall(client, INSTALL, TAGS_FILES);
    }

    /** {@link #startInstall(DamClient)} for the multilingual search model. */
    static boolean startSearchInstall(DamClient client) throws IOException {
        return startInstall(client, SEARCH_INSTALL, SEARCH_FILES);
    }

    private static synchronized boolean startInstall(DamClient client, Install install, ModelFiles model) throws IOException {
        if ("downloading".equals(install.state)) {
            return false;
        }
        Map<String, Object> status = model.status(client);
        List<Map<String, Object>> files = new ArrayList<>();
        long total = 0;
        Object fs = status.get("files");
        if (fs instanceof List) {
            for (Object o : (List<?>) fs) {
                if (o instanceof Map && !Boolean.TRUE.equals(((Map<?, ?>) o).get("present"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> f = (Map<String, Object>) o;
                    String url = String.valueOf(f.get("url"));
                    if (!url.startsWith(SOURCE)) {
                        throw new IOException("unexpected model URL " + url);
                    }
                    files.add(f);
                    total += f.get("size") instanceof Number ? ((Number) f.get("size")).longValue() : 0;
                }
            }
        }
        install.state = "downloading";
        install.bytes = 0;
        install.total = total;
        install.error = null;
        install.file = null;
        Thread t = new Thread(() -> download(client, files, install, model), "studio-dam-model-download");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void download(DamClient client, List<Map<String, Object>> files, Install install, ModelFiles model) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        try {
            for (Map<String, Object> f : files) {
                install.file = String.valueOf(f.get("name"));
                HttpRequest req = HttpRequest.newBuilder(URI.create(String.valueOf(f.get("url")))).timeout(Duration.ofMinutes(60)).GET().build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    res.body().close();
                    throw new IOException(install.file + ": Hugging Face answered " + res.statusCode());
                }
                try (InputStream in = new Counting(res.body(), install)) {
                    model.install(client, install.file, in);
                }
            }
            install.file = null;
            install.state = "done";
            LOG.info("{} installed", install.name);
        } catch (IOException | RuntimeException e) {
            install.error = e.getMessage();
            install.state = "failed";
            LOG.warn("{} download failed: {}", install.name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            install.state = "failed";
            install.error = "interrupted";
        }
    }

    /** Counts what goes through, for the progress bar. */
    private static final class Counting extends java.io.FilterInputStream {
        private final Install install;

        Counting(InputStream in, Install install) {
            super(in);
            this.install = install;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                install.bytes++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                install.bytes += n;
            }
            return n;
        }
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
