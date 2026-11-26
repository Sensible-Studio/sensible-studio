/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.sling.api.resource.Resource;

import com.adobe.cq.wcm.style.ComponentStyleInfo;
import com.day.cq.wcm.api.policies.ContentPolicy;

/**
 * Style System: the instance stores the chosen style ids in {@code cq:styleIds}; its policy
 * defines them under {@code cq:styleGroups/<group>/cq:styles/<style>} with {@code cq:styleId}
 * and {@code cq:styleClasses}. The applied classes are those of the chosen ids, in policy order.
 */
final class ComponentStyleInfoImpl implements ComponentStyleInfo {

    private final String classes;

    private ComponentStyleInfoImpl(String classes) {
        this.classes = classes;
    }

    static ComponentStyleInfo of(Resource resource, ContentPolicy policy) {
        String[] ids = resource.getValueMap().get("cq:styleIds", new String[0]);
        if (ids.length == 0 || policy == null) {
            return new ComponentStyleInfoImpl(null);
        }
        Resource policyRes = policy.adaptTo(Resource.class);
        Resource groups = policyRes == null ? null : policyRes.getChild("cq:styleGroups");
        if (groups == null) {
            return new ComponentStyleInfoImpl(null);
        }
        Set<String> wanted = new HashSet<>(Arrays.asList(ids));
        Set<String> out = new LinkedHashSet<>();
        for (Resource group : groups.getChildren()) {
            Resource styles = group.getChild("cq:styles");
            if (styles == null) {
                continue;
            }
            for (Resource style : styles.getChildren()) {
                String id = style.getValueMap().get("cq:styleId", String.class);
                String cls = style.getValueMap().get("cq:styleClasses", String.class);
                if (id != null && cls != null && wanted.contains(id)) {
                    out.addAll(Arrays.asList(cls.trim().split("\\s+")));
                }
            }
        }
        return new ComponentStyleInfoImpl(out.isEmpty() ? null : String.join(" ", out));
    }

    @Override
    public String getAppliedCssClasses() {
        return classes;
    }
}
