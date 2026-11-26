/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_COPY;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CONFIG;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_EXCLUDED_PATHS;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_IS_DEEP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_ROLLOUT_CONFIGS;

import java.util.ArrayList;
import java.util.Arrays;
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

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutConfigManager;

/**
 * The MSM settings of the page properties, stored as in AEM.
 * <ul>
 *   <li>Live Copy tab: {@code jcr:content/cq:LiveSyncConfig} of the page. On the live copy root it holds
 *       {@code cq:isDeep} ("Live Copy Inheritance") and {@code cq:excludedPaths}; on any page of a live copy it may
 *       hold {@code cq:rolloutConfigs}, which then apply to the page and below instead of the ones inherited from the
 *       parent.</li>
 *   <li>Blueprint tab: {@code jcr:content/cq:BlueprintSyncConfig/cq:rolloutConfigs}, the rollout configurations its
 *       live copies use when none of their pages names its own.</li>
 * </ul>
 */
public final class PageMsmConfig {

    public static final String BLUEPRINT_SYNC_CONFIG = "cq:BlueprintSyncConfig";

    private PageMsmConfig() {
    }

    /** @return the rollout configurations a blueprint page gives its live copies, or {@code null} */
    static String[] blueprintConfigs(ResourceResolver resolver, String blueprintPage) {
        for (String p = blueprintPage; p != null && p.lastIndexOf('/') > 0; p = Msm.parentOf(p)) {
            Resource cfg = resolver.getResource(p + Msm.CONTENT_SEGMENT + "/" + BLUEPRINT_SYNC_CONFIG);
            String[] v = cfg == null ? null : Msm.strings(cfg.getValueMap(), PN_ROLLOUT_CONFIGS);
            if (v != null && v.length > 0) {
                return v;
            }
        }
        return null;
    }

    /** The Live Copy tab: what the page's own configuration says, and what applies. */
    static Map<String, Object> liveCopy(ResourceResolver resolver, String page, LiveCopyImpl lc) {
        Map<String, Object> m = new LinkedHashMap<>();
        Resource own = resolver.getResource(Msm.configPath(page));
        ValueMap vm = own == null ? ValueMap.EMPTY : own.getValueMap();
        String[] ownConfigs = Msm.strings(vm, PN_ROLLOUT_CONFIGS);
        boolean root = lc != null && page.equals(lc.getPath());
        m.put("root", root);
        m.put("deep", lc == null || lc.isDeep());
        m.put("inheritRolloutConfigs", ownConfigs == null || ownConfigs.length == 0);
        m.put("rolloutConfigs", ownConfigs == null ? new ArrayList<>() : Arrays.asList(ownConfigs));
        List<String> effective = new ArrayList<>();
        if (lc != null) {
            for (RolloutConfig c : lc.getRolloutConfigs()) {
                effective.add(c.getPath());
            }
        }
        m.put("effectiveRolloutConfigs", effective);
        m.put("excludedPaths", lc == null ? new ArrayList<>() : new ArrayList<>(lc.getExclusions()));
        return m;
    }

    /** The Blueprint tab: the rollout configurations stored on the page for its live copies. */
    static Map<String, Object> blueprint(ResourceResolver resolver, String page) {
        Map<String, Object> m = new LinkedHashMap<>();
        Resource cfg = resolver.getResource(page + Msm.CONTENT_SEGMENT + "/" + BLUEPRINT_SYNC_CONFIG);
        String[] v = cfg == null ? null : Msm.strings(cfg.getValueMap(), PN_ROLLOUT_CONFIGS);
        m.put("rolloutConfigs", v == null ? new ArrayList<>() : Arrays.asList(v));
        return m;
    }

