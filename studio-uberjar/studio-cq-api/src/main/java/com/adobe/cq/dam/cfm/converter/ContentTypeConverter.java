/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm.converter;

/**
 * Converts content fragment text (plain text, Markdown, HTML) to HTML.
 */
public interface ContentTypeConverter {

    /**
     * @param value       the text
     * @param contentType {@code text/plain}, {@code text/x-markdown} or {@code text/html}
     * @return HTML
     */
    String convertToHTML(String value, String contentType);
}
