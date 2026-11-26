/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_RELATIONSHIP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CONFIG;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_PROPERTY_LIVE_SYNC_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_EXCLUDED_PATHS;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_IS_CANCELLED_FOR_CHILDREN;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_LAST_ROLLEDOUT;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_LAST_ROLLEDOUT_BY;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_PROPERTY_INHERITANCE_CANCELLED;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.jcr.RangeIterator;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.LiveCopy;
import com.day.cq.wcm.msm.api.LiveRelationship;
import com.day.cq.wcm.msm.api.LiveRelationshipManager;
import com.day.cq.wcm.msm.api.RolloutConfigManager;
import com.day.cq.wcm.msm.api.RolloutManager;

/**
 * Live relationships read from content ({@link Relationships}) plus the operations that change inheritance: cancel and
 * re-enable a target or single properties, and detach. Re-enabling only removes the markers; the caller synchronises
 * the target afterwards ({@link RolloutManagerImpl#synchronize}).
 *
 * <p>Also listens to changes below {@code /content} to drop the blueprint index when a live copy configuration or a
 * page appears, moves or disappears.
 */
@Component(
        service = {LiveRelationshipManager.class, LiveRelationshipManagerImpl.class, ResourceChangeListener.class},
        property = {
                ResourceChangeListener.PATHS + "=/content",
                ResourceChangeListener.CHANGES + "=ADDED",
                ResourceChangeListener.CHANGES + "=REMOVED",
                ResourceChangeListener.CHANGES + "=CHANGED"
        })
public class LiveRelationshipManagerImpl implements LiveRelationshipManager, ResourceChangeListener {

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    volatile RolloutConfigManager configManager;

    final LiveCopyIndex index = new LiveCopyIndex();

    Relationships relationships(ResourceResolver resolver) {
        return new Relationships(resolver, configManager);
    }

    // ------------------------------------------------------------------ queries

    @Override
    public boolean hasLiveRelationship(Resource resource) {
        return resource != null && relationships(resource.getResourceResolver()).isInherited(resource);
    }

    @Override
    public boolean isSource(Resource resource) {
        if (resource == null) {
            return false;
        }
        ResourceResolver resolver = resource.getResourceResolver();
        return !relationships(resolver).relationshipsOf(resource.getPath(), index.entries(resolver)).isEmpty();
    }

    @Override
    public boolean isInLiveCopy(Resource resource) {
        return resource != null && relationships(resource.getResourceResolver()).liveCopyOf(resource.getPath()) != null;
    }

    @Override
    public LiveRelationship getLiveRelationship(Resource resource, boolean advancedStatus) {
        if (resource == null) {
            return null;
        }
        Relationships rel = relationships(resource.getResourceResolver());
        if (!rel.isInherited(resource)) {
            return null;
        }
        LiveRelationshipImpl r = rel.relationshipOf(resource.getPath());
        if (r != null && advancedStatus) {
            r.getStatus();
        }
        return r;
    }

    /** @return the relationship of any path in a live copy, inherited or local, existing or not (or {@code null}) */
    public LiveRelationship getRelationship(ResourceResolver resolver, String targetPath) {
        return relationships(resolver).relationshipOf(targetPath);
    }

    @Override
    public RangeIterator getLiveRelationships(Resource source, String targetPathFilter, RolloutManager.Trigger trigger) {
        if (source == null) {
            return new Range(java.util.Collections.emptyList());
        }
        ResourceResolver resolver = source.getResourceResolver();
        List<LiveRelationshipImpl> all = relationships(resolver).relationshipsOf(source.getPath(), index.entries(resolver));
        all.removeIf(r -> (targetPathFilter != null && !Msm.isSameOrDescendant(r.getTargetPath(), targetPathFilter))
                || (trigger != null && !r.getLiveCopy().isTrigger(trigger)));
        return new Range(all);
    }

    @Override
    public LiveCopy getLiveCopy(Resource resource) {
        return resource == null ? null : relationships(resource.getResourceResolver()).liveCopyOf(resource.getPath());
    }

    /** Relationships of a source to all its live copies (no trigger filter). */
    List<LiveRelationshipImpl> relationshipsOf(ResourceResolver resolver, String sourcePath) {
        return relationships(resolver).relationshipsOf(sourcePath, index.entries(resolver));
    }

