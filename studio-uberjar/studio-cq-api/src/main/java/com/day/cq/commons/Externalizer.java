/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.commons;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Turns repository paths into absolute URLs for a named environment (author, publish, local).
 * Available as an OSGi service; the domains are configurable.
 */
public interface Externalizer {

    String LOCAL = "local";
    String AUTHOR = "author";
    String PUBLISH = "publish";

    /** @return an absolute URL on the publish domain, with resolver mappings applied */
    String publishLink(ResourceResolver resolver, String path);

    /** @return an absolute URL on the publish domain using {@code scheme} */
    String publishLink(ResourceResolver resolver, String scheme, String path);

    /** @return an absolute URL on the author domain */
    String authorLink(ResourceResolver resolver, String path);

    /** @return an absolute URL on the author domain using {@code scheme} */
    String authorLink(ResourceResolver resolver, String scheme, String path);

    /** @return an absolute URL on the named domain */
    String externalLink(ResourceResolver resolver, String domain, String path);

    /** @return an absolute URL on the named domain using {@code scheme} */
    String externalLink(ResourceResolver resolver, String domain, String scheme, String path);

    /** @return an absolute URL based on the current request's scheme, host and port */
    String absoluteLink(SlingHttpServletRequest request, String scheme, String path);

    /** @return {@code path} mapped for the current request (no scheme or host) */
    String relativeLink(SlingHttpServletRequest request, String path);
}
