package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.ReferenceTranslator;
import io.sensible.studio.translation.api.Segment;
import io.sensible.studio.translation.api.SegmentResult;
import io.sensible.studio.translation.api.TranslationRequest;
import io.sensible.studio.translation.api.TranslationResponse;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language copies of pages, as AEM lays them out: {@code /content/<site>/[<country>|language-masters]/<lang>/...}.
 * The language root is the nearest ancestor named with an ISO language code; its siblings are the other languages.
 *
 * <ul>
 *   <li>A missing copy is created (with the missing pages above it) and its texts translated. Copies are plain
 *       pages: MSM markers, replication and version state are not copied, so live copies can roll out from a
 *       translated language master. A live copy is never written: its blueprint is translated instead.</li>
 *   <li>Which properties are text comes from {@link PageTranslationRules}. Paths to pages, assets, content and
 *       experience fragments of the source language point to the target language's when that exists (or is
 *       translated in the same batch), in properties and in rich text links.</li>
 *   <li>Updating a copy translates only source texts that changed since the last translation and texts the copy
 *       lacks; components new in the source are added. A version of the copy is saved first. A copy that Sensible Studio did not
 *       translate (no record, e.g. made by people) keeps its texts unless {@code force}.</li>
 *   <li>The record lives outside the page, at {@code /var/studio/translation/pages/<copy path>}, so neither rendering nor
 *       MSM rollouts carry it; the page itself gets {@code cq:lastTranslationUpdate}.</li>
 * </ul>
 */
final class PageTranslation {

    static final String RECORDS = "/var/studio/translation/pages";
    static final String LAST_UPDATE = "cq:lastTranslationUpdate";
    static final int MAX_PAGES = 2000;

    private static final Pattern LANG = Pattern.compile("^[a-z]{2}([_-][a-zA-Z0-9]{2,4})?$");
    private static final Pattern PATH = Pattern.compile("^(/content/[^.?#]*)(.*)$", Pattern.DOTALL);
    private static final Pattern DAM_PATH = Pattern.compile("^(/content/dam/[^?#]*)(.*)$", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("((?:href|src)\\s*=\\s*[\"'])(/content/[^\"'?#]+)");

    private static final Set<String> SKIP_PROPS = new HashSet<>(Arrays.asList(
            "jcr:uuid", "jcr:versionHistory", "jcr:baseVersion", "jcr:predecessors", "jcr:isCheckedOut", "jcr:created",
            "jcr:createdBy", "jcr:mergeFailed", "jcr:activity", "jcr:configuration", "jcr:lockOwner", "jcr:lockIsDeep",
            "cq:lastReplicated", "cq:lastReplicatedBy", "cq:lastReplicationAction", "cq:lastPublished", "cq:lastPublishedBy",
            "cq:lastRolledout", "cq:lastRolledoutBy", "cq:isCancelledForChildren", "cq:propertyInheritanceCancelled",
            "cq:lockOwner", LAST_UPDATE));
    private static final Set<String> SKIP_MIXINS = new HashSet<>(Arrays.asList(
            "mix:versionable", "mix:referenceable", "mix:lockable", "cq:LiveRelationship", "cq:LiveSyncCancelled",
            "cq:PropertyLiveSyncCancelled"));
    private static final Set<String> SKIP_NODES = new HashSet<>(Arrays.asList(
            "cq:LiveSyncConfig", "cq:BlueprintSyncConfig", "cq:annotations"));

    private final ResourceResolver resolver;
    private final Translator translator;
    private final PageTranslationRules rules;
    private final List<ReferenceTranslator> referenceTranslators;
    /** References already translated in this request (or by earlier requests of the same batch), by source path. */
    private final Set<String> referencesDone = new HashSet<>();

    PageTranslation(ResourceResolver resolver, Translator translator, PageTranslationRules rules) {
        this(resolver, translator, rules, new ArrayList<>());
    }

    PageTranslation(ResourceResolver resolver, Translator translator, PageTranslationRules rules, List<ReferenceTranslator> referenceTranslators) {
        this.resolver = resolver;
        this.translator = translator;
        this.rules = rules;
        this.referenceTranslators = referenceTranslators;
    }

    /** Where records are read and written: a service session when there is one (authors need no rights on /var). */
    private ResourceResolver records;

    PageTranslation records(ResourceResolver serviceResolver) {
        this.records = serviceResolver;
        return this;
    }

    private ResourceResolver records() {
        return records == null ? resolver : records;
    }

    /** Whether the user may change the page (or the page content) at a path; true without JCR (tests). */
    private boolean canWrite(String path) {
        try {
            javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
            return session == null || session.hasPermission(path + "/jcr:content", "set_property");
        } catch (Exception | LinkageError e) {
            return true;
        }
    }

    /** Who the work is done for (a background job's starter), when it is not the session's user. */
    private String actingUser;

    PageTranslation as(String user) {
        this.actingUser = user;
        return this;
    }

    private String user() {
        return actingUser != null ? actingUser : resolver.getUserID();
    }

    /** References not to translate again (the UI passes the ones earlier pages of a batch translated). */
    PageTranslation skipReferences(Collection<String> paths) {
        if (paths != null) referencesDone.addAll(paths);
        return this;
    }

    // ------------------------------------------------------------------------------------------- language roots

    static final class LanguageRoot {
        final String root;
        final String language;
        final String relative;

        LanguageRoot(String root, String language, String relative) {
            this.root = root;
            this.language = language;
            this.relative = relative;
        }

        String rootOf(String lang) {
            return root.substring(0, root.lastIndexOf('/') + 1) + lang;
        }

        String pathIn(String lang) {
            return rootOf(lang) + (relative.isEmpty() ? "" : "/" + relative);
        }
    }

    static boolean isLanguage(String name) {
        if (name == null || !LANG.matcher(name).matches()) return false;
        return Arrays.asList(Locale.getISOLanguages()).contains(name.substring(0, 2));
    }

    /** The nearest ancestor (or the path itself) named with a language code, below {@code /content/<x>}. */
    static LanguageRoot languageRoot(String path) {
        if (path == null || !path.startsWith("/content/")) return null;
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 3; i--) {
            if (isLanguage(parts[i])) {
                String root = String.join("/", Arrays.copyOfRange(parts, 0, i + 1));
                return new LanguageRoot(root, parts[i], path.length() > root.length() ? path.substring(root.length() + 1) : "");
            }
        }
        return null;
    }

    /** A DAM asset that is not a content fragment (those have their own translation). */
    static boolean isAsset(Resource r) {
        if (r == null || !"dam:Asset".equals(r.getValueMap().get("jcr:primaryType", String.class))) return false;
        Resource c = r.getChild("jcr:content");
        return c == null || !"true".equals(String.valueOf(c.getValueMap().get("contentFragment")));
    }

    /** What Sensible Studio translates: pages (and experience fragments) and assets' metadata. */
    static boolean isDoc(Resource r) {
        return isPage(r) || isAsset(r);
    }

    /** Where a document's texts are: a page's content, an asset's metadata. */
    private static Resource textRoot(Resource doc) {
        if (doc == null) return null;
        return doc.getChild(isAsset(doc) ? "jcr:content/metadata" : "jcr:content");
    }

    List<Seg> segmentsOf(Resource doc) {
        if (isAsset(doc)) return assetSegments(doc.getChild("jcr:content/metadata"));
        return segments(doc == null ? null : doc.getChild("jcr:content"));
    }

