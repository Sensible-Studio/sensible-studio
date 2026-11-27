/*
 * SPDX-License-Identifier: Apache-2.0
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
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * A locked page is changed by the person who locked it and by nobody else. The lock was a note on the page and a
 * notice in the editor: a second author could still edit its components, add to it, change its properties, all
 * through the Sling POST servlet, which knows nothing of page locks. This refuses a write below a locked page's
 * content to anyone but the lock's owner, with 423 and the owner's name. Unlocking is not a write of this kind
 * (it goes through the editor's endpoint, which lets the owner and the administrator do it).
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.methods=POST",
                "sling.filter.methods=PUT",
                "sling.filter.methods=DELETE",
                "sling.filter.pattern=/content/.*",
                "service.ranking:Integer=2000"
        })
public class PageLockFilter implements Filter {

    private static final String CONTENT = "/jcr:content";

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile org.apache.sling.settings.SlingSettingsService settings;

    /** A lock is the authors' affair: on a publish instance the page arrives with its lock, and visitors are not held by it. */
    private boolean forVisitors() {
        org.apache.sling.settings.SlingSettingsService s = settings;
        return s != null && s.getRunModes().contains("publish");
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest && !forVisitors()) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            String method = request.getMethod();
            if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
                ResourceResolver resolver = request.getResourceResolver();
                String owner = lockedBy(resolver, request.getResource().getPath(), resolver.getUserID());
                if (owner != null) {
                    HttpServletResponse response = (HttpServletResponse) res;
                    String message = "This page is locked by " + owner + ". It can be changed again once it is unlocked.";
                    response.setStatus(423);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"status.code\":423,\"status.message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\",\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                    return;
                }
                tellOfAnotherAuthor(request, (HttpServletResponse) res);
            }
        }
        chain.doFilter(req, res);
    }

    /** The editor says when it read the page (the page's own date then). */
    static final String SEEN = "X-Studio-Page-Seen";
    static final String CHANGED_BY = "X-Studio-Changed-By";
    static final String CHANGED_AT = "X-Studio-Changed-At";

    /**
     * Without a lock the last save wins, and the author whose screen is older never learned that somebody else
     * had been at the page. Nothing is refused: the answer names who changed it since, and when, and the editor
     * says so.
     */
    private static void tellOfAnotherAuthor(SlingHttpServletRequest request, HttpServletResponse response) {
        String seen = request.getHeader(SEEN);
        String content = PageModified.contentOf(request.getResource().getPath());
        if (seen == null || content == null) {
            return;
        }
        ResourceResolver resolver = request.getResourceResolver();
        Resource page = resolver.getResource(content);
        if (page == null) {
            return;
        }
        java.util.Calendar modified = page.getValueMap().get("cq:lastModified", java.util.Calendar.class);
        String by = page.getValueMap().get("cq:lastModifiedBy", String.class);
        String other = changedBy(seen, modified == null ? null : modified.toInstant(), by, resolver.getUserID());
        if (other != null) {
            response.setHeader(CHANGED_BY, other.replaceAll("[^\\p{Alnum}@._ -]", "?"));
            response.setHeader(CHANGED_AT, modified.toInstant().toString());
        }
    }

    /** Who changed the page after {@code seen}, when it was somebody else; null otherwise (and when it cannot be told). */
    static String changedBy(String seen, java.time.Instant modified, String by, String user) {
        if (modified == null || by == null || by.equals(user)) {
            return null;
        }
        try {
            return modified.isAfter(java.time.Instant.parse(seen).plusSeconds(1)) ? by : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Who else holds the lock of the page that {@code path} is, or is content of; null when nobody does. */
    static String lockedBy(ResourceResolver resolver, String path, String user) {
        if (path == null || !path.startsWith("/content/")) {
            return null;
        }
        int at = path.indexOf(CONTENT + "/");
        String page = at > 0 ? path.substring(0, at) : path.endsWith(CONTENT) ? path.substring(0, path.length() - CONTENT.length()) : path;
        Resource content = resolver.getResource(page + CONTENT);
        if (content == null) {
            return null;
        }
        String owner = content.getValueMap().get(Pages.PN_LOCK_OWNER, String.class);
        return owner == null || owner.isEmpty() || owner.equals(user) ? null : owner;
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
