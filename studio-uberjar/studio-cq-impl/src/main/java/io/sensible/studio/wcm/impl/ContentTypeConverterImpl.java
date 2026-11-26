/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.osgi.service.component.annotations.Component;

import com.adobe.cq.dam.cfm.converter.ContentTypeConverter;
import com.day.text.Text;

/**
 * HTML passes through; plain text becomes escaped paragraphs; Markdown is treated as plain text
 * for now (headings and emphasis are not rendered).
 */
@Component(service = ContentTypeConverter.class)
public class ContentTypeConverterImpl implements ContentTypeConverter {

    @Override
    public String convertToHTML(String value, String contentType) {
        if (value == null) {
            return null;
        }
        if ("text/html".equals(contentType)) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (String para : value.split("\\r?\\n\\s*\\r?\\n")) {
            if (!para.trim().isEmpty()) {
                sb.append("<p>").append(Text.escapeXml(para.trim()).replaceAll("\\r?\\n", "<br>")).append("</p>");
            }
        }
        return sb.toString();
    }
}
