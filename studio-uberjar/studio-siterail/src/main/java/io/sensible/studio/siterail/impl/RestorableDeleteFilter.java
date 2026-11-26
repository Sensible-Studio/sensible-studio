/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the Sling POST delete ({@code :operation=delete}) restorable, like the console's delete: before the delete
 * runs, every page in what goes gets a version and a deletion record. A locked page or a target the user may not
 * remove stops the request here (as the delete itself would). Deletes in the DAM are left alone.
 */
@Component(service = Filter.class, property = {
        "sling.filter.scope=REQUEST",
        "sling.filter.methods=POST",
        "service.ranking:Integer=-1000"
})
public class RestorableDeleteFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(RestorableDeleteFilter.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public void init(FilterConfig config) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest && "delete".equals(req.getParameter(":operation"))) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            List<String> targets = targets(request);
            if (!targets.isEmpty()) {
                ResourceResolver service = service();
                try {
                    new VersionRestore(request.getResourceResolver(), service).prepareDelete(targets);
                } catch (SecurityException e) {
                    request.getResourceResolver().revert();
                    ((HttpServletResponse) res).sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
                    return;
                } catch (Exception e) {
                    // the delete goes ahead: without a version the page cannot be restored, as before
                    request.getResourceResolver().revert();
                    LOG.warn("Could not keep a version of {} before deleting it", targets, e);
                } finally {
                    if (service != null) {
                        service.close();
                    }
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** The paths a Sling POST delete removes ({@code :applyTo}, or the request's resource), pages and folders of sites. */
    static List<String> targets(SlingHttpServletRequest request) {
        List<String> paths = new ArrayList<>();
        String[] applyTo = request.getParameterValues(":applyTo");
        Resource base = request.getResource();
        if (applyTo != null && applyTo.length > 0) {
            for (String a : applyTo) {
                if (a == null || a.trim().isEmpty() || a.contains("*")) {
                    continue; // wildcards: left to the delete, not versioned
                }
                Resource r = a.startsWith("/") ? request.getResourceResolver().getResource(a)
                        : base == null ? null : request.getResourceResolver().getResource(base, a);
                if (r != null) {
                    paths.add(r.getPath());
                }
            }
        } else if (base != null && !Resource.RESOURCE_TYPE_NON_EXISTING.equals(base.getResourceType())) {
            paths.add(base.getPath());
        }
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            Resource r = request.getResourceResolver().getResource(p);
            if (p.startsWith("/content/") && !p.startsWith("/content/dam") && !p.startsWith("/content/cq:")
                    && r != null && (SiteRail.isPage(r) || SiteRail.isFolder(r))) {
                out.add(p);
            }
        }
        return out;
    }

    private ResourceResolver service() {
        try {
            return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SiteRailServlet.SUBSERVICE));
        } catch (LoginException e) {
            return null;
        }
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