    private static String kindOf(Resource doc) {
        if (isAsset(doc)) {
            Resource m = doc.getChild("jcr:content/metadata");
            String format = m == null ? null : m.getValueMap().get("dc:format", String.class);
            return "metadata of a " + (format != null && format.startsWith("video/") ? "video" : format != null && format.startsWith("image/") ? "image" : "file")
                    + " (its title is also its caption, its description its alternative text)";
        }
        Resource c = doc == null ? null : doc.getChild("jcr:content");
        return "web page (" + (c == null ? "page" : String.valueOf(c.getValueMap().get("sling:resourceType", "page"))) + ")";
    }

    /** An asset's texts: the metadata properties the rules name. */
    List<Seg> assetSegments(Resource metadata) {
        List<Seg> out = new ArrayList<>();
        if (metadata == null) return out;
        ValueMap vm = metadata.getValueMap();
        for (String prop : rules.at(resolver, metadata.getPath()).assetProperties) {
            Object v = vm.get(prop);
            String label = "Asset · " + assetLabel(prop);
            if (v instanceof String) {
                if (PageTranslationRules.isText((String) v)) out.add(new Seg("", prop, -1, (String) v, false, label));
            } else if (v instanceof String[]) {
                String[] a = (String[]) v;
                for (int i = 0; i < a.length; i++) {
                    if (PageTranslationRules.isText(a[i])) out.add(new Seg("", prop, i, a[i], false, a.length > 1 ? label + " " + (i + 1) : label));
                }
            }
        }
        return out;
    }

    static String assetLabel(String prop) {
        switch (prop) {
            case "dc:title":
                return "Title";
            case "dc:description":
                return "Description (alternative text)";
            case "photoshop:Headline":
                return "Headline";
            default:
                return propertyLabel(prop);
        }
    }

    static boolean isPage(Resource r) {
        if (r == null) return false;
        if ("cq:Page".equals(r.getValueMap().get("jcr:primaryType", String.class))) return true;
        Resource c = r.getChild("jcr:content");
        return c != null && "cq:PageContent".equals(c.getValueMap().get("jcr:primaryType", String.class));
    }

    static boolean isFolder(Resource r) {
        String t = r == null ? null : r.getValueMap().get("jcr:primaryType", String.class);
        return t != null && (t.equals("sling:Folder") || t.equals("sling:OrderedFolder") || t.equals("nt:folder"));
    }

