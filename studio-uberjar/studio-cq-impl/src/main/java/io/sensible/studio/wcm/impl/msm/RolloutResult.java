/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a rollout did, for the UI and the log. */
public final class RolloutResult {

    /** target pages whose content was synchronised */
    public final List<String> updated = new ArrayList<>();
    /** target pages created */
    public final List<String> created = new ArrayList<>();
    /** target pages deleted because their source was deleted */
    public final List<String> deleted = new ArrayList<>();
    /** target pages skipped because they are suspended or excluded */
    public final List<String> skipped = new ArrayList<>();
    /** pages created in the live copy with the name of a new blueprint page */
    public final List<String> conflicts = new ArrayList<>();
    /** local pages renamed to make room for a blueprint page */
    public final List<String> renamed = new ArrayList<>();
    /** target pages saved as a version before the rollout (the {@code targetVersion} action) */
    public final List<String> versioned = new ArrayList<>();
    /** actions named in a rollout configuration that Sensible Studio does not know */
    public final List<String> unknownActions = new ArrayList<>();
    public int nodes;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("updated", updated);
        m.put("created", created);
        m.put("deleted", deleted);
        m.put("skipped", skipped);
        m.put("conflicts", conflicts);
        m.put("renamed", renamed);
        m.put("versioned", versioned);
        m.put("unknownActions", unknownActions);
        m.put("nodes", nodes);
        return m;
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
