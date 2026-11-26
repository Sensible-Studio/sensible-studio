/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import org.apache.sling.api.resource.Resource;

/**
 * MSM state of a resource for the editor and the consoles, without services (content only).
 */
public final class MsmState {

    public static final String INHERITED = "inherited";
    public static final String CANCELLED = "cancelled";

    private MsmState() {
    }

    /**
     * @return {@value #INHERITED} for a component rolled out from a blueprint and still synchronised,
     *         {@value #CANCELLED} if its inheritance is cancelled (itself, a container above it or its page),
     *         {@code null} for local components and content outside live copies
     */
    public static String of(Resource resource) {
        if (resource == null) {
            return null;
        }
        Relationships rels = new Relationships(resource.getResourceResolver(), null);
        if (!rels.isInherited(resource)) {
            return null;
        }
        LiveRelationshipImpl rel = rels.relationshipOf(resource.getPath());
        return rel == null ? null : rel.status().cancelled ? CANCELLED : INHERITED;
    }

    /** @return {@code true} if the resource lies inside a live copy (inherited or local) */
    public static boolean isInLiveCopy(Resource resource) {
        return resource != null && new Relationships(resource.getResourceResolver(), null).liveCopyOf(resource.getPath()) != null;
    }

    /** @return {@code true} if the page is the root of a live copy */
    public static boolean isLiveCopyRoot(Resource page) {
        Resource cfg = page == null ? null : page.getResourceResolver().getResource(Msm.configPath(page.getPath()));
        return cfg != null && cfg.getValueMap().get("cq:master", String.class) != null;
    }
}