    /** A live copy (or a page inside one): MSM owns its content. */
    boolean isLiveCopy(String path) {
        for (String p = path; p != null && p.startsWith("/content/"); p = ResourceUtil.getParent(p)) {
            Resource c = resolver.getResource(p + "/jcr:content");
            if (c == null) continue;
            if (c.getChild("cq:LiveSyncConfig") != null) return true;
            if (p.equals(path) && Arrays.asList(strings(c.getValueMap().get("jcr:mixinTypes"))).contains("cq:LiveRelationship")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- reading

    /** The page's language, its language copies and their translation state; for a copy also its own record. */
    Map<String, Object> languages(String path) {
        Resource page = resolver.getResource(path);
        // a page, or a file in Media (its metadata is what is translated)
        if (!isDoc(page)) throw new IllegalArgumentException("No page at " + path);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("title", title(page));
        out.put("liveCopy", isLiveCopy(path));
        LanguageRoot lr = languageRoot(path);
        if (lr == null) {
            out.put("languageRoot", null);
            return out;
        }
        out.put("languageRoot", lr.root);
        out.put("language", lr.language);
        Map<String, Object> own = record(path);
        if (own != null) out.put("translation", own);

        List<Map<String, Object>> copies = new ArrayList<>();
        Resource parent = resolver.getResource(ResourceUtil.getParent(lr.root));
        if (parent != null) {
            for (Resource sibling : parent.getChildren()) {
                if (!isLanguage(sibling.getName()) || sibling.getName().equals(lr.language) || !(isPage(sibling) || isFolder(sibling))) continue;
                String copyPath = lr.pathIn(sibling.getName());
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("language", sibling.getName());
                c.put("name", Languages.name(sibling.getName()));
                c.put("root", sibling.getPath());
                c.put("path", copyPath);
                Resource copy = resolver.getResource(copyPath);
                c.put("exists", isDoc(copy));
                c.put("liveCopy", isLiveCopy(copyPath));
                if (isDoc(copy)) {
                    c.put("title", title(copy));
                    Map<String, Object> rec = record(copyPath);
                    if (rec != null) c.put("translation", rec);
                }
                copies.add(c);
            }
        }
        copies.sort((a, b) -> String.valueOf(a.get("language")).compareTo(String.valueOf(b.get("language"))));
        out.put("copies", copies);
        out.put("languages", Languages.common());
        return out;
    }

    /** A copy's record with {@code outdated} (its source changed since), or null. */
    Map<String, Object> record(String copyPath) {
        if (resolver.getResource(copyPath) == null) return null; // only for copies the user can see
        Resource r = records().getResource(RECORDS + copyPath);
        if (r == null || r.getValueMap().get("sourcePath", String.class) == null) return null;
        ValueMap vm = r.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : new String[]{"sourcePath", "sourceLanguage", "targetLanguage", "status", "provider", "attribution",
                "translatedBy", "approvedBy", "changesRequestedBy", "reviewComment", "reviewedBy"}) {
            if (vm.get(k, String.class) != null) m.put(k, vm.get(k, String.class));
        }
        for (String k : new String[]{"translatedAt", "approvedAt", "changesRequestedAt", "reviewedAt"}) {
            Calendar c = vm.get(k, Calendar.class);
            if (c != null) m.put(k, c.getTimeInMillis());
        }
        m.put("failed", Arrays.asList(vm.get("failed", new String[0])));
        Resource source = resolver.getResource(vm.get("sourcePath", String.class));
        if (isDoc(source)) {
            Map<String, String> stored = hashes(vm.get("hashes", new String[0]));
            Set<String> failedIds = new HashSet<>(Arrays.asList(vm.get("failed", new String[0])));
            boolean outdated = false;
            for (Seg s : segmentsOf(source)) {
                if (failedIds.contains(s.id())) continue; // not translated yet: reported as failed, not as outdated
                if (!hash(s.text).equals(stored.get(s.id()))) {
                    outdated = true;
                    break;
                }
            }
            m.put("outdated", outdated);
        } else {
            m.put("sourceMissing", true);
        }
        return m;
    }

    /** The page and, when {@code deep}, the pages below it, parents first. */
    List<String> pages(String path, boolean deep) {
        Resource page = resolver.getResource(path);
        if (!isPage(page)) throw new IllegalArgumentException("No page at " + path);
        List<String> out = new ArrayList<>();
        collect(page, deep, out);
        return out;
    }

    private void collect(Resource page, boolean deep, List<String> out) {
        if (out.size() >= MAX_PAGES) throw new IllegalArgumentException("More than " + MAX_PAGES + " pages: translate a smaller part of the site");
        out.add(page.getPath());
        if (!deep) return;
        for (Resource child : page.getChildren()) if (isPage(child)) collect(child, true, out);
    }

    // ------------------------------------------------------------------------------------------- translating

    /**
     * Translates one page into one language: creates or updates the copy. {@code batch}: the source pages translated
     * together (links to them point to their copies even before those exist). Results: the page's, after the ones
     * of pages created above it.
     */
    List<Map<String, Object>> translate(String path, String target, String provider, boolean force, Collection<String> batch)
            throws TranslationException, PersistenceException {
        return translate(path, target, provider, force, batch, false);
    }

    /**
     * As above; with {@code references}, the experience fragments and content fragments (any content a
     * {@link ReferenceTranslator} accepts) the page refers to are translated first, so the copy points to their copies.
     */
    List<Map<String, Object>> translate(String path, String target, String provider, boolean force, Collection<String> batch, boolean references)
            throws TranslationException, PersistenceException {
        Resource source = resolver.getResource(path);
        if (!isPage(source)) throw new IllegalArgumentException("No page at " + path);
        LanguageRoot lr = languageRoot(path);
        if (lr == null) throw new IllegalArgumentException(path + " is not in a language root (…/<site>/<language>/…, e.g. …/language-masters/en)");
        if (target == null || !isLanguage(target)) throw new IllegalArgumentException("Not a language code: " + target);
        if (target.equals(lr.language)) throw new IllegalArgumentException(path + " is already in " + Languages.name(target));
        Set<String> inBatch = new HashSet<>(batch == null ? new ArrayList<>() : batch);
        inBatch.add(path);

        List<Map<String, Object>> results = new ArrayList<>();
        // the pages above the copy, from the language root down, when missing
        List<String> above = new ArrayList<>();
        for (String p = ResourceUtil.getParent(lr.pathIn(target)); p != null && p.length() >= lr.rootOf(target).length(); p = ResourceUtil.getParent(p)) {
            above.add(0, p);
        }
        for (String copyAbove : above) {
            if (resolver.getResource(copyAbove) != null) continue;
            LanguageRoot alr = languageRoot(copyAbove);
            String sourceAbove = alr.pathIn(lr.language);
            Resource sa = resolver.getResource(sourceAbove);
            if (isFolder(sa)) {
                // experience fragments live in folders: the folder is copied as it is
                copyFolder(sa, copyAbove);
                continue;
            }
            if (!isPage(sa)) throw new IllegalArgumentException("No page at " + sourceAbove + " to make " + copyAbove + " from");
            Map<String, Object> r = translateOne(sourceAbove, lr, target, provider, false, inBatch);
            r.put("above", true);
            results.add(r);
        }
        if (references) results.addAll(translateReferences(source, lr.language, target, provider, force));
        results.add(translateOne(path, lr, target, provider, force, inBatch));
        return results;
    }

    /** The experience fragments and other referenced content of a page, translated (each once per request). */
    private List<Map<String, Object>> translateReferences(Resource source, String from, String target, String provider, boolean force)
            throws TranslationException, PersistenceException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ref : references(source.getChild("jcr:content"))) {
            LanguageRoot rlr = languageRoot(ref);
            if (rlr == null || !rlr.language.equals(from) || !referencesDone.add(ref)) continue;
            try {
                if (ref.startsWith("/content/experience-fragments/") && isPage(resolver.getResource(ref))) {
                    for (Map<String, Object> r : translate(ref, target, provider, force, java.util.Collections.singletonList(ref), true)) {
                        r.put("reference", true);
                        out.add(r);
                    }
                    continue;
                }
                boolean handled = false;
                for (ReferenceTranslator t : referenceTranslators) {
                    if (!t.accepts(resolver, ref)) continue;
                    for (Map<String, Object> r : t.translate(resolver, ref, target, provider, force)) {
                        Map<String, Object> m = new LinkedHashMap<>(r);
                        m.put("reference", true);
                        out.add(m);
                        if (m.get("source") != null) referencesDone.add(String.valueOf(m.get("source")));
                    }
                    handled = true;
                    break;
                }
                if (!handled && isAsset(resolver.getResource(ref)) && rules.at(resolver, source.getPath()).translateAssets) {
                    // an asset gets a language copy when it has texts (title, description) to translate
                    Map<String, Object> r = translateAsset(ref, target, provider, force);
                    if (!"NO_TEXT".equals(r.get("status"))) {
                        r.put("reference", true);
                        out.add(r);
                    }
                }
            } catch (IllegalArgumentException e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", ref);
                m.put("path", rlr.pathIn(target));
                m.put("language", target);
                m.put("reference", true);
                m.put("status", "SKIPPED");
                m.put("error", e.getMessage());
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- assets

    /**
     * Makes or updates an asset's language copy (the asset copied into the target language folder, the binary
     * included) with its metadata texts translated. An asset without texts is not copied: pages keep showing the
     * shared one ({@code status} NO_TEXT). Records, review and approval are a page's.
     */
    Map<String, Object> translateAsset(String path, String target, String provider, boolean force) throws TranslationException, PersistenceException {
        Resource source = resolver.getResource(path);
        if (!isAsset(source)) throw new IllegalArgumentException("No asset at " + path);
        LanguageRoot lr = languageRoot(path);
        if (lr == null || !path.startsWith("/content/dam/")) {
            throw new IllegalArgumentException(path + " is not in a language folder (e.g. /content/dam/<site>/en/...)");
        }
        if (target == null || !isLanguage(target)) throw new IllegalArgumentException("Not a language code: " + target);
        if (target.equals(lr.language)) throw new IllegalArgumentException(path + " is already in " + Languages.name(target));
        String copyPath = lr.pathIn(target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", path);
        result.put("path", copyPath);
        result.put("language", target);
        result.put("kind", "asset");
        List<Seg> segs = segmentsOf(source);
        Resource copy = resolver.getResource(copyPath);
        if (segs.isEmpty()) {
            result.put("status", "NO_TEXT");
            return result;
        }
        if (copy != null && !isAsset(copy)) throw new IllegalArgumentException(copyPath + " exists and is not an asset");
        boolean created = copy == null;
        Resource recordRes = created ? forgetRecord(copyPath) : records().getResource(RECORDS + copyPath);
        Map<String, String> stored = recordRes == null ? null : hashes(recordRes.getValueMap().get("hashes", new String[0]));
        Set<String> present = new HashSet<>();
        if (created) {
            copy = copyAsset(source, copyPath, lr, target);
        } else {
            Resource m = copy.getChild("jcr:content/metadata");
            for (Seg s : segs) if (m != null && valueAt(m, s) != null) present.add(s.id());
            checkpoint(copyPath);
        }
        if (copy.getChild("jcr:content") == null) {
            resolver.create(copy, "jcr:content", Collections.singletonMap("jcr:primaryType", (Object) "dam:AssetContent"));
        }
        Resource metadata = copy.getChild("jcr:content/metadata");
        if (metadata == null) {
            metadata = resolver.create(copy.getChild("jcr:content"), "metadata", Collections.singletonMap("jcr:primaryType", (Object) "nt:unstructured"));
        }

        List<Segment> todo = new ArrayList<>();
        Map<String, Seg> byId = new HashMap<>();
        int unchanged = 0;
        int kept = 0;
        for (Seg s : segs) {
            byId.put(s.id(), s);
            boolean translate;
            if (created || !present.contains(s.id()) || force) translate = true;
            else if (stored == null) {
                translate = false; // a copy Sensible Studio did not make: its texts are someone's translation
                kept++;
            } else if (hash(s.text).equals(stored.get(s.id()))) {
                translate = false;
                unchanged++;
            } else translate = true;
            if (translate) todo.add(new Segment(s.id(), s.text, false, s.label));
        }
        TranslationResponse response = null;
        if (!todo.isEmpty()) {
            TranslationRequest request = new TranslationRequest(lr.language, target, todo)
                    .provider(provider).title(title(source)).kind(kindOf(source)).useMemory(!force);
            try {
                response = translator.translate(resolver, path, request);
            } catch (TranslationException e) {
                resolver.revert();
                throw e;
            }
        }
        Calendar now = Calendar.getInstance();
        List<String> failed = new ArrayList<>();
        int translated = 0;
        if (response != null) {
            for (SegmentResult r : response.getResults()) {
                Seg s = byId.get(r.getId());
                if (s == null) continue;
                if (!r.isTranslated()) {
                    failed.add(r.getId()); // a new copy keeps the source's text, an updated one its translation
                    continue;
                }
                write(metadata, s, r.getText());
                translated++;
            }
        }
        ModifiableValueMap content = copy.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        content.put(LAST_UPDATE, now);
        content.put("jcr:lastModified", now);
        content.put("jcr:lastModifiedBy", user());

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("sourcePath", path);
        rec.put("sourceLanguage", lr.language);
        rec.put("targetLanguage", target);
        rec.put("status", "TRANSLATED");
        rec.put("translatedAt", now);
        rec.put("translatedBy", user());
        rec.put("failed", failed.toArray(new String[0]));
        if (response != null) {
            rec.put("provider", response.getProvider());
            rec.put("attribution", response.getAttribution());
        }
        List<String> lines = new ArrayList<>();
        Set<String> failedSet = new HashSet<>(failed);
        for (Seg s : segs) if (!failedSet.contains(s.id())) lines.add(s.id() + "=" + hash(s.text));
        rec.put("hashes", lines.toArray(new String[0]));
        resolver.commit();
        if (response != null || recordRes == null || created) {
            writeRecord(copyPath, rec, response == null && recordRes != null);
            records().commit();
        }

        result.put("created", created);
        result.put("translated", translated);
        result.put("characters", todo.stream().mapToInt(t -> t.getText().length()).sum());
        result.put("unchanged", unchanged);
        result.put("kept", kept);
        result.put("failed", failed);
        result.put("status", "TRANSLATED");
        if (response != null) {
            result.put("attribution", response.getAttribution());
            result.put("fromMemory", response.getFromMemory());
        }
        return result;
    }

    /** Copies an asset (renditions included) into the target language, creating the folders on the way. */
    private Resource copyAsset(Resource source, String copyPath, LanguageRoot lr, String target) throws PersistenceException {
        Resource folder = damFolders(ResourceUtil.getParent(copyPath), lr, target);
        javax.jcr.Session session = null;
        try {
            session = resolver.adaptTo(javax.jcr.Session.class);
        } catch (LinkageError ignored) {
            // no JCR (tests)
        }
        Resource copy;
        if (session != null) {
            // a workspace copy shares the binaries instead of streaming them again
            resolver.commit();
            try {
                session.getWorkspace().copy(source.getPath(), copyPath);
            } catch (javax.jcr.RepositoryException e) {
                throw new PersistenceException("Could not copy " + source.getPath() + " to " + copyPath + ": " + e.getMessage(), e);
            }
            resolver.refresh();
            copy = resolver.getResource(copyPath);
        } else {
            copy = copyResource(source, folder, ResourceUtil.getName(copyPath));
        }
        if (copy == null) throw new PersistenceException("The copy of " + source.getPath() + " is missing");
        Resource content = copy.getChild("jcr:content");
        if (content != null) {
            ModifiableValueMap c = content.adaptTo(ModifiableValueMap.class);
            c.put("cq:parentPath", ResourceUtil.getParent(copyPath));
            for (String k : new String[]{"cq:lastReplicated", "cq:lastReplicatedBy", "cq:lastReplicationAction", "cq:lastReplicated_publish",
                    "cq:lastReplicatedBy_publish", "cq:lastReplicationAction_publish"}) {
                c.remove(k);
            }
        }
        return copy;
    }

    private Resource copyResource(Resource from, Resource parent, String name) throws PersistenceException {
        Map<String, Object> p = new HashMap<>();
        for (Map.Entry<String, Object> e : from.getValueMap().entrySet()) {
            if (SKIP_PROPS.contains(e.getKey())) continue;
            if ("jcr:mixinTypes".equals(e.getKey())) {
                List<String> mixins = new ArrayList<>();
                for (String m : strings(e.getValue())) if (!SKIP_MIXINS.contains(m)) mixins.add(m);
                if (!mixins.isEmpty()) p.put("jcr:mixinTypes", mixins.toArray(new String[0]));
                continue;
            }
            p.put(e.getKey(), e.getValue());
        }
        Resource created = resolver.create(parent, name, p);
        for (Resource child : from.getChildren()) copyResource(child, created, child.getName());
        return created;
    }

    /** The DAM folders of {@code path} below the target language folder, titled like the source's. */
    private Resource damFolders(String path, LanguageRoot lr, String target) throws PersistenceException {
        Resource existing = resolver.getResource(path);
        if (existing != null) return existing;
        String parentPath = ResourceUtil.getParent(path);
        if (parentPath == null || !path.startsWith("/content/dam/")) throw new IllegalArgumentException("No folder at " + path);
        Resource parent = damFolders(parentPath, lr, target);
        Resource folder = resolver.create(parent, ResourceUtil.getName(path), Collections.singletonMap("jcr:primaryType", (Object) "sling:OrderedFolder"));
        String targetRoot = lr.rootOf(target);
        String title = null;
        if (path.equals(targetRoot)) {
            title = Languages.name(target);
        } else if (path.startsWith(targetRoot + "/")) {
            Resource src = resolver.getResource(lr.root + path.substring(targetRoot.length()) + "/jcr:content");
            title = src == null ? null : src.getValueMap().get("jcr:title", String.class);
        }
        Map<String, Object> c = new HashMap<>();
        c.put("jcr:primaryType", "nt:unstructured");
        if (title != null) c.put("jcr:title", title);
        resolver.create(folder, "jcr:content", c);
        return folder;
    }

    /** A version of an asset copy before an update changes it (when it is versionable). */
    private void checkpoint(String path) {
        try {
            javax.jcr.Session session = resolver.adaptTo(javax.jcr.Session.class);
            if (session == null) return;
            javax.jcr.Node node = session.getNode(path);
            if (!node.isNodeType("mix:versionable")) return;
            resolver.commit();
            session.getWorkspace().getVersionManager().checkpoint(path);
        } catch (Exception | LinkageError ignored) {
            // a version is a safety net, not a condition
        }
    }

    /** Paths of experience fragments and DAM content a page's components refer to. */
    List<String> references(Resource content) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (content != null) collectReferences(content, out);
        return new ArrayList<>(out);
    }

    private void collectReferences(Resource r, Set<String> out) {
        for (Object v : r.getValueMap().values()) {
            for (String s : strings(v instanceof String || v instanceof String[] ? v : null)) {
                if (s.startsWith("/content/experience-fragments/")) {
                    Matcher m = PATH.matcher(s);
                    if (m.matches()) out.add(m.group(1));
                } else if (s.startsWith("/content/dam/")) {
                    Matcher m = DAM_PATH.matcher(s);
                    if (m.matches()) out.add(m.group(1));
                }
            }
        }
        for (Resource child : r.getChildren()) {
            if (SKIP_NODES.contains(child.getName()) || PageTranslationRules.SKIPPED_NODES.contains(child.getName()) || isPage(child)) continue;
            collectReferences(child, out);
        }
    }

    private void copyFolder(Resource source, String copyPath) throws PersistenceException {
        Resource parent = resolver.getResource(ResourceUtil.getParent(copyPath));
        if (parent == null) throw new IllegalArgumentException("No folder at " + ResourceUtil.getParent(copyPath));
        Map<String, Object> p = new HashMap<>();
        for (Map.Entry<String, Object> e : source.getValueMap().entrySet()) if (!SKIP_PROPS.contains(e.getKey())) p.put(e.getKey(), e.getValue());
        Resource folder = resolver.create(parent, ResourceUtil.getName(copyPath), p);
        Resource content = source.getChild("jcr:content");
        if (content != null) {
            Map<String, Object> c = new HashMap<>();
            for (Map.Entry<String, Object> e : content.getValueMap().entrySet()) if (!SKIP_PROPS.contains(e.getKey())) c.put(e.getKey(), e.getValue());
            resolver.create(folder, "jcr:content", c);
        }
    }

    private Map<String, Object> translateOne(String path, LanguageRoot pageRoot, String target, String provider, boolean force, Set<String> batch)
            throws TranslationException, PersistenceException {
        LanguageRoot lr = languageRoot(path);
        String copyPath = lr.pathIn(target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", path);
        result.put("path", copyPath);
        result.put("language", target);
        if (isLiveCopy(copyPath)) {
            result.put("status", "SKIPPED");
            result.put("error", copyPath + " is a live copy: its content comes from its blueprint. Translate the blueprint, then roll it out.");
            return result;
        }
        Resource source = resolver.getResource(path);
        Resource sourceContent = source.getChild("jcr:content");
        Resource copy = resolver.getResource(copyPath);
        boolean created = copy == null;
        if (copy != null && !isPage(copy)) throw new IllegalArgumentException(copyPath + " exists and is not a page");
        Resource recordRes = created ? forgetRecord(copyPath) : records().getResource(RECORDS + copyPath);
        Map<String, String> stored = recordRes == null ? null : hashes(recordRes.getValueMap().get("hashes", new String[0]));

        List<Seg> segs = segments(sourceContent);
        // what the copy holds before components new in the source are added to it
        Set<String> present = new HashSet<>();
        if (created) {
            copy = copyPage(source, copyPath, lr.language, target, batch);
        } else {
            for (Seg s : segs) if (valueAt(copy.getChild("jcr:content"), s) != null) present.add(s.id());
            versionBeforeUpdate(copyPath, lr.language);
            addMissingNodes(sourceContent, copy.getChild("jcr:content"), lr.language, target, batch);
        }
        Resource copyContent = copy.getChild("jcr:content");

        List<Segment> todo = new ArrayList<>();
        Map<String, Seg> byId = new HashMap<>();
        int unchanged = 0;
        int kept = 0;
        for (Seg s : segs) {
            byId.put(s.id(), s);
            boolean translate;
            if (created || !present.contains(s.id()) || force) translate = true;
            else if (stored == null) {
                translate = false; // a copy Sensible Studio did not make: its texts are someone's translation
                kept++;
            } else if (hash(s.text).equals(stored.get(s.id()))) {
                translate = false;
                unchanged++;
            } else translate = true;
            if (translate) todo.add(new Segment(s.id(), s.text, s.html, s.label));
        }

        TranslationResponse response = null;
        if (!todo.isEmpty()) {
            TranslationRequest request = new TranslationRequest(lr.language, target, todo)
                    .provider(provider)
                    .title(title(source))
                    .kind("web page (" + String.valueOf(sourceContent.getValueMap().get("sling:resourceType", "page")) + ")")
                    .useMemory(!force);
            try {
                response = translator.translate(resolver, path, request);
            } catch (TranslationException e) {
                resolver.revert();
                throw e;
            }
        }

        Calendar now = Calendar.getInstance();
        List<String> failed = new ArrayList<>();
        int translated = 0;
        if (response != null) {
            for (SegmentResult r : response.getResults()) {
                Seg s = byId.get(r.getId());
                if (s == null) continue;
                if (!r.isTranslated()) {
                    failed.add(r.getId());
                    continue;
                }
                String text = s.html ? relink(r.getText(), lr.language, target, batch) : r.getText();
                write(copyContent, s, text);
                translated++;
            }
        }

        ModifiableValueMap content = copyContent.adaptTo(ModifiableValueMap.class);
        content.put(LAST_UPDATE, now);
        content.put("cq:lastModified", now);
        content.put("cq:lastModifiedBy", user());

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("sourcePath", path);
        rec.put("sourceLanguage", lr.language);
        rec.put("targetLanguage", target);
        rec.put("status", "TRANSLATED");
        rec.put("translatedAt", now);
        rec.put("translatedBy", user());
        rec.put("failed", failed.toArray(new String[0]));
        if (response != null) {
            rec.put("provider", response.getProvider());
            rec.put("attribution", response.getAttribution());
        }
        List<String> lines = new ArrayList<>();
        Set<String> failedSet = new HashSet<>(failed);
        for (Seg s : segs) if (!failedSet.contains(s.id())) lines.add(s.id() + "=" + hash(s.text));
        rec.put("hashes", lines.toArray(new String[0]));
        resolver.commit();
        writeRecord(copyPath, rec, response == null && recordRes != null);
        records().commit();

        result.put("created", created);
        result.put("translated", translated);
        result.put("characters", todo.stream().mapToInt(t -> t.getText().length()).sum());
        result.put("unchanged", unchanged);
        result.put("kept", kept);
        result.put("failed", failed);
        result.put("status", "TRANSLATED");
        if (response != null) {
            result.put("attribution", response.getAttribution());
            result.put("fromMemory", response.getFromMemory());
        }
        return result;
    }

    /** Marks a copy's translation as reviewed. */
    void approve(String copyPath) throws PersistenceException {
        approve(copyPath, user());
    }

    /**
     * A reviewer asked for changes (review workflow): the copy's state becomes "changes requested" with the
     * reviewer's comment, until it is translated or corrected again.
     */
    void requestChanges(String copyPath, String by, String comment) throws PersistenceException {
        Resource r = records().getResource(RECORDS + copyPath);
        if (r == null) throw new IllegalArgumentException(copyPath + " was not translated by Sensible Studio");
        ModifiableValueMap vm = r.adaptTo(ModifiableValueMap.class);
        vm.put("status", "CHANGES");
        vm.put("changesRequestedAt", Calendar.getInstance());
        vm.put("changesRequestedBy", by);
        if (comment != null && !comment.trim().isEmpty()) vm.put("reviewComment", comment.trim());
        else vm.remove("reviewComment");
        vm.remove("approvedAt");
        vm.remove("approvedBy");
        records().commit();
    }

    /** Approves on behalf of someone (the reviewer who completed a workflow step). */
    void approve(String copyPath, String by) throws PersistenceException {
        if (resolver.getResource(copyPath) == null) throw new IllegalArgumentException("No page at " + copyPath);
        if (!canWrite(copyPath)) throw new IllegalArgumentException("You may not change " + copyPath);
        Resource r = records().getResource(RECORDS + copyPath);
        if (r == null) throw new IllegalArgumentException(copyPath + " was not translated by Sensible Studio");
        ModifiableValueMap vm = r.adaptTo(ModifiableValueMap.class);
        vm.put("status", "APPROVED");
        vm.put("approvedAt", Calendar.getInstance());
        vm.put("approvedBy", by);
        vm.remove("changesRequestedAt");
        vm.remove("changesRequestedBy");
        vm.remove("reviewComment");
        records().commit();
        // what people approved is what the translation memory offers next time
        Resource source = resolver.getResource(vm.get("sourcePath", ""));
        Resource copy = textRoot(resolver.getResource(copyPath));
        if (isDoc(source) && copy != null) {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (Seg s : segmentsOf(source)) {
                String t = valueAt(copy, s);
                if (t != null && !t.equals(s.text)) pairs.put(s.text, t);
            }
            translator.remember(vm.get("sourceLanguage", String.class), vm.get("targetLanguage", String.class), pairs);
        }
    }

    // ------------------------------------------------------------------------------------------- review

    /** Texts of a review that break their markup, by segment id. */
    static final class ReviewException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final Map<String, String> errors;

        ReviewException(Map<String, String> errors) {
            super(errors.size() + " text(s) cannot be saved");
            this.errors = errors;
        }
    }

    /** A copy's texts next to its source's: {@code segments} with id, label, html, source, translation, failed, changed. */
    Map<String, Object> review(String copyPath) {
        Resource copy = resolver.getResource(copyPath);
        if (!isDoc(copy)) throw new IllegalArgumentException("No page or asset at " + copyPath);
        Resource rec = records().getResource(RECORDS + copyPath);
        if (rec == null) throw new IllegalArgumentException(copyPath + " was not translated by Sensible Studio");
        ValueMap rv = rec.getValueMap();
        Resource source = resolver.getResource(rv.get("sourcePath", ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", copyPath);
        out.put("title", title(copy));
        out.put("translation", record(copyPath));
        out.put("canEdit", canWrite(copyPath));
        List<Map<String, Object>> segs = new ArrayList<>();
        if (isDoc(source)) {
            out.put("sourceTitle", title(source));
            Set<String> failed = new HashSet<>(Arrays.asList(rv.get("failed", new String[0])));
            Map<String, String> stored = hashes(rv.get("hashes", new String[0]));
            Resource copyContent = textRoot(copy);
            for (Seg s : segmentsOf(source)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("label", s.label);
                m.put("html", s.html);
                m.put("source", s.text);
                m.put("translation", valueAt(copyContent, s));
                m.put("failed", failed.contains(s.id()));
                m.put("changed", stored.containsKey(s.id()) && !hash(s.text).equals(stored.get(s.id())));
                segs.add(m);
            }
        }
        out.put("segments", segs);
        return out;
    }

    /** Saves reviewers' corrections (kept markup is checked); marks the texts as no longer failed. */
    void saveReview(String copyPath, Map<String, String> translations) throws PersistenceException {
        Resource copy = resolver.getResource(copyPath);
        if (!isDoc(copy)) throw new IllegalArgumentException("No page or asset at " + copyPath);
        if (!canWrite(copyPath)) throw new IllegalArgumentException("You may not change " + copyPath);
        Resource rec = records().getResource(RECORDS + copyPath);
        if (rec == null) throw new IllegalArgumentException(copyPath + " was not translated by Sensible Studio");
        Resource source = resolver.getResource(rec.getValueMap().get("sourcePath", ""));
        if (!isDoc(source)) throw new IllegalArgumentException("The source of " + copyPath + " is gone");
        Map<String, Seg> byId = new LinkedHashMap<>();
        for (Seg s : segmentsOf(source)) byId.put(s.id(), s);
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : translations.entrySet()) {
            Seg s = byId.get(e.getKey());
            if (s == null) errors.put(e.getKey(), "Not a text of this copy");
            else if (e.getValue() == null || e.getValue().trim().isEmpty()) errors.put(e.getKey(), "A translation is needed");
            else {
                String damage = Markup.damage(s.text, e.getValue(), s.html);
                if (damage != null) errors.put(e.getKey(), damage);
            }
        }
        if (!errors.isEmpty()) throw new ReviewException(errors);
        Resource copyContent = textRoot(copy);
        for (Map.Entry<String, String> e : translations.entrySet()) write(copyContent, byId.get(e.getKey()), e.getValue());
        Calendar now = Calendar.getInstance();
        ModifiableValueMap content = copy.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        content.put(isAsset(copy) ? "jcr:lastModified" : "cq:lastModified", now);
        content.put(isAsset(copy) ? "jcr:lastModifiedBy" : "cq:lastModifiedBy", user());
        resolver.commit();
        ModifiableValueMap rv = rec.adaptTo(ModifiableValueMap.class);
        List<String> failed = new ArrayList<>(Arrays.asList(rv.get("failed", new String[0])));
        failed.removeAll(translations.keySet());
        rv.put("failed", failed.toArray(new String[0]));
        // the reviewer translated the source as it is now
        Map<String, String> hashes = hashes(rv.get("hashes", new String[0]));
        for (String id : translations.keySet()) hashes.put(id, hash(byId.get(id).text));
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> h : hashes.entrySet()) lines.add(h.getKey() + "=" + h.getValue());
        rv.put("hashes", lines.toArray(new String[0]));
        rv.put("reviewedAt", now);
        rv.put("reviewedBy", user());
        if ("CHANGES".equals(rv.get("status", String.class))) rv.put("status", "TRANSLATED"); // corrected: to review again
        records().commit();
    }

    /** Translates chosen texts of a copy again (without the memory), with its connector unless another is given. */
    Map<String, Object> retranslate(String copyPath, Collection<String> ids, String provider) throws TranslationException, PersistenceException {
        Resource copy = resolver.getResource(copyPath);
        if (!isDoc(copy)) throw new IllegalArgumentException("No page or asset at " + copyPath);
        if (!canWrite(copyPath)) throw new IllegalArgumentException("You may not change " + copyPath);
        Resource rec = records().getResource(RECORDS + copyPath);
        if (rec == null) throw new IllegalArgumentException(copyPath + " was not translated by Sensible Studio");
        ValueMap rv = rec.getValueMap();
        String sourcePath = rv.get("sourcePath", "");
        Resource source = resolver.getResource(sourcePath);
        if (!isDoc(source)) throw new IllegalArgumentException("The source of " + copyPath + " is gone");
        String from = rv.get("sourceLanguage", String.class);
        String to = rv.get("targetLanguage", String.class);
        List<Segment> todo = new ArrayList<>();
        Map<String, Seg> byId = new LinkedHashMap<>();
        for (Seg s : segmentsOf(source)) {
            if (!ids.contains(s.id())) continue;
            byId.put(s.id(), s);
            todo.add(new Segment(s.id(), s.text, s.html, s.label));
        }
        if (todo.isEmpty()) throw new IllegalArgumentException("Choose texts of this copy");
        String using = provider != null ? provider : rv.get("provider", String.class);
        TranslationResponse response = translator.translate(resolver, sourcePath, new TranslationRequest(from, to, todo)
                .provider(using).title(title(source)).kind(kindOf(source)).useMemory(false));
        Resource copyContent = textRoot(copy);
        Set<String> batch = new HashSet<>();
        batch.add(sourcePath);
        List<String> failed = new ArrayList<>(Arrays.asList(rv.get("failed", new String[0])));
        Map<String, String> hashes = hashes(rv.get("hashes", new String[0]));
        int translated = 0;
        for (SegmentResult r : response.getResults()) {
            Seg s = byId.get(r.getId());
            if (r.isTranslated()) {
                write(copyContent, s, s.html ? relink(r.getText(), from, to, batch) : r.getText());
                failed.remove(r.getId());
                hashes.put(r.getId(), hash(s.text));
                translated++;
            } else if (!failed.contains(r.getId())) {
                failed.add(r.getId());
            }
        }
        resolver.commit();
        ModifiableValueMap w = rec.adaptTo(ModifiableValueMap.class);
        w.put("failed", failed.toArray(new String[0]));
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : hashes.entrySet()) lines.add(e.getKey() + "=" + e.getValue());
        w.put("hashes", lines.toArray(new String[0]));
        records().commit();
        Map<String, Object> out = review(copyPath);
        out.put("retranslated", translated);
        out.put("attribution", response.getAttribution());
        return out;
    }

    /** The page copies Sensible Studio translated (all, or one language / below a path / in a state), newest first. */
    List<Map<String, Object>> queue(String language, String under, String status, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = records().getResource(RECORDS);
        if (root != null) collectRecords(root, language, under, status, limit, out);
        out.sort((a, b) -> Long.compare(((Number) b.getOrDefault("translatedAt", 0L)).longValue(), ((Number) a.getOrDefault("translatedAt", 0L)).longValue()));
        return out;
    }

    private void collectRecords(Resource r, String language, String under, String status, int limit, List<Map<String, Object>> out) {
        if (out.size() >= limit) return;
        ValueMap vm = r.getValueMap();
        if (vm.get("sourcePath", String.class) != null) {
            String copyPath = r.getPath().substring(RECORDS.length());
            boolean match = (language == null || language.equals(vm.get("targetLanguage", String.class)))
                    && (under == null || copyPath.startsWith(under) || vm.get("sourcePath", "").startsWith(under));
            if (match) {
                Map<String, Object> rec = record(copyPath);
                if (rec != null) {
                    String state = state(rec);
                    if (status == null || status.equals(state)) {
                        Map<String, Object> m = new LinkedHashMap<>(rec);
                        m.put("kind", copyPath.startsWith("/content/dam/") ? "asset" : copyPath.startsWith("/content/experience-fragments/") ? "experience-fragment" : "page");
                        m.put("path", copyPath);
                        m.put("title", title(resolver.getResource(copyPath)));
                        m.put("state", state);
                        out.add(m);
                    }
                }
            }
        }
        for (Resource child : r.getChildren()) collectRecords(child, language, under, status, limit, out);
    }

    /** Content fragment copies Sensible Studio translated (their records live in the fragment, {@code jcr:content/translation}). */
    List<Map<String, Object>> fragmentQueue(String language, String under, String status, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String root = under != null && under.startsWith("/content/dam") ? under : "/content/dam";
        java.util.Iterator<Resource> it;
        try {
            it = resolver.findResources("SELECT * FROM [nt:unstructured] AS t WHERE ISDESCENDANTNODE(t, '" + root.replace("'", "''")
                    + "') AND NAME(t) = 'translation'", "JCR-SQL2");
        } catch (RuntimeException e) {
            return out; // no query engine (tests)
        }
        while (it.hasNext() && out.size() < limit) {
            Resource t = it.next();
            ValueMap vm = t.getValueMap();
            if (vm.get("sourcePath", String.class) == null || !"jcr:content".equals(t.getParent().getName())) continue;
            if (language != null && !language.equals(vm.get("targetLanguage", String.class))) continue;
            Resource asset = t.getParent().getParent();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "content-fragment");
            m.put("path", asset.getPath());
            m.put("title", t.getParent().getValueMap().get("jcr:title", asset.getName()));
            for (String k : new String[]{"sourcePath", "sourceLanguage", "targetLanguage", "status", "provider", "attribution", "translatedBy",
                    "approvedBy", "changesRequestedBy", "reviewComment"}) {
                if (vm.get(k, String.class) != null) m.put(k, vm.get(k, String.class));
            }
            for (String k : new String[]{"translatedAt", "approvedAt"}) {
                Calendar c = vm.get(k, Calendar.class);
                if (c != null) m.put(k, c.getTimeInMillis());
            }
            m.put("failed", Arrays.asList(vm.get("failed", new String[0])));
            String state = state(m);
            m.put("state", state);
            if (status == null || status.equals(state)) out.add(m);
        }
        return out;
    }

    /** changes (requested by a reviewer), outdated, failed, approved, review (to review). */
    static String state(Map<String, Object> rec) {
        if ("CHANGES".equals(rec.get("status"))) return "changes";
        if (Boolean.TRUE.equals(rec.get("outdated"))) return "outdated";
        Object failed = rec.get("failed");
        if (failed instanceof Collection && !((Collection<?>) failed).isEmpty()) return "failed";
        return "APPROVED".equals(rec.get("status")) ? "approved" : "review";
    }

    // ------------------------------------------------------------------------------------------- segments

    static final class Seg {
        final String node;
        final String prop;
        final int index;
        final String text;
        final boolean html;
        final String label;

        Seg(String node, String prop, int index, String text, boolean html, String label) {
            this.node = node;
            this.prop = prop;
            this.index = index;
            this.text = text;
            this.html = html;
            this.label = label;
        }

        String id() {
            return node + "@" + prop + (index >= 0 ? "#" + index : "");
        }
    }

    List<Seg> segments(Resource content) {
        List<Seg> out = new ArrayList<>();
        // the rules of the page's translation context (the most specific one above it)
        if (content != null) walk(content, "", out, rules.at(resolver, content.getPath()));
        return out;
    }

    private void walk(Resource r, String rel, List<Seg> out, PageTranslationRules.Rules rules) {
        ValueMap vm = r.getValueMap();
        String type = vm.get("sling:resourceType", String.class);
        if (rules.isExcludedType(type)) return;
        boolean rich = "true".equalsIgnoreCase(String.valueOf(vm.get("textIsRich")));
        String component = humanize(type == null ? (rel.isEmpty() ? "page" : ResourceUtil.getName(rel)) : type.substring(type.lastIndexOf('/') + 1));
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            String prop = e.getKey();
            if (!rules.isTranslatable(type, prop)) continue;
            Object v = e.getValue();
            if (v instanceof String) {
                String s = (String) v;
                if (PageTranslationRules.isText(s)) {
                    out.add(new Seg(rel, prop, -1, s, rich && ("text".equals(prop) || "jcr:description".equals(prop)) || PageTranslationRules.looksLikeHtml(s),
                            component + " · " + propertyLabel(prop)));
                }
            } else if (v instanceof String[]) {
                String[] a = (String[]) v;
                for (int i = 0; i < a.length; i++) {
                    if (PageTranslationRules.isText(a[i])) {
                        out.add(new Seg(rel, prop, i, a[i], PageTranslationRules.looksLikeHtml(a[i]), component + " · " + propertyLabel(prop) + " " + (i + 1)));
                    }
                }
            }
        }
        for (Resource child : r.getChildren()) {
            if (PageTranslationRules.SKIPPED_NODES.contains(child.getName()) || isPage(child)) continue;
            walk(child, rel.isEmpty() ? child.getName() : rel + "/" + child.getName(), out, rules);
        }
    }

