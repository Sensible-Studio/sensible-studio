/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.tagging;

import org.apache.sling.api.resource.Resource;

import com.day.cq.commons.RangeIterator;

/**
 * Resolves tags and finds tagged content. Obtain with {@code resolver.adaptTo(TagManager.class)}.
 */
public interface TagManager {

    /**
     * @param tagID tag id ({@code ns:path}) or absolute tag path
     * @return the tag, or {@code null}
     */
    Tag resolve(String tagID);

    /** @return the tags set on a resource (its {@code cq:tags}) */
    Tag[] getTags(Resource resource);

    /**
     * @param basePath           where to search
     * @param tagIDs             tags to look for
     * @param oneMatchIsEnough   {@code true} for OR, {@code false} for AND
     * @return the tagged resources (the nodes that carry {@code cq:tags})
     */
    RangeIterator<Resource> find(String basePath, String[] tagIDs, boolean oneMatchIsEnough);

    /** Same as {@code find(basePath, tagIDs, false)}. */
    RangeIterator<Resource> find(String basePath, String[] tagIDs);

    /** @return all namespaces */
    Tag[] getNamespaces();
}
