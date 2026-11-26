/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.net.URI;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentFilter;

/**
 * The tiers content goes to, as in AEM as a Cloud Service: <b>publish</b> (the site's visitors) and <b>preview</b>
 * (a publish instance where authors and reviewers look at content before it goes live). An agent is the preview
 * tier's when its {@code jcr:content} says {@code tier=preview}; Publish never delivers to it, Publish to Preview
 * delivers only to it, and its requests keep their own status on the content
 * ({@code cq:lastReplicated_preview}, {@code cq:lastReplicatedBy_preview}, {@code cq:lastReplicationAction_preview},
 * the names AEM as a Cloud Service uses), so previewing never changes what Pages say about publishing.
 */
final class Tiers {

    static final String PUBLISH = "publish";
    static final String PREVIEW = "preview";
    /** On an agent's {@code jcr:content}: {@code preview}, or nothing (publish). */
    static final String PN_TIER = "tier";
    /** On an agent's {@code jcr:content}: where visitors of that tier see the content (e.g. http://localhost:4505). */
    static final String PN_PUBLIC_URL = "publicUrl";

    private Tiers() {
    }

    static boolean isPreview(Agent a) {
        return a != null && a.getConfiguration() != null && a.getConfiguration().getProperties() != null
                && PREVIEW.equals(a.getConfiguration().getProperties().get(PN_TIER, String.class));
    }

    /** {@code preview} or null (publish) from a request parameter; anything else is refused. */
    static String of(String tier) {
        if (tier == null || tier.isEmpty() || PUBLISH.equals(tier)) {
            return null;
        }
        if (PREVIEW.equals(tier)) {
            return PREVIEW;
        }
        throw new IllegalArgumentException("A tier is publish or preview");
    }

    /** The suffix of the status properties of a tier: {@code _preview}, or nothing for publish. */
    static String suffix(String tier) {
        return PREVIEW.equals(tier) ? "_" + PREVIEW : "";
    }

    /** The enabled agents of a tier (null: publish). */
    static AgentFilter filter(String tier) {
        return PREVIEW.equals(tier) ? a -> a != null && a.isEnabled() && isPreview(a) : a -> a != null && a.isEnabled() && !isPreview(a);
    }

    /** Where the tier's visitors see the content: the agent's public URL, else the origin of its transport URI. */
    static String publicUrl(Agent a) {
        if (a == null || a.getConfiguration() == null) {
            return null;
        }
        String url = a.getConfiguration().getProperties() == null ? null : a.getConfiguration().getProperties().get(PN_PUBLIC_URL, String.class);
        if (url != null && !url.trim().isEmpty()) {
            return url.trim().replaceAll("/+$", "");
        }
        String transport = a.getConfiguration().getTransportURI();
        try {
            URI u = transport == null ? null : URI.create(transport);
            return u == null || u.getScheme() == null || u.getAuthority() == null ? null : u.getScheme() + "://" + u.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