    private static final Map<String, String> PROPERTY_LABELS = new HashMap<>();

    static {
        PROPERTY_LABELS.put("jcr:title", "Title");
        PROPERTY_LABELS.put("jcr:description", "Description");
        PROPERTY_LABELS.put("pageTitle", "Page title");
        PROPERTY_LABELS.put("navTitle", "Navigation title");
        PROPERTY_LABELS.put("text", "Text");
        PROPERTY_LABELS.put("alt", "Alternative text");
        PROPERTY_LABELS.put("actionText", "Button text");
        PROPERTY_LABELS.put("linkText", "Link text");
        PROPERTY_LABELS.put("cq:panelTitle", "Panel title");
        PROPERTY_LABELS.put("helpMessage", "Help message");
    }

    static String propertyLabel(String prop) {
        String l = PROPERTY_LABELS.get(prop);
        return l != null ? l : humanize(prop.contains(":") ? prop.substring(prop.indexOf(':') + 1) : prop);
    }

    /** e.g. {@code contentfragment} → "Contentfragment", {@code item_0} → "Item 0", {@code actionText} → "Action text". */
    static String humanize(String name) {
        String s = name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[_-]+", " ").trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? name : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String valueAt(Resource content, Seg s) {
        Resource node = s.node.isEmpty() ? content : content.getChild(s.node);
        if (node == null) return null;
        Object v = node.getValueMap().get(s.prop);
        if (s.index < 0) return v instanceof String ? (String) v : null;
        String[] a = strings(v);
        return s.index < a.length ? a[s.index] : null;
    }

