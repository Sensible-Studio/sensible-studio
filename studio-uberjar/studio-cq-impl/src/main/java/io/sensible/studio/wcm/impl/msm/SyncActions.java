/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_RELATIONSHIP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_PROPERTY_INHERITANCE_CANCELLED;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.LiveAction;
import com.day.cq.wcm.msm.api.LiveRelationship;

/**
 * Sensible Studio's synchronisation actions. Each runs on one source / target pair; the rollout walks the page content tree and
 * calls them for every pair, so they never recurse themselves (except {@link ReferencesUpdate}, which runs once per
 * page after the page content is synchronised).
 */
final class SyncActions {

    /** Mixins that stay with the node they were set on and are never copied from the source. */
    static final Set<String> OWN_MIXINS = new HashSet<>(Arrays.asList(
            "cq:LiveSync", NT_LIVE_SYNC_CANCELLED, "cq:PropertyLiveSyncCancelled", "mix:versionable",
            "mix:simpleVersionable", "mix:lockable", "rep:AccessControllable", "rep:RepoAccessControllable"));

    private SyncActions() {
    }

    abstract static class Base implements LiveAction {
        final String name;
        final SyncFilter filter;

        Base(String name, SyncFilter filter) {
            this.name = name;
            this.filter = filter;
        }

        @Override
        public String getName() {
            return name;
        }

        static boolean pageContent(Resource r) {
            return Msm.isPageContentNode(r.getPath());
        }

        boolean skipProperty(String key, boolean pageContent, Set<String> cancelled) {
            return filter.isPropertyExcluded(key, pageContent) || cancelled.contains(key);
        }

        static Set<String> cancelled(Resource target) {
            return Msm.exists(target)
                    ? new HashSet<>(Arrays.asList(Msm.strings(target.getValueMap(), PN_PROPERTY_INHERITANCE_CANCELLED)))
                    : new HashSet<>();
        }

