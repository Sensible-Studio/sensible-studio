package io.sensible.studio.cfm.impl;

import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;
import io.sensible.studio.translation.api.TranslationResponse;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language copies of content fragments, as AEM keeps them: the copy of
 * {@code /content/dam/<site>/<lang>/<path>} in language {@code fr} is {@code /content/dam/<site>/fr/<path>}. The
 * language root is the nearest ancestor folder named with a language code.
 *
 * <p>Translated: the fragment's title and description, every variation's title, and the text elements the model
 * marks {@code translatable}. Other elements are copied; references to fragments and assets below the source
 * language root (elements and links in rich text) point to the target language when it has the item.</p>
 *
 * <p>Updating a language copy translates only what changed in the source since the last translation (hashes of
 * the source texts in {@code jcr:content/translation/hashes}); a version of the copy is created first when the
 * repository supports versions. The copy's {@code jcr:content/translation} records source, languages,
 * connector, date and status ({@code TRANSLATED}, {@code APPROVED}, or {@code ERROR_UPDATE} when segments
 * failed).</p>
 */
final class FragmentTranslation {

    static final String TRANSLATION = "translation";
    static final String HASHES = "hashes";
    private static final int MAX_REFERENCED = 200;
    private static final Pattern LANG = Pattern.compile("^[a-z]{2,3}([_-][a-zA-Z0-9]{2,4})?$");
    private static final Pattern LINK = Pattern.compile("((?:href|src)\\s*=\\s*[\"'])(/content/dam/[^\"'?#]+)");
    private static final Set<String> SKIP_PROPS = new HashSet<>(Arrays.asList(
            "jcr:uuid", "jcr:versionHistory", "jcr:baseVersion", "jcr:predecessors", "jcr:isCheckedOut",
            "jcr:created", "jcr:createdBy", "jcr:mergeFailed", "jcr:activity", "jcr:configuration"));

    private final ResourceResolver resolver;
    private final Translator translator;

    FragmentTranslation(ResourceResolver resolver, Translator translator) {
        this.resolver = resolver;
        this.translator = translator;
    }

    // ------------------------------------------------------------------------------------------- languages

    /** A path's language root: {@code root} (e.g. /content/dam/wknd-shared/en), {@code language}, {@code relative}. */
    static final class LanguageRoot {
        final String root;
        final String language;
        final String relative;

        LanguageRoot(String root, String language, String relative) {
            this.root = root;
            this.language = language;
            this.relative = relative;
        }

        String pathIn(String language) {
            return root.substring(0, root.lastIndexOf('/') + 1) + language + (relative.isEmpty() ? "" : "/" + relative);
        }

        String rootOf(String language) {
            return root.substring(0, root.lastIndexOf('/') + 1) + language;
        }
    }

    static boolean isLanguage(String name) {
        if (name == null || !LANG.matcher(name).matches()) return false;
        String base = name.split("[_-]")[0];
        return Arrays.asList(Locale.getISOLanguages()).contains(base);
    }

