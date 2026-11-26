/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import java.util.Calendar;

import org.apache.sling.api.resource.ValueMap;

/**
 * A saved version of a page: a JCR version of its {@code jcr:content} node, with an optional label and comment.
 */
public class Revision {

    private final String id;
    private final String versionName;
    private final String path;
    private final String label;
    private final String comment;
    private final Calendar created;
    private final ValueMap properties;

    public Revision(String id, String versionName, String path, String label, String comment, Calendar created,
            ValueMap properties) {
        this.id = id;
        this.versionName = versionName;
        this.path = path;
        this.label = label;
        this.comment = comment;
        this.created = created;
        this.properties = properties;
    }

    /** @return the identifier of the version, to pass to {@link PageManager#restore(String, String)} */
    public String getId() {
        return id;
    }

    /** @return the JCR version name ({@code 1.0}, {@code 1.1}, ...) */
    public String getVersionName() {
        return versionName;
    }

    /** @return the path of the page */
    public String getPath() {
        return path;
    }

    /** @return the version label, or {@code null} */
    public String getLabel() {
        return label;
    }

    /** @return the comment given when the version was created, or {@code null} */
    public String getComment() {
        return comment;
    }

    public Calendar getCreated() {
        return created;
    }

    /** @return the page properties as they were in this version */
    public ValueMap getProperties() {
        return properties;
    }

    /** @return the page title in this version */
    public String getTitle() {
        return properties == null ? null : properties.get("jcr:title", String.class);
    }
}