        static void save(ResourceResolver resolver, boolean autoSave) throws WCMException {
            if (autoSave) {
                Msm.commit(resolver);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // ------------------------------------------------------------------ contentCopy

    /** Creates target nodes that exist in the source only. */
    static final class ContentCopy extends Base {
        ContentCopy(SyncFilter filter) {
            super("contentCopy", filter);
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset)
                throws WCMException {
            if (!Msm.exists(source) || Msm.exists(target) || filter.isNodeExcluded(source)) {
                return;
            }
            boolean pageContent = pageContent(source);
            if (!pageContent && filter.isChildExcluded(source.getName())) {
                return;
            }
            ResourceResolver resolver = source.getResourceResolver();
            String targetPath = relation.getTargetPath();
            Resource parent = resolver.getResource(Msm.parentOf(targetPath));
            if (parent == null) {
                return;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            ValueMap vm = source.getValueMap();
            props.put(Msm.PRIMARY, vm.get(Msm.PRIMARY, "nt:unstructured"));
            Set<String> mixins = new LinkedHashSet<>(Msm.mixins(source));
            mixins.removeAll(OWN_MIXINS);
            mixins.add(NT_LIVE_RELATIONSHIP);
            props.put(Msm.MIXINS, mixins.toArray(new String[0]));
            for (Map.Entry<String, Object> e : vm.entrySet()) {
                if (!filter.isPropertyExcluded(e.getKey(), pageContent)) {
                    props.put(e.getKey(), e.getValue());
                }
            }
            props.put("cq:lastRolledout", Calendar.getInstance());
            props.put("cq:lastRolledoutBy", user(resolver));
            Msm.createNode(resolver, parent, Msm.nameOf(targetPath), props);
            save(resolver, autoSave);
        }
    }

    // ------------------------------------------------------------------ contentUpdate

    /** Updates the properties of inherited target nodes from the source. */
    static final class ContentUpdate extends Base {
        ContentUpdate(SyncFilter filter) {
            super("contentUpdate", filter);
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset)
                throws WCMException {
            if (!Msm.exists(source) || !Msm.exists(target) || Msm.hasMixin(target, NT_LIVE_SYNC_CANCELLED)
                    || filter.isNodeExcluded(source)) {
                return;
            }
            boolean pageContent = pageContent(target);
            if (!pageContent && !Msm.hasMixin(target, NT_LIVE_RELATIONSHIP)) {
                return; // added in the live copy: local, never overwritten
            }
            Set<String> cancelled = cancelled(target);
            ValueMap src = source.getValueMap();
            ModifiableValueMap tgt = Msm.writable(target);
            for (Map.Entry<String, Object> e : src.entrySet()) {
                String key = e.getKey();
                if (!skipProperty(key, pageContent, cancelled) && !same(tgt.get(key), e.getValue())) {
                    tgt.put(key, e.getValue());
                }
            }
            for (String key : new ArrayList<>(tgt.keySet())) {
                if (!src.containsKey(key) && !skipProperty(key, pageContent, cancelled)) {
                    tgt.remove(key);
                }
            }
            Set<String> mixins = Msm.mixins(target);
            Set<String> wanted = new LinkedHashSet<>(mixins);
            for (String m : Msm.mixins(source)) {
                if (!OWN_MIXINS.contains(m)) {
                    wanted.add(m);
                }
            }
            wanted.add(NT_LIVE_RELATIONSHIP);
            if (!wanted.equals(mixins)) {
                tgt.put(Msm.MIXINS, wanted.toArray(new String[0]));
            }
            Msm.stamp(target, Calendar.getInstance(), user(target.getResourceResolver()));
            save(target.getResourceResolver(), autoSave);
        }

        static boolean same(Object a, Object b) {
            if (a instanceof InputStream || b instanceof InputStream) {
                return false;
            }
            if (a instanceof Object[] && b instanceof Object[]) {
                return Arrays.deepEquals((Object[]) a, (Object[]) b);
            }
            if (a instanceof Calendar && b instanceof Calendar) {
                return ((Calendar) a).getTimeInMillis() == ((Calendar) b).getTimeInMillis();
            }
            return Objects.equals(a, b);
        }
    }

    // ------------------------------------------------------------------ contentDelete

    /** Removes inherited target nodes whose source was removed. Local nodes stay. */
    static final class ContentDelete extends Base {
        ContentDelete(SyncFilter filter) {
            super("contentDelete", filter);
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset)
                throws WCMException {
            if (Msm.exists(source) || !Msm.exists(target) || pageContent(target)
                    || !Msm.hasMixin(target, NT_LIVE_RELATIONSHIP) || Msm.hasMixin(target, NT_LIVE_SYNC_CANCELLED)
                    || filter.isNodeExcluded(target) || filter.isChildExcluded(target.getName())) {
                return;
            }
            ResourceResolver resolver = target.getResourceResolver();
            try {
                prune(resolver, target);
            } catch (PersistenceException e) {
                throw new WCMException("Could not delete " + target.getPath(), e);
            }
            save(resolver, autoSave);
        }

        /**
         * Deletes an inherited node whose source is gone, but keeps what authors added inside it: local components
         * stay, and so do the inherited nodes above them, turned into local ones (no longer synchronised).
         */
        static void prune(ResourceResolver resolver, Resource node) throws PersistenceException, WCMException {
            if (!RolloutManagerImpl.hasLocalNodes(node)) {
                resolver.delete(node);
                return;
            }
            for (Resource child : RolloutManagerImpl.childrenOf(node)) {
                if (Msm.hasMixin(child, NT_LIVE_RELATIONSHIP)) {
                    prune(resolver, child);
                }
            }
            Msm.removeMixin(node, NT_LIVE_RELATIONSHIP);
        }
    }

    // ------------------------------------------------------------------ orderChildren

    /**
     * Orders the inherited children of the target like their sources. Local children keep their place after the
     * inherited sibling they follow.
     */
    static final class OrderChildren extends Base {
        OrderChildren(SyncFilter filter) {
            super("orderChildren", filter);
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset)
                throws WCMException {
            if (!Msm.exists(source) || !Msm.exists(target) || Msm.hasMixin(target, NT_LIVE_SYNC_CANCELLED)) {
                return;
            }
            List<String> current = Msm.childNames(target);
            List<String> wanted = wantedOrder(current, Msm.childNames(source));
            if (wanted.equals(current)) {
                return;
            }
            for (String n : wanted) {
                orderBefore(target, n, null);
            }
            save(target.getResourceResolver(), autoSave);
        }
    }

    /**
     * @return the target children in blueprint order: inherited children (names present in the source) take the
     *         source order, local children keep their slots
     */
    static List<String> wantedOrder(List<String> current, List<String> source) {
        Set<String> inSource = new LinkedHashSet<>(source);
        List<String> sourceOrder = new ArrayList<>();
        for (String n : inSource) {
            if (current.contains(n)) {
                sourceOrder.add(n);
            }
        }
        List<String> wanted = new ArrayList<>();
        int next = 0;
        for (String n : current) {
            wanted.add(inSource.contains(n) ? sourceOrder.get(next++) : n);
        }
        return wanted;
    }

    /** Moves child {@code name} before {@code before} ({@code null}: to the end). */
    static void orderBefore(Resource parent, String name, String before) throws WCMException {
        Node node = parent.adaptTo(Node.class);
        try {
            if (node != null) {
                node.orderBefore(name, before);
                return;
            }
            parent.getResourceResolver().orderBefore(parent, name, before);
        } catch (RepositoryException | PersistenceException e) {
            throw new WCMException("Could not order " + parent.getPath() + "/" + name, e);
        } catch (UnsupportedOperationException | AbstractMethodError | NoSuchMethodError e) {
            // a resource provider without ordering: leave the order as it is
        }
    }

    // ------------------------------------------------------------------ referencesUpdate

    /**
     * Once per page: rewrites links in the target page content that point into the blueprint so they point to the
     * same page in the live copy, if that page exists there.
     */
    static class ReferencesUpdate extends Base {
        ReferencesUpdate(SyncFilter filter) {
            this("referencesUpdate", filter);
        }

        ReferencesUpdate(String name, SyncFilter filter) {
            super(name, filter);
        }

        /** @return the path prefix links are rewritten from and the one they are rewritten to, or {@code null} */
        String[] mapping(LiveRelationship relation) {
            return new String[] {relation.getLiveCopy().getBlueprintPath(), relation.getLiveCopy().getPath()};
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset)
                throws WCMException {
            if (!Msm.exists(target) || !pageContent(target)) {
                return;
            }
            String[] m = mapping(relation);
            if (m == null) {
                return;
            }
            if (rewriteTree(target, m[0], m[1], true) > 0) {
                save(target.getResourceResolver(), autoSave);
            }
        }

        private int rewriteTree(Resource node, String from, String to, boolean pageContent) throws WCMException {
            if (Msm.hasMixin(node, NT_LIVE_SYNC_CANCELLED) || filter.isNodeExcluded(node)) {
                return 0;
            }
            if (!pageContent && Msm.isComponent(node) && !Msm.hasMixin(node, NT_LIVE_RELATIONSHIP)) {
                return 0; // a component added in the live copy: its links are the author's own
            }
            int changed = 0;
            Set<String> cancelled = cancelled(node);
            ModifiableValueMap mvm = null;
            for (Map.Entry<String, Object> e : node.getValueMap().entrySet()) {
                String key = e.getKey();
                if ((key.startsWith("jcr:") && !"jcr:description".equals(key)) || skipProperty(key, pageContent, cancelled)) {
                    continue;
                }
                Object v = e.getValue();
                Object nv = rewriteValue(node.getResourceResolver(), v, from, to);
                if (nv != v) {
                    if (mvm == null) {
                        mvm = Msm.writable(node);
                    }
                    mvm.put(key, nv);
                    changed++;
                }
            }
            for (Resource child : node.getChildren()) {
                changed += rewriteTree(child, from, to, false);
            }
            return changed;
        }

        private static Object rewriteValue(ResourceResolver resolver, Object v, String from, String to) {
            if (v instanceof String) {
                String s = Links.rewrite(resolver, (String) v, from, to);
                return s.equals(v) ? v : s;
            }
            if (v instanceof String[]) {
                String[] in = (String[]) v;
                String[] out = new String[in.length];
                boolean any = false;
                for (int i = 0; i < in.length; i++) {
                    out[i] = Links.rewrite(resolver, in[i], from, to);
                    any |= !out[i].equals(in[i]);
                }
                return any ? out : v;
            }
            return v;
        }
    }

    /**
     * {@code referencesUpdateXF} / {@code referencesUpdateCF}: links to experience fragments (or content fragments)
     * of the blueprint's site and language point to the live copy's own ones, when those exist. The site / language
     * part is found by dropping the trailing path segments blueprint and live copy have in common:
     * {@code /content/wknd/language-masters/en} → {@code /content/wknd/us/en} maps
     * {@code /content/experience-fragments/wknd/language-masters/…} to {@code /content/experience-fragments/wknd/us/…}.
     */
    static final class LocalizedReferencesUpdate extends ReferencesUpdate {
        private final String base;

        LocalizedReferencesUpdate(String name, String base, SyncFilter filter) {
            super(name, filter);
            this.base = base;
        }

        @Override
        String[] mapping(LiveRelationship relation) {
            String[] roots = siteRoots(relation.getLiveCopy().getBlueprintPath(), relation.getLiveCopy().getPath());
            return roots == null ? null : new String[] {base + roots[0], base + roots[1]};
        }

        /** @return the blueprint and live copy paths without their common trailing segments, relative to /content */
        static String[] siteRoots(String blueprint, String liveCopy) {
            if (!blueprint.startsWith("/content/") || !liveCopy.startsWith("/content/")) {
                return null;
            }
            String b = blueprint.substring("/content/".length());
            String l = liveCopy.substring("/content/".length());
            while (b.contains("/") && l.contains("/") && Msm.nameOf("/" + b).equals(Msm.nameOf("/" + l))) {
                b = b.substring(0, b.lastIndexOf('/'));
                l = l.substring(0, l.lastIndexOf('/'));
            }
            return b.equals(l) ? null : new String[] {b, l};
        }
    }

    // ------------------------------------------------------------------ targetVersion

    static final String TARGET_VERSION = "targetVersion";

    /**
     * Saves the live copy pages as versions before the rollout changes them, so a rollout can be undone (Restore in
     * the page's versions). The rollout engine takes those versions itself, for every page of the rollout before
     * anything changes (a version is taken from saved content); in the action list it is only a marker.
     */
    static final class TargetVersion extends Base {
        TargetVersion() {
            super(TARGET_VERSION, SyncFilter.NONE);
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset) {
            // see RolloutManagerImpl#rollout: versions are taken before the rollout starts
        }
    }

    // ------------------------------------------------------------------ no-ops

    /**
     * Actions Sensible Studio accepts in rollout configurations but has nothing to do for: {@code targetActivate} and
     * {@code targetDeactivate} need replication; {@code personalizationContentRollout} (targeting) and
     * {@code updateField} belong to AEM features Sensible Studio does not have. Accepting them lets AEM's own rollout
     * configurations run unchanged.
     */
    static final class Noop extends Base {
        Noop(String name) {
            super(name, SyncFilter.NONE);
        }

        @Override
        public void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean reset) {
            // nothing to do
        }
    }

    static String user(ResourceResolver resolver) {
        String u = resolver.getUserID();
        return u == null ? "anonymous" : u;
    }
}
