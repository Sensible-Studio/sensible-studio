/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Live Copy of Media folders (AEM's MSM for assets), with the markers the page MSM uses:
 * <pre>
 * &lt;live copy root&gt;/jcr:content            mixin cq:LiveSync
 *   cq:LiveSyncConfig                       cq:master (the source folder), cq:isDeep, cq:rolloutConfigs
 * &lt;folder or asset&gt;/jcr:content           mixin cq:LiveRelationship: follows its source
 *                                         + mixin cq:LiveSyncCancelled: suspended, left alone
 *   cq:lastRolledout, cq:lastRolledoutBy
 * &lt;asset&gt;/jcr:content/metadata             cq:propertyInheritanceCancelled (+ mixin cq:PropertyLiveSyncCancelled):
 *                                         fields changed in the live copy, kept as they are
 * </pre>
 * Files and folders without {@code cq:LiveRelationship} inside a live copy are local: a rollout
 * never touches them. The page MSM leaves {@code /content/dam} alone, so the two do not meet.
 *
 * <p>Everything runs with the asker's resolver: reading the source, writing the live copy.</p>
 */
final class DamLiveCopy {

    static final String CONFIG = "cq:LiveSyncConfig";
    static final String MIX_SYNC = "cq:LiveSync";
    static final String MIX_RELATIONSHIP = "cq:LiveRelationship";
    static final String MIX_CANCELLED = "cq:LiveSyncCancelled";
    static final String MIX_PROPERTY_CANCELLED = "cq:PropertyLiveSyncCancelled";
    static final String PN_MASTER = "cq:master";
    static final String PN_DEEP = "cq:isDeep";
    static final String PN_ROLLOUT_CONFIGS = "cq:rolloutConfigs";
    static final String PN_CANCELLED_PROPERTIES = "cq:propertyInheritanceCancelled";
    static final String PN_LAST_ROLLED_OUT = "cq:lastRolledout";
    static final String PN_LAST_ROLLED_OUT_BY = "cq:lastRolledoutBy";
    /** On a live copy's asset: the version of the original it was given, so processing leaves it alone. */
    static final String PN_ROLLED_ORIGINAL = "studio:rolledOutOriginal";
    static final String DEFAULT_ROLLOUT = "/libs/msm/wcm/rolloutconfigs/default";
    /** In {@code cq:rolloutConfigs}: changes in the source are pushed at once (AEM's "Push on modify"). */
    static final String PUSH_ON_MODIFY = "/libs/msm/wcm/rolloutconfigs/pushonmodify";
    /** On the live copy configuration: the person who turned pushing on; pushes happen with their rights. */
    static final String PN_PUSH_BY = "studio:pushOnModifyBy";
    /** On a folder's jcr:content: the names of the files in it that are live copy roots (found without walking files). */
    static final String PN_FILE_ROOTS = "studio:liveCopyFiles";
    /** Who a rollout is made for, when a service does it for them. */
    static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    private static final Set<String> LIVE_MIXINS = new HashSet<>(Arrays.asList(
            MIX_SYNC, MIX_RELATIONSHIP, MIX_CANCELLED, MIX_PROPERTY_CANCELLED, "rep:AccessControllable"));
    /** Never copied: the repository makes them, or they belong to one node only. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "jcr:primaryType", "jcr:mixinTypes", "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:baseVersion",
            "jcr:predecessors", "jcr:versionHistory", "jcr:isCheckedOut", "jcr:lockOwner", "jcr:lockIsDeep",
            "jcr:mergeFailed", "jcr:activity", "jcr:configuration"));
    /** Children that stay with the live copy: its own review talk, access control and configuration. */
    private static final Set<String> LOCAL_CHILDREN = new HashSet<>(Arrays.asList("cq:annotations", "rep:policy", CONFIG));
    /** Properties of an asset's jcr:content that are the live copy's own. */
    private static final List<String> LOCAL_PREFIXES = Arrays.asList(
            "jcr:", "cq:lastReplicat", "cq:LiveSync", "cq:lastRolledout", "cq:propertyInheritanceCancelled",
            "cq:isCancelledForChildren", PN_ROLLED_ORIGINAL, PN_PUSH_BY);

    /** What a rollout did. */
    static final class Result {
        int added;
        int updated;
        int removed;
        /** local files or folders in the way, or suspended ones: left as they are */
        final List<String> skipped = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("added", added);
            m.put("updated", updated);
            m.put("removed", removed);
            m.put("skipped", skipped);
            return m;
        }

        void add(Result o) {
            added += o.added;
            updated += o.updated;
            removed += o.removed;
            skipped.addAll(o.skipped);
        }
    }

    private DamLiveCopy() {
    }

    // ------------------------------------------------------------------------------------- reading

    static boolean hasMixin(Resource r, String mixin) {
        if (r == null) {
            return false;
        }
        String[] ms = r.getValueMap().get("jcr:mixinTypes", String[].class);
        return ms != null && Arrays.asList(ms).contains(mixin);
    }

    /** The node holding the live copy markers of a folder or asset. */
    static Resource content(Resource item) {
        return item == null ? null : item.getChild("jcr:content");
    }

    static boolean isRoot(Resource item) {
        Resource c = content(item);
        return c != null && c.getChild(CONFIG) != null;
    }

    /** Follows its source (the root counts: it is the copy of the source folder). */
    static boolean isInherited(Resource item) {
        Resource c = content(item);
        return isRoot(item) || hasMixin(c, MIX_RELATIONSHIP);
    }

    static boolean isSuspended(Resource item) {
        return hasMixin(content(item), MIX_CANCELLED);
    }

    /** The live copy root at or above the item, or null. */
    static Resource rootOf(Resource item) {
        for (Resource r = item; r != null && DamItems.inDam(r.getPath()); r = r.getParent()) {
            if (isRoot(r)) {
                return r;
            }
        }
        return null;
    }

    static String masterOf(Resource root) {
        Resource cfg = content(root).getChild(CONFIG);
        return cfg == null ? null : cfg.getValueMap().get(PN_MASTER, String.class);
    }

    /** The source path of an item inside a live copy. */
    static String sourcePathOf(Resource item, Resource root) {
        String master = masterOf(root);
        return master == null ? null : master + item.getPath().substring(root.getPath().length());
    }

    /** "root", "inherited", "suspended" or "local"; null outside a live copy. Cheap: for listings. */
    static String status(Resource item) {
        Resource c = content(item);
        if (c == null) {
            return null;
        }
        if (c.getChild(CONFIG) != null) {
            return "root";
        }
        if (hasMixin(c, MIX_RELATIONSHIP)) {
            return hasMixin(c, MIX_CANCELLED) ? "suspended" : "inherited";
        }
        return null;
    }

    static boolean isPushOnModify(Resource root) {
        Resource cfg = root == null || content(root) == null ? null : content(root).getChild(CONFIG);
        return cfg != null && Arrays.asList(cfg.getValueMap().get(PN_ROLLOUT_CONFIGS, new String[0])).contains(PUSH_ON_MODIFY);
    }

    /**
     * Turns pushing on or off for a live copy. Whoever turns it on must be able to change the live
     * copy and read its source: later pushes are made with their rights.
     */
    static void setPushOnModify(ResourceResolver resolver, Resource item, boolean push) throws PersistenceException {
        Resource root = rootOf(item);
        if (root == null) {
            throw new IllegalArgumentException(item.getPath() + " is not in a live copy");
        }
        String master = masterOf(root);
        if (push && (master == null || resolver.getResource(master) == null)) {
            throw new IllegalArgumentException("The source " + master + " is gone or cannot be read");
        }
        Resource cfg = content(root).getChild(CONFIG);
        ModifiableValueMap mv = cfg.adaptTo(ModifiableValueMap.class);
        if (mv == null) {
            throw new IllegalStateException("This live copy cannot be changed here");
        }
        Set<String> configs = new LinkedHashSet<>(Arrays.asList(mv.get(PN_ROLLOUT_CONFIGS, new String[0])));
        if (configs.isEmpty()) {
            configs.add(DEFAULT_ROLLOUT);
        }
        if (push) {
            configs.add(PUSH_ON_MODIFY);
            mv.put(PN_PUSH_BY, resolver.getUserID());
        } else {
            configs.remove(PUSH_ON_MODIFY);
            mv.remove(PN_PUSH_BY);
        }
        mv.put(PN_ROLLOUT_CONFIGS, configs.toArray(new String[0]));
    }

    /** Every live copy root in Media the user can read: folders are walked, not files. */
    static List<Resource> roots(ResourceResolver resolver) {
        List<Resource> out = new ArrayList<>();
        Resource dam = resolver.getResource(DamItems.ROOT);
        if (dam == null) {
            return out;
        }
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(dam);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 200_000) {
            Resource f = queue.poll();
            if (isRoot(f)) {
                out.add(f);
            }
            Resource fc = content(f);
            for (String n : fc == null ? new String[0] : fc.getValueMap().get(PN_FILE_ROOTS, new String[0])) {
                Resource a = f.getChild(n);
                if (a != null && isRoot(a)) {
                    out.add(a);
                }
            }
            for (Resource c : f.getChildren()) {
                if (DamItems.isFolder(c)) {
                    queue.add(c);
                }
            }
        }
        return out;
    }

    /** The live copies that follow a source path (it, or a folder above it, is their source). */
    static List<Resource> liveCopiesOf(ResourceResolver resolver, String sourcePath) {
        List<Resource> out = new ArrayList<>();
        for (Resource root : roots(resolver)) {
            String master = masterOf(root);
            if (master != null && (sourcePath.equals(master) || sourcePath.startsWith(master + "/"))) {
                out.add(root);
            }
        }
        return out;
    }

    /** What the console shows for one folder or file. */
    static Map<String, Object> describe(ResourceResolver resolver, Resource item) {
        Map<String, Object> m = new LinkedHashMap<>();
        Resource root = rootOf(item);
        if (root != null) {
            m.put("root", root.getPath());
            m.put("source", sourcePathOf(item, root));
            m.put("sourceExists", resolver.getResource(sourcePathOf(item, root)) != null);
            String status = status(item);
            m.put("status", status == null ? "local" : status);
            m.put("pushOnModify", isPushOnModify(root));
            Resource c = content(item);
            if (c != null) {
                m.put("lastRolledOut", c.getValueMap().get(PN_LAST_ROLLED_OUT, Calendar.class));
                m.put("lastRolledOutBy", c.getValueMap().get(PN_LAST_ROLLED_OUT_BY, String.class));
                Resource md = c.getChild("metadata");
                m.put("cancelledProperties", md == null ? Collections.emptyList()
                        : Arrays.asList(md.getValueMap().get(PN_CANCELLED_PROPERTIES, new String[0])));
            }
        }
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Resource lc : liveCopiesOf(resolver, item.getPath())) {
            String master = masterOf(lc);
            String target = lc.getPath() + item.getPath().substring(master.length());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("root", lc.getPath());
            e.put("path", target);
            e.put("title", lc.getChild("jcr:content") == null ? lc.getName()
                    : lc.getChild("jcr:content").getValueMap().get("jcr:title", lc.getName()));
            Resource t = resolver.getResource(target);
            String st = t == null ? "missing" : status(t);
            e.put("status", st == null ? "local" : st);
            copies.add(e);
        }
        m.put("liveCopies", copies);
        return m;
    }

    // ------------------------------------------------------------------------------------- writing

    /**
     * Makes a live copy of a Media folder: the folder {@code parent/name} with everything in it,
     * following {@code source}.
     */
    static Map<String, Object> create(ResourceResolver resolver, Resource source, Resource parent, String name, String title)
            throws PersistenceException {
        return create(resolver, source, parent, name, title, false);
    }

    /** {@link #create(ResourceResolver, Resource, Resource, String, String)}, pushing the source's changes at once when {@code push}. */
    static Map<String, Object> create(ResourceResolver resolver, Resource source, Resource parent, String name, String title, boolean push)
            throws PersistenceException {
        boolean file = DamItems.isAsset(source);
        if (!(file || DamItems.isFolder(source)) || DamItems.ROOT.equals(source.getPath())) {
            throw new IllegalArgumentException("A live copy is made of a folder or a file in Media");
        }
        if (!DamItems.isFolder(parent)) {
            throw new IllegalArgumentException("A live copy goes into a folder in Media");
        }
        if (name == null || !(file ? name.matches("^[^/\\[\\]:*|\"]{1,150}$") && !name.startsWith(".")
                : name.matches("^[a-z0-9][a-z0-9._-]{0,149}$"))) {
            throw new IllegalArgumentException(file ? "Not a file name: " + name : "Names use a-z, 0-9, '.', '_' and '-'");
        }
        String target = parent.getPath() + "/" + name;
        if (target.equals(source.getPath()) || target.startsWith(source.getPath() + "/")) {
            throw new IllegalArgumentException("A live copy cannot be inside its source");
        }
        if (rootOf(source) != null && source.getPath().startsWith(target + "/")) {
            throw new IllegalArgumentException("A live copy cannot contain its source");
        }
        if (parent.getChild(name) != null) {
            throw new IllegalStateException(target + " exists already");
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("jcr:primaryType", "nt:unstructured");
        cfg.put(PN_MASTER, source.getPath());
        cfg.put(PN_DEEP, !file);
        cfg.put(PN_ROLLOUT_CONFIGS, push ? new String[] {DEFAULT_ROLLOUT, PUSH_ON_MODIFY} : new String[] {DEFAULT_ROLLOUT});
        if (push) {
            cfg.put(PN_PUSH_BY, resolver.getUserID());
        }
        if (file) {
            // the file with its renditions and metadata; the live copy markers on its jcr:content
            Resource copy = copyTree(resolver, source, parent, name);
            Resource content = content(copy);
            addMixin(content, MIX_SYNC);
            addMixin(content, MIX_RELATIONSHIP);
            ModifiableValueMap mv = content.adaptTo(ModifiableValueMap.class);
            for (String k : new String[] {"cq:lastReplicated", "cq:lastReplicatedBy", "cq:lastReplicationAction"}) {
                mv.remove(k);
            }
            Resource original = content.getChild("renditions/original/jcr:content");
            Calendar v = original == null ? null : original.getValueMap().get("jcr:lastModified", Calendar.class);
            if (v != null) {
                mv.put(PN_ROLLED_ORIGINAL, v.getTimeInMillis());
            }
            resolver.create(content, CONFIG, cfg);
            listFileRoot(parent, name, true);
            stamp(resolver, copy);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", copy.getPath());
            Result one = new Result();
            one.added = 1;
            out.putAll(one.toMap());
            return out;
        }
        Resource folder = resolver.create(parent, name, Collections.singletonMap("jcr:primaryType", source.getValueMap().get("jcr:primaryType", "sling:Folder")));
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("jcr:primaryType", "nt:unstructured");
        cp.put("jcr:mixinTypes", new String[] {MIX_SYNC});
        cp.put("jcr:title", title == null || title.trim().isEmpty() ? titleOf(source) : title.trim());
        Resource content = resolver.create(folder, "jcr:content", cp);
        resolver.create(content, CONFIG, cfg);
        Result r = syncFolder(resolver, source, folder, true, false);
        stamp(resolver, folder);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", folder.getPath());
        out.putAll(r.toMap());
        return out;
    }

    /**
     * Brings a part of a live copy up to date with its source: a file, a folder (with what is in
     * it) or the whole live copy. {@code reset} also takes back what the live copy changed:
     * suspended files and fields follow the source again.
     */
    static Result synchronize(ResourceResolver resolver, Resource item, boolean reset) throws PersistenceException {
        return synchronize(resolver, item, reset, reset);
    }

    /** {@link #synchronize(ResourceResolver, Resource, boolean)}; {@code force} also copies what did not change since. */
    static Result synchronize(ResourceResolver resolver, Resource item, boolean reset, boolean force) throws PersistenceException {
        Resource root = rootOf(item);
        if (root == null) {
            throw new IllegalArgumentException(item.getPath() + " is not in a live copy");
        }
        String src = sourcePathOf(item, root);
        Resource source = resolver.getResource(src);
        if (source == null) {
            throw new IllegalArgumentException("The source " + src + " is gone or cannot be read");
        }
        if (reset) {
            clearCancellations(resolver, item);
        }
        Result r = new Result();
        if (!isInherited(item)) {
            r.skipped.add(item.getPath());
            return r;
        }
        if (DamItems.isAsset(item)) {
            if (isSuspended(item)) {
                r.skipped.add(item.getPath());
            } else if (updateAsset(resolver, source, item, force)) {
                r.updated++;
            }
        } else {
            r.add(syncFolder(resolver, source, item, false, force));
            stamp(resolver, item);
        }
        return r;
    }

    /** From the source side: every live copy that follows {@code source} gets its changes. */
    static Map<String, Object> rollout(ResourceResolver resolver, Resource source) throws PersistenceException {
        List<Map<String, Object>> each = new ArrayList<>();
        Result total = new Result();
        for (Resource lc : liveCopiesOf(resolver, source.getPath())) {
            String target = lc.getPath() + source.getPath().substring(masterOf(lc).length());
            Resource t = resolver.getResource(target);
            Result r;
            if (t == null) {
                // new in the source: made in the live copy when its parent there follows the source
                r = new Result();
                Resource parentTarget = resolver.getResource(target.substring(0, target.lastIndexOf('/')));
                if (parentTarget != null && isInherited(parentTarget) && !isSuspended(parentTarget)) {
                    copyNew(resolver, source, parentTarget, source.getName());
                    r.added++;
                } else {
                    r.skipped.add(target);
                }
            } else {
                r = synchronize(resolver, t, false);
            }
            total.add(r);
            Map<String, Object> m = new LinkedHashMap<>(r.toMap());
            m.put("liveCopy", lc.getPath());
            each.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>(total.toMap());
        out.put("liveCopies", each);
        return out;
    }

    /** Stops (or starts again) following the source for one file or folder. */
    static void suspend(ResourceResolver resolver, Resource item, boolean suspend) throws PersistenceException {
        if (rootOf(item) == null || isRoot(item)) {
            throw new IllegalArgumentException(isRoot(item) ? "Detach the live copy instead" : item.getPath() + " is not in a live copy");
        }
        Resource c = content(item);
        if (c == null || !hasMixin(c, MIX_RELATIONSHIP)) {
            throw new IllegalArgumentException(item.getPath() + " is local to the live copy");
        }
        if (suspend) {
            addMixin(c, MIX_CANCELLED);
            c.adaptTo(ModifiableValueMap.class).put("cq:isCancelledForChildren", true);
        } else {
            c.adaptTo(ModifiableValueMap.class).remove("cq:isCancelledForChildren");
            removeMixin(c, MIX_CANCELLED);
        }
    }

    /** The live copy becomes ordinary folders and files, kept as they are. */
    static void detach(ResourceResolver resolver, Resource item) throws PersistenceException {
        Resource root = rootOf(item);
        if (root == null) {
            throw new IllegalArgumentException(item.getPath() + " is not in a live copy");
        }
        Resource c = content(root);
        resolver.delete(c.getChild(CONFIG));
        removeMixin(c, MIX_SYNC);
        if (DamItems.isAsset(root) && root.getParent() != null) {
            listFileRoot(root.getParent(), root.getName(), false);
        }
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Resource r = queue.poll();
            Resource rc = content(r);
            if (rc != null) {
                ModifiableValueMap mv = rc.adaptTo(ModifiableValueMap.class);
                for (String k : new String[] {"cq:isCancelledForChildren", PN_LAST_ROLLED_OUT, PN_LAST_ROLLED_OUT_BY, PN_ROLLED_ORIGINAL}) {
                    mv.remove(k);
                }
                removeMixin(rc, MIX_CANCELLED);
                removeMixin(rc, MIX_RELATIONSHIP);
                Resource md = rc.getChild("metadata");
                if (md != null) {
                    md.adaptTo(ModifiableValueMap.class).remove(PN_CANCELLED_PROPERTIES);
                    removeMixin(md, MIX_PROPERTY_CANCELLED);
                }
            }
            if (DamItems.isFolder(r)) {
                for (Resource ch : r.getChildren()) {
                    if (DamItems.isFolder(ch) || DamItems.isAsset(ch)) {
                        queue.add(ch);
                    }
                }
            }
        }
    }

    /**
     * Fields of a live copy's file changed there: they stop following the source (AEM breaks
     * inheritance on a field before it can be changed; here the change itself does it).
     */
    static void keepLocal(Resource asset, List<String> fields) {
        if (fields.isEmpty() || !hasMixin(content(asset), MIX_RELATIONSHIP)) {
            return;
        }
        Resource md = content(asset).getChild("metadata");
        if (md == null) {
            return;
        }
        Set<String> cancelled = new LinkedHashSet<>(Arrays.asList(md.getValueMap().get(PN_CANCELLED_PROPERTIES, new String[0])));
        if (cancelled.addAll(fields)) {
            addMixin(md, MIX_PROPERTY_CANCELLED);
            md.adaptTo(ModifiableValueMap.class).put(PN_CANCELLED_PROPERTIES, cancelled.toArray(new String[0]));
        }
    }

    /** A field follows the source again (its value comes back with the next synchronization, done here). */
    static void inheritAgain(ResourceResolver resolver, Resource asset, String field) throws PersistenceException {
        Resource md = content(asset) == null ? null : content(asset).getChild("metadata");
        if (md == null) {
            return;
        }
        Set<String> cancelled = new LinkedHashSet<>(Arrays.asList(md.getValueMap().get(PN_CANCELLED_PROPERTIES, new String[0])));
        if (cancelled.remove(field)) {
            ModifiableValueMap mv = md.adaptTo(ModifiableValueMap.class);
            if (cancelled.isEmpty()) {
                mv.remove(PN_CANCELLED_PROPERTIES);
                removeMixin(md, MIX_PROPERTY_CANCELLED);
            } else {
                mv.put(PN_CANCELLED_PROPERTIES, cancelled.toArray(new String[0]));
            }
            synchronize(resolver, asset, false, true);
        }
    }

    // ------------------------------------------------------------------------------------- the copy

    private static Result syncFolder(ResourceResolver resolver, Resource source, Resource target, boolean fresh, boolean force)
            throws PersistenceException {
        Result r = new Result();
        if (!fresh && isSuspended(target)) {
            r.skipped.add(target.getPath());
            return r;
        }
        // the folder's own title and description
        Resource sc = content(source);
        Resource tc = content(target);
        if (sc != null && tc != null && !isRoot(target)) {
            ModifiableValueMap mv = tc.adaptTo(ModifiableValueMap.class);
            for (String k : new String[] {"jcr:title", "jcr:description"}) {
                Object v = sc.getValueMap().get(k);
                if (v == null) {
                    mv.remove(k);
                } else {
                    mv.put(k, v);
                }
            }
        }
        Set<String> inSource = new HashSet<>();
        for (Resource s : source.getChildren()) {
            boolean asset = DamItems.isAsset(s);
            if (!asset && !DamItems.isFolder(s)) {
                continue;
            }
            inSource.add(s.getName());
            Resource t = target.getChild(s.getName());
            if (t == null) {
                copyNew(resolver, s, target, s.getName());
                r.added++;
            } else if (!isInherited(t) || isRoot(t)) {
                r.skipped.add(t.getPath()); // local, in the way
            } else if (isSuspended(t)) {
                r.skipped.add(t.getPath());
            } else if (asset && DamItems.isAsset(t)) {
                if (updateAsset(resolver, s, t, force)) {
                    r.updated++;
                }
            } else if (!asset && DamItems.isFolder(t)) {
                r.add(syncFolder(resolver, s, t, false, force));
                stamp(resolver, t);
            } else {
                r.skipped.add(t.getPath()); // a file became a folder or the other way round
            }
        }
        // gone from the source: what followed it goes too; what is local stays
        for (Resource t : copy(target.getChildren())) {
            if (!inSource.contains(t.getName()) && (DamItems.isAsset(t) || DamItems.isFolder(t))
                    && hasMixin(content(t), MIX_RELATIONSHIP) && !isSuspended(t)) {
                resolver.delete(t);
                r.removed++;
            }
        }
        return r;
    }

    /** A file or folder new in the source, copied with its markers. */
    private static void copyNew(ResourceResolver resolver, Resource source, Resource targetParent, String name) throws PersistenceException {
        Resource t = copyTree(resolver, source, targetParent, name);
        markTree(resolver, t);
    }

    private static void markTree(ResourceResolver resolver, Resource item) throws PersistenceException {
        Resource c = content(item);
        if (c == null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("jcr:primaryType", "nt:unstructured");
            c = resolver.create(item, "jcr:content", p);
        }
        addMixin(c, MIX_RELATIONSHIP);
        ModifiableValueMap mv = c.adaptTo(ModifiableValueMap.class);
        for (String k : new String[] {"cq:lastReplicated", "cq:lastReplicatedBy", "cq:lastReplicationAction"}) {
            mv.remove(k);
        }
        stamp(resolver, item);
        if (DamItems.isAsset(item)) {
            Resource original = c.getChild("renditions/original/jcr:content");
            Calendar v = original == null ? null : original.getValueMap().get("jcr:lastModified", Calendar.class);
            if (v != null) {
                mv.put(PN_ROLLED_ORIGINAL, v.getTimeInMillis());
            }
        } else {
            for (Resource ch : item.getChildren()) {
                if (DamItems.isAsset(ch) || DamItems.isFolder(ch)) {
                    markTree(resolver, ch);
                }
            }
        }
    }

    /**
     * The source file's renditions, metadata (but the fields kept local) and properties, when it
     * changed since the last rollout (or always, with {@code force}).
     */
    private static boolean updateAsset(ResourceResolver resolver, Resource source, Resource target, boolean force)
            throws PersistenceException {
        Resource sc = content(source);
        Resource tc = content(target);
        if (sc == null || tc == null) {
            return false;
        }
        Calendar last = tc.getValueMap().get(PN_LAST_ROLLED_OUT, Calendar.class);
        if (!force && last != null && !changedSince(sc, last.getTimeInMillis())) {
            return false;
        }
        // renditions and other children: replaced; metadata: merged; the live copy's own: kept
        for (Resource ch : copy(tc.getChildren())) {
            if (!"metadata".equals(ch.getName()) && !LOCAL_CHILDREN.contains(ch.getName())) {
                resolver.delete(ch);
            }
        }
        for (Resource ch : sc.getChildren()) {
            if (!"metadata".equals(ch.getName()) && !LOCAL_CHILDREN.contains(ch.getName())) {
                copyTree(resolver, ch, tc, ch.getName());
            }
        }
        ModifiableValueMap tv = tc.adaptTo(ModifiableValueMap.class);
        for (String k : new ArrayList<>(tv.keySet())) {
            if (!local(k) && !sc.getValueMap().containsKey(k)) {
                tv.remove(k);
            }
        }
        for (Map.Entry<String, Object> e : sc.getValueMap().entrySet()) {
            if (!local(e.getKey())) {
                tv.put(e.getKey(), e.getValue());
            }
        }
        Resource sm = sc.getChild("metadata");
        Resource tm = tc.getChild("metadata");
        if (sm != null) {
            if (tm == null) {
                copyTree(resolver, sm, tc, "metadata");
            } else {
                Set<String> kept = new HashSet<>(Arrays.asList(tm.getValueMap().get(PN_CANCELLED_PROPERTIES, new String[0])));
                ModifiableValueMap mv = tm.adaptTo(ModifiableValueMap.class);
                for (String k : new ArrayList<>(mv.keySet())) {
                    if (!PROTECTED.contains(k) && !kept.contains(k) && !PN_CANCELLED_PROPERTIES.equals(k) && !sm.getValueMap().containsKey(k)) {
                        mv.remove(k);
                    }
                }
                for (Map.Entry<String, Object> e : sm.getValueMap().entrySet()) {
                    if (!PROTECTED.contains(e.getKey()) && !kept.contains(e.getKey()) && !PN_CANCELLED_PROPERTIES.equals(e.getKey())) {
                        mv.put(e.getKey(), e.getValue());
                    }
                }
                for (Resource ch : copy(tm.getChildren())) {
                    if (!kept.contains(ch.getName())) {
                        resolver.delete(ch);
                    }
                }
                for (Resource ch : sm.getChildren()) {
                    if (!kept.contains(ch.getName()) && tm.getChild(ch.getName()) == null) {
                        copyTree(resolver, ch, tm, ch.getName());
                    }
                }
            }
        }
        Resource original = tc.getChild("renditions/original/jcr:content");
        Calendar v = original == null ? null : original.getValueMap().get("jcr:lastModified", Calendar.class);
        if (v != null) {
            tv.put(PN_ROLLED_ORIGINAL, v.getTimeInMillis());
        }
        stamp(resolver, target);
        return true;
    }

    private static boolean local(String key) {
        for (String p : LOCAL_PREFIXES) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Something under the asset's jcr:content was modified after {@code since}. */
    private static boolean changedSince(Resource content, long since) {
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(content);
        int n = 0;
        while (!queue.isEmpty() && n++ < 500) {
            Resource r = queue.poll();
            Calendar m = r.getValueMap().get("jcr:lastModified", Calendar.class);
            if (m != null && m.getTimeInMillis() > since) {
                return true;
            }
            for (Resource ch : r.getChildren()) {
                if (!LOCAL_CHILDREN.contains(ch.getName())) {
                    queue.add(ch);
                }
            }
        }
        return false;
    }

    /** A plain copy of a subtree (binaries included), without the live copy markers or access control. */
    static Resource copyTree(ResourceResolver resolver, Resource source, Resource targetParent, String name) throws PersistenceException {
        ValueMap v = source.getValueMap();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jcr:primaryType", v.get("jcr:primaryType", "nt:unstructured"));
        String[] mixins = v.get("jcr:mixinTypes", String[].class);
        if (mixins != null) {
            List<String> keep = new ArrayList<>();
            for (String m : mixins) {
                if (!LIVE_MIXINS.contains(m) && !m.startsWith("mix:versionable") && !m.startsWith("rep:")) {
                    keep.add(m);
                }
            }
            if (!keep.isEmpty()) {
                p.put("jcr:mixinTypes", keep.toArray(new String[0]));
            }
        }
        for (String k : v.keySet()) {
            if (PROTECTED.contains(k) || k.startsWith("rep:") || k.startsWith("cq:LiveSync") || PN_CANCELLED_PROPERTIES.equals(k)) {
                continue;
            }
            Object val = "jcr:data".equals(k) ? v.get(k, java.io.InputStream.class) : v.get(k);
            if (val != null) {
                p.put(k, val);
            }
        }
        Resource t = resolver.create(targetParent, name, p);
        for (Resource ch : source.getChildren()) {
            if (!LOCAL_CHILDREN.contains(ch.getName())) {
                copyTree(resolver, ch, t, ch.getName());
            }
        }
        return t;
    }

    private static void clearCancellations(ResourceResolver resolver, Resource item) {
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(item);
        while (!queue.isEmpty()) {
            Resource r = queue.poll();
            Resource c = content(r);
            if (c != null && hasMixin(c, MIX_RELATIONSHIP)) {
                c.adaptTo(ModifiableValueMap.class).remove("cq:isCancelledForChildren");
                removeMixin(c, MIX_CANCELLED);
                Resource md = c.getChild("metadata");
                if (md != null) {
                    md.adaptTo(ModifiableValueMap.class).remove(PN_CANCELLED_PROPERTIES);
                    removeMixin(md, MIX_PROPERTY_CANCELLED);
                }
            }
            if (DamItems.isFolder(r)) {
                for (Resource ch : r.getChildren()) {
                    if (DamItems.isFolder(ch) || DamItems.isAsset(ch)) {
                        queue.add(ch);
                    }
                }
            }
        }
    }

    private static void listFileRoot(Resource folder, String name, boolean add) throws PersistenceException {
        Resource c = content(folder);
        if (c == null) {
            if (!add) {
                return;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("jcr:primaryType", "nt:unstructured");
            c = folder.getResourceResolver().create(folder, "jcr:content", p);
        }
        ModifiableValueMap mv = c.adaptTo(ModifiableValueMap.class);
        Set<String> names = new LinkedHashSet<>(Arrays.asList(mv.get(PN_FILE_ROOTS, new String[0])));
        boolean changed = add ? names.add(name) : names.remove(name);
        if (changed) {
            if (names.isEmpty()) {
                mv.remove(PN_FILE_ROOTS);
            } else {
                mv.put(PN_FILE_ROOTS, names.toArray(new String[0]));
            }
        }
    }

    private static void stamp(ResourceResolver resolver, Resource item) {
        Resource c = content(item);
        ModifiableValueMap mv = c == null ? null : c.adaptTo(ModifiableValueMap.class);
        if (mv != null) {
            mv.put(PN_LAST_ROLLED_OUT, Calendar.getInstance());
            mv.put(PN_LAST_ROLLED_OUT_BY, ACTOR.get() != null ? ACTOR.get() : resolver.getUserID());
        }
    }

    static void addMixin(Resource r, String mixin) {
        if (hasMixin(r, mixin)) {
            return;
        }
        ModifiableValueMap mv = r.adaptTo(ModifiableValueMap.class);
        List<String> ms = new ArrayList<>(Arrays.asList(mv.get("jcr:mixinTypes", new String[0])));
        ms.add(mixin);
        mv.put("jcr:mixinTypes", ms.toArray(new String[0]));
    }

    static void removeMixin(Resource r, String mixin) {
        if (!hasMixin(r, mixin)) {
            return;
        }
        ModifiableValueMap mv = r.adaptTo(ModifiableValueMap.class);
        List<String> ms = new ArrayList<>(Arrays.asList(mv.get("jcr:mixinTypes", new String[0])));
        ms.remove(mixin);
        mv.put("jcr:mixinTypes", ms.toArray(new String[0]));
    }

    private static String titleOf(Resource folder) {
        Resource c = content(folder);
        return c == null ? folder.getName() : c.getValueMap().get("jcr:title", folder.getName());
    }

    private static List<Resource> copy(Iterable<Resource> it) {
        List<Resource> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }
}
