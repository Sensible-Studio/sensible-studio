/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.launches.impl;

import java.io.IOException;
import java.util.Calendar;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The future side of Timewarp, like AEM's launch preview: {@code page.html?timewarp=<epoch ms>} with a
 * moment after now renders the page as the launch due by then has it: the request is forwarded to
 * the page inside the newest unpromoted launch whose live date is at or before that moment and that
 * contains the page. Runs before the versions Timewarp (which only looks backwards).
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/content/.*",
                "service.ranking:Integer=-1900"
        })
public class LaunchPreviewFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchPreviewFilter.class);
    /** Request attribute: the launch path the page is shown from. */
    public static final String ATTR_LAUNCH = "studio.launch.preview";

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof SlingHttpServletRequest) || req.getAttribute(ATTR_LAUNCH) != null) {
            chain.doFilter(req, res);
            return;
        }
        SlingHttpServletRequest request = (SlingHttpServletRequest) req;
        String tw = request.getParameter("timewarp");
        String path = request.getResource().getPath();
        if (tw == null || !tw.matches("\\d+") || path.startsWith(Launches.ROOT + "/") || !"html".equals(request.getRequestPathInfo().getExtension())) {
            chain.doFilter(req, res);
            return;
        }
        long time = Long.parseLong(tw);
        if (time <= System.currentTimeMillis()) {
            chain.doFilter(req, res);
            return;
        }
        String target = null;
        try {
            Session session = request.getResourceResolver().adaptTo(Session.class);
            Calendar at = Calendar.getInstance();
            at.setTimeInMillis(time);
            Calendar best = null;
            for (Launches.Launch l : Launches.list(session)) {
                if (l.promoted != null || l.liveDate == null || l.liveDate.after(at)) {
                    continue;
                }
                if (!l.covers(path)) {
                    continue;
                }
                String in = Launches.inLaunch(l, path);
                if (session.nodeExists(in + "/jcr:content") && (best == null || l.liveDate.after(best))) {
                    best = l.liveDate;
                    target = in;
                    request.setAttribute(ATTR_LAUNCH, l.path);
                }
            }
        } catch (RepositoryException e) {
            LOG.warn("Launch preview of {}: {}", path, e.getMessage());
        }
        if (target == null) {
            chain.doFilter(req, res);
            return;
        }
        Resource launchPage = request.getResourceResolver().getResource(target);
        if (launchPage == null) {
            chain.doFilter(req, res);
            return;
        }
        RequestDispatcherOptions options = new RequestDispatcherOptions();
        options.setReplaceSelectors(request.getRequestPathInfo().getSelectorString());
        request.getRequestDispatcher(launchPage, options).forward(request, (SlingHttpServletResponse) res);
    }

    @Override
    public void destroy() {
        // nothing to do
    }
}
