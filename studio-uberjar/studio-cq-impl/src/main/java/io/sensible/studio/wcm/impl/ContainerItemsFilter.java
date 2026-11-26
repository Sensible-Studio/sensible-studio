/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The item list of container components (Carousel, Tabs, Accordion) as their dialog's "Items" tab posts it: besides
 * the usual Sling POST parameters ({@code ./item_1/cq:panelTitle}, {@code ./item_2/sling:resourceType} for a new
 * item), the request carries {@code itemOrder} (the child names in their new order, comma separated) and
 * {@code deletedItems} (the child names to remove). Sling's POST servlet ignores both; this filter applies them
 * once the POST succeeded, in the requesting user's session.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.methods=POST",
                "service.ranking:Integer=-1000"
        })
public class ContainerItemsFilter implements Filter {

    static final String ITEM_ORDER = "itemOrder";
    static final String DELETED_ITEMS = "deletedItems";

    private static final Logger LOG = LoggerFactory.getLogger(ContainerItemsFilter.class);

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof SlingHttpServletRequest) || !"POST".equals(((SlingHttpServletRequest) req).getMethod())
                || (req.getParameter(ITEM_ORDER) == null && req.getParameter(DELETED_ITEMS) == null)) {
            chain.doFilter(req, res);
            return;
        }
        SlingHttpServletRequest request = (SlingHttpServletRequest) req;
        StatusCapture status = new StatusCapture((HttpServletResponse) res);
        chain.doFilter(req, status);
        if (status.status >= 300) {
            return;
        }
        Resource container = request.getResourceResolver().getResource(request.getResource().getPath());
        if (container == null) {
            return;
        }
        try {
            apply(container, names(request.getParameter(DELETED_ITEMS)), names(request.getParameter(ITEM_ORDER)));
        } catch (RepositoryException | PersistenceException e) {
            LOG.warn("Could not apply the item order of {}", container.getPath(), e);
            request.getResourceResolver().revert();
        }
    }

    /** Removes the {@code deleted} children, then moves the {@code order} children to the end in that order. */
    static void apply(Resource container, List<String> deleted, List<String> order)
            throws RepositoryException, PersistenceException {
        boolean changed = false;
        for (String name : deleted) {
            Resource item = container.getChild(name);
            if (item != null) {
                container.getResourceResolver().delete(item);
                changed = true;
            }
        }
        Node node = container.adaptTo(Node.class);
        if (node != null && node.getPrimaryNodeType().hasOrderableChildNodes()) {
            for (String name : order) {
                if (!deleted.contains(name) && node.hasNode(name)) {
                    node.orderBefore(name, null);
                    changed = true;
                }
            }
        }
        if (changed) {
            container.getResourceResolver().commit();
        }
    }

    static List<String> names(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String name = part.trim();
            // child names only: never a path, so nothing outside the container can be touched
            if (!name.isEmpty() && name.indexOf('/') < 0 && !name.equals(".") && !name.equals("..")) {
                out.add(name);
            }
        }
        return out;
    }

    @Override
    public void destroy() {
        // nothing to release
    }

    /** Remembers the status the POST servlet answered with. */
    private static final class StatusCapture extends HttpServletResponseWrapper {
        int status = HttpServletResponse.SC_OK;

        StatusCapture(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            status = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            status = sc;
            super.sendError(sc, msg);
        }
    }
}
