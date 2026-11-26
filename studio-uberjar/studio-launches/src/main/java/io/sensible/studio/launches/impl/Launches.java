/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.launches.impl;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.VersionManager;

/**
 * Launches, laid out as AEM lays them out: {@code /content/launches/<yyyy>/<MM>/<dd>/<name>} holds the
 * launch ({@code jcr:content}: title, live date, source, whether the pages are MSM live copies) and,
 * below {@code content}, the source pages at their own paths ({@code <launch>/content/content/wknd/us/en}).
 * The ancestors of the source page are thin {@code cq:Page} shells (properties of their {@code jcr:content},
 * no components), so inheritance up the tree (configuration, language, header fragment) works in the
 * launch as on the site; {@code launchShell=true} marks them, and promotion ignores them.
 * <p>
 * Promotion copies a launch page's {@code jcr:content} over its source page (a version of the source
 * is saved first), creates source pages for pages added in the launch, and drops the MSM state a live
 * copy carries.
 */
public final class Launches {

    public static final String ROOT = "/content/launches";
    public static final String CONTENT = "content";
    public static final String PROP_SOURCE = "sourceRootResource";
    /** Every source page of a launch made from several pages (the first is also {@link #PROP_SOURCE}). */
    public static final String PROP_SOURCES = "sourceRootResources";
    public static final String PROP_LIVE_DATE = "liveDate";
    public static final String PROP_LIVE_COPY = "isLiveCopy";
    public static final String PROP_DEEP = "isDeep";
    public static final String PROP_CREATED = "created";
    public static final String PROP_CREATED_BY = "createdBy";
    /** AEM's "Production ready": at the live date the launch is promoted and its pages published by themselves. */
    public static final String PROP_PRODUCTION_READY = "isProductionReady";
    /** Why the promotion at the live date did not happen; while set, it is not tried again. */
    public static final String PROP_AUTO_ERROR = "autoPromotionError";
    /** The pages published after the promotion at the live date. */
    public static final String PROP_AUTO_PUBLISHED = "autoPublished";
    public static final String PROP_PROMOTED = "promoted";
    public static final String PROP_PROMOTED_BY = "promotedBy";
    public static final String PROP_SHELL = "launchShell";
    public static final String RT_LAUNCH = "wcm/launches/components/launch";

    /** MSM state of a live copy page that must not travel to the source on promotion. */
    static final Set<String> MSM_MIXINS = new HashSet<>(Arrays.asList("cq:LiveRelationship", "cq:LiveSync", "cq:LiveSyncCancelled", "cq:PropertyLiveSyncCancelled"));
    static final Set<String> MSM_PROPS = new HashSet<>(Arrays.asList("cq:master", "cq:lastRolledout", "cq:lastRolledoutBy", "cq:isCancelledForChildren", "cq:propertyInheritanceCancelled"));
    static final Set<String> MSM_NODES = new HashSet<>(Arrays.asList("cq:LiveSyncConfig"));

    private Launches() {
    }

    /** A launch, read from its node. */
    public static final class Launch {
        public final String path;
        public final String name;
        public final String title;
        public final String description;
        public final String source;
        /** The pages the launch was made from: {@link #source} first. */
        public final List<String> sources;
        public final Calendar liveDate;
        public final boolean liveCopy;
        public final boolean deep;
        public final Calendar created;
        public final String createdBy;
        public final Calendar promoted;
        public final boolean productionReady;
        public final String autoError;
        public final String promotedBy;

        Launch(Node n) throws RepositoryException {
            Node jc = n.getNode("jcr:content");
            path = n.getPath();
            name = n.getName();
            title = str(jc, "jcr:title", name);
            description = str(jc, "jcr:description", null);
            source = str(jc, PROP_SOURCE, null);
            List<String> all = new ArrayList<>();
            if (jc.hasProperty(PROP_SOURCES)) {
                for (javax.jcr.Value v : jc.getProperty(PROP_SOURCES).getValues()) {
                    all.add(v.getString());
                }
            } else if (source != null) {
                all.add(source);
            }
            sources = java.util.Collections.unmodifiableList(all);
            liveDate = date(jc, PROP_LIVE_DATE);
            liveCopy = jc.hasProperty(PROP_LIVE_COPY) && jc.getProperty(PROP_LIVE_COPY).getBoolean();
            deep = jc.hasProperty(PROP_DEEP) && jc.getProperty(PROP_DEEP).getBoolean();
            created = date(jc, PROP_CREATED);
            createdBy = str(jc, PROP_CREATED_BY, null);
            promoted = date(jc, PROP_PROMOTED);
            productionReady = jc.hasProperty(PROP_PRODUCTION_READY) && jc.getProperty(PROP_PRODUCTION_READY).getBoolean();
            autoError = str(jc, PROP_AUTO_ERROR, null);
            promotedBy = str(jc, PROP_PROMOTED_BY, null);
        }

