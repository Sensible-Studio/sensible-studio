/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.WCMException;

/**
 * {@code /bin/studio/siterail.json}: the data of the side rails of the Sites console.
 *
 * <pre>
 * GET  op=timeline&amp;path=/content/…        events of a page (activities, versions, workflows, comments) and its lock owner
 * GET  op=site&amp;path=/content/…            the site a path belongs to: languages, page counts, configuration, templates
 * GET  op=templates&amp;path=/content/…       the templates used below a path (for the filter)
 * GET  op=search&amp;path=…&amp;q=…&amp;template=…&amp;tag=…&amp;modified=days&amp;livecopy=livecopy|notlivecopy&amp;locked=true
 *          &amp;meta=&lt;property&gt;=&lt;value&gt;… (one of the values, per property) &amp;metaText=&lt;property&gt;=&lt;text&gt;… (contains)
 * POST op=comment&amp;path=…&amp;message=…        add a comment to a page
 * POST op=deleteComment&amp;id=…              delete one's own comment
 * POST op=saveVersion&amp;path=…&amp;label=…&amp;comment=…
 * GET  op=versionsInside&amp;path=…           the versioned pages inside a page, existing and deleted (Restore Version)
 * GET  op=treeAt&amp;path=…&amp;date=ISO           the tree as Restore Tree would make it
 * POST op=restoreVersions&amp;restore=&lt;page&gt;|&lt;version&gt;…   restore versions (deleted pages are created again)
 * POST op=restoreTree&amp;path=…&amp;date=ISO&amp;preserve=true|false
 * POST op=restore&amp;path=…&amp;version=…        revert one page to a version
 * POST op=delete&amp;path=…(repeated)         delete with a version of every page first, so it can be restored
 * POST op=forgetDeleted&amp;path=…            admin only: remove a deleted page's versions and record for good
 * GET  /bin/studio/versionpreview.html?path=…&amp;version=…   Preview Version (VersionPreviewServlet)
 * GET  op=diffVersion&amp;path=…&amp;from=current|&lt;version&gt;&amp;to=…   what differs between two states (Compare)
 * GET  op=searches                          the signed-in user's saved searches (Filter rail)
 * POST op=saveSearch&amp;name=…&amp;criteria={…}  save (or replace) a search of the user
 * POST op=deleteSearch&amp;name=…             delete one of the user's saved searches
 * GET  op=searchForm                        the filters the Filter rail shows (Search Forms, {@link SiteSearchForms})
 * GET  op=propertyValues&amp;path=…&amp;property=…   the values a page property has below a path, the most used first
 * POST op=saveSearchForm&amp;facets=[…]       admin: the filters, in order
 * POST op=resetSearchForm                  admin: back to the default filters
 * </pre>
 *
 * Reads and checks run with the resolver of the request, so the repository permissions of the user apply; records
 * under {@code /var} (comments, deletions, saved searches) are written by the service user afterwards.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/siterail",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"
})
public class SiteRailServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(SiteRailServlet.class);

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, false);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, true);
    }

    /** The subservice of {@code studio-siterail-service} (see the repoinit and service user mapping in studio-compat-config). */
    static final String SUBSERVICE = "siterail";
    private static final String STORE = SiteRailServlet.class.getName() + ".store";

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    /** The bundle's service resolver, or null before its service user exists (repoinit runs at startup). */
    private ResourceResolver service() {
        try {
            return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
        } catch (LoginException e) {
            LOG.debug("No service user for {}: working as the user", SUBSERVICE, e);
            return null;
        }
    }

    private void handle(SlingHttpServletRequest r, SlingHttpServletResponse response, boolean post) throws IOException {
        ResourceResolver service = service();
        try {
            r.setAttribute(STORE, service);
            handle(r, response, post, service);
        } finally {
            r.removeAttribute(STORE);
            if (service != null) {
                service.close();
            }
        }
    }

    private void handle(SlingHttpServletRequest r, SlingHttpServletResponse response, boolean post, ResourceResolver service)
            throws IOException {
        String op = String.valueOf(r.getParameter("op"));
        SiteRail rail = new SiteRail(r.getResourceResolver(), service);
        try {
            Object out = post ? write(op, r, rail) : read(op, r, rail);
            if (out == null) {
                send(response, 404, error("Unknown op " + op));
            } else {
                send(response, 200, out);
            }
        } catch (IllegalArgumentException e) {
            revert(r, service);
            send(response, 400, error(e.getMessage()));
        } catch (SecurityException e) {
            revert(r, service);
            send(response, 403, error(e.getMessage()));
        } catch (WCMException | PersistenceException | RepositoryException | RuntimeException e) {
            revert(r, service);
            LOG.warn("Site rail op {} failed", op, e);
            send(response, 500, error(e.getMessage()));
        }
    }

    private static Object read(String op, SlingHttpServletRequest r, SiteRail rail) throws WCMException, RepositoryException {
        switch (op) {
            case "timeline": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("events", rail.timeline(r.getParameter("path")));
                out.put("lockOwner", rail.lockOwner(r.getParameter("path")));
                return out;
            }
            case "site":
                return rail.site(r.getParameter("path"));
            case "templates":
                return Collections.singletonMap("templates", rail.templatesUsed(path(r)));
            case "search":
                return rail.search(criteria(r));
            case "versionsInside":
                return Collections.singletonMap("pages", versions(r).versionsInside(r.getParameter("path")));
            case "treeAt":
                return versions(r).treeAt(r.getParameter("path"), time(r.getParameter("date")));
            case "diffVersion":
                return new VersionCompare(r.getResourceResolver()).diff(r.getParameter("path"), r.getParameter("from"), r.getParameter("to"));
            case "searches":
                return Collections.singletonMap("searches", searches(r).list());
            case "searchForm":
                return SiteSearchForms.read(r.getResourceResolver());
            case "propertyValues": {
                int limit = 50;
                try {
                    limit = Math.max(1, Math.min(200, Integer.parseInt(String.valueOf(r.getParameter("limit")))));
                } catch (NumberFormatException e) {
                    // the default
                }
                return SiteSearchForms.values(r.getResourceResolver(), path(r), r.getParameter("property"), limit);
            }
            default:
                return null;
        }
    }

    private static void revert(SlingHttpServletRequest r, ResourceResolver service) {
        r.getResourceResolver().revert();
        if (service != null) {
            service.revert();
        }
    }

    private static SavedSearches searches(SlingHttpServletRequest r) {
        Object store = r.getAttribute(STORE);
        return new SavedSearches(r.getResourceResolver(), store instanceof ResourceResolver ? (ResourceResolver) store : null);
    }

    private static VersionRestore versions(SlingHttpServletRequest r) {
        Object store = r.getAttribute(STORE);
        return new VersionRestore(r.getResourceResolver(), store instanceof ResourceResolver ? (ResourceResolver) store : null);
    }

    /** An ISO-8601 time ({@code 2026-10-17T18:30:00Z}, with or without seconds or zone: UTC) or milliseconds; now if empty. */
    static long time(String s) {
        if (s == null || s.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        String t = s.trim();
        try {
            if (t.matches("\\d+")) {
                return Long.parseLong(t);
            }
            try {
                return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli();
            } catch (java.time.format.DateTimeParseException e) {
                return java.time.LocalDateTime.parse(t).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a date: " + s);
        }
    }

    static SiteRail.Criteria criteria(SlingHttpServletRequest r) {
        SiteRail.Criteria c = new SiteRail.Criteria();
        c.root = path(r);
        c.text = r.getParameter("q");
        c.template = r.getParameter("template");
        String[] tags = r.getParameterValues("tag");
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.trim().isEmpty()) {
                    c.tags.add(t.trim());
                }
            }
        }
        String modified = r.getParameter("modified");
        try {
            c.modifiedDays = modified == null || modified.isEmpty() ? 0 : Integer.parseInt(modified);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number of days: " + modified);
        }
        c.liveCopy = r.getParameter("livecopy");
        c.locked = "true".equals(r.getParameter("locked"));
        for (String m : values(r, "meta")) {
            int eq = m.indexOf('=');
            if (eq > 0 && eq < m.length() - 1) {
                c.meta.computeIfAbsent(m.substring(0, eq), k -> new java.util.LinkedHashSet<>()).add(m.substring(eq + 1));
            }
        }
        for (String m : values(r, "metaText")) {
            int eq = m.indexOf('=');
            if (eq > 0 && eq < m.length() - 1) {
                c.metaText.put(m.substring(0, eq), m.substring(eq + 1));
            }
        }
        return c;
    }

    private static Object write(String op, SlingHttpServletRequest r, SiteRail rail)
            throws WCMException, PersistenceException, RepositoryException {
        switch (op) {
            case "comment":
                return rail.addComment(r.getParameter("path"), r.getParameter("message"));
            case "deleteComment":
                rail.deleteComment(r.getParameter("id"));
                return Collections.singletonMap("deleted", r.getParameter("id"));
            case "saveVersion":
                return rail.saveVersion(r.getParameter("path"), r.getParameter("label"), r.getParameter("comment"));
            case "restore": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("path", r.getParameter("path"));
                out.put("status", versions(r).restoreOne(r.getParameter("path"), r.getParameter("version")));
                return out;
            }
            case "restoreVersions": {
                Map<String, String> choices = new LinkedHashMap<>();
                String[] values = r.getParameterValues("restore");
                for (String v : values == null ? new String[0] : values) {
                    int bar = v.lastIndexOf('|');
                    if (bar <= 0) {
                        throw new IllegalArgumentException("restore=<page>|<version>, not " + v);
                    }
                    choices.put(v.substring(0, bar), v.substring(bar + 1));
                }
                if (choices.isEmpty()) {
                    throw new IllegalArgumentException("Choose the pages to restore");
                }
                return Collections.singletonMap("results", versions(r).restoreVersions(choices));
            }
            case "restoreTree":
                return versions(r).restoreTree(r.getParameter("path"), time(r.getParameter("date")),
                        !"false".equals(r.getParameter("preserve")));
            case "saveSearch":
                return searches(r).save(r.getParameter("name"), r.getParameter("criteria"));
            case "saveSearchForm":
                return SiteSearchForms.save(r.getResourceResolver(), r.getParameter("facets"));
            case "resetSearchForm":
                return SiteSearchForms.reset(r.getResourceResolver());
            case "deleteSearch":
                searches(r).delete(r.getParameter("name"));
                return Collections.singletonMap("deleted", r.getParameter("name"));
            case "forgetDeleted":
                versions(r).forgetDeleted(r.getParameter("path"));
                return Collections.singletonMap("forgotten", r.getParameter("path"));
            case "delete": {
                String[] paths = r.getParameterValues("path");
                if (paths == null || paths.length == 0) {
                    throw new IllegalArgumentException("Nothing to delete");
                }
                return Collections.singletonMap("versioned", versions(r).delete(java.util.Arrays.asList(paths)));
            }
            default:
                return null;
        }
    }

    private static String[] values(SlingHttpServletRequest r, String name) {
        String[] v = r.getParameterValues(name);
        return v == null ? new String[0] : v;
    }

    private static String path(SlingHttpServletRequest r) {
        String p = r.getParameter("path");
        return p == null || p.isEmpty() ? "/content" : p;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null ? "" : message);
        return m;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }
}
