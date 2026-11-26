package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.ReferenceTranslator;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translation projects, like AEM's: what to translate (pages, their sub-pages, experience fragments, content
 * fragments, assets), into which languages and with which connector, and one job per language that goes
 * Draft → In progress → Ready for review → Approved → Complete. A job is worked one item at a time (the console
 * drives it, so a job left half-way resumes where it stopped); an update runs the job again and only what changed
 * in the sources is translated.
 * <pre>
 * /var/studio/translation/projects/&lt;name&gt;   title, description, sourceLanguage, languages[], provider, deep, includeReferences,
 *                                           dueDate, created, createdBy, modified
 *   items/item_&lt;n&gt;                       path, kind (page | experience-fragment | content-fragment | asset)
 *   jobs/&lt;language&gt;                       status, started, startedBy, finished, approvedAt, approvedBy, completedAt, copies[], referencesDone[]
 *     items/item_&lt;n&gt;                     status (DRAFT | TRANSLATED | FAILED | SKIPPED), copyPath, translated, unchanged, failedTexts, error, at
 * </pre>
 */
final class TranslationProjects {

    static final String ROOT = "/var/studio/translation/projects";
    static final int MAX_ITEMS = 500;

    static final String DRAFT = "DRAFT";
    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String READY_FOR_REVIEW = "READY_FOR_REVIEW";
    static final String APPROVED = "APPROVED";
    static final String COMPLETE = "COMPLETE";
    static final String CANCELLED = "CANCELLED";

    private final ResourceResolver resolver;
    private final ResourceResolver store;
    private final Translator translator;
    private final PageTranslationRules rules;
    private final List<ReferenceTranslator> referenceTranslators;

    /**
     * @param resolver the user's: content is read and translated with their rights
     * @param store    where projects and records are kept (the service session; the user's when there is none)
     */
    TranslationProjects(ResourceResolver resolver, ResourceResolver store, Translator translator, PageTranslationRules rules,
                        List<ReferenceTranslator> referenceTranslators) {
        this.resolver = resolver;
        this.store = store == null ? resolver : store;
        this.translator = translator;
        this.rules = rules;
        this.referenceTranslators = referenceTranslators == null ? Collections.emptyList() : referenceTranslators;
    }

    /** Who the work is done for (a background job's starter), when it is not the session's user. */
    private String actingUser;

    TranslationProjects as(String user) {
        this.actingUser = user;
        return this;
    }

    private PageTranslation pages() {
        return new PageTranslation(resolver, translator, rules, referenceTranslators).records(store == resolver ? null : store).as(actingUser);
    }

    // ------------------------------------------------------------------------------------------- reading

