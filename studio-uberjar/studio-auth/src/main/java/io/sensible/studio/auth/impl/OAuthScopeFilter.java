package io.sensible.studio.auth.impl;

import java.io.IOException;
import java.util.Set;

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
 * What an OAuth token may do besides what its user may: with the scope {@code read} only GET, HEAD
 * and OPTIONS; anything else needs {@code write} (403 {@code insufficient_scope}). The OAuth clients
 * and tokens themselves are never managed with a token. Requests that did not come with a token are
 * not touched.
 */
@Component(service = Filter.class, property = {
        "sling.filter.scope=REQUEST",
        "service.ranking:Integer=9000",
        "service.description=Sensible Studio: OAuth scopes" })
public final class OAuthScopeFilter implements Filter {

    @Override
    public void init(FilterConfig config) {
        // nothing
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        Object scopes = request.getAttribute(BearerHandler.SCOPES);
        if (scopes instanceof Set) {
            String why = refused(request.getMethod(), request.getRequestURI(), (Set<?>) scopes);
            if (why != null) {
                HttpServletResponse response = (HttpServletResponse) res;
                response.setStatus(403);
                response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"" + OAuth.WRITE + "\"");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"error\":\"insufficient_scope\",\"error_description\":\"" + why + "\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /** Why a request with a token of these scopes may not go on; null if it may. */
    static String refused(String method, String uri, Set<?> scopes) {
        if (uri != null && (uri.startsWith("/bin/studio/oauth") || uri.startsWith("/security/oauth"))) {
            return "OAuth clients and tokens are not managed with a token";
        }
        boolean safe = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (!safe && !scopes.contains(OAuth.WRITE)) {
            return "The token may only read (scope write is needed for " + method + ")";
        }
        return null;
    }

    @Override
    public void destroy() {
        // nothing
    }
}
