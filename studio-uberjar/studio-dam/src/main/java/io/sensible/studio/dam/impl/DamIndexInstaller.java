/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.Collections;

import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts {@link DamIndex} into {@code /oak:index} when it is missing or older than this bundle,
 * with the {@code studio-dam-indexer} service user (allowed only to manage index definitions).
 * Oak then builds the index in the background; until it is ready, searches walk the tree.
 */
@Component(immediate = true)
public class DamIndexInstaller {

    static final String SUBSERVICE = "indexer";
    private static final Logger LOG = LoggerFactory.getLogger(DamIndexInstaller.class);

    @Reference
    private ResourceResolverFactory factory;

    @Activate
    void activate() {
        Thread t = new Thread(this::installIfNeeded, "studio-dam-index-installer");
        t.setDaemon(true);
        t.start();
    }

    void installIfNeeded() {
        try (ResourceResolver resolver = factory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                LOG.warn("DAM index: no JCR session for the indexer service user");
                return;
            }
            if (DamIndex.outdated(session)) {
                DamIndex.markReady(session, false);
                DamIndex.install(session, true);
                LOG.info("DAM index {} (version {}) installed; Oak is indexing /content/dam", DamIndex.PATH, DamIndex.VERSION);
            }
            // Searches switch to the index once Oak has built it (it clears "reindex").
            for (int i = 0; i < 720 && !DamIndex.built(session); i++) {
                Thread.sleep(5000);
            }
            if (DamIndex.built(session)) {
                DamIndex.markReady(session, true);
                LOG.info("DAM index ready: searches use {}", DamIndex.PATH);
            } else {
                LOG.warn("DAM index still building after an hour; searches keep walking the tree until the next start");
            }
        } catch (Exception e) {
            LOG.warn("DAM index not installed ({}); DAM searches walk the folder tree", e.toString());
        }
    }
}