        /** Where the source page lives inside the launch. */
        public String sourceInLaunch() {
            return path + "/" + CONTENT + source;
        }

        /** Production ready, its live date has come and it was not promoted (nor tried and failed): promote it now. */
        public boolean due(Calendar now) {
            return productionReady && promoted == null && autoError == null && liveDate != null && !liveDate.after(now);
        }

        /** Whether a page of the site is one of the launch's sources or below one. */
        public boolean covers(String page) {
            if (page == null) {
                return false;
            }
            for (String s : sources) {
                if (page.equals(s) || page.startsWith(s + "/")) {
                    return true;
                }
            }
            return false;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("name", name);
            m.put("title", title);
            m.put("description", description);
            m.put("source", source);
            m.put("sources", sources);
            m.put("sourceInLaunch", sourceInLaunch());
            m.put("liveDate", iso(liveDate));
            m.put("liveCopy", liveCopy);
            m.put("deep", deep);
            m.put("created", iso(created));
            m.put("createdBy", createdBy);
            m.put("promoted", iso(promoted));
            m.put("promotedBy", promotedBy);
            m.put("productionReady", productionReady);
            m.put("autoError", autoError);
            return m;
        }
    }

    /** One page of a launch and what promotion would do with it. */
    public static final class LaunchPage {
        public final String path;
        public final String sourcePath;
        public final String title;
        public final boolean modified;
        public final boolean sourceExists;
        public final Calendar lastModified;

        LaunchPage(String path, String sourcePath, String title, boolean modified, boolean sourceExists, Calendar lastModified) {
            this.path = path;
            this.sourcePath = sourcePath;
            this.title = title;
            this.modified = modified;
            this.sourceExists = sourceExists;
            this.lastModified = lastModified;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("sourcePath", sourcePath);
            m.put("title", title);
            m.put("modified", modified);
            m.put("sourceExists", sourceExists);
            m.put("lastModified", iso(lastModified));
            return m;
        }
    }

    // ------------------------------------------------------------------------------------------ lookup

    /** The launch a path (a launch node or anything under it) belongs to; null otherwise. */
    public static Launch of(Session session, String path) throws RepositoryException {
        if (path == null || !path.startsWith(ROOT + "/")) {
            return null;
        }
        String[] parts = path.split("/");
        // /content/launches/yyyy/MM/dd/name -> 7 parts with the leading empty one
        if (parts.length < 7) {
            return null;
        }
        String launchPath = String.join("/", Arrays.copyOfRange(parts, 0, 7));
        if (!session.nodeExists(launchPath + "/jcr:content")) {
            return null;
        }
        Node n = session.getNode(launchPath);
        return n.getNode("jcr:content").hasProperty(PROP_SOURCE) ? new Launch(n) : null;
    }

    /** The source page of a page inside a launch ({@code <launch>/content/<source>}); null when outside. */
    public static String sourceOf(Launch launch, String pathInLaunch) {
        String prefix = launch.path + "/" + CONTENT;
        if (pathInLaunch == null || !pathInLaunch.startsWith(prefix + "/")) {
            return null;
        }
        return pathInLaunch.substring(prefix.length());
    }

    public static String inLaunch(Launch launch, String sourcePath) {
        return launch.path + "/" + CONTENT + sourcePath;
    }

    public static List<Launch> list(Session session) throws RepositoryException {
        List<Launch> out = new ArrayList<>();
        if (!session.nodeExists(ROOT)) {
            return out;
        }
        collectLaunches(session.getNode(ROOT), 0, out);
        out.sort((a, b) -> b.created == null || a.created == null ? 0 : b.created.compareTo(a.created));
        return out;
    }

