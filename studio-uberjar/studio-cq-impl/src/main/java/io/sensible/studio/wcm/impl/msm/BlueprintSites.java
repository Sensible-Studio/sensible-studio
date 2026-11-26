/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.MSMNameConstants;

/**
 * Blueprint configurations and Create Site, as in AEM.
 *
 * <p>A blueprint configuration is a page whose content has {@code sling:resourceType = wcm/msm/components/blueprint}
 * and a {@code sitePath}: the root of the blueprint site, whose child pages are its languages
 * ({@code /content/wknd/language-masters} → {@code en}, {@code fr}, …). They live in {@code /apps/msm},
 * {@code /libs/msm} (e.g. {@code /libs/msm/experience-fragments/blueprintconfigs/…}) and the legacy
 * {@code /etc/blueprints}.
 *
 * <p>Create Site makes, below the destination (observed on AEM 6.5):
 * <ul>
 *   <li>the site root: a shallow live copy of {@code sitePath}, with the given title and {@code cq:siteOwner};</li>
 *   <li>per chosen language: a shallow live copy of the language page;</li>
 *   <li>per chosen chapter (a child page of a language, chosen by name): a deep live copy, in every language that has
 *       it.</li>
 * </ul>
 * Without "live copy" the same structure is created as plain copies.
 */
public final class BlueprintSites {

    public static final String RT_BLUEPRINT = "wcm/msm/components/blueprint";
    static final String TEMPLATE = "/libs/wcm/msm/templates/blueprint";
    static final String PN_SITE_PATH = "sitePath";
    static final String PN_SITE_OWNER = "cq:siteOwner";
    static final String[] ROOTS = {"/apps/msm", "/libs/msm", "/etc/blueprints"};

    private BlueprintSites() {
    }

    /** @return the blueprint configurations the resolver can read, by title */
    public static List<Map<String, Object>> blueprints(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String root : ROOTS) {
            collect(resolver.getResource(root), 0, out, seen);
        }
        out.sort((a, b) -> String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title"))));
        return out;
    }

    private static void collect(Resource r, int depth, List<Map<String, Object>> out, Set<String> seen) {
        if (r == null || depth > 3) {
            return;
        }
        Map<String, Object> bp = describe(r);
        if (bp != null && seen.add(r.getPath())) {
            out.add(bp);
            return;
        }
        for (Resource c : r.getChildren()) {
            if (!Msm.JCR_CONTENT.equals(c.getName()) && !c.getName().startsWith("rep:")) {
                collect(c, depth + 1, out, seen);
            }
        }
    }

