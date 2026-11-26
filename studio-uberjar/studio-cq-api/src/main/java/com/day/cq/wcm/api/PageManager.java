/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import java.util.Calendar;
import java.util.Collection;

import org.apache.sling.api.resource.Resource;

/**
 * Reads and changes pages. Obtain one with {@code resourceResolver.adaptTo(PageManager.class)}.
 */
public interface PageManager {

    /**
     * @param pagePath absolute path
     * @return the page, or {@code null} if the path does not point to a page
     */
    Page getPage(String pagePath);

    /**
     * @param resource any resource, typically a component inside {@code jcr:content}
     * @return the nearest page at or above the resource, or {@code null}
     */
    Page getContainingPage(Resource resource);

    /**
     * @param path any absolute path
     * @return the nearest page at or above the path, or {@code null}
     */
    Page getContainingPage(String path);

    /**
     * Creates a page and commits.
     *
     * @see #create(String, String, String, String, boolean)
     */
    Page create(String parentPath, String pageName, String template, String title) throws WCMException;

    /**
     * @param parentPath path of the parent resource (must exist)
     * @param pageName   name of the new page; {@code null} derives a unique name from the title
     * @param template   template path; its initial content is copied into the new page
     * @param title      page title, may be {@code null}
     * @param autoSave   {@code true} to commit the resolver
     * @return the new page
     * @throws WCMException if the parent is missing, the name is taken or the write fails
     */
    Page create(String parentPath, String pageName, String template, String title, boolean autoSave)
            throws WCMException;

    /**
     * Deletes a page and commits.
     *
     * @see #delete(Page, boolean, boolean)
     */
    void delete(Page page, boolean shallow) throws WCMException;

    /**
     * @param page     page to delete
     * @param shallow  {@code true} to delete only the content and keep child pages
     * @param autoSave {@code true} to commit the resolver
     */
    void delete(Page page, boolean shallow, boolean autoSave) throws WCMException;

    /**
     * @param resource resource to delete
     * @param shallow  {@code true} to keep child pages (only meaningful for pages)
     * @param autoSave {@code true} to commit the resolver
     */
    void delete(Resource resource, boolean shallow, boolean autoSave) throws WCMException;

    /**
     * Copies a page and commits.
     *
     * @see #copy(Page, String, String, boolean, boolean, boolean)
     */
    Page copy(Page page, String destination, String beforeName, boolean shallow, boolean resolveConflict)
            throws WCMException;

    /**
     * @param page            source page
     * @param destination     absolute path of the copy
     * @param beforeName      name of the sibling to place the copy before, or {@code null} for last
     * @param shallow         {@code true} to copy only the page, not its child pages
     * @param resolveConflict {@code true} to pick a free name when the destination exists
     * @param autoSave        {@code true} to commit the resolver
     * @return the copy
     */
    Page copy(Page page, String destination, String beforeName, boolean shallow, boolean resolveConflict,
            boolean autoSave) throws WCMException;

    /**
     * Moves a page and commits. References are not rewritten in this version; {@code adjustRefs}
     * is accepted for API compatibility.
     *
     * @return the page at its new location
     */
    Page move(Page page, String destination, String beforeName, boolean shallow, boolean resolveConflict,
            String[] adjustRefs) throws WCMException;

    /**
     * Reorders a page among its siblings and commits.
     *
     * @param page       page to move
     * @param beforeName sibling to place it before, or {@code null} for last
     */
    void order(Page page, String beforeName) throws WCMException;

    /** Saves the current state of the page as a new version, without label or comment. */
    Revision createRevision(Page page) throws WCMException;

    /**
     * Saves the current state of the page ({@code jcr:content}) as a new version.
     *
     * @param label a version label, unique for the page, or {@code null}
     * @param comment a comment, or {@code null}
     */
    Revision createRevision(Page page, String label, String comment) throws WCMException;

    /**
     * @param cal only versions created up to then, or {@code null} for all
     * @return the versions of the page, newest first
     */
    Collection<Revision> getRevisions(String path, Calendar cal) throws WCMException;

    /**
     * Restores a version of the page content. The current state is saved as a version first.
     *
     * @param revisionId {@link Revision#getId()}
     * @return the restored page
     */
    Page restore(String path, String revisionId) throws WCMException;

    /**
     * @param templatePath absolute path of a template
     * @return the template, or {@code null}
     */
    Template getTemplate(String templatePath);
}