    private void write(Resource content, Seg s, String text) {
        Resource node = s.node.isEmpty() ? content : content.getChild(s.node);
        if (node == null) return;
        ModifiableValueMap vm = node.adaptTo(ModifiableValueMap.class);
        if (s.index < 0) {
            vm.put(s.prop, text);
            return;
        }
        String[] a = strings(vm.get(s.prop));
        if (s.index >= a.length) return;
        a = a.clone();
        a[s.index] = text;
        vm.put(s.prop, a);
    }

    // ------------------------------------------------------------------------------------------- copies

    private Resource copyPage(Resource source, String copyPath, String from, String to, Set<String> batch) throws PersistenceException {
        Resource parent = resolver.getResource(ResourceUtil.getParent(copyPath));
        if (parent == null) throw new IllegalArgumentException("No page at " + ResourceUtil.getParent(copyPath));
        Resource page = resolver.create(parent, ResourceUtil.getName(copyPath), filtered(source.getValueMap(), from, to, batch));
        Resource content = source.getChild("jcr:content");
        if (content != null) copyTree(content, page, from, to, batch);
        return page;
    }

    private void copyTree(Resource from, Resource parent, String fromLang, String to, Set<String> batch) throws PersistenceException {
        Resource created = resolver.create(parent, from.getName(), filtered(from.getValueMap(), fromLang, to, batch));
        for (Resource child : from.getChildren()) {
            if (SKIP_NODES.contains(child.getName()) || isPage(child)) continue;
            copyTree(child, created, fromLang, to, batch);
        }
    }

