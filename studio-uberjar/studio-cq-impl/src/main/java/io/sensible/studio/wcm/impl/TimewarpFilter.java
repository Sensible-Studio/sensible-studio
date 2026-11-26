/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.util.Calendar;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.Page;

/**
 * Timewarp, like AEM's: {@code page.html?timewarp=<epoch ms>} renders the page as it was at that
 * moment, from the newest version saved before it. Without a version from before that time the
 * current content renders. The frame of the editor's Timewarp mode uses it.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/content/.*",
                "service.ranking:Integer=-2000"
        })
public class TimewarpFilter implements Filter {

    /** Request attribute: the epoch ms the page is rendered for. */
    public static final String ATTR_TIME = "studio.timewarp";
    /** Request attribute: the name of the version rendered, or absent when the current content renders. */
    public static final String ATTR_VERSION = "studio.timewarp.version";

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof SlingHttpServletRequest)) {
            chain.doFilter(req, res);
            return;
        }
        SlingHttpServletRequest request = (SlingHttpServletRequest) req;
        String tw = request.getParameter("timewarp");
        if (tw == null || tw.isEmpty() || !"html".equals(request.getRequestPathInfo().getExtension())) {
            chain.doFilter(req, res);
            return;
        }
        long time;
        try {
            time = Long.parseLong(tw.trim());
        } catch (NumberFormatException e) {
            chain.doFilter(req, res);
            return;
        }
        ResourceResolver resolver = request.getResourceResolver();
        Page page = new PageManagerImpl(resolver).getContainingPage(request.getResource());
        if (page == null) {
            chain.doFilter(req, res);
            return;
        }
        request.setAttribute(ATTR_TIME, time);
        Version version = versionAt(resolver, page.getPath() + "/jcr:content", time);
        if (version == null) {
            chain.doFilter(req, res);
            return;
        }
        String frozenPath;
        try {
            frozenPath = version.getFrozenNode().getPath();
            request.setAttribute(ATTR_VERSION, version.getName());
        } catch (RepositoryException e) {
            chain.doFilter(req, res);
            return;
        }
        TimewarpResolver warped = new TimewarpResolver(resolver, page.getPath() + "/jcr:content", frozenPath);
        SlingHttpServletRequest wrapped = new SlingHttpServletRequestWrapper(request) {
            @Override
            public ResourceResolver getResourceResolver() {
                return warped;
            }

            @Override
            public Resource getResource() {
                // the resource of the current include (Sling changes it per include), seen through the warp
                Resource current = super.getResource();
                Resource w = warped.wrap(current);
                return w != null ? w : current;
            }
        };
        chain.doFilter(wrapped, res);
    }

    /** @return the newest version of the node created at or before {@code time}, or null */
    static Version versionAt(ResourceResolver resolver, String path, long time) {
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return null;
        }
        try {
            if (!session.nodeExists(path) || !session.getNode(path).isNodeType("mix:versionable")) {
                return null;
            }
            VersionHistory history = session.getWorkspace().getVersionManager().getVersionHistory(path);
            Version best = null;
            for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
                Version v = it.nextVersion();
                if ("jcr:rootVersion".equals(v.getName())) {
                    continue;
                }
                Calendar created = v.getCreated();
                if (created.getTimeInMillis() <= time && (best == null || created.after(best.getCreated()))) {
                    best = v;
                }
            }
            return best;
        } catch (RepositoryException e) {
            return null;
        }
    }

    @Override
    public void destroy() {
        // nothing to do
    }
}
