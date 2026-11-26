/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;

import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.commons.Filter;
import com.day.cq.commons.LabeledResource;
import com.day.cq.tagging.Tag;

/**
 * A page: a {@code cq:Page} resource whose properties live on its {@code jcr:content} child.
 *
 * <p>Obtain one with {@code resource.adaptTo(Page.class)} or {@link PageManager#getPage(String)}.
 */
public interface Page extends Adaptable, LabeledResource {

    /** @return absolute path of the page resource */
    String getPath();

    /** @return the page manager that created this page */
    PageManager getPageManager();

    /** @return the {@code jcr:content} resource, or {@code null} if the page has no content */
    Resource getContentResource();

    /**
     * @param relPath path relative to {@code jcr:content}
     * @return the resource, or {@code null}
     */
    Resource getContentResource(String relPath);

    /** @return the direct child pages */
    Iterator<Page> listChildren();

    /**
     * @param filter filter applied to children, may be {@code null}
     * @return the direct child pages accepted by the filter
     */
    Iterator<Page> listChildren(Filter<Page> filter);

    /**
     * @param filter filter applied to children, may be {@code null}
     * @param deep   {@code true} to walk the whole subtree (depth first)
     * @return the child pages accepted by the filter
     */
    Iterator<Page> listChildren(Filter<Page> filter, boolean deep);

    /**
     * @param name child page name
     * @return {@code true} if a direct child page with that name exists
     */
    boolean hasChild(String name);

    /** @return number of path segments, {@code /content} has depth 1 */
    int getDepth();

    /** @return the direct parent page, or {@code null} if the parent is not a page */
    Page getParent();

    /**
     * @param level 1 is the direct parent, 2 the grandparent and so on
     * @return the page, or {@code null} if no page exists there
     */
    Page getParent(int level);

    /**
     * Example for {@code /content/wknd/us/en}: level 0 is {@code /content}, 1 is
     * {@code /content/wknd}, 3 is the page itself, 4 is {@code null}.
     *
     * @param level absolute level, starting at 0
     * @return the ancestor page at that level, or {@code null}
     */
    Page getAbsoluteParent(int level);

    /** @return properties of {@code jcr:content}, an empty map if there is none */
    ValueMap getProperties();

    /**
     * @param relPath path relative to {@code jcr:content}
     * @return properties of that resource, or {@code null} if it does not exist
     */
    ValueMap getProperties(String relPath);

    /** @return the page name (last path segment) */
    String getName();

    /** @return {@code jcr:title} or {@code null} */
    String getTitle();

    /** @return {@code pageTitle} or {@code null} */
    String getPageTitle();

    /** @return {@code navTitle} or {@code null} */
    String getNavigationTitle();

    /** @return {@code jcr:description} or {@code null} */
    String getDescription();

    /** @return value of {@code hideInNav} */
    boolean isHideInNav();

    /** @return {@code true} if the page has a {@code jcr:content} child */
    boolean hasContent();

    /** @return {@code true} if now is between on-time and off-time */
    boolean isValid();

    /**
     * @return 0 if valid now, a positive number of milliseconds until the page becomes valid, or a
     *         negative number if the off-time is in the past
     */
    long timeUntilValid();

    /** @return {@code onTime} or {@code null} */
    Calendar getOnTime();

    /** @return {@code offTime} or {@code null} */
    Calendar getOffTime();

    /** @return {@code cq:lastModified}, falling back to {@code jcr:lastModified} */
    Calendar getLastModified();

    /** @return {@code cq:lastModifiedBy}, falling back to {@code jcr:lastModifiedBy} */
    String getLastModifiedBy();

    /** @return {@code sling:vanityPath} or {@code null} */
    String getVanityUrl();

    /** @return the tags referenced in {@code cq:tags}; unresolvable ids are skipped */
    Tag[] getTags();

    /**
     * Locks the page for the current user.
     *
     * @throws WCMException if the page is already locked or has no content
     */
    void lock() throws WCMException;

    /** @return {@code true} if the page is locked */
    boolean isLocked();

    /** @return the lock owner, or {@code null} if not locked */
    String getLockOwner();

    /** @return {@code true} if the current user may unlock the page */
    boolean canUnlock();

    /**
     * @throws WCMException if the current user may not unlock the page
     */
    void unlock() throws WCMException;

    /** @return the template from {@code cq:template}, or {@code null} */
    Template getTemplate();

    /**
     * Same as {@code getLanguage(false)}.
     *
     * @return the content language
     */
    Locale getLanguage();

    /**
     * @param ignoreContent {@code true} to skip {@code jcr:language} and derive the language from
     *                      the path only
     * @return the content language, never {@code null}
     */
    Locale getLanguage(boolean ignoreContent);
}
