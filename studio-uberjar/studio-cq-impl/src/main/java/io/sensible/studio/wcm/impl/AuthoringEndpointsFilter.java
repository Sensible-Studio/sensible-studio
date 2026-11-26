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
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * {@code /bin/studio/…} is what the consoles and the editor talk to. Nobody who is not signed in has
 * business there, on an author or on a publish, apart from the few endpoints visitors of a site use
 * (a form submission, audience tracking, a signed media URL, a shared link). The servlets check
 * rights for what they change, but several answered a read to anyone: the agents with their target
 * URLs, the page tree, the workflow models. This filter refuses all of them in one place, before a
 * servlet is reached. The same for the list of stored GraphQL queries and for storing one
 * ({@code /graphql/list}, {@code /graphql/persist}): a visitor runs a stored query
 * ({@code /graphql/execute}), and needs to see neither all of them nor to write one.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=(/bin/studio|/graphql/list|/graphql/persist)([/.;].*)?",
                "service.ranking:Integer=5000"
        })
@Designate(ocd = AuthoringEndpointsFilter.Config.class)
public class AuthoringEndpointsFilter implements Filter {

    @ObjectClassDefinition(
            name = "Sensible Studio: endpoints open to visitors",
            description = "Everything under /bin/studio needs a signed-in user, except the paths listed here.")
    public @interface Config {
        @AttributeDefinition(
                name = "Open paths",
                description = "Paths under /bin/studio that answer without a sign-in: the path itself, what is below it, and any extension of it.")
        String[] open() default {
                "/bin/studio/forms/submit",
                "/bin/studio/targeting/track",
                "/bin/studio/dam/deliver",
                "/bin/studio/dam/linkshare"
        };
    }

    static final String ROOT = "/bin/studio";
    /** Outside /bin/studio, closed to who is not signed in whatever the open list says. */
    static final String[] CLOSED = {"/graphql/list", "/graphql/persist"};

    private volatile String[] open = new String[0];

    @Activate
    @Modified
    void configure(Config config) {
        open = config.open() == null ? new String[0] : config.open().clone();
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            String user = request.getResourceResolver().getUserID();
            if (refused(request.getPathInfo(), user, open)) {
                HttpServletResponse response = (HttpServletResponse) res;
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"error\":\"sign in\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /** Is this a request of someone not signed in for an endpoint that is not a visitor's? */
    static boolean refused(String path, String user, String[] open) {
        if (path == null) {
            return false;
        }
        boolean closed = false;
        for (String c : CLOSED) {
            closed |= under(path, c);
        }
        if (!closed && !under(path, ROOT)) {
            return false;
        }
        if (user != null && !"anonymous".equals(user)) {
            return false;
        }
        for (String o : open) {
            // an open path must itself be below the root: "/bin" or "/" in the list opens nothing
            if (o != null && o.length() > ROOT.length() && under(o, ROOT) && under(path, o)) {
                return false;
            }
        }
        return true;
    }

    /** {@code path} is {@code base}, something below it, or it with selectors, an extension or path parameters. */
    private static boolean under(String path, String base) {
        if (!path.startsWith(base)) {
            return false;
        }
        if (path.length() == base.length()) {
            return true;
        }
        char next = path.charAt(base.length());
        return next == '/' || next == '.' || next == ';';
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
