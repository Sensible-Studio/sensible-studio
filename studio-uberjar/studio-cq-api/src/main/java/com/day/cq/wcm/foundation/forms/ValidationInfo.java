/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.SlingHttpServletRequest;

/**
 * Validation errors of a form submission, stored on the request.
 */
public final class ValidationInfo {

    static final String REQUEST_ATTR = ValidationInfo.class.getName();

    private final Map<String, List<String>> messages = new HashMap<>();

    private ValidationInfo() {
    }

    /** Creates (or returns the existing) validation info of the request. */
    public static ValidationInfo createValidationInfo(SlingHttpServletRequest request) {
        ValidationInfo info = getValidationInfo(request);
        if (info == null) {
            info = new ValidationInfo();
            request.setAttribute(REQUEST_ATTR, info);
        }
        return info;
    }

    /** @return the validation info of the request, or {@code null} */
    public static ValidationInfo getValidationInfo(HttpServletRequest request) {
        Object o = request.getAttribute(REQUEST_ATTR);
        return o instanceof ValidationInfo ? (ValidationInfo) o : null;
    }

    /** @param field field name, {@code null} for form-level errors */
    public void addErrorMessage(String field, String message) {
        messages.computeIfAbsent(field == null ? "" : field, k -> new ArrayList<>()).add(message);
    }

    /** @return the messages for a field, never {@code null} */
    public String[] getErrorMessages(String field) {
        List<String> list = messages.get(field == null ? "" : field);
        return list == null ? new String[0] : list.toArray(new String[0]);
    }
}
