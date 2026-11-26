/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.tagging;

import java.util.Iterator;
import java.util.Locale;

import org.apache.sling.api.adapter.Adaptable;

/**
 * A content tag such as {@code wknd:activity/surfing}.
 *
 * <p>Sensible Studio covers the read side of tagging only; the tag editing API is not part of this slice.
 */
public interface Tag extends Adaptable {

    /** @return the tag id, e.g. {@code wknd:activity/surfing} */
    String getTagID();

    /** @return the id without the namespace, e.g. {@code activity/surfing} */
    String getLocalTagID();

    /** @return the repository path of the tag */
    String getPath();

    /** @return the node name, e.g. {@code surfing} */
    String getName();

    /** @return the title, falling back to the name */
    String getTitle();

    /**
     * @param locale requested locale
     * @return the localized title ({@code jcr:title.<lang>}), falling back to {@link #getTitle()}
     */
    String getTitle(Locale locale);

    /** @return the description or {@code null} */
    String getDescription();

    /** @return the namespace tag (the root of this tag's tree) */
    Tag getNamespace();

    /** @return the parent tag, or {@code null} for a namespace */
    Tag getParent();

    /** @return the direct child tags */
    Iterator<Tag> listChildren();
}
