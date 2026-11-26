/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Component;

/**
 * Sends visits of Sling's built-in login form ({@code /system/sling/form/login}, where several Sensible Studio screens send
 * anonymous visitors) to Sensible Studio's login page, with the same query ({@code resource}, {@code j_reason}).
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/system/sling/form/login",
                "sling.filter.methods=GET",
                "service.ranking:Integer=1000"
        })
public class LoginRedirectFilter implements Filter {

    static final String SLING_FORM = "/system/sling/form/login";

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"GET".equals(request.getMethod()) || !SLING_FORM.equals(path)) {
            chain.doFilter(req, res);
            return;
        }
        String query = request.getQueryString();
        ((HttpServletResponse) res).sendRedirect(request.getContextPath() + LoginPageServlet.URL
                + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
