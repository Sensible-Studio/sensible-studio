/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * {@link DamEmbeddingIndex} of the multilingual model's embeddings: pictures found by a
 * description in any language. Empty (and never built) while that model is not installed.
 */
@Component(service = {DamSearchIndex.class, ResourceChangeListener.class}, property = {
        ResourceChangeListener.PATHS + "=/content/dam",
        ResourceChangeListener.CHANGES + "=ADDED",
        ResourceChangeListener.CHANGES + "=CHANGED",
        ResourceChangeListener.CHANGES + "=REMOVED"})
public class DamSearchIndex extends DamEmbeddingIndex {

    @Reference
    private ResourceResolverFactory searchResolverFactory;

    public DamSearchIndex() {
        super(DamSimilar.Space.SEARCH);
    }

    DamSearchIndex(ResourceResolverFactory resolverFactory) {
        super(resolverFactory, DamSimilar.Space.SEARCH);
        this.searchResolverFactory = resolverFactory;
    }

    @Override
    protected ResourceResolverFactory factory() {
        return searchResolverFactory;
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }
}
