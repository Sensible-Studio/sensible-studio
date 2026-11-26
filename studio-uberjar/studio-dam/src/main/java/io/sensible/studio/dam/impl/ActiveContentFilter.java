/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.Locale;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

/**
 * DAM binaries that a browser could run (SVG, HTML, XML) are served sandboxed: an SVG with a
 * script opened from {@code /content/dam/...} must not run in Sensible Studio's origin, where the author is
 * logged in. {@code Content-Security-Policy: sandbox} gives it a unique origin and no scripts;
 * {@code nosniff} stops the browser from guessing other active types. Images still display.
 */
@Component(service = Filter.class, property = {
        "sling.filter.scope=REQUEST",
        "sling.filter.pattern=/content/dam/.*",
        "sling.filter.methods=GET",
        "sling.filter.methods=HEAD",
        "service.ranking:Integer=-900"})
public class ActiveContentFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest && res instanceof SlingHttpServletResponse) {
            Resource r = ((SlingHttpServletRequest) req).getResource();
            if (active(mimeOf(r))) {
                SlingHttpServletResponse response = (SlingHttpServletResponse) res;
                response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'");
                response.setHeader("X-Content-Type-Options", "nosniff");
            }
        }
        chain.doFilter(req, res);
    }

    static String mimeOf(Resource r) {
        if (r == null) {
            return null;
        }
        Resource c = "jcr:content".equals(r.getName()) ? r : r.getChild("jcr:content");
        String mime = c == null ? null : c.getValueMap().get("jcr:mimeType", String.class);
        if (mime == null && c != null) {
            // an asset (its own URL now answers with the original): the original's type, else the metadata's
            Resource original = c.getChild("renditions/original/jcr:content");
            mime = original != null ? original.getValueMap().get("jcr:mimeType", String.class) : null;
            Resource metadata = mime == null ? c.getChild("metadata") : null;
            mime = metadata != null ? metadata.getValueMap().get("dc:format", String.class) : mime;
        }
        return mime;
    }

    static boolean active(String mime) {
        if (mime == null) {
            return false;
        }
        String m = mime.toLowerCase(Locale.ROOT);
        return m.contains("svg") || m.contains("html") || m.contains("xml") || m.contains("javascript");
    }

    @Override
    public void destroy() {
    }
}
