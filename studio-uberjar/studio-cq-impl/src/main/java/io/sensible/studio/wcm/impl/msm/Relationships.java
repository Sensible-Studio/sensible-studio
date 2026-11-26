/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_RELATIONSHIP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_EXCLUDED_PATHS;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_IS_CANCELLED_FOR_CHILDREN;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_IS_DEEP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_LAST_ROLLEDOUT;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_LAST_ROLLEDOUT_BY;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_PROPERTY_INHERITANCE_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_ROLLOUT_CONFIGS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutConfigManager;

/**
 * Resolves live relationships from content alone, with the permissions of one resolver.
 *
 * <p>A live copy is defined by the {@code jcr:content/cq:LiveSyncConfig} node (with {@code cq:master}) of its root
 * page. A path belongs to the live copy of the nearest such root above it, if that live copy covers it (shallow live
 * copies cover only the root page, excluded child pages are left out). Its source is the same relative path below
 * {@code cq:master}. The {@code cq:LiveRelationship} mixin tells whether a target node was rolled out (it is
 * inherited) or added in the live copy (it is local); the mixin alone does not make a live copy, content often keeps
 * it after it was copied around.
 */
final class Relationships {

    private final ResourceResolver resolver;
    private final RolloutConfigManager configManager;

    Relationships(ResourceResolver resolver, RolloutConfigManager configManager) {
        this.resolver = resolver;
        this.configManager = configManager;
    }

    ResourceResolver resolver() {
        return resolver;
    }

    // ------------------------------------------------------------------ live copies

    /** @return the live copy that {@code path} belongs to, or {@code null} */
    LiveCopyImpl liveCopyOf(String path) {
        if (path == null || !path.startsWith("/content/") || Msm.contentPath(path) == null) {
            return null;
        }
        String page = Msm.pagePathOf(path);
        for (String p = page; p.lastIndexOf('/') > 0; p = Msm.parentOf(p)) {
            Resource cfg = resolver.getResource(Msm.configPath(p));
            if (cfg == null) {
                continue;
            }
            ValueMap vm = cfg.getValueMap();
            String master = vm.get(PN_MASTER, String.class);
            if (master == null || master.isEmpty()) {
                continue; // a page that only overrides the rollout configurations
            }
            LiveCopyImpl lc = new LiveCopyImpl(p, master, vm.get(PN_IS_DEEP, Boolean.TRUE), p.equals(page),
                    Msm.strings(vm, PN_EXCLUDED_PATHS), configsFor(page));
            return lc.covers(path) ? lc : null;
        }
        return null;
    }