    private static void collectLaunches(Node n, int depth, List<Launch> out) throws RepositoryException {
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (c.getName().startsWith("jcr:") || c.getName().startsWith("rep:")) {
                continue;
            }
            if (depth == 3) {
                if (c.hasNode("jcr:content") && c.getNode("jcr:content").hasProperty(PROP_SOURCE)) {
                    out.add(new Launch(c));
                }
            } else if (depth < 3) {
                collectLaunches(c, depth + 1, out);
            }
        }
    }

    /** The real pages of a launch (shells left out), document order, with their promotion state. */
    public static List<LaunchPage> pages(Session session, Launch launch) throws RepositoryException {
        List<LaunchPage> out = new ArrayList<>();
        String root = launch.path + "/" + CONTENT;
        if (session.nodeExists(root)) {
            collectPages(session, launch, session.getNode(root), out);
        }
        return out;
    }

    private static void collectPages(Session session, Launch launch, Node n, List<LaunchPage> out) throws RepositoryException {
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (c.getName().equals("jcr:content") || c.getName().startsWith("rep:")) {
                continue;
            }
            if (c.isNodeType("cq:Page") && c.hasNode("jcr:content") && !isShell(c)) {
                Node jc = c.getNode("jcr:content");
                Calendar lm = date(jc, "cq:lastModified");
                boolean modified = lm != null && launch.created != null && lm.after(launch.created);
                String source = sourceOf(launch, c.getPath());
                out.add(new LaunchPage(c.getPath(), source, str(jc, "jcr:title", c.getName()), modified, source != null && session.nodeExists(source), lm));
            }
            collectPages(session, launch, c, out);
        }
    }

    static boolean isShell(Node page) throws RepositoryException {
        return page.hasNode("jcr:content") && page.getNode("jcr:content").hasProperty(PROP_SHELL)
                && page.getNode("jcr:content").getProperty(PROP_SHELL).getBoolean();
    }

    // ------------------------------------------------------------------------------------------ create

    /**
     * Creates the launch node, the shells down to the source page's parent and, unless {@code liveCopy},
     * the copy of the source page ({@code deep}: with its subpages). With {@code liveCopy} the caller
     * creates the page as an MSM live copy at {@link Launch#sourceInLaunch()}. The caller saves.
     */
    public static Launch create(Session session, String sourcePage, String title, Calendar liveDate, boolean deep, boolean liveCopy, String description)
            throws RepositoryException {
        if (sourcePage == null || !session.nodeExists(sourcePage) || !session.getNode(sourcePage).isNodeType("cq:Page")) {
            throw new IllegalArgumentException("No page at " + sourcePage);
        }
        if (sourcePage.startsWith(ROOT + "/")) {
            throw new IllegalArgumentException("A launch cannot be made from a launch page");
        }
        String name = nameFrom(title == null || title.isEmpty() ? session.getNode(sourcePage).getName() : title);
        Calendar now = Calendar.getInstance();
        Node day = mkdirs(session, String.format(Locale.ROOT, "%s/%tY/%tm/%td", ROOT, now, now, now), "sling:Folder");
        String unique = name;
        for (int i = 1; day.hasNode(unique); i++) {
            unique = name + "-" + i;
        }
        Node launch = day.addNode(unique, "cq:Page");
        Node jc = launch.addNode("jcr:content", "cq:PageContent");
        jc.setProperty("sling:resourceType", RT_LAUNCH);
        jc.setProperty("jcr:title", title == null || title.isEmpty() ? unique : title);
        if (description != null && !description.isEmpty()) {
            jc.setProperty("jcr:description", description);
        }
        jc.setProperty(PROP_SOURCE, sourcePage);
        if (liveDate != null) {
            jc.setProperty(PROP_LIVE_DATE, liveDate);
        }
        jc.setProperty(PROP_LIVE_COPY, liveCopy);
        jc.setProperty(PROP_DEEP, deep);
        jc.setProperty(PROP_CREATED, now);
        jc.setProperty(PROP_CREATED_BY, session.getUserID());

        Node parent = shells(session, launch.addNode(CONTENT, "sling:Folder"), sourcePage);
        if (!liveCopy) {
            copyPage(session.getNode(sourcePage), parent, sourcePage.substring(sourcePage.lastIndexOf('/') + 1), deep);
        }
        return new Launch(launch);
    }

    /** The shells: every ancestor of the source page, as a page with its properties only; returns the last one. */
    private static Node shells(Session session, Node parent, String sourcePage) throws RepositoryException {
        String[] segs = sourcePage.substring(1).split("/");
        StringBuilder sp = new StringBuilder();
        for (int i = 0; i < segs.length - 1; i++) {
            sp.append('/').append(segs[i]);
            if (parent.hasNode(segs[i])) {
                parent = parent.getNode(segs[i]);
                continue;
            }
            Node src = session.getNode(sp.toString());
            Node shell = parent.addNode(segs[i], src.isNodeType("cq:Page") ? "cq:Page" : "sling:Folder");
            if (src.hasNode("jcr:content")) {
                Node sjc = src.getNode("jcr:content");
                Node shellJc = shell.addNode("jcr:content", sjc.getPrimaryNodeType().getName());
                copyProperties(sjc, shellJc);
                shellJc.setProperty(PROP_SHELL, true);
            }
            parent = shell;
        }
        return parent;
    }

    /**
     * One more source page in a launch (AEM's Create Launch from several selected pages): the shells it
     * still needs and, unless {@code liveCopy}, its copy. Returns where the page lives in the launch.
     * A page already in the launch is refused (one below a source copied without its subpages is not in it). The caller saves.
     */
    public static String addSource(Session session, Launch launch, String sourcePage, boolean deep, boolean liveCopy) throws RepositoryException {
        if (sourcePage == null || !session.nodeExists(sourcePage) || !session.getNode(sourcePage).isNodeType("cq:Page")) {
            throw new IllegalArgumentException("No page at " + sourcePage);
        }
        if (sourcePage.startsWith(ROOT + "/")) {
            throw new IllegalArgumentException("A launch cannot be made from a launch page");
        }
        String in = inLaunch(launch, sourcePage);
        if (session.nodeExists(in)) {
            throw new IllegalArgumentException(sourcePage + " is already in the release");
        }
        Node parent = shells(session, session.getNode(launch.path + "/" + CONTENT), sourcePage);
        if (!liveCopy) {
            copyPage(session.getNode(sourcePage), parent, sourcePage.substring(sourcePage.lastIndexOf('/') + 1), deep);
        }
        List<String> all = new ArrayList<>(launch.sources);
        all.add(sourcePage);
        session.getNode(launch.path).getNode("jcr:content").setProperty(PROP_SOURCES, all.toArray(new String[0]));
        return in;
    }

    /**
     * Production ready or not. It needs a live date; setting it again clears an earlier failure, so the
     * promotion is tried again. The caller saves.
     */
    public static void setProductionReady(Session session, Launch launch, boolean ready) throws RepositoryException {
        Node jc = session.getNode(launch.path).getNode("jcr:content");
        if (ready && !jc.hasProperty(PROP_LIVE_DATE)) {
            throw new IllegalArgumentException("A release that goes live by itself needs a live date");
        }
        jc.setProperty(PROP_PRODUCTION_READY, ready);
        if (jc.hasProperty(PROP_AUTO_ERROR)) {
            jc.getProperty(PROP_AUTO_ERROR).remove();
        }
    }

    /** A page name from a title, AEM style: lower case, dashes. */
    static String nameFrom(String title) {
        String n = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("[\\p{M}]", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "launch" : n.length() > 60 ? n.substring(0, 60) : n;
    }

    // ------------------------------------------------------------------------------------------ promote

    /** What {@link #promote} did. */
    public static final class Promotion {
        public final List<String> promoted = new ArrayList<>();
        public final List<String> created = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("promoted", promoted);
            m.put("created", created);
            m.put("skipped", skipped);
            return m;
        }
    }

    /**
     * Promotes the launch's pages to their sources: {@code scope} is {@code all}, {@code page} (only
     * {@code pagePath}) or {@code pageAndSubpages}; {@code modifiedOnly} skips pages untouched since the
     * launch was created. The caller saves (versions are created right away).
     */
    public static Promotion promote(Session session, Launch launch, String scope, String pagePath, boolean modifiedOnly) throws RepositoryException {
        Promotion result = new Promotion();
        VersionManager vm = session.getWorkspace().getVersionManager();
        for (LaunchPage p : pages(session, launch)) {
            boolean inScope = "page".equals(scope) ? p.path.equals(pagePath)
                    : "pageAndSubpages".equals(scope) ? p.path.equals(pagePath) || p.path.startsWith(pagePath + "/") : true;
            if (!inScope || p.sourcePath == null) {
                continue;
            }
            if (modifiedOnly && !p.modified && p.sourceExists) {
                result.skipped.add(p.path);
                continue;
            }
            Node launchPage = session.getNode(p.path);
            Node source;
            if (session.nodeExists(p.sourcePath)) {
                source = session.getNode(p.sourcePath);
                // the source keeps a version from before the promotion
                if (!source.isNodeType("mix:versionable")) {
                    source.addMixin("mix:versionable");
                    session.save();
                }
                if (session.hasPendingChanges()) {
                    session.save();
                }
                vm.checkpoint(source.getPath());
                if (source.hasNode("jcr:content")) {
                    source.getNode("jcr:content").remove();
                }
                result.promoted.add(p.sourcePath);
            } else {
                String parentPath = p.sourcePath.substring(0, p.sourcePath.lastIndexOf('/'));
                if (!session.nodeExists(parentPath)) {
                    result.skipped.add(p.path);
                    continue;
                }
                source = session.getNode(parentPath).addNode(p.sourcePath.substring(p.sourcePath.lastIndexOf('/') + 1), "cq:Page");
                result.created.add(p.sourcePath);
            }
            Node content = copy(launchPage.getNode("jcr:content"), source, "jcr:content", true);
            stripMsm(content);
            content.setProperty("cq:lastModified", Calendar.getInstance());
            content.setProperty("cq:lastModifiedBy", session.getUserID());
            // the order of a new page among its siblings is the launch's
            orderLike(launchPage, source);
        }
        Node jc = session.getNode(launch.path).getNode("jcr:content");
        jc.setProperty(PROP_PROMOTED, Calendar.getInstance());
        jc.setProperty(PROP_PROMOTED_BY, session.getUserID());
        return result;
    }

    private static void orderLike(Node launchPage, Node source) throws RepositoryException {
        Node launchParent = launchPage.getParent();
        Node sourceParent = source.getParent();
        if (!sourceParent.getPrimaryNodeType().hasOrderableChildNodes()) {
            return;
        }
        boolean found = false;
        for (NodeIterator it = launchParent.getNodes(); it.hasNext();) {
            Node sib = it.nextNode();
            if (found && sourceParent.hasNode(sib.getName())) {
                sourceParent.orderBefore(source.getName(), sib.getName());
                return;
            }
            if (sib.getName().equals(launchPage.getName())) {
                found = true;
            }
        }
    }

    static void stripMsm(Node n) throws RepositoryException {
        for (NodeType m : n.getMixinNodeTypes()) {
            if (MSM_MIXINS.contains(m.getName())) {
                n.removeMixin(m.getName());
            }
        }
        for (String p : MSM_PROPS) {
            if (n.hasProperty(p)) {
                n.getProperty(p).remove();
            }
        }
        if (n.hasProperty(PROP_SHELL)) {
            n.getProperty(PROP_SHELL).remove();
        }
        List<Node> gone = new ArrayList<>();
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (MSM_NODES.contains(c.getName())) {
                gone.add(c);
            } else {
                stripMsm(c);
            }
        }
        for (Node g : gone) {
            g.remove();
        }
    }

    // ------------------------------------------------------------------------------------------ copies

    /** Copies a page: its content and, when {@code deep}, its subpages. */
    static Node copyPage(Node src, Node parent, String name, boolean deep) throws RepositoryException {
        Node dst = parent.addNode(name, src.getPrimaryNodeType().getName());
        for (NodeType mixin : src.getMixinNodeTypes()) {
            if (!"mix:versionable".equals(mixin.getName())) {
                dst.addMixin(mixin.getName());
            }
        }
        copyProperties(src, dst);
        for (NodeIterator it = src.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (c.isNodeType("cq:Page")) {
                if (deep) {
                    copyPage(c, dst, c.getName(), true);
                }
            } else {
                copy(c, dst, c.getName(), true);
            }
        }
        return dst;
    }

    /** A transient deep copy; child pages are copied only when {@code withPages}. */
    static Node copy(Node src, Node parent, String name, boolean withPages) throws RepositoryException {
        Node dst = parent.addNode(name, src.getPrimaryNodeType().getName());
        for (NodeType mixin : src.getMixinNodeTypes()) {
            if (!"mix:versionable".equals(mixin.getName())) {
                dst.addMixin(mixin.getName());
            }
        }
        copyProperties(src, dst);
        for (NodeIterator it = src.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (c.isNodeType("cq:Page") && !withPages) {
                continue;
            }
            copy(c, dst, c.getName(), withPages);
        }
        return dst;
    }

    static void copyProperties(Node src, Node dst) throws RepositoryException {
        for (PropertyIterator it = src.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (p.getDefinition().isProtected() || p.getName().startsWith("jcr:") && !p.getName().equals("jcr:title")
                    && !p.getName().equals("jcr:description") && !p.getName().equals("jcr:language")) {
                continue;
            }
            if (p.isMultiple()) {
                dst.setProperty(p.getName(), p.getValues(), p.getType());
            } else {
                dst.setProperty(p.getName(), p.getValue());
            }
        }
    }

    static Node mkdirs(Session session, String path, String type) throws RepositoryException {
        Node n = session.getRootNode();
        for (String seg : path.substring(1).split("/")) {
            n = n.hasNode(seg) ? n.getNode(seg) : n.addNode(seg, type);
        }
        return n;
    }

    static String str(Node n, String name, String dflt) throws RepositoryException {
        return n.hasProperty(name) ? n.getProperty(name).getString() : dflt;
    }

    static Calendar date(Node n, String name) throws RepositoryException {
        return n.hasProperty(name) ? n.getProperty(name).getDate() : null;
    }

    static String iso(Calendar c) {
        if (c == null) {
            return null;
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }
}
