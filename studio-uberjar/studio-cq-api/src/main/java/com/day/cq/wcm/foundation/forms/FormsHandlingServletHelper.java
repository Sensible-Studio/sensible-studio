/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import java.io.IOException;
import java.util.Set;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;

import com.day.cq.wcm.foundation.security.SaferSlingPostValidator;

/**
 * Shared logic of form POST handling. Sensible Studio accepts the configuration but does not process form
 * submissions yet: POSTs are answered with 501 and filtered requests pass through unchanged.
 */
public class FormsHandlingServletHelper {

    private final String[] parameterNameWhitelist;
    private final Set<String> formResourceTypes;

    public FormsHandlingServletHelper(String[] parameterNameWhitelist, SaferSlingPostValidator validator,
            Set<String> formResourceTypes, boolean allowExpressions, FormStructureHelperFactory factory) {
        this.parameterNameWhitelist = parameterNameWhitelist;
        this.formResourceTypes = formResourceTypes;
    }

    public void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED, "Form submissions are not supported yet");
    }

    public void handleFilter(ServletRequest request, ServletResponse response, FilterChain chain,
            String extension, String selector) throws IOException, ServletException {
        chain.doFilter(request, response);
    }

    /** @return the configured form resource types */
    public Set<String> getFormResourceTypes() {
        return formResourceTypes;
    }

    /** @return the parameters forms may write */
    public String[] getParameterNameWhitelist() {
        return parameterNameWhitelist;
    }
}