    static LanguageRoot languageRoot(String path) {
        if (path == null || !path.startsWith("/content/dam/")) return null;
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 3; i--) {
            if (isLanguage(parts[i])) {
                String root = String.join("/", Arrays.copyOfRange(parts, 0, i + 1));
                String relative = path.length() > root.length() ? path.substring(root.length() + 1) : "";
                return new LanguageRoot(root, parts[i], relative);
            }
        }
        return null;
    }

    /** The languages next to a path's language root, its copies (for a fragment) and what they hold. */
    Map<String, Object> languages(String path) {
        LanguageRoot lr = languageRoot(path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        if (lr == null) {
            m.put("languageRoot", null);
            return m;
        }
        m.put("languageRoot", lr.root);
        m.put("language", lr.language);
        Resource site = resolver.getResource(lr.root.substring(0, lr.root.lastIndexOf('/')));
        List<Map<String, Object>> languages = new ArrayList<>();
        List<Map<String, Object>> copies = new ArrayList<>();
        if (site != null) {
            for (Resource child : site.getChildren()) {
                if (!isLanguage(child.getName())) continue;
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("code", child.getName());
                l.put("name", Locale.forLanguageTag(child.getName().replace('_', '-')).getDisplayName(Locale.ENGLISH));
                l.put("root", child.getPath());
                languages.add(l);
                if (child.getName().equals(lr.language)) continue;
                Resource copy = resolver.getResource(lr.pathIn(child.getName()));
                if (copy != null) {
                    Map<String, Object> c = info(copy);
                    c.put("language", child.getName());
                    copies.add(c);
                }
            }
        }
        m.put("languages", languages);
        m.put("copies", copies);
        Resource self = resolver.getResource(path);
        if (self != null && self.getChild("jcr:content/" + TRANSLATION) != null) m.put("translation", info(self));
        return m;
    }

    /** The translation record of a language copy, with the source segments changed since (key {@code outdated}). */
    Map<String, Object> info(Resource copy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", copy.getPath());
        Resource t = copy.getChild("jcr:content/" + TRANSLATION);
        if (t == null) {
            m.put("status", null);
            return m;
        }
        ValueMap p = t.getValueMap();
        for (String k : new String[]{"sourcePath", "sourceLanguage", "targetLanguage", "status", "provider", "attribution", "translatedBy", "approvedBy",
                "changesRequestedBy", "reviewComment"}) {
            m.put(k, p.get(k, String.class));
        }
        m.put("translatedAt", FragmentStore.iso(p.get("translatedAt", Calendar.class)));
        m.put("approvedAt", FragmentStore.iso(p.get("approvedAt", Calendar.class)));
        String[] failed = p.get("failed", String[].class);
        m.put("failed", failed == null ? new String[0] : failed);
        Resource source = p.get("sourcePath", String.class) == null ? null : resolver.getResource(p.get("sourcePath", String.class));
        if (source != null && ContentFragmentImpl.isFragment(source)) {
            Map<String, String> stored = hashes(t);
            List<String> outdated = new ArrayList<>();
            for (Seg s : segments(source)) {
                if (!hash(s.text).equals(stored.get(s.id))) outdated.add(s.id);
            }
            m.put("outdated", outdated);
        } else {
            m.put("outdated", new ArrayList<>());
            m.put("sourceMissing", source == null);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------- translate

    /**
     * Translates fragments into languages: creates the language copies that do not exist, updates the others.
     * With {@code includeReferences}, the fragments they reference (below the same language root) are translated too.
     * Answers one result per fragment and language.
     */
    List<Map<String, Object>> translate(List<String> paths, List<String> targets, String provider, boolean includeReferences,
                                        boolean force) throws TranslationException, PersistenceException, FragmentStore.ValidationException {
        if (translator == null) throw new UnsupportedOperationException("Sensible Studio Translation is not installed");
        List<FragmentStore.FieldError> errors = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        for (String p : paths) {
            Resource r = p == null ? null : resolver.getResource(p);
            if (!ContentFragmentImpl.isFragment(r)) errors.add(new FragmentStore.FieldError("paths", "Not a content fragment: " + p));
            else if (languageRoot(p) == null) {
                errors.add(new FragmentStore.FieldError("paths", p + " is not in a language folder (e.g. /content/dam/<site>/en/...)"));
            } else {
                all.add(p);
            }
        }
        if (targets == null || targets.isEmpty()) errors.add(new FragmentStore.FieldError("languages", "Choose at least one language."));
        else for (String t : targets) if (!isLanguage(t)) errors.add(new FragmentStore.FieldError("languages", "Not a language code: " + t));
        if (!errors.isEmpty()) throw new FragmentStore.ValidationException(errors);
        if (includeReferences) all = withReferences(all);

        List<Map<String, Object>> out = new ArrayList<>();
        for (String target : targets) {
            Set<String> batch = new LinkedHashSet<>();
            for (String p : all) if (!languageRoot(p).language.equals(target)) batch.add(p);
            for (String p : batch) out.add(translateOne(p, target, provider, force, batch));
        }
        return out;
    }

    /** The fragments referenced (transitively) from {@code paths} below their language roots. */
    Set<String> withReferences(Set<String> paths) {
        Set<String> seen = new LinkedHashSet<>(paths);
        Deque<String> todo = new ArrayDeque<>(paths);
        while (!todo.isEmpty() && seen.size() < MAX_REFERENCED) {
            String p = todo.poll();
            Resource asset = resolver.getResource(p);
            LanguageRoot lr = languageRoot(p);
            if (asset == null || lr == null) continue;
            ContentFragmentImpl cf = ContentFragmentImpl.of(asset);
            Resource data = asset.getChild("jcr:content/data");
            if (data == null) continue;
            for (Resource variation : data.getChildren()) {
                for (FragmentModel.ElementDef def : cf.getModel().getElements()) {
                    if (!"fragment-reference".equals(def.metaType)) continue;
                    for (String ref : strings(variation.getValueMap().get(def.name))) {
                        if (ref.startsWith(lr.root + "/") && ContentFragmentImpl.isFragment(resolver.getResource(ref)) && seen.add(ref)) {
                            todo.add(ref);
                        }
                    }
                }
            }
        }
        return seen;
    }

    private Map<String, Object> translateOne(String path, String target, String provider, boolean force, Set<String> batch)
            throws TranslationException, PersistenceException {
        Resource source = resolver.getResource(path);
        LanguageRoot lr = languageRoot(path);
        String targetPath = lr.pathIn(target);
        Resource copy = resolver.getResource(targetPath);
        boolean created = copy == null;
        Map<String, String> stored = new HashMap<>();
        if (created) {
            copy = copyTree(source, targetPath, lr);
        } else {
            if (!ContentFragmentImpl.isFragment(copy)) throw new IllegalArgumentException(targetPath + " exists and is not a content fragment");
            Resource t = copy.getChild("jcr:content/" + TRANSLATION);
            if (t != null && !force) stored = hashes(t);
            versionBeforeUpdate(targetPath, lr.language);
            copy = resolver.getResource(targetPath);
        }

        // what to translate: new or changed source texts (all of them when the copy is new or forced)
        List<Seg> segs = segments(source);
        List<Segment> todo = new ArrayList<>();
        int unchanged = 0;
        for (Seg s : segs) {
            if (!created && hash(s.text).equals(stored.get(s.id))) unchanged++;
            else todo.add(new Segment(s.id, s.text, s.html, s.label));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", path);
        result.put("path", targetPath);
        result.put("language", target);
        result.put("created", created);

        TranslationResponse response = null;
        if (!todo.isEmpty()) {
            ContentFragmentImpl cf = ContentFragmentImpl.of(source);
            TranslationRequest request = new TranslationRequest(lr.language, target, todo)
                    .provider(provider)
                    .title(source.getChild("jcr:content").getValueMap().get("jcr:title", source.getName()))
                    .kind("content fragment" + (cf.getModel().getTitle() != null ? " of the model " + cf.getModel().getTitle() : ""))
                    .useMemory(!force);
            try {
                response = translator.translate(resolver, path, request);
            } catch (TranslationException e) {
                resolver.revert();
                throw e;
            }
        }

        // write: translated texts, copied values of the other elements, references remapped
        Calendar now = Calendar.getInstance();
        List<String> failed = new ArrayList<>();
        Map<String, String> newHashes = new LinkedHashMap<>(stored);
        Map<String, String> translations = new HashMap<>();
        if (response != null) {
            for (SegmentResult r : response.getResults()) {
                if (r.isTranslated()) translations.put(r.getId(), r.getText());
                else failed.add(r.getId() + ": " + r.getError());
            }
        }
        for (Seg s : segs) {
            String text = translations.get(s.id);
            if (text == null) {
                if (!created && newHashes.containsKey(s.id) && hash(s.text).equals(newHashes.get(s.id))) continue; // unchanged
                if (created) text = s.text; // failed in a new copy: the source text stands in
                else continue; // failed in an update: the copy keeps its translation
            } else {
                newHashes.put(s.id, hash(s.text));
            }
            write(copy, s, s.html ? relink(text, lr, target, batch) : text, now);
        }
        syncUntranslated(source, copy, lr, target, batch, now);

        ModifiableValueMap content = copy.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        content.put("lastFragmentSave", now);
        content.put("jcr:lastModified", now);
        content.put("jcr:lastModifiedBy", userId());
        Resource t = copy.getChild("jcr:content/" + TRANSLATION);
        if (t == null) t = resolver.create(copy.getChild("jcr:content"), TRANSLATION, props("jcr:primaryType", "nt:unstructured"));
        ModifiableValueMap tp = t.adaptTo(ModifiableValueMap.class);
        tp.put("sourcePath", path);
        tp.put("sourceLanguage", lr.language);
        tp.put("targetLanguage", target);
        tp.put("status", failed.isEmpty() ? "TRANSLATED" : "ERROR_UPDATE");
        tp.remove("changesRequestedAt");
        tp.remove("changesRequestedBy");
        tp.remove("reviewComment");
        if (response != null) {
            tp.put("provider", response.getProvider());
            if (response.getAttribution() != null) tp.put("attribution", response.getAttribution());
            tp.put("translatedAt", now);
            tp.put("translatedBy", userId());
            tp.remove("approvedAt");
            tp.remove("approvedBy");
        }
        if (failed.isEmpty()) tp.remove("failed");
        else tp.put("failed", failed.toArray(new String[0]));
        Resource h = t.getChild(HASHES);
        if (h != null) resolver.delete(h);
        Map<String, Object> hp = new HashMap<>();
        hp.put("jcr:primaryType", "nt:unstructured");
        for (Map.Entry<String, String> e : newHashes.entrySet()) hp.put(e.getKey().replace('/', '~'), e.getValue());
        resolver.create(t, HASHES, hp);
        resolver.commit();

        result.put("translated", translations.size());
        result.put("characters", todo.stream().mapToInt(seg -> seg.getText().length()).sum());
        result.put("unchanged", unchanged);
        result.put("failed", failed);
        result.put("status", failed.isEmpty() ? "TRANSLATED" : "ERROR_UPDATE");
        if (response != null) result.put("attribution", response.getAttribution());
        return result;
    }

    /** Marks a language copy reviewed. */
    void approve(String path) throws PersistenceException {
        approve(path, userId());
    }

    /** Approves on behalf of someone (the reviewer of a review workflow). */
    void approve(String path, String by) throws PersistenceException {
        Resource copy = path == null ? null : resolver.getResource(path);
        Resource t = copy == null ? null : copy.getChild("jcr:content/" + TRANSLATION);
        if (t == null) throw new IllegalArgumentException("Not a language copy: " + path);
        ModifiableValueMap p = t.adaptTo(ModifiableValueMap.class);
        p.put("status", "APPROVED");
        p.put("approvedAt", Calendar.getInstance());
        p.put("approvedBy", by);
        p.remove("changesRequestedAt");
        p.remove("changesRequestedBy");
        p.remove("reviewComment");
        resolver.commit();
        // what people approved is what the translation memory offers next time
        Resource source = resolver.getResource(p.get("sourcePath", ""));
        if (translator != null && ContentFragmentImpl.isFragment(source)) {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (Seg s : segments(source)) {
                String text = valueOf(copy, s);
                if (text != null && !text.isEmpty() && !text.equals(s.text)) pairs.put(s.text, text);
            }
            translator.remember(p.get("sourceLanguage", String.class), p.get("targetLanguage", String.class), pairs);
        }
    }

    /** A copy's texts next to its source's (review, XLIFF export); null when it is not a copy Sensible Studio translated. */
    Map<String, Object> review(String path) {
        Resource copy = path == null ? null : resolver.getResource(path);
        Resource t = copy == null ? null : copy.getChild("jcr:content/" + TRANSLATION);
        if (t == null || !ContentFragmentImpl.isFragment(copy)) return null;
        ValueMap tv = t.getValueMap();
        Resource source = resolver.getResource(tv.get("sourcePath", ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("title", copy.getChild("jcr:content").getValueMap().get("jcr:title", copy.getName()));
        out.put("sourceLanguage", tv.get("sourceLanguage", String.class));
        out.put("targetLanguage", tv.get("targetLanguage", String.class));
        List<Map<String, Object>> segs = new ArrayList<>();
        if (ContentFragmentImpl.isFragment(source)) {
            for (Seg s : segments(source)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id);
                m.put("label", s.label);
                m.put("html", s.html);
                m.put("source", s.text);
                m.put("translation", valueOf(copy, s));
                segs.add(m);
            }
        }
        out.put("segments", segs);
        return out;
    }

    /** Saves corrected texts of a copy (checked by the caller); they count as reviewed. */
    void saveReview(String path, Map<String, String> translations) throws PersistenceException {
        Resource copy = path == null ? null : resolver.getResource(path);
        Resource t = copy == null ? null : copy.getChild("jcr:content/" + TRANSLATION);
        if (t == null) throw new IllegalArgumentException("Not a language copy: " + path);
        Resource source = resolver.getResource(t.getValueMap().get("sourcePath", ""));
        if (!ContentFragmentImpl.isFragment(source)) throw new IllegalArgumentException("The source of " + path + " is gone");
        Map<String, Seg> byId = new LinkedHashMap<>();
        for (Seg s : segments(source)) byId.put(s.id, s);
        for (String id : translations.keySet()) if (!byId.containsKey(id)) throw new IllegalArgumentException("Not a text of " + path + ": " + id);
        Calendar now = Calendar.getInstance();
        for (Map.Entry<String, String> e : translations.entrySet()) write(copy, byId.get(e.getKey()), e.getValue(), now);
        ModifiableValueMap content = copy.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        content.put("jcr:lastModified", now);
        content.put("jcr:lastModifiedBy", userId());
        ModifiableValueMap tp = t.adaptTo(ModifiableValueMap.class);
        List<String> failed = new ArrayList<>();
        for (String f : tp.get("failed", new String[0])) {
            String id = f.contains(":") ? f.substring(0, f.indexOf(':')) : f;
            if (!translations.containsKey(id)) failed.add(f);
        }
        if (failed.isEmpty()) tp.remove("failed");
        else tp.put("failed", failed.toArray(new String[0]));
        tp.put("reviewedAt", now);
        tp.put("reviewedBy", userId());
        if ("CHANGES".equals(tp.get("status", String.class)) || "ERROR_UPDATE".equals(tp.get("status", String.class)) && failed.isEmpty()) {
            tp.put("status", "TRANSLATED");
        }
        // the reviewer translated the source as it is now
        Resource h = t.getChild(HASHES);
        if (h == null) h = resolver.create(t, HASHES, props("jcr:primaryType", "nt:unstructured"));
        ModifiableValueMap hv = h.adaptTo(ModifiableValueMap.class);
        for (String id : translations.keySet()) hv.put(id.replace('/', '~'), hash(byId.get(id).text));
        resolver.commit();
    }

    /** A segment's text in a fragment (null when missing). */
    static String valueOf(Resource fragment, Seg s) {
        Resource node = s.variation == null ? fragment.getChild("jcr:content") : fragment.getChild("jcr:content/data/" + s.variation);
        if (node == null) return null;
        Object v = node.getValueMap().get(s.property);
        if (s.index < 0) return v instanceof String ? (String) v : null;
        String[] a = node.getValueMap().get(s.property, String[].class);
        return a != null && s.index < a.length ? a[s.index] : null;
    }

    // ------------------------------------------------------------------------------------------- segments

    /** One translatable text of a fragment. */
    static final class Seg {
        final String id;
        final String text;
        final boolean html;
        final String label;
        /** where it goes: "jcr:content" property, or a variation's element (with index for multiple values) */
        final String variation;
        final String property;
        final int index;

        Seg(String id, String text, boolean html, String label, String variation, String property, int index) {
            this.id = id;
            this.text = text;
            this.html = html;
            this.label = label;
            this.variation = variation;
            this.property = property;
            this.index = index;
        }
    }

    List<Seg> segments(Resource asset) {
        List<Seg> out = new ArrayList<>();
        ValueMap content = asset.getChild("jcr:content").getValueMap();
        addText(out, "_title", content.get("jcr:title", String.class), "Fragment title", null, "jcr:title");
        addText(out, "_description", content.get("jcr:description", String.class), "Fragment description", null, "jcr:description");
        ContentFragmentImpl cf = ContentFragmentImpl.of(asset);
        Resource data = asset.getChild("jcr:content/data");
        if (data == null) return out;
        for (Resource variation : data.getChildren()) {
            String v = variation.getName();
            ValueMap p = variation.getValueMap();
            if (!FragmentStore.MASTER.equals(v)) {
                addText(out, v + "/_title", p.get("jcr:title", String.class), "Variation title", v, "jcr:title");
                addText(out, v + "/_description", p.get("jcr:description", String.class), "Variation description", v, "jcr:description");
            }
            for (FragmentModel.ElementDef def : cf.getModel().getElements()) {
                if (!def.translatable || !def.isText()) continue;
                String type = p.get(def.name + ContentFragmentImpl.CONTENT_TYPE_SUFFIX,
                        def.defaultContentType != null ? def.defaultContentType : "text/plain");
                boolean html = type.contains("html");
                Object raw = p.get(def.name);
                if (raw instanceof String[]) {
                    String[] values = (String[]) raw;
                    for (int i = 0; i < values.length; i++) {
                        if (values[i] != null && !values[i].isEmpty()) {
                            out.add(new Seg(v + "/" + def.name + "/" + i, values[i], html, def.title, v, def.name, i));
                        }
                    }
                } else if (raw instanceof String && !((String) raw).isEmpty()) {
                    out.add(new Seg(v + "/" + def.name, (String) raw, html, def.title, v, def.name, -1));
                }
            }
        }
        return out;
    }

    private static void addText(List<Seg> out, String id, String text, String label, String variation, String property) {
        if (text != null && !text.trim().isEmpty()) out.add(new Seg(id, text, false, label, variation, property, -1));
    }

    private void write(Resource copy, Seg s, String text, Calendar now) throws PersistenceException {
        Resource node = s.variation == null ? copy.getChild("jcr:content") : ensureVariation(copy, s.variation);
        ModifiableValueMap p = node.adaptTo(ModifiableValueMap.class);
        if (s.index >= 0) {
            String[] values = p.get(s.property, String[].class);
            if (values == null || values.length <= s.index) {
                String[] grown = new String[s.index + 1];
                if (values != null) System.arraycopy(values, 0, grown, 0, values.length);
                for (int i = 0; i < grown.length; i++) if (grown[i] == null) grown[i] = "";
                values = grown;
            }
            values[s.index] = text;
            p.put(s.property, values);
        } else {
            p.put(s.property, text);
        }
        if (s.variation != null && !s.property.startsWith("jcr:")) p.put(s.property + "@LastModified", now);
    }

    /** Elements that are not translated follow the source: copied, with references remapped. */
    private void syncUntranslated(Resource source, Resource copy, LanguageRoot lr, String target, Set<String> batch, Calendar now)
            throws PersistenceException {
        ContentFragmentImpl cf = ContentFragmentImpl.of(source);
        Resource data = source.getChild("jcr:content/data");
        if (data == null) return;
        for (Resource variation : data.getChildren()) {
            Resource to = ensureVariation(copy, variation.getName());
            ModifiableValueMap p = to.adaptTo(ModifiableValueMap.class);
            ValueMap from = variation.getValueMap();
            for (FragmentModel.ElementDef def : cf.getModel().getElements()) {
                if (def.translatable && def.isText()) continue;
                Object raw = from.get(def.name);
                Object mapped = remap(raw, lr, target, batch);
                Object current = p.get(def.name);
                if (raw == null) {
                    if (current != null) p.remove(def.name);
                } else if (!equal(mapped, current)) {
                    p.put(def.name, mapped);
                    p.put(def.name + "@LastModified", now);
                }
            }
        }
        // the copy carries the source's model and tags
        ModifiableValueMap copyData = copy.getChild("jcr:content/data").adaptTo(ModifiableValueMap.class);
        String model = data.getValueMap().get("cq:model", String.class);
        if (model != null) copyData.put("cq:model", model);
    }

    private Object remap(Object raw, LanguageRoot lr, String target, Set<String> batch) {
        if (raw instanceof String) return remapPath((String) raw, lr, target, batch);
        if (raw instanceof String[]) {
            String[] in = (String[]) raw;
            String[] out = new String[in.length];
            for (int i = 0; i < in.length; i++) out[i] = remapPath(in[i], lr, target, batch);
            return out;
        }
        return raw;
    }

    /** A path below the source language root, moved to the target language when that has it (or gets it now). */
    String remapPath(String value, LanguageRoot lr, String target, Set<String> batch) {
        if (value == null || !value.startsWith(lr.root + "/")) return value;
        String mapped = lr.rootOf(target) + value.substring(lr.root.length());
        return batch.contains(value) || resolver.getResource(mapped) != null ? mapped : value;
    }

    private String relink(String html, LanguageRoot lr, String target, Set<String> batch) {
        Matcher m = LINK.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + remapPath(m.group(2), lr, target, batch)));
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- copies

    private Resource copyTree(Resource source, String targetPath, LanguageRoot lr) throws PersistenceException {
        // the folders on the way, titled like the source's
        String parent = targetPath.substring(0, targetPath.lastIndexOf('/'));
        Resource folder = folders(parent, lr, languageRoot(targetPath).language);
        Resource copy = copy(source, folder, source.getName());
        Resource t = copy.getChild("jcr:content/" + TRANSLATION);
        if (t != null) resolver.delete(t); // a copy of a copy starts its own record
        ModifiableValueMap content = copy.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        content.remove(FragmentVersions.COMMENT);
        content.remove(FragmentVersions.CREATOR);
        return copy;
    }

    private Resource copy(Resource from, Resource parent, String name) throws PersistenceException {
        Map<String, Object> p = new HashMap<>();
        for (Map.Entry<String, Object> e : from.getValueMap().entrySet()) {
            if (SKIP_PROPS.contains(e.getKey())) continue;
            if ("jcr:mixinTypes".equals(e.getKey())) {
                List<String> mixins = new ArrayList<>();
                for (String mx : strings(e.getValue())) if (!"mix:versionable".equals(mx) && !"mix:referenceable".equals(mx)) mixins.add(mx);
                if (!mixins.isEmpty()) p.put("jcr:mixinTypes", mixins.toArray(new String[0]));
                continue;
            }
            p.put(e.getKey(), e.getValue());
        }
        Resource created = resolver.create(parent, name, p);
        for (Resource child : from.getChildren()) {
            if (TRANSLATION.equals(child.getName()) && "jcr:content".equals(from.getName())) continue;
            copy(child, created, child.getName());
        }
        return created;
    }

    /** Creates the folders of {@code path} (below the target language root) titled like the source's. */
    private Resource folders(String path, LanguageRoot lr, String target) throws PersistenceException {
        Resource existing = resolver.getResource(path);
        if (existing != null) return existing;
        Resource parent = folders(path.substring(0, path.lastIndexOf('/')), lr, target);
        String name = path.substring(path.lastIndexOf('/') + 1);
        Resource folder = resolver.create(parent, name, props("jcr:primaryType", "sling:OrderedFolder"));
        String targetRoot = lr.rootOf(target);
        String title;
        if (path.equals(targetRoot)) {
            title = Locale.forLanguageTag(target.replace('_', '-')).getDisplayName(Locale.ENGLISH);
        } else {
            Resource source = path.startsWith(targetRoot + "/") ? resolver.getResource(lr.root + path.substring(targetRoot.length())) : null;
            title = source == null ? null : FragmentModel.valueMap(source.getChild("jcr:content")).get("jcr:title", String.class);
        }
        if (title != null) resolver.create(folder, "jcr:content", props("jcr:primaryType", "nt:unstructured", "jcr:title", title));
        return folder;
    }

    private Resource ensureVariation(Resource copy, String variation) throws PersistenceException {
        Resource data = copy.getChild("jcr:content/data");
        Resource v = data.getChild(variation);
        return v != null ? v : resolver.create(data, variation, props("jcr:primaryType", "nt:unstructured"));
    }

    private void versionBeforeUpdate(String path, String sourceLanguage) {
        try {
            new FragmentVersions(resolver).create(path, null, "Before translation update from " + sourceLanguage);
        } catch (UnsupportedOperationException ignored) {
            // no JCR versions here (tests): the update goes ahead without
        } catch (Exception e) {
            // a version is a safety net, not a condition
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static Map<String, String> hashes(Resource translation) {
        Map<String, String> out = new HashMap<>();
        Resource h = translation.getChild(HASHES);
        if (h == null) return out;
        for (Map.Entry<String, Object> e : h.getValueMap().entrySet()) {
            if (e.getKey().startsWith("jcr:")) continue;
            out.put(e.getKey().replace('~', '/'), String.valueOf(e.getValue()));
        }
        return out;
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof String) {
            out.add((String) o);
        } else if (o instanceof Object[]) {
            for (Object x : (Object[]) o) {
                if (x != null) out.add(x.toString());
            }
        }
        return out;
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Object[] && b instanceof Object[]) return Arrays.equals((Object[]) a, (Object[]) b);
        return a == null ? b == null : a.equals(b);
    }

    private String userId() {
        String id = resolver.getUserID();
        return id == null ? "anonymous" : id;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
