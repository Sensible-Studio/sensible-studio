/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When a picture is removed, the image sets it was the first picture of are shown by the next
 * one ({@link DamSets#refreshSetsStartingWith}). The set keeps naming the removed picture (it
 * counts as missing), so restoring it brings it back in its place.
 *
 * <p>A removed folder is reported as one change, for the folder only: the sets that began with a
 * picture in it keep their picture until their pictures change.</p>
 */
@Component(service = ResourceChangeListener.class, property = {
        ResourceChangeListener.PATHS + "=/content/dam",
        ResourceChangeListener.CHANGES + "=REMOVED"})
public class DamSetFollower implements ResourceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(DamSetFollower.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    public DamSetFollower() {
    }

    DamSetFollower(ResourceResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        Set<String> removed = new LinkedHashSet<>();
        for (ResourceChange change : changes) {
            String path = change.getPath();
            if (change.getType() == ResourceChange.ChangeType.REMOVED && !path.contains("/jcr:content")) {
                removed.add(path);
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AssetProcessingJob.SUBSERVICE))) {
            int n = 0;
            for (String path : removed) {
                n += DamSets.refreshSetsStartingWith(resolver, path);
            }
            if (n > 0) {
                resolver.commit();
                LOG.info("{} image set(s) are shown by their next picture", n);
            }
        } catch (LoginException e) {
            LOG.warn("Image sets not followed: no service user for subservice {}", AssetProcessingJob.SUBSERVICE);
        } catch (PersistenceException | RuntimeException e) {
            LOG.info("Image sets keep their picture: {}", e.toString());
        }
    }
}