    /** Components the source has and the copy does not get copied in, at the same place among their siblings. */
    private void addMissingNodes(Resource source, Resource copy, String from, String to, Set<String> batch) throws PersistenceException {
        if (source == null || copy == null) return;
        List<Resource> children = new ArrayList<>();
        source.getChildren().forEach(children::add);
        for (int i = 0; i < children.size(); i++) {
            Resource child = children.get(i);
            if (SKIP_NODES.contains(child.getName()) || PageTranslationRules.SKIPPED_NODES.contains(child.getName()) || isPage(child)) continue;
            Resource there = copy.getChild(child.getName());
            if (there == null) {
                copyTree(child, copy, from, to, batch);
                // place it before the next sibling the copy has too
                for (int j = i + 1; j < children.size(); j++) {
                    if (copy.getChild(children.get(j).getName()) != null) {
                        orderBefore(copy, child.getName(), children.get(j).getName());
                        break;
                    }
                }
            } else {
                addMissingNodes(child, there, from, to, batch);
            }
        }
    }

    private void orderBefore(Resource parent, String name, String before) {
        try {
            javax.jcr.Node node = parent.adaptTo(javax.jcr.Node.class);
            if (node != null && node.getPrimaryNodeType().hasOrderableChildNodes()) node.orderBefore(name, before);
        } catch (Exception | LinkageError ignored) {
            // the order is a nicety; the content is there
        }
    }

