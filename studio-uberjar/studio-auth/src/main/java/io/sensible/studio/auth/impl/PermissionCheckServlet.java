package io.sensible.studio.auth.impl;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Whether the visitor of this request may read a page: what the cache in front of the publish
 * asks before it serves a protected page it keeps (a closed user group's), the way a dispatcher's
 * permission-sensitive caching asks its auth checker. {@code GET|HEAD /bin/studio/permissioncheck?uri=<path>}
 * with the visitor's cookies: 200 when the path resolves to a resource the visitor can read, 403
 * when not (or not at all, so that it does not say what exists), 400 without a path.
 *
 * <p>It answers for the visitor alone, with the visitor's own resolver: it tells nobody anything
 * about a page the caller could not ask for anyway.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/permissioncheck",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=HEAD"
})
public class PermissionCheckServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        answer(request, response);
    }

    @Override
    protected void doHead(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        answer(request, response);
    }

    private void answer(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String path = pathOf(request.getParameter("uri"));
        if (path == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        Resource resource = request.getResourceResolver().resolve(request, path);
        boolean readable = resource != null && !ResourceUtil.isNonExistingResource(resource);
        response.setStatus(readable ? HttpServletResponse.SC_OK : HttpServletResponse.SC_FORBIDDEN);
    }

    /** The path asked about: absolute, without a query or a fragment, no {@code ..}; null when it is not one. */
    static String pathOf(String uri) {
        if (uri == null) {
            return null;
        }
        int end = uri.length();
        for (char c : new char[] {'?', '#'}) {
            int at = uri.indexOf(c);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String path = uri.substring(0, end);
        if (!path.startsWith("/") || path.contains("/../") || path.endsWith("/..") || path.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) < 0x20) {
                return null;
            }
        }
        return path;
    }
}