    List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = store.getResource(ROOT);
        if (root == null) return out;
        for (Resource p : root.getChildren()) {
            if (p.getValueMap().get("title", String.class) == null) continue;
            Map<String, Object> m = summary(p);
            List<Map<String, Object>> jobs = new ArrayList<>();
            for (Resource job : children(p, "jobs")) jobs.add(jobSummary(p, job));
            m.put("jobs", jobs);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(((Number) b.getOrDefault("created", 0L)).longValue(), ((Number) a.getOrDefault("created", 0L)).longValue()));
        return out;
    }

    Map<String, Object> get(String name) {
        Resource p = project(name);
        Map<String, Object> m = summary(p);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Resource item : children(p, "items")) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", item.getName());
            i.put("path", item.getValueMap().get("path", String.class));
            i.put("kind", item.getValueMap().get("kind", String.class));
            Resource source = resolver.getResource(item.getValueMap().get("path", ""));
            i.put("title", source == null ? null : titleOf(source));
            i.put("exists", source != null);
            items.add(i);
        }
        m.put("items", items);
        PageTranslation pt = pages();
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Resource job : children(p, "jobs")) {
            Map<String, Object> j = jobSummary(p, job);
            List<Map<String, Object>> states = new ArrayList<>();
            Map<String, Integer> review = new LinkedHashMap<>();
            for (Resource item : children(p, "items")) {
                Resource ji = job.getChild("items/" + item.getName());
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", item.getName());
                s.put("status", ji == null ? DRAFT : ji.getValueMap().get("status", DRAFT));
                if (ji != null) {
                    ValueMap v = ji.getValueMap();
                    for (String k : new String[]{"copyPath", "error"}) if (v.get(k, String.class) != null) s.put(k, v.get(k, String.class));
                    for (String k : new String[]{"translated", "unchanged", "failedTexts"}) if (v.get(k, Integer.class) != null) s.put(k, v.get(k, Integer.class));
                    Calendar at = v.get("at", Calendar.class);
                    if (at != null) s.put("at", at.getTimeInMillis());
                }
                states.add(s);
            }
            j.put("items", states);
            // the copies the job made (with what the pages refer to) and where their review stands
            List<Map<String, Object>> copies = new ArrayList<>();
            for (String copy : job.getValueMap().get("copies", new String[0])) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("path", copy);
                String state = reviewState(pt, copy);
                c.put("state", state);
                Resource r = resolver.getResource(copy);
                c.put("title", r == null ? null : titleOf(r));
                c.put("kind", kindOf(r, copy));
                copies.add(c);
                if (state != null) review.merge(state, 1, Integer::sum);
            }
            j.put("copies", copies);
            j.put("review", review);
            jobs.add(j);
        }
        m.put("jobs", jobs);
        return m;
    }

    private Map<String, Object> summary(Resource p) {
        ValueMap v = p.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("title", v.get("title", p.getName()));
        for (String k : new String[]{"description", "sourceLanguage", "provider", "createdBy"}) if (v.get(k, String.class) != null) m.put(k, v.get(k, String.class));
        m.put("languages", Arrays.asList(v.get("languages", new String[0])));
        m.put("deep", v.get("deep", false));
        m.put("includeReferences", v.get("includeReferences", true));
        for (String k : new String[]{"created", "modified", "dueDate"}) {
            Calendar c = v.get(k, Calendar.class);
            if (c != null) m.put(k, c.getTimeInMillis());
        }
        int n = 0;
        for (Resource ignored : children(p, "items")) n++;
        m.put("itemCount", n);
        Calendar due = v.get("dueDate", Calendar.class);
        m.put("overdue", due != null && due.before(Calendar.getInstance()) && !closed(p));
        if (v.get("dueReminder", String.class) != null) m.put("dueReminder", v.get("dueReminder", String.class));
        return m;
    }

    private Map<String, Object> jobSummary(Resource p, Resource job) {
        ValueMap v = job.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", job.getName());
        m.put("status", v.get("status", DRAFT));
        for (String k : new String[]{"startedBy", "approvedBy", "error", "background", "backgroundBy"}) if (v.get(k, String.class) != null) m.put(k, v.get(k, String.class));
        Calendar bg = v.get("backgroundStarted", Calendar.class);
        if (bg != null) m.put("backgroundStarted", bg.getTimeInMillis());
        for (String k : new String[]{"started", "finished", "approvedAt", "completedAt"}) {
            Calendar c = v.get(k, Calendar.class);
            if (c != null) m.put(k, c.getTimeInMillis());
        }
        int total = 0;
        int done = 0;
        int failed = 0;
        for (Resource item : children(p, "items")) {
            total++;
            Resource ji = job.getChild("items/" + item.getName());
            String s = ji == null ? DRAFT : ji.getValueMap().get("status", DRAFT);
            if (!DRAFT.equals(s)) done++;
            if ("FAILED".equals(s)) failed++;
        }
        m.put("total", total);
        m.put("done", done);
        m.put("failedItems", failed);
        m.put("copyCount", v.get("copies", new String[0]).length);
        m.put("textsTranslated", v.get("textsTranslated", 0L));
        m.put("characters", v.get("characters", 0L));
        m.put("fromMemory", v.get("fromMemory", 0L));
        m.put("textsImported", v.get("textsImported", 0L));
        return m;
    }

    private String reviewState(PageTranslation pt, String copy) {
        try {
            Map<String, Object> rec = pt.record(copy);
            if (rec != null) return PageTranslation.state(rec);
            Resource t = resolver.getResource(copy + "/jcr:content/translation");
            if (t == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            ValueMap v = t.getValueMap();
            m.put("status", v.get("status", String.class));
            m.put("failed", Arrays.asList(v.get("failed", new String[0])));
            return PageTranslation.state(m);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------- changing

    /**
     * Creates a project: {@code paths} (pages, with their sub-pages when {@code deep}; experience fragments,
     * content fragments, assets), all in one language, into {@code languages}.
     */
    Map<String, Object> create(String title, String description, List<String> paths, boolean deep, boolean includeReferences,
                               List<String> languages, String provider, Calendar dueDate) throws PersistenceException {
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("A title is needed");
        Map<String, String> items = expand(paths, deep, null);
        String sourceLanguage = sourceLanguage(items.keySet(), null);
        List<String> targets = targets(languages, sourceLanguage);
        Resource root = ensure(ROOT);
        String name = uniqueName(root, title);
        Calendar now = Calendar.getInstance();
        Map<String, Object> p = new HashMap<>();
        p.put("jcr:primaryType", "nt:unstructured");
        p.put("title", title.trim());
        if (description != null && !description.trim().isEmpty()) p.put("description", description.trim());
        p.put("sourceLanguage", sourceLanguage);
        p.put("languages", targets.toArray(new String[0]));
        if (provider != null && !provider.trim().isEmpty()) p.put("provider", provider.trim());
        p.put("deep", deep);
        p.put("includeReferences", includeReferences);
        if (dueDate != null) p.put("dueDate", dueDate);
        p.put("created", now);
        p.put("createdBy", user());
        p.put("modified", now);
        Resource project = store.create(root, name, p);
        Resource itemsRes = store.create(project, "items", folder());
        int n = 0;
        for (Map.Entry<String, String> e : items.entrySet()) {
            Map<String, Object> ip = folder();
            ip.put("path", e.getKey());
            ip.put("kind", e.getValue());
            store.create(itemsRes, "item_" + n++, ip);
        }
        Resource jobs = store.create(project, "jobs", folder());
        for (String t : targets) {
            Map<String, Object> jp = folder();
            jp.put("status", DRAFT);
            store.create(jobs, t, jp);
        }
        store.commit();
        syncReminder(name);
        return get(name);
    }

    /** Adds pages (with sub-pages when {@code deep}), fragments or assets to a project; its jobs go back to work. */
    Map<String, Object> addItems(String name, List<String> paths, boolean deep) throws PersistenceException {
        Resource p = project(name);
        Set<String> existing = new LinkedHashSet<>();
        int n = 0;
        for (Resource item : children(p, "items")) {
            existing.add(item.getValueMap().get("path", ""));
            n = Math.max(n, Integer.parseInt(item.getName().substring("item_".length())) + 1);
        }
        Map<String, String> items = expand(paths, deep, existing);
        if (items.isEmpty()) throw new IllegalArgumentException("Everything chosen is in the project already");
        sourceLanguage(items.keySet(), p.getValueMap().get("sourceLanguage", String.class));
        if (existing.size() + items.size() > MAX_ITEMS) throw new IllegalArgumentException("A project holds at most " + MAX_ITEMS + " items");
        Resource itemsRes = p.getChild("items");
        for (Map.Entry<String, String> e : items.entrySet()) {
            Map<String, Object> ip = folder();
            ip.put("path", e.getKey());
            ip.put("kind", e.getValue());
            store.create(itemsRes, "item_" + n++, ip);
        }
        for (Resource job : children(p, "jobs")) {
            String s = job.getValueMap().get("status", DRAFT);
            if (READY_FOR_REVIEW.equals(s) || APPROVED.equals(s)) job.adaptTo(ModifiableValueMap.class).put("status", IN_PROGRESS);
        }
        touch(p);
        store.commit();
        syncReminder(name);
        return get(name);
    }

    Map<String, Object> removeItem(String name, String itemId) throws PersistenceException {
        Resource p = project(name);
        Resource item = p.getChild("items/" + itemId);
        if (item == null) throw new IllegalArgumentException("No item " + itemId + " in the project");
        store.delete(item);
        for (Resource job : children(p, "jobs")) {
            Resource ji = job.getChild("items/" + itemId);
            if (ji != null) store.delete(ji);
        }
        touch(p);
        store.commit();
        for (Resource job : children(p, "jobs")) settle(p, job);
        store.commit();
        return get(name);
    }

    /**
     * Translates one item of a job (a page with what it refers to, when the project says so). Connector failures
     * (no credentials, service down) leave the item to do and are thrown, so the console stops; other problems
     * mark the item failed and the job goes on.
     */
    Map<String, Object> translateItem(String name, String language, String itemId, boolean force) throws TranslationException, PersistenceException {
        Resource p = project(name);
        Resource job = job(p, language);
        String status = job.getValueMap().get("status", DRAFT);
        if (COMPLETE.equals(status) || CANCELLED.equals(status)) throw new IllegalArgumentException("The " + Languages.name(language) + " job is " + label(status));
        Resource item = p.getChild("items/" + itemId);
        if (item == null) throw new IllegalArgumentException("No item " + itemId + " in the project");
        String path = item.getValueMap().get("path", "");
        String kind = item.getValueMap().get("kind", "page");
        ValueMap pv = p.getValueMap();
        String provider = pv.get("provider", String.class);
        boolean references = pv.get("includeReferences", true);

        ModifiableValueMap jv = job.adaptTo(ModifiableValueMap.class);
        if (DRAFT.equals(status) || jv.get("started", Calendar.class) == null) {
            jv.put("started", Calendar.getInstance());
            jv.put("startedBy", user());
        }
        jv.put("status", IN_PROGRESS);
        jv.remove("error");
        store.commit();

        Set<String> done = new LinkedHashSet<>(Arrays.asList(jv.get("referencesDone", new String[0])));
        Set<String> copies = new LinkedHashSet<>(Arrays.asList(jv.get("copies", new String[0])));
        Map<String, Object> outcome = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        long[] stats = new long[3]; // texts translated, characters sent, texts from the memory
        try {
            PageTranslation pt = pages().skipReferences(done);
            if ("asset".equals(kind)) {
                results.add(pt.translateAsset(path, language, provider, force));
            } else if ("content-fragment".equals(kind)) {
                ReferenceTranslator t = translatorFor(path);
                if (t == null) throw new IllegalArgumentException("Content fragments cannot be translated here (studio-cfm is missing)");
                results.addAll(t.translate(resolver, path, language, provider, force));
            } else {
                List<String> batch = new ArrayList<>();
                for (Resource i : children(p, "items")) {
                    String k = i.getValueMap().get("kind", "");
                    if ("page".equals(k) || "experience-fragment".equals(k)) batch.add(i.getValueMap().get("path", ""));
                }
                results.addAll(pt.translate(path, language, provider, force, batch, references));
            }
            Map<String, Object> own = null;
            for (Map<String, Object> r : results) {
                stats[0] += number(r.get("translated"));
                stats[1] += number(r.get("characters"));
                stats[2] += number(r.get("fromMemory"));
                String copy = r.get("path") == null ? null : String.valueOf(r.get("path"));
                boolean skipped = "SKIPPED".equals(r.get("status")) || "NO_TEXT".equals(r.get("status"));
                if (copy != null && !skipped && resolver.getResource(copy) != null) copies.add(copy);
                if (Boolean.TRUE.equals(r.get("reference")) && r.get("source") != null) done.add(String.valueOf(r.get("source")));
                if (path.equals(r.get("source")) && !Boolean.TRUE.equals(r.get("reference"))) own = r;
            }
            if (own == null && !results.isEmpty()) own = results.get(results.size() - 1);
            outcome.put("status", own == null ? "FAILED" : "NO_TEXT".equals(own.get("status")) || "SKIPPED".equals(own.get("status")) ? "SKIPPED" : "TRANSLATED");
            if (own != null) {
                if (own.get("path") != null) outcome.put("copyPath", String.valueOf(own.get("path")));
                if (own.get("error") != null) outcome.put("error", String.valueOf(own.get("error")));
                else if ("NO_TEXT".equals(own.get("status"))) outcome.put("error", "No texts to translate: the asset stays shared");
                outcome.put("translated", number(own.get("translated")));
                outcome.put("unchanged", number(own.get("unchanged")));
                Object f = own.get("failed");
                outcome.put("failedTexts", f instanceof List ? ((List<?>) f).size() : f instanceof Object[] ? ((Object[]) f).length : 0);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            resolver.revert();
            outcome.put("status", "FAILED");
            outcome.put("error", e.getMessage());
        } catch (TranslationException e) {
            resolver.revert();
            store.refresh();
            Resource j = job(project(name), language);
            j.adaptTo(ModifiableValueMap.class).put("error", e.getMessage());
            store.commit();
            throw e;
        }

        // the outcome, in the store (fresh: the translation committed records through it)
        store.refresh();
        p = project(name);
        job = job(p, language);
        Resource jitems = job.getChild("items");
        if (jitems == null) jitems = store.create(job, "items", folder());
        Resource ji = jitems.getChild(itemId);
        Map<String, Object> values = new HashMap<>(outcome);
        values.put("at", Calendar.getInstance());
        if (ji == null) {
            values.put("jcr:primaryType", "nt:unstructured");
            store.create(jitems, itemId, values);
        } else {
            ModifiableValueMap v = ji.adaptTo(ModifiableValueMap.class);
            for (String k : new String[]{"copyPath", "error", "translated", "unchanged", "failedTexts"}) v.remove(k);
            v.putAll(values);
        }
        ModifiableValueMap w = job.adaptTo(ModifiableValueMap.class);
        w.put("copies", copies.toArray(new String[0]));
        w.put("referencesDone", done.toArray(new String[0]));
        w.put("textsTranslated", w.get("textsTranslated", 0L) + stats[0]);
        w.put("characters", w.get("characters", 0L) + stats[1]);
        w.put("fromMemory", w.get("fromMemory", 0L) + stats[2]);
        touch(p);
        store.commit();
        settle(p, job);
        store.commit();
        Map<String, Object> out = new LinkedHashMap<>(outcome);
        out.put("id", itemId);
        out.put("project", get(name));
        return out;
    }

    /**
     * Moves a job on: {@code APPROVED} approves all the copies it made (their texts feed the translation memory),
     * {@code COMPLETE} closes it, {@code CANCELLED} stops it, {@code DRAFT} ("Update") sets every item to do again
     * (only what changed in the sources is translated then).
     */
    Map<String, Object> setStatus(String name, String language, String status) throws Exception {
        Resource p = project(name);
        Resource job = job(p, language);
        ModifiableValueMap jv = job.adaptTo(ModifiableValueMap.class);
        String current = jv.get("status", DRAFT);
        Calendar now = Calendar.getInstance();
        switch (status == null ? "" : status) {
            case APPROVED: {
                if (!READY_FOR_REVIEW.equals(current) && !IN_PROGRESS.equals(current)) {
                    throw new IllegalArgumentException("Only a translated job can be approved (this one is " + label(current) + ")");
                }
                PageTranslation pt = pages();
                List<String> problems = new ArrayList<>();
                for (String copy : jv.get("copies", new String[0])) {
                    try {
                        if (pt.record(copy) != null) {
                            pt.approve(copy, user());
                            continue;
                        }
                        boolean approved = false;
                        for (ReferenceTranslator t : referenceTranslators) {
                            if (t.approve(resolver, copy, user())) {
                                approved = true;
                                break;
                            }
                        }
                        if (!approved) problems.add(copy + ": not a copy Sensible Studio translated");
                    } catch (IllegalArgumentException e) {
                        problems.add(copy + ": " + e.getMessage());
                    }
                }
                store.refresh();
                jv = job(project(name), language).adaptTo(ModifiableValueMap.class);
                jv.put("status", APPROVED);
                jv.put("approvedAt", now);
                jv.put("approvedBy", user());
                if (problems.isEmpty()) jv.remove("error");
                else jv.put("error", String.join("; ", problems));
                break;
            }
            case COMPLETE:
                if (!APPROVED.equals(current) && !READY_FOR_REVIEW.equals(current)) {
                    throw new IllegalArgumentException("A job is completed once it is translated (this one is " + label(current) + ")");
                }
                jv.put("status", COMPLETE);
                jv.put("completedAt", now);
                break;
            case CANCELLED:
                if (COMPLETE.equals(current)) throw new IllegalArgumentException("The job is complete");
                jv.put("status", CANCELLED);
                break;
            case DRAFT: {
                // update: everything to do again (unchanged texts are not sent to the connector)
                Resource items = job.getChild("items");
                if (items != null) {
                    for (Resource ji : items.getChildren()) ji.adaptTo(ModifiableValueMap.class).put("status", DRAFT);
                }
                jv.put("status", DRAFT);
                jv.put("referencesDone", new String[0]);
                for (String k : new String[]{"finished", "approvedAt", "approvedBy", "completedAt", "error"}) jv.remove(k);
                break;
            }
            default:
                throw new IllegalArgumentException("Not a job status: " + status);
        }
        touch(project(name));
        store.commit();
        syncReminder(name);
        return get(name);
    }

    /** Sets (or, with null, removes) the project's due date; its Inbox reminder follows. */
    Map<String, Object> setDueDate(String name, Calendar due) throws PersistenceException {
        Resource p = project(name);
        ModifiableValueMap v = p.adaptTo(ModifiableValueMap.class);
        TranslationDueReminders.end(resolver, v.get("dueReminder", String.class));
        v.remove("dueReminder");
        if (due == null) v.remove("dueDate");
        else v.put("dueDate", due);
        touch(p);
        store.commit();
        syncReminder(name);
        return get(name);
    }

    /** Every job complete or cancelled. */
    private static boolean closed(Resource p) {
        boolean any = false;
        for (Resource job : children(p, "jobs")) {
            any = true;
            String s = job.getValueMap().get("status", DRAFT);
            if (!COMPLETE.equals(s) && !CANCELLED.equals(s)) return false;
        }
        return any;
    }

    /** A project with a due date and open jobs has a reminder in its owner's Inbox; a closed one has none. */
    private void syncReminder(String name) throws PersistenceException {
        Resource p = project(name);
        ModifiableValueMap v = p.adaptTo(ModifiableValueMap.class);
        String reminder = v.get("dueReminder", String.class);
        Calendar due = v.get("dueDate", Calendar.class);
        if (closed(p) || due == null) {
            if (reminder != null) {
                TranslationDueReminders.end(resolver, reminder);
                v.remove("dueReminder");
                store.commit();
            }
        } else if (reminder == null) {
            String id = TranslationDueReminders.start(resolver, name, v.get("title", name), due);
            if (id != null) {
                v.put("dueReminder", id);
                store.commit();
            }
        }
    }

    /** Adds a language to a project (a new job). */
    Map<String, Object> addLanguage(String name, String language) throws PersistenceException {
        Resource p = project(name);
        List<String> langs = new ArrayList<>(Arrays.asList(p.getValueMap().get("languages", new String[0])));
        List<String> targets = targets(Collections.singletonList(language), p.getValueMap().get("sourceLanguage", String.class));
        if (langs.contains(targets.get(0))) throw new IllegalArgumentException("The project has a " + Languages.name(language) + " job already");
        langs.add(targets.get(0));
        p.adaptTo(ModifiableValueMap.class).put("languages", langs.toArray(new String[0]));
        Resource jobs = p.getChild("jobs");
        Map<String, Object> jp = folder();
        jp.put("status", DRAFT);
        store.create(jobs, targets.get(0), jp);
        touch(p);
        store.commit();
        syncReminder(name);
        return get(name);
    }

    // ------------------------------------------------------------------------------------------- XLIFF

    /** A job's copies with their texts (pages, assets, fragments), as XLIFF 1.2 for translators outside Sensible Studio. */
    String exportXliff(String name, String language) {
        return exportXliff(name, language, "1.2");
    }

    /** As XLIFF {@code version} 1.2 (default) or 2.0. */
    String exportXliff(String name, String language, String version) {
        if (version != null && !"1.2".equals(version) && !"2.0".equals(version)) throw new IllegalArgumentException("XLIFF " + version + " is not offered: 1.2 or 2.0");
        Resource p = project(name);
        Resource job = job(p, language);
        List<Map<String, Object>> copies = new ArrayList<>();
        PageTranslation pt = pages();
        for (String copy : job.getValueMap().get("copies", new String[0])) {
            Map<String, Object> review = reviewOf(pt, copy);
            if (review == null) continue;
            Object segs = review.get("segments");
            if (segs instanceof List && !((List<?>) segs).isEmpty()) copies.add(review);
        }
        if (copies.isEmpty()) throw new IllegalArgumentException("The " + Languages.name(language) + " job has no translated copies to export yet: start it first");
        String title = p.getValueMap().get("title", name);
        String source = p.getValueMap().get("sourceLanguage", "");
        return "2.0".equals(version) ? Xliff.write2(title, source, language, copies) : Xliff.write(title, source, language, copies);
    }

    /**
     * Imports a translated XLIFF into a job's copies: only copies of this job, only texts that changed, every text
     * checked like a reviewer's correction (markup kept). Texts that pass are saved (as reviewed); the others are
     * reported. Results: files, saved, unchanged, errors [{path, id, label, message}].
     */
    Map<String, Object> importXliff(String name, String language, java.io.InputStream in) throws Exception {
        Resource p = project(name);
        Resource job = job(p, language);
        String status = job.getValueMap().get("status", DRAFT);
        if (COMPLETE.equals(status) || CANCELLED.equals(status)) throw new IllegalArgumentException("The " + Languages.name(language) + " job is " + label(status));
        Xliff.Parsed parsed = Xliff.read(in);
        if (parsed.targetLanguage != null && !parsed.targetLanguage.isEmpty() && !parsed.targetLanguage.equals(language)) {
            throw new IllegalArgumentException("The file is a " + Languages.name(parsed.targetLanguage) + " translation, not " + Languages.name(language));
        }
        Set<String> ours = new LinkedHashSet<>(Arrays.asList(job.getValueMap().get("copies", new String[0])));
        PageTranslation pt = pages();
        List<Map<String, Object>> errors = new ArrayList<>();
        int saved = 0;
        int unchanged = 0;
        int files = 0;
        for (Map.Entry<String, Map<String, String>> file : parsed.targets.entrySet()) {
            String copy = file.getKey();
            if (!ours.contains(copy)) {
                errors.add(error(copy, null, null, "Not a copy of this job: left out"));
                continue;
            }
            Map<String, Object> review = reviewOf(pt, copy);
            if (review == null) {
                errors.add(error(copy, null, null, "No longer a copy Sensible Studio translated: left out"));
                continue;
            }
            files++;
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> segs = (List<Map<String, Object>>) review.get("segments");
            for (Map<String, Object> s : segs) byId.put(String.valueOf(s.get("id")), s);
            Map<String, String> good = new LinkedHashMap<>();
            for (Map.Entry<String, String> unit : file.getValue().entrySet()) {
                Map<String, Object> s = byId.get(unit.getKey());
                if (s == null) {
                    errors.add(error(copy, unit.getKey(), null, "Not a text of this copy (the source changed?)"));
                    continue;
                }
                String text = unit.getValue();
                if (text.equals(s.get("translation"))) {
                    unchanged++;
                    continue;
                }
                String damage = Markup.damage(String.valueOf(s.get("source")), text, Boolean.TRUE.equals(s.get("html")));
                if (damage != null) errors.add(error(copy, unit.getKey(), String.valueOf(s.get("label")), damage));
                else good.put(unit.getKey(), text);
            }
            if (good.isEmpty()) continue;
            if (pt.record(copy) != null) pt.saveReview(copy, good);
            else {
                ReferenceTranslator t = translatorFor(copy);
                if (t == null) {
                    errors.add(error(copy, null, null, "Cannot be corrected here"));
                    continue;
                }
                t.saveReview(resolver, copy, good);
            }
            saved += good.size();
        }
        store.refresh();
        ModifiableValueMap jv = job(project(name), language).adaptTo(ModifiableValueMap.class);
        jv.put("textsImported", jv.get("textsImported", 0L) + saved);
        jv.put("lastImport", Calendar.getInstance());
        jv.put("lastImportBy", user());
        touch(project(name));
        store.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("saved", saved);
        out.put("unchanged", unchanged);
        out.put("errors", errors);
        out.put("project", get(name));
        return out;
    }

    private Map<String, Object> reviewOf(PageTranslation pt, String copy) {
        try {
            if (pt.record(copy) != null) return pt.review(copy);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (ReferenceTranslator t : referenceTranslators) {
            try {
                Map<String, Object> r = t.review(resolver, copy);
                if (r != null) return r;
            } catch (RuntimeException ignored) {
                // another translator's copy
            }
        }
        return null;
    }

    private static Map<String, Object> error(String path, String id, String label, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        if (id != null) m.put("id", id);
        if (label != null) m.put("label", label);
        m.put("message", message);
        return m;
    }

    // ------------------------------------------------------------------------------------------- background

    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String STOPPING = "STOPPING";

    /**
     * Marks a job to be worked on the server (the caller queues the Sling job): its items to do, with the rights of
     * the translation worker, on behalf of the user, who must be able to write where the copies go.
     */
    Map<String, Object> queueBackground(String name, String language) throws PersistenceException {
        Resource p = project(name);
        Resource job = job(p, language);
        ModifiableValueMap jv = job.adaptTo(ModifiableValueMap.class);
        String status = jv.get("status", DRAFT);
        if (!DRAFT.equals(status) && !IN_PROGRESS.equals(status)) {
            throw new IllegalArgumentException("Only a job with items to do runs (this one is " + label(status) + ")");
        }
        String bg = jv.get("background", String.class);
        if (QUEUED.equals(bg) || RUNNING.equals(bg)) throw new IllegalArgumentException("The job is running on the server already");
        checkCanWrite(p, language);
        jv.put("background", QUEUED);
        jv.put("backgroundBy", user());
        jv.put("backgroundStarted", Calendar.getInstance());
        jv.remove("stopRequested");
        jv.remove("error");
        store.commit();
        return get(name);
    }

    /** Asks a background job to stop after the item it is on. */
    Map<String, Object> stopBackground(String name, String language) throws PersistenceException {
        ModifiableValueMap jv = job(project(name), language).adaptTo(ModifiableValueMap.class);
        String bg = jv.get("background", String.class);
        if (bg == null) throw new IllegalArgumentException("The job is not running on the server");
        if (QUEUED.equals(bg)) clearBackground(jv);
        else {
            jv.put("stopRequested", true);
            jv.put("background", STOPPING);
        }
        store.commit();
        return get(name);
    }

    /** The background job's loop: items to do, one at a time, until done, stopped or the connector fails. */
    void runBackground(String name, String language) throws PersistenceException {
        Resource p = project(name);
        ModifiableValueMap jv = job(p, language).adaptTo(ModifiableValueMap.class);
        if (jv.get("background", String.class) == null) return; // stopped before it started
        jv.put("background", RUNNING);
        store.commit();
        try {
            while (true) {
                store.refresh();
                p = project(name);
                Resource job = job(p, language);
                ValueMap v = job.getValueMap();
                String status = v.get("status", DRAFT);
                if (v.get("stopRequested", false) || COMPLETE.equals(status) || CANCELLED.equals(status)) break;
                String next = null;
                for (Resource item : children(p, "items")) {
                    Resource ji = job.getChild("items/" + item.getName());
                    if (DRAFT.equals(ji == null ? DRAFT : ji.getValueMap().get("status", DRAFT))) {
                        next = item.getName();
                        break;
                    }
                }
                if (next == null) break;
                try {
                    translateItem(name, language, next, false);
                } catch (TranslationException e) {
                    break; // translateItem recorded the error on the job
                }
            }
        } finally {
            store.refresh();
            try {
                clearBackground(job(project(name), language).adaptTo(ModifiableValueMap.class));
                store.commit();
            } catch (IllegalArgumentException gone) {
                // the project was deleted meanwhile
            }
        }
    }

    private static void clearBackground(ModifiableValueMap jv) {
        jv.remove("background");
        jv.remove("stopRequested");
    }

    /** The user may create or change the copies of the project's items in the language (checked with their session). */
    private void checkCanWrite(Resource p, String language) {
        javax.jcr.Session session;
        try {
            session = resolver.adaptTo(javax.jcr.Session.class);
        } catch (LinkageError e) {
            return;
        }
        if (session == null) return; // no JCR (tests)
        for (Resource item : children(p, "items")) {
            String path = item.getValueMap().get("path", "");
            PageTranslation.LanguageRoot lr = PageTranslation.languageRoot(path);
            if (lr == null) continue;
            String at = lr.pathIn(language);
            while (at != null && resolver.getResource(at) == null) at = ResourceUtil.getParent(at);
            if (at == null) continue;
            try {
                if (!session.hasPermission(at, "add_node,set_property")) {
                    throw new IllegalArgumentException("You may not write " + at + ": translate " + path + " yourself, or ask someone who may");
                }
            } catch (javax.jcr.RepositoryException e) {
                throw new IllegalArgumentException("Could not check the rights on " + at + ": " + e.getMessage());
            }
        }
    }

    void delete(String name) throws PersistenceException {
        TranslationDueReminders.end(resolver, project(name).getValueMap().get("dueReminder", String.class));
        store.delete(project(name));
        store.commit();
    }

    /** In progress until every item is done, then ready for review. */
    private void settle(Resource p, Resource job) {
        ModifiableValueMap jv = job.adaptTo(ModifiableValueMap.class);
        String s = jv.get("status", DRAFT);
        if (!IN_PROGRESS.equals(s) && !READY_FOR_REVIEW.equals(s)) return;
        boolean all = true;
        boolean any = false;
        for (Resource item : children(p, "items")) {
            Resource ji = job.getChild("items/" + item.getName());
            String is = ji == null ? DRAFT : ji.getValueMap().get("status", DRAFT);
            if (DRAFT.equals(is)) all = false;
            else any = true;
        }
        if (all && any) {
            jv.put("status", READY_FOR_REVIEW);
            jv.put("finished", Calendar.getInstance());
        } else {
            jv.put("status", any ? IN_PROGRESS : DRAFT);
            jv.remove("finished");
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** What the paths are (pages with their sub-pages when deep), in order, without what is there already. */
    private Map<String, String> expand(List<String> paths, boolean deep, Set<String> existing) {
        if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("Choose what to translate");
        Map<String, String> out = new LinkedHashMap<>();
        PageTranslation pt = pages();
        for (String path : paths) {
            Resource r = path == null ? null : resolver.getResource(path);
            if (r == null) throw new IllegalArgumentException("Nothing at " + path);
            List<String> found = PageTranslation.isPage(r) ? pt.pages(path, deep) : Collections.singletonList(path);
            for (String f : found) {
                if (existing != null && existing.contains(f)) continue;
                String kind = kindOf(resolver.getResource(f), f);
                if (kind == null) throw new IllegalArgumentException(f + " is not a page, experience fragment, content fragment or asset");
                out.putIfAbsent(f, kind);
                if (out.size() + (existing == null ? 0 : existing.size()) > MAX_ITEMS) {
                    throw new IllegalArgumentException("A project holds at most " + MAX_ITEMS + " items: choose a smaller part of the site");
                }
            }
        }
        return out;
    }

    private String kindOf(Resource r, String path) {
        if (r == null) return null;
        if (PageTranslation.isPage(r)) return path.startsWith("/content/experience-fragments/") ? "experience-fragment" : "page";
        if (translatorFor(path) != null) return "content-fragment";
        if (PageTranslation.isAsset(r)) return "asset";
        return null;
    }

    private ReferenceTranslator translatorFor(String path) {
        for (ReferenceTranslator t : referenceTranslators) {
            try {
                if (t.accepts(resolver, path)) return t;
            } catch (RuntimeException ignored) {
                // a translator that cannot tell does not take it
            }
        }
        return null;
    }

    private static String sourceLanguage(Iterable<String> paths, String expected) {
        String lang = expected;
        for (String path : paths) {
            PageTranslation.LanguageRoot lr = PageTranslation.languageRoot(path);
            if (lr == null) throw new IllegalArgumentException(path + " is not in a language root (…/<site>/<language>/…)");
            if (lang == null) lang = lr.language;
            else if (!lang.equals(lr.language)) {
                throw new IllegalArgumentException("A project translates from one language: " + path + " is in " + Languages.name(lr.language)
                        + ", the project's source is " + Languages.name(lang));
            }
        }
        if (lang == null) throw new IllegalArgumentException("Choose what to translate");
        return lang;
    }

    private static List<String> targets(List<String> languages, String sourceLanguage) {
        if (languages == null || languages.isEmpty()) throw new IllegalArgumentException("Choose at least one language");
        List<String> out = new ArrayList<>();
        for (String l : languages) {
            String t = l == null ? "" : l.trim();
            if (!PageTranslation.isLanguage(t)) throw new IllegalArgumentException("Not a language code: " + l);
            if (t.equals(sourceLanguage)) throw new IllegalArgumentException("The source is in " + Languages.name(t) + " already");
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    private Resource project(String name) {
        if (name == null || name.isEmpty() || name.contains("/")) throw new IllegalArgumentException("No project " + name);
        Resource p = store.getResource(ROOT + "/" + name);
        if (p == null) throw new IllegalArgumentException("No project " + name);
        return p;
    }

    private static Resource job(Resource project, String language) {
        Resource job = language == null ? null : project.getChild("jobs/" + language);
        if (job == null) throw new IllegalArgumentException("The project has no " + Languages.name(language) + " job");
        return job;
    }

    private static Iterable<Resource> children(Resource r, String name) {
        Resource c = r.getChild(name);
        if (c == null) return Collections.emptyList();
        List<Resource> out = new ArrayList<>();
        c.getChildren().forEach(out::add);
        // item_2 before item_10
        out.sort((a, b) -> {
            int x = order(a.getName());
            int y = order(b.getName());
            return x != y ? Integer.compare(x, y) : a.getName().compareTo(b.getName());
        });
        return out;
    }

    private static int order(String name) {
        if (!name.startsWith("item_")) return 0;
        try {
            return Integer.parseInt(name.substring(5));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Resource ensure(String path) throws PersistenceException {
        Resource r = store.getResource(path);
        if (r != null) return r;
        Resource parent = ensure(ResourceUtil.getParent(path));
        return store.create(parent, ResourceUtil.getName(path), Collections.singletonMap("jcr:primaryType", (Object) "sling:Folder"));
    }

    static String uniqueName(Resource root, String title) {
        String base = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "project";
        if (base.length() > 60) base = base.substring(0, 60).replaceAll("-+$", "");
        String name = base;
        for (int i = 1; root.getChild(name) != null; i++) name = base + "-" + i;
        return name;
    }

    private static String titleOf(Resource r) {
        if (PageTranslation.isAsset(r)) {
            Object t = r.getChild("jcr:content/metadata") == null ? null : r.getChild("jcr:content/metadata").getValueMap().get("dc:title");
            if (t instanceof String && !((String) t).isEmpty()) return (String) t;
            if (t instanceof String[] && ((String[]) t).length > 0) return ((String[]) t)[0];
        }
        Resource c = r.getChild("jcr:content");
        String t = c == null ? null : c.getValueMap().get("jcr:title", String.class);
        return t == null ? r.getName() : t;
    }

    private static String label(String status) {
        return status.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static int number(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private void touch(Resource p) {
        p.adaptTo(ModifiableValueMap.class).put("modified", Calendar.getInstance());
    }

    private String user() {
        if (actingUser != null) return actingUser;
        String u = resolver.getUserID();
        return u == null ? "anonymous" : u;
    }

    private static Map<String, Object> folder() {
        Map<String, Object> m = new HashMap<>();
        m.put("jcr:primaryType", "nt:unstructured");
        return m;
    }
}