    private Map<String, Object> filtered(ValueMap vm, String from, String to, Set<String> batch) {
        Map<String, Object> p = new HashMap<>();
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            String k = e.getKey();
            if (SKIP_PROPS.contains(k)) continue;
            Object v = e.getValue();
            if ("jcr:mixinTypes".equals(k)) {
                List<String> mixins = new ArrayList<>();
                for (String m : strings(v)) if (!SKIP_MIXINS.contains(m)) mixins.add(m);
                if (!mixins.isEmpty()) p.put(k, mixins.toArray(new String[0]));
                continue;
            }
            if (v instanceof String) v = remap((String) v, from, to, batch);
            else if (v instanceof String[]) {
                String[] a = ((String[]) v).clone();
                for (int i = 0; i < a.length; i++) a[i] = remap(a[i], from, to, batch);
                v = a;
            }
            p.put(k, v);
        }
        return p;
    }

    /** A path of the source language (page, asset, fragment) as the target language's, when that exists or is being made. */
    String remap(String value, String from, String to, Set<String> batch) {
        if (value == null || !value.startsWith("/content/")) {
            return value != null && value.contains("/content/") && PageTranslationRules.looksLikeHtml(value) ? relink(value, from, to, batch) : value;
        }
        // assets keep their extension in the path; pages are linked as <page>.html
        Matcher m = (value.startsWith("/content/dam/") ? DAM_PATH : PATH).matcher(value);
        if (!m.matches()) return value;
        String base = m.group(1);
        LanguageRoot lr = languageRoot(base);
        if (lr == null || !lr.language.equals(from)) return value;
        String mapped = lr.pathIn(to);
        return batch.contains(base) || resolver.getResource(mapped) != null ? mapped + m.group(2) : value;
    }

    private String relink(String html, String from, String to, Set<String> batch) {
        Matcher m = LINK.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + remap(m.group(2), from, to, batch)));
        m.appendTail(sb);
        return sb.toString();
    }

    private void versionBeforeUpdate(String copyPath, String from) {
        try {
            com.day.cq.wcm.api.PageManager pm = resolver.adaptTo(com.day.cq.wcm.api.PageManager.class);
            com.day.cq.wcm.api.Page page = pm == null ? null : pm.getPage(copyPath);
            if (page != null) pm.createRevision(page, null, "Before translation update from " + Languages.name(from));
        } catch (Exception | LinkageError ignored) {
            // a version is a safety net, not a condition (and there are no page versions without the WCM bundle)
        }
    }

    // ------------------------------------------------------------------------------------------- record

    /**
     * A copy made anew starts without the record of an earlier copy at the same path (one that was deleted): its
     * review and approval were of other texts. A record is kept when its copy is deleted, so that a restored copy
     * has it again. Answers null: there is no record any more.
     */
    private Resource forgetRecord(String copyPath) throws PersistenceException {
        Resource old = records().getResource(RECORDS + copyPath);
        if (old != null) records().delete(old);
        return null;
    }

    private void writeRecord(String copyPath, Map<String, Object> values, boolean keepProvider) throws PersistenceException {
        Resource r = ensure(records(), RECORDS + copyPath);
        ModifiableValueMap vm = r.adaptTo(ModifiableValueMap.class);
        if (keepProvider) {
            values.remove("provider");
            values.remove("attribution");
        }
        vm.remove("approvedAt");
        vm.remove("approvedBy");
        vm.remove("changesRequestedAt");
        vm.remove("changesRequestedBy");
        vm.remove("reviewComment");
        vm.putAll(values);
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) return r;
        Resource parent = ensure(resolver, ResourceUtil.getParent(path));
        Map<String, Object> p = new HashMap<>();
        p.put("jcr:primaryType", path.startsWith(RECORDS + "/") && path.length() > RECORDS.length() ? "nt:unstructured" : "sling:Folder");
        return resolver.create(parent, ResourceUtil.getName(path), p);
    }

    private static Map<String, String> hashes(String[] lines) {
        Map<String, String> out = new HashMap<>();
        for (String l : lines) {
            int eq = l.lastIndexOf('=');
            if (eq > 0) out.put(l.substring(0, eq), l.substring(eq + 1));
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

    private static String title(Resource page) {
        if (isAsset(page)) {
            Resource m = page.getChild("jcr:content/metadata");
            Object t = m == null ? null : m.getValueMap().get("dc:title");
            String[] a = strings(t);
            if (a.length > 0 && a[0] != null && !a[0].trim().isEmpty()) return a[0];
            return page.getName();
        }
        Resource c = page.getChild("jcr:content");
        String t = c == null ? null : c.getValueMap().get("jcr:title", String.class);
        return t == null ? page.getName() : t;
    }

    private static String[] strings(Object o) {
        if (o instanceof String[]) return (String[]) o;
        if (o instanceof Object[]) return Arrays.stream((Object[]) o).map(String::valueOf).toArray(String[]::new);
        if (o == null) return new String[0];
        return new String[]{String.valueOf(o)};
    }
}