    /** @return the blueprint configuration at {@code r}, or {@code null} if it is none */
    static Map<String, Object> describe(Resource r) {
        if (r == null || !Msm.isPage(r)) {
            return null;
        }
        Resource content = r.getChild(Msm.JCR_CONTENT);
        ValueMap vm = content == null ? ValueMap.EMPTY : content.getValueMap();
        String sitePath = vm.get(PN_SITE_PATH, String.class);
        if (sitePath == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", r.getPath());
        m.put("title", vm.get("jcr:title", r.getName()));
        m.put("description", vm.get("jcr:description", String.class));
        m.put("sitePath", sitePath);
        m.put("thumbnail", content.getChild("thumbnail") != null || content.getChild("image") != null);
        // the product's own configurations (/libs) are not changed from the console
        m.put("readOnly", !isEditable(r.getPath()));
        return m;
    }

    /** @return the languages (child pages of the site root) and chapters (child pages of the languages, by name) */
    public static Map<String, Object> structure(ResourceResolver resolver, String blueprint) throws WCMException {
        Map<String, Object> bp = describe(resolver.getResource(blueprint));
        if (bp == null) {
            throw new WCMException("No blueprint configuration at " + blueprint);
        }
        return structure(resolver, bp);
    }

    /** The structure of a site that has no blueprint configuration yet (Create Site with a new blueprint). */
    public static Map<String, Object> structureOfSite(ResourceResolver resolver, String sitePath) throws WCMException {
        if (Msm.contentPath(sitePath) == null) {
            throw new WCMException("Invalid site root " + sitePath);
        }
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("path", null);
        bp.put("sitePath", sitePath);
        Resource site = resolver.getResource(sitePath);
        Resource c = site == null ? null : site.getChild(Msm.JCR_CONTENT);
        bp.put("title", c == null ? Msm.nameOf(sitePath) : c.getValueMap().get("jcr:title", Msm.nameOf(sitePath)));
        return structure(resolver, bp);
    }

    private static Map<String, Object> structure(ResourceResolver resolver, Map<String, Object> bp) throws WCMException {
        Resource site = resolver.getResource((String) bp.get("sitePath"));
        if (!Msm.isPage(site)) {
            throw new WCMException("The blueprint site " + bp.get("sitePath") + " does not exist");
        }
        List<Map<String, Object>> languages = new ArrayList<>();
        Map<String, Map<String, Object>> chapters = new LinkedHashMap<>();
        for (Resource lang : site.getChildren()) {
            if (!Msm.isPage(lang)) {
                continue;
            }
            Map<String, Object> l = item(lang);
            String language = languageName(lang.getName());
            if (language != null) {
                l.put("title", language); // English, Deutsch shown as German: like AEM's language picker
            }
            languages.add(l);
            for (Resource ch : lang.getChildren()) {
                if (Msm.isPage(ch) && !chapters.containsKey(ch.getName())) {
                    chapters.put(ch.getName(), item(ch));
                }
            }
        }
        bp.put("languages", languages);
        bp.put("chapters", new ArrayList<>(chapters.values()));
        return bp;
    }

    /** @return the English name of a language page name ({@code en}, {@code de_ch}, {@code pt-br}), or {@code null} */
    static String languageName(String name) {
        if (!name.matches("[a-zA-Z]{2,3}([_-][a-zA-Z]{2,4})?")) {
            return null;
        }
        java.util.Locale l = java.util.Locale.forLanguageTag(name.replace('_', '-'));
        String display = l.getDisplayName(java.util.Locale.ENGLISH);
        return display.isEmpty() || display.equalsIgnoreCase(name) ? null : display;
    }

    private static Map<String, Object> item(Resource page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", page.getPath());
        m.put("name", page.getName());
        Resource c = page.getChild(Msm.JCR_CONTENT);
        m.put("title", c == null ? page.getName() : c.getValueMap().get("jcr:title", page.getName()));
        return m;
    }

    /** What to create. */
    public static final class SiteRequest {
        public String blueprint;
        public String parent;
        public String name;
        public String title;
        public String owner;
        /** language page paths below the site root */
        public List<String> languages = new ArrayList<>();
        /** chapter names */
        public List<String> chapters = new ArrayList<>();
        public boolean liveCopy = true;
        public String[] rolloutConfigs;
        /**
         * Instead of {@link #blueprint}: a blueprint configuration to create with the site, for this site root (with
         * {@link #newBlueprintTitle}). Created with the site, so a wizard left half way leaves nothing behind.
         */
        public String newBlueprintSite;
        public String newBlueprintTitle;
    }

    /** Creates the site; nothing is committed. @return what was created */
    public static RolloutResult createSite(ResourceResolver resolver, RolloutManagerImpl rollouts, SiteRequest req)
            throws WCMException {
        if ((req.blueprint == null || req.blueprint.isEmpty()) && req.newBlueprintSite != null) {
            String title = req.newBlueprintTitle == null ? "" : req.newBlueprintTitle.trim();
            String base = nameFrom(title.isEmpty() ? Msm.nameOf(req.newBlueprintSite) : title);
            String bpName = base;
            Resource folder = resolver.getResource("/apps/msm");
            for (int i = 1; folder != null && folder.getChild(bpName) != null; i++) {
                bpName = base + "-" + i;
            }
            req.blueprint = createBlueprint(resolver, bpName, title.isEmpty() ? Msm.nameOf(req.newBlueprintSite) : title,
                    null, req.newBlueprintSite);
        }
        Map<String, Object> bp = describe(resolver.getResource(req.blueprint));
        if (bp == null) {
            throw new WCMException("No blueprint configuration at " + req.blueprint);
        }
        String sitePath = (String) bp.get("sitePath");
        if (Msm.contentPath(sitePath) == null || !Msm.isPage(resolver.getResource(sitePath))) {
            throw new WCMException("The blueprint site " + sitePath + " does not exist");
        }
        if (req.title == null || req.title.trim().isEmpty()) {
            throw new WCMException("A title is required");
        }
        if (req.languages.isEmpty()) {
            throw new WCMException("Choose at least one language");
        }
        String name = req.name == null || req.name.trim().isEmpty() ? nameFrom(req.title) : req.name.trim();
        if (Msm.contentPath(req.parent) == null || !Msm.isValidName(name)) {
            throw new WCMException("Invalid destination " + req.parent + "/" + name);
        }
        String root = req.parent + "/" + name;
        RolloutResult result = new RolloutResult();
        if (req.liveCopy) {
            merge(result, rollouts.createLiveCopy(resolver, sitePath, req.parent, name, req.title.trim(), false,
                    req.rolloutConfigs, null));
        } else {
            plainCopy(resolver, sitePath, root, true);
            Msm.writable(resolver.getResource(root + Msm.CONTENT_SEGMENT)).put("jcr:title", req.title.trim());
            result.created.add(root);
        }
        if (req.owner != null && !req.owner.trim().isEmpty()) {
            Msm.writable(resolver.getResource(root + Msm.CONTENT_SEGMENT)).put(PN_SITE_OWNER, req.owner.trim());
        }
        for (String lang : req.languages) {
            if (!Msm.isSameOrDescendant(lang, sitePath) || !Msm.parentOf(lang).equals(sitePath)
                    || !Msm.isPage(resolver.getResource(lang))) {
                throw new WCMException(lang + " is not a language of " + sitePath);
            }
            String langName = Msm.nameOf(lang);
            if (req.liveCopy) {
                merge(result, rollouts.createLiveCopy(resolver, lang, root, langName, null, false, req.rolloutConfigs, null));
            } else {
                plainCopy(resolver, lang, root + "/" + langName, true);
                result.created.add(root + "/" + langName);
            }
            for (String chapter : req.chapters) {
                String source = lang + "/" + chapter;
                if (!Msm.isValidName(chapter) || !Msm.isPage(resolver.getResource(source))) {
                    continue; // this language has no such chapter
                }
                if (req.liveCopy) {
                    merge(result, rollouts.createLiveCopy(resolver, source, root + "/" + langName, chapter, null, true,
                            req.rolloutConfigs, null));
                } else {
                    plainCopy(resolver, source, root + "/" + langName + "/" + chapter, false);
                    result.created.add(root + "/" + langName + "/" + chapter);
                }
            }
        }
        Resource content = resolver.getResource(root + Msm.CONTENT_SEGMENT);
        if (content != null) {
            Msm.writable(content).put("cq:lastModified", Calendar.getInstance());
        }
        return result;
    }

    /**
     * Copies a page without any MSM state: the blueprint site may itself be (or contain) live copies, whose
     * configurations and markers a plain copy must not carry along.
     */
    private static void plainCopy(ResourceResolver resolver, String source, String dest, boolean shallow) throws WCMException {
        PageManager pm = resolver.adaptTo(PageManager.class);
        Page page = pm == null ? null : pm.getPage(source);
        if (page == null) {
            throw new WCMException("Cannot copy " + source);
        }
        pm.copy(page, dest, null, shallow, false, false);
        Resource copy = resolver.getResource(dest);
        if (copy != null) {
            stripAll(copy);
        }
    }

    private static final String[] MIXINS = {MSMNameConstants.NT_LIVE_RELATIONSHIP, MSMNameConstants.NT_LIVE_SYNC,
            MSMNameConstants.NT_LIVE_SYNC_CANCELLED, MSMNameConstants.NT_PROPERTY_LIVE_SYNC_CANCELLED};
    private static final String[] PROPS = {MSMNameConstants.PN_LAST_ROLLEDOUT, MSMNameConstants.PN_LAST_ROLLEDOUT_BY,
            MSMNameConstants.PN_IS_CANCELLED_FOR_CHILDREN, MSMNameConstants.PN_PROPERTY_INHERITANCE_CANCELLED};

    /** Removes every live copy configuration, MSM mixin and MSM property in a tree. */
    static void stripAll(Resource r) throws WCMException {
        if (MSMNameConstants.NT_LIVE_SYNC_CONFIG.equals(r.getName())) {
            try {
                r.getResourceResolver().delete(r);
            } catch (org.apache.sling.api.resource.PersistenceException e) {
                throw new WCMException("Could not remove " + r.getPath(), e);
            }
            return;
        }
        if (!Msm.isPage(r)) {
            boolean marked = false;
            for (String m : MIXINS) {
                marked |= Msm.hasMixin(r, m);
                Msm.removeMixin(r, m);
            }
            ValueMap vm = r.getValueMap();
            boolean props = false;
            for (String p : PROPS) {
                props |= vm.containsKey(p);
            }
            if (marked || props) {
                org.apache.sling.api.resource.ModifiableValueMap mvm = Msm.writable(r);
                for (String p : PROPS) {
                    mvm.remove(p);
                }
            }
        }
        List<Resource> children = new ArrayList<>();
        r.getChildren().forEach(children::add);
        for (Resource c : children) {
            stripAll(c);
        }
    }

    private static void merge(RolloutResult into, RolloutResult r) {
        into.created.addAll(r.created);
        into.updated.addAll(r.updated);
        into.skipped.addAll(r.skipped);
        into.conflicts.addAll(r.conflicts);
        into.unknownActions.addAll(r.unknownActions);
        into.nodes += r.nodes;
    }

    /** AEM-like page name from a title: lower case, letters and digits, dashes between words. */
    static String nameFrom(String title) {
        String n = java.text.Normalizer.normalize(title.trim().toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "site" : n;
    }

    /** Creates a blueprint configuration page below {@code /apps/msm}. Nothing is committed. */
    public static String createBlueprint(ResourceResolver resolver, String name, String title, String description,
            String sitePath) throws WCMException {
        if (!Msm.isValidName(name) || title == null || title.trim().isEmpty()) {
            throw new WCMException("A name and a title are required");
        }
        if (Msm.contentPath(sitePath) == null || !Msm.isPage(resolver.getResource(sitePath))) {
            throw new WCMException("The site root " + sitePath + " is not a page");
        }
        Resource parent = resolver.getResource("/apps/msm");
        try {
            if (parent == null) {
                Resource apps = resolver.getResource("/apps");
                if (apps == null) {
                    throw new WCMException("/apps is not writable");
                }
                Map<String, Object> folder = new LinkedHashMap<>();
                folder.put(Msm.PRIMARY, "sling:Folder");
                folder.put("jcr:title", "MSM");
                parent = resolver.create(apps, "msm", folder);
            }
            if (parent.getChild(name) != null) {
                throw new WCMException("/apps/msm/" + name + " already exists");
            }
            Map<String, Object> page = new LinkedHashMap<>();
            page.put(Msm.PRIMARY, Msm.NT_PAGE);
            Resource p = resolver.create(parent, name, page);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put(Msm.PRIMARY, "nt:unstructured");
            content.put("jcr:title", title.trim());
            if (description != null && !description.trim().isEmpty()) {
                content.put("jcr:description", description.trim());
            }
            content.put("cq:template", TEMPLATE);
            content.put("sling:resourceType", RT_BLUEPRINT);
            content.put(PN_SITE_PATH, sitePath);
            resolver.create(p, Msm.JCR_CONTENT, content);
            return p.getPath();
        } catch (org.apache.sling.api.resource.PersistenceException e) {
            throw new WCMException("Could not create the blueprint: " + e.getMessage(), e);
        }
    }

    /** Configurations made here (/apps/msm, /etc/blueprints) can be changed; the ones in /libs are the product's. */
    static boolean isEditable(String path) {
        return path.startsWith("/apps/msm/") || path.startsWith("/etc/blueprints/");
    }

    private static Resource editable(ResourceResolver resolver, String path) throws WCMException {
        Resource r = path == null ? null : resolver.getResource(path);
        if (describe(r) == null) {
            throw new WCMException("No blueprint configuration at " + path);
        }
        if (!isEditable(path)) {
            throw new WCMException(path + " belongs to the product and is not changed here");
        }
        return r;
    }

    /** Changes a blueprint configuration's title, description and source (Blueprint Properties). Nothing is committed. */
    public static Map<String, Object> updateBlueprint(ResourceResolver resolver, String path, String title, String description,
            String sitePath) throws WCMException {
        Resource r = editable(resolver, path);
        if (title == null || title.trim().isEmpty()) {
            throw new WCMException("A title is required");
        }
        if (Msm.contentPath(sitePath) == null || !Msm.isPage(resolver.getResource(sitePath))) {
            throw new WCMException("The site root " + sitePath + " is not a page");
        }
        org.apache.sling.api.resource.ModifiableValueMap m = r.getChild(Msm.JCR_CONTENT).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        if (m == null) {
            throw new WCMException("Cannot change " + path);
        }
        m.put("jcr:title", title.trim());
        if (description == null || description.trim().isEmpty()) {
            m.remove("jcr:description");
        } else {
            m.put("jcr:description", description.trim());
        }
        m.put(PN_SITE_PATH, sitePath);
        m.put("cq:lastModified", java.util.Calendar.getInstance());
        if (resolver.getUserID() != null) {
            m.put("cq:lastModifiedBy", resolver.getUserID());
        }
        return describe(r);
    }

    /** Removes a blueprint configuration. The sites made from it stay, live copies of its source as before. Nothing is committed. */
    public static void deleteBlueprint(ResourceResolver resolver, String path) throws WCMException {
        Resource r = editable(resolver, path);
        try {
            resolver.delete(r);
        } catch (org.apache.sling.api.resource.PersistenceException e) {
            throw new WCMException("Could not delete " + path + ": " + e.getMessage(), e);
        }
    }
}