    /**
     * Saves the Live Copy tab.
     *
     * @param deep {@code null} to leave it (only the live copy root has it)
     * @param configs {@code null} to inherit the rollout configurations from the parent (or the blueprint)
     * @param excluded {@code null} to leave them (only the live copy root has them)
     */
    static void writeLiveCopy(ResourceResolver resolver, RolloutConfigManager manager, String page, Boolean deep,
            String[] configs, String[] excluded) throws WCMException {
        Relationships rels = new Relationships(resolver, manager);
        LiveCopyImpl lc = rels.liveCopyOf(page);
        if (lc == null || !Msm.isPage(resolver.getResource(page))) {
            throw new WCMException(page + " is not in a linked copy");
        }
        boolean root = page.equals(lc.getPath());
        if (!root && (deep != null || excluded != null)) {
            throw new WCMException("Only the root of the linked copy (" + lc.getPath() + ") has these settings");
        }
        String[] valid = validConfigs(resolver, manager, configs);
        Resource content = resolver.getResource(page + Msm.CONTENT_SEGMENT);
        if (content == null) {
            throw new WCMException(page + " has no content");
        }
        Resource cfg = content.getChild(NT_LIVE_SYNC_CONFIG);
        if (cfg == null) {
            if (valid == null) {
                return; // inherits, and has nothing else to say
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(Msm.PRIMARY, NT_LIVE_COPY);
            cfg = Msm.createNode(resolver, content, NT_LIVE_SYNC_CONFIG, props);
        }
        ModifiableValueMap mvm = Msm.writable(cfg);
        if (valid == null) {
            mvm.remove(PN_ROLLOUT_CONFIGS);
        } else {
            mvm.put(PN_ROLLOUT_CONFIGS, valid);
        }
        if (deep != null) {
            mvm.put(PN_IS_DEEP, deep);
        }
        if (excluded != null) {
            Set<String> clean = new LinkedHashSet<>();
            for (String e : excluded) {
                String t = e == null ? "" : e.trim();
                while (t.startsWith("/")) {
                    t = t.substring(1);
                }
                if (!t.isEmpty() && !t.contains("..")) {
                    clean.add(t);
                }
            }
            if (clean.isEmpty()) {
                mvm.remove(PN_EXCLUDED_PATHS);
            } else {
                mvm.put(PN_EXCLUDED_PATHS, clean.toArray(new String[0]));
            }
        }
        // a page that no longer overrides anything loses its configuration node
        if (!root && mvm.get(PN_MASTER, String.class) == null && !mvm.containsKey(PN_ROLLOUT_CONFIGS)) {
            delete(resolver, cfg);
        } else if (!Msm.hasMixin(content, NT_LIVE_SYNC)) {
            Msm.addMixin(content, NT_LIVE_SYNC);
        }
    }

    /** Saves the Blueprint tab; no configurations removes the setting. */
    static void writeBlueprint(ResourceResolver resolver, RolloutConfigManager manager, String page, String[] configs)
            throws WCMException {
        Resource content = resolver.getResource(page + Msm.CONTENT_SEGMENT);
        if (content == null || !Msm.isPage(resolver.getResource(page))) {
            throw new WCMException(page + " is not a page");
        }
        String[] valid = validConfigs(resolver, manager, configs);
        Resource cfg = content.getChild(BLUEPRINT_SYNC_CONFIG);
        if (valid == null || valid.length == 0) {
            if (cfg != null) {
                delete(resolver, cfg);
            }
            return;
        }
        if (cfg == null) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(Msm.PRIMARY, BLUEPRINT_SYNC_CONFIG);
            cfg = Msm.createNode(resolver, content, BLUEPRINT_SYNC_CONFIG, props);
        }
        Msm.writable(cfg).put(PN_ROLLOUT_CONFIGS, valid);
    }

    private static String[] validConfigs(ResourceResolver resolver, RolloutConfigManager manager, String[] configs)
            throws WCMException {
        if (configs == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String c : configs) {
            if (c == null || c.trim().isEmpty()) {
                continue;
            }
            if (manager != null && manager.getRolloutConfig(resolver, c.trim()) == null) {
                throw new WCMException("No sync rule at " + c);
            }
            out.add(c.trim());
        }
        return out.toArray(new String[0]);
    }

    private static void delete(ResourceResolver resolver, Resource r) throws WCMException {
        try {
            resolver.delete(r);
        } catch (PersistenceException e) {
            throw new WCMException("Could not remove " + r.getPath(), e);
        }
    }
}
