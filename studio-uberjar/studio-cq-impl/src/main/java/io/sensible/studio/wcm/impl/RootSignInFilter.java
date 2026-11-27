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
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.osgi.service.component.annotations.Component;

/**
 * The root of an instance, asked for by someone who is not signed in and may not read it (an author): the
 * sign-in page. Only {@code /content} asks for a sign-in by itself; {@code /} resolved to a resource without a
 * path, which the page handling could not take, and the answer was a 500. That is also where signing out lands.
 * Where the root can be read (a publish instance sends its visitors on from there) nothing changes.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/",
                "sling.filter.methods=GET",
                "sling.filter.methods=HEAD",
                "service.ranking:Integer=2000"
        })
public class RootSignInFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            String uri = request.getRequestURI().substring(request.getContextPath().length());
            Resource resource = request.getResource();
            String user = request.getResourceResolver().getUserID();
            boolean unreadable = resource == null || resource.getPath() == null || ResourceUtil.isNonExistingResource(resource);
            if ("/".equals(uri) && unreadable && (user == null || "anonymous".equals(user))) {
                ((HttpServletResponse) res).sendRedirect(request.getContextPath() + LoginPageServlet.URL);
                return;
            }
        }
        chain.doFilter(req, res);
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