    /**
     * Rollout configurations of a page: the nearest {@code cq:rolloutConfigs} on a live copy configuration at or above
     * the page, else the ones the blueprint gives its live copies ({@code cq:BlueprintSyncConfig} on the blueprint page
     * or above, the Blueprint tab of its page properties), else the default configuration.
     */
    Supplier<List<RolloutConfig>> configsFor(String page) {
        return () -> {
            String[] paths = null;
            String blueprintPage = null;
            for (String p = page; p.lastIndexOf('/') > 0 && paths == null; p = Msm.parentOf(p)) {
                Resource cfg = resolver.getResource(Msm.configPath(p));
                if (cfg != null) {
                    String[] v = cfg.getValueMap().get(PN_ROLLOUT_CONFIGS, String[].class);
                    if (v != null && v.length > 0) {
                        paths = v;
                    }
                    String master = cfg.getValueMap().get(PN_MASTER, String.class);
                    if (blueprintPage == null && master != null && !master.isEmpty()) {
                        blueprintPage = master + page.substring(p.length());
                    }
                }
            }
            if (paths == null && blueprintPage != null) {
                paths = PageMsmConfig.blueprintConfigs(resolver, blueprintPage);
            }
            if (paths == null) {
                paths = new String[] {RolloutConfigManager.DEFAULT_CONFIG};
            }
            List<RolloutConfig> out = new ArrayList<>();
            if (configManager != null) {
                for (String cp : paths) {
                    RolloutConfig c = configManager.getRolloutConfig(resolver, cp);
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
            return out;
        };
    }

    // ------------------------------------------------------------------ relationships

    /** @return the relationship of a target path (existing or not), or {@code null} if it is in no live copy */
    LiveRelationshipImpl relationshipOf(String targetPath) {
        LiveCopyImpl lc = liveCopyOf(targetPath);
        return lc == null ? null : new LiveRelationshipImpl(lc, lc.sourceOf(targetPath), targetPath, this);
    }

    /** @return {@code true} if the target exists in a live copy and was rolled out (not added locally) */
    boolean isInherited(Resource target) {
        return Msm.exists(target) && liveCopyOf(target.getPath()) != null
                && Msm.hasMixin(Msm.markerNode(target), NT_LIVE_RELATIONSHIP);
    }

    /** @return the relationships of {@code sourcePath} to the live copies in {@code index} */
    List<LiveRelationshipImpl> relationshipsOf(String sourcePath, List<LiveCopyIndex.Entry> index) {
        List<LiveRelationshipImpl> out = new ArrayList<>();
        for (LiveCopyIndex.Entry e : index) {
            if (!Msm.isSameOrDescendant(sourcePath, e.master)) {
                continue;
            }
            String target = e.root + sourcePath.substring(e.master.length());
            LiveCopyImpl lc = liveCopyOf(target);
            if (lc != null && lc.getPath().equals(e.root) && lc.getBlueprintPath().equals(e.master)) {
                out.add(new LiveRelationshipImpl(lc, sourcePath, target, this));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ status

    LiveStatusImpl status(LiveCopyImpl lc, String sourcePath, String targetPath) {
        Resource target = resolver.getResource(targetPath);
        boolean targetExists = target != null;
        boolean page = !Msm.isInPageContent(targetPath);
        Resource marker = page ? (target == null ? null : target.getChild(Msm.JCR_CONTENT)) : target;
        ValueMap mv = marker == null ? ValueMap.EMPTY : marker.getValueMap();

        boolean cancelled = false;
        boolean forChildren = false;
        boolean byAncestor = false;
        if (Msm.hasMixin(marker, NT_LIVE_SYNC_CANCELLED)) {
            cancelled = true;
            forChildren = mv.get(PN_IS_CANCELLED_FOR_CHILDREN, Boolean.FALSE);
        }
        for (String a = Msm.parentOf(targetPath); !cancelled && Msm.isSameOrDescendant(a, lc.getPath()); a = Msm.parentOf(a)) {
            boolean ancestorPage = !Msm.isInPageContent(a);
            Resource node = resolver.getResource(ancestorPage ? a + Msm.CONTENT_SEGMENT : a);
            if (node != null && Msm.hasMixin(node, NT_LIVE_SYNC_CANCELLED)) {
                boolean children = node.getValueMap().get(PN_IS_CANCELLED_FOR_CHILDREN, Boolean.FALSE);
                // a suspended page suspends its own content; other ancestors only when cancelled for their children
                if (children || (!ancestorPage && Msm.isPageContentNode(a))) {
                    cancelled = true;
                    byAncestor = true;
                    forChildren = children;
                }
            }
        }
        Calendar last = mv.get(PN_LAST_ROLLEDOUT, Calendar.class);
        String lastBy = mv.get(PN_LAST_ROLLEDOUT_BY, String.class);
        return new LiveStatusImpl(cancelled, forChildren, byAncestor, resolver.getResource(sourcePath) != null,
                targetExists, page,
                new LinkedHashSet<>(Arrays.asList(Msm.strings(mv, PN_PROPERTY_INHERITANCE_CANCELLED))), last, lastBy,
                Msm.hasMixin(marker, NT_LIVE_RELATIONSHIP));
    }
}