    /** @return live copy roots and their blueprints readable by the resolver's user */
    List<LiveCopyIndex.Entry> liveCopies(ResourceResolver resolver) {
        return index.entries(resolver);
    }

    // ------------------------------------------------------------------ changes

    @Override
    public void cancelRelationship(ResourceResolver resolver, LiveRelationship relation, boolean deep, boolean autoSave)
            throws WCMException {
        Resource marker = marker(resolver, relation);
        if (!Msm.hasMixin(marker, NT_LIVE_RELATIONSHIP)) {
            throw new WCMException(relation.getTargetPath() + " was not rolled out from the blueprint");
        }
        Msm.addMixin(marker, NT_LIVE_SYNC_CANCELLED);
        Msm.writable(marker).put(PN_IS_CANCELLED_FOR_CHILDREN, deep);
        done(resolver, relation, autoSave);
    }

    @Override
    public void reenableRelationship(ResourceResolver resolver, LiveRelationship relation, boolean autoSave)
            throws WCMException {
        Resource marker = marker(resolver, relation);
        if (!Msm.hasMixin(marker, NT_LIVE_RELATIONSHIP)) {
            // local content was never rolled out: re-enabling would let the next rollout replace or delete it
            throw new WCMException(relation.getTargetPath() + " was not rolled out from the blueprint");
        }
        Msm.removeMixin(marker, NT_LIVE_SYNC_CANCELLED);
        Msm.writable(marker).remove(PN_IS_CANCELLED_FOR_CHILDREN);
        done(resolver, relation, autoSave);
    }

    @Override
    public void cancelPropertyRelationship(ResourceResolver resolver, LiveRelationship relation, String[] names,
            boolean autoSave) throws WCMException {
        Resource marker = marker(resolver, relation);
        Set<String> cancelled = new LinkedHashSet<>(Arrays.asList(Msm.strings(marker.getValueMap(), PN_PROPERTY_INHERITANCE_CANCELLED)));
        for (String n : names) {
            String name = propertyName(n);
            if (!name.isEmpty()) {
                cancelled.add(name);
            }
        }
        Msm.addMixin(marker, NT_PROPERTY_LIVE_SYNC_CANCELLED);
        Msm.writable(marker).put(PN_PROPERTY_INHERITANCE_CANCELLED, cancelled.toArray(new String[0]));
        done(resolver, relation, autoSave);
    }

    @Override
    public void reenablePropertyRelationship(ResourceResolver resolver, LiveRelationship relation, String[] names,
            boolean autoSave) throws WCMException {
        Resource marker = marker(resolver, relation);
        Set<String> cancelled = new LinkedHashSet<>(Arrays.asList(Msm.strings(marker.getValueMap(), PN_PROPERTY_INHERITANCE_CANCELLED)));
        for (String n : names) {
            cancelled.remove(propertyName(n));
        }
        ModifiableValueMap mvm = Msm.writable(marker);
        if (cancelled.isEmpty()) {
            mvm.remove(PN_PROPERTY_INHERITANCE_CANCELLED);
            Msm.removeMixin(marker, NT_PROPERTY_LIVE_SYNC_CANCELLED);
        } else {
            mvm.put(PN_PROPERTY_INHERITANCE_CANCELLED, cancelled.toArray(new String[0]));
        }
        done(resolver, relation, autoSave);
    }

    /** {@code ./jcr:title} and {@code /image} (a lockable child node) become {@code jcr:title} and {@code image}. */
    static String propertyName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n;
    }

    @Override
    public void endRelationship(ResourceResolver resolver, LiveRelationship relation, boolean autoSave)
            throws WCMException {
        LiveCopy lc = relation.getLiveCopy();
        String target = relation.getTargetPath();
        Resource node = resolver.getResource(target);
        if (node == null) {
            throw new WCMException("No live copy target at " + target);
        }
        boolean page = !Msm.isInPageContent(target);
        if (page && target.equals(lc.getPath())) {
            Resource cfg = resolver.getResource(Msm.configPath(target));
            if (cfg != null) {
                delete(resolver, cfg);
            }
            Msm.removeMixin(node.getChild(Msm.JCR_CONTENT), NT_LIVE_SYNC);
        } else if (page) {
            Resource cfg = resolver.getResource(Msm.configPath(lc.getPath()));
            if (cfg == null) {
                throw new WCMException("Cannot read the live copy configuration of " + lc.getPath());
            }
            Set<String> excluded = new LinkedHashSet<>(Arrays.asList(Msm.strings(cfg.getValueMap(), PN_EXCLUDED_PATHS)));
            excluded.add(LiveCopyImpl.normalize(target.substring(lc.getPath().length())));
            Msm.writable(cfg).put(PN_EXCLUDED_PATHS, excluded.toArray(new String[0]));
        }
        strip(node, true);
        index.invalidate();
        done(resolver, relation, autoSave);
    }

    /** Removes the MSM markers from a node and its descendants, stopping at nested live copy roots. */
    static void strip(Resource node, boolean first) throws WCMException {
        if (!first && Msm.isPage(node)) {
            Resource cfg = node.getChild(Msm.JCR_CONTENT + "/" + NT_LIVE_SYNC_CONFIG);
            if (cfg != null && cfg.getValueMap().get(PN_MASTER, String.class) != null) {
                return;
            }
        }
        if (!Msm.isPage(node) && !NT_LIVE_SYNC_CONFIG.equals(node.getName())) {
            boolean marked = false;
            for (String m : new String[] {NT_LIVE_RELATIONSHIP, NT_LIVE_SYNC_CANCELLED, NT_PROPERTY_LIVE_SYNC_CANCELLED}) {
                marked |= Msm.hasMixin(node, m);
                Msm.removeMixin(node, m);
            }
            if (marked || node.getValueMap().containsKey(PN_LAST_ROLLEDOUT)) {
                ModifiableValueMap mvm = Msm.writable(node);
                for (String p : new String[] {PN_LAST_ROLLEDOUT, PN_LAST_ROLLEDOUT_BY, PN_IS_CANCELLED_FOR_CHILDREN,
                        PN_PROPERTY_INHERITANCE_CANCELLED}) {
                    mvm.remove(p);
                }
            }
        }
        for (Resource child : node.getChildren()) {
            strip(child, false);
        }
    }

    private static Resource marker(ResourceResolver resolver, LiveRelationship relation) throws WCMException {
        Resource target = resolver.getResource(relation.getTargetPath());
        Resource marker = Msm.markerNode(target);
        if (marker == null) {
            throw new WCMException("No live copy target at " + relation.getTargetPath());
        }
        return marker;
    }

    private static void delete(ResourceResolver resolver, Resource r) throws WCMException {
        try {
            resolver.delete(r);
        } catch (PersistenceException e) {
            throw new WCMException("Could not delete " + r.getPath(), e);
        }
    }

    private static void done(ResourceResolver resolver, LiveRelationship relation, boolean autoSave) throws WCMException {
        if (relation instanceof LiveRelationshipImpl) {
            ((LiveRelationshipImpl) relation).refresh();
        }
        if (autoSave) {
            Msm.commit(resolver);
        }
    }

    // ------------------------------------------------------------------ index invalidation

    @Override
    public void onChange(List<ResourceChange> changes) {
        for (ResourceChange c : changes) {
            String p = c.getPath();
            if (p.contains(NT_LIVE_SYNC_CONFIG)
                    || (c.getType() != ResourceChange.ChangeType.CHANGED && !Msm.isInPageContent(p))) {
                index.invalidate();
                return;
            }
        }
    }

    /** {@link RangeIterator} over a list. */
    static final class Range implements RangeIterator {
        private final List<?> items;
        private final Iterator<?> it;
        private long position;

        Range(List<?> items) {
            this.items = items;
            this.it = items.iterator();
        }

        @Override
        public void skip(long skipNum) {
            for (long i = 0; i < skipNum; i++) {
                next();
            }
        }

        @Override
        public long getSize() {
            return items.size();
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public Object next() {
            if (!it.hasNext()) {
                throw new NoSuchElementException();
            }
            position++;
            return it.next();
        }
    }
}
