/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.caconfig.ConfigurationBuilder;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a site's analytics scripts ({@link AnalyticsConfig}, set in Site configurations) into the head of its
 * pages: on a publish instance always, on the author only when the configuration says so and the page is shown
 * as published ({@code wcmmode=disabled}). A page of a site without the configuration passes through untouched
 * and unbuffered. The data the scripts read is Core Components' data layer ({@code window.adobeDataLayer}),
 * which a site turns on in its Data Layer configuration.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/content/.*",
                "sling.filter.methods=GET",
                "service.ranking:Integer=-1700"
        })
public class AnalyticsFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsFilter.class);

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY)
    private volatile org.apache.sling.settings.SlingSettingsService settings;

    private boolean live() {
        org.apache.sling.settings.SlingSettingsService s = settings;
        return s != null && s.getRunModes().contains("publish");
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        String tags = req instanceof SlingHttpServletRequest && res instanceof HttpServletResponse
                ? tagsFor((SlingHttpServletRequest) req) : "";
        if (tags.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletResponse response = (HttpServletResponse) res;
        Buffer buffer = new Buffer(response);
        chain.doFilter(req, buffer);
        buffer.flushWriter();
        byte[] bytes = buffer.bytes();
        String type = buffer.getContentType();
        if (type != null && type.contains("text/html") && buffer.getStatus() < 300) {
            bytes = inject(new String(bytes, StandardCharsets.UTF_8), tags).getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
        }
        response.getOutputStream().write(bytes);
    }

    private String tagsFor(SlingHttpServletRequest request) {
        if (!"html".equals(request.getRequestPathInfo().getExtension())) {
            return "";
        }
        Resource page = request.getResource();
        String path = page.getPath();
        if (page.getChild("jcr:content") == null || path.startsWith("/content/experience-fragments/")) {
            return "";
        }
        String wcmmode = request.getParameter("wcmmode");
        boolean live = live();
        // on a publish instance wcmmode means nothing; on the author only the page shown as published counts
        if (!live && !"disabled".equals(wcmmode)) {
            return "";
        }
        try {
            ConfigurationBuilder builder = page.adaptTo(ConfigurationBuilder.class);
            AnalyticsConfig config = builder == null ? null : builder.as(AnalyticsConfig.class);
            if (config == null || (!live && !config.onAuthor())) {
                return "";
            }
            return tags(config.scripts(), config.async());
        } catch (RuntimeException e) {
            LOG.warn("Analytics configuration of {}: {}", path, e.getMessage());
            return "";
        }
    }

    /** The script elements for the addresses that are addresses; anything else is left out. */
    static String tags(String[] scripts, boolean async) {
        StringBuilder sb = new StringBuilder();
        for (String s : scripts == null ? new String[0] : scripts) {
            String src = s == null ? "" : s.trim();
            if (!isAddress(src)) {
                continue;
            }
            sb.append("<script src=\"").append(src.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                    .replace(">", "&gt;")).append('"').append(async ? " async" : "").append("></script>\n");
        }
        return sb.toString();
    }

    static boolean isAddress(String src) {
        if (src.isEmpty() || src.chars().anyMatch(c -> c <= ' ')) {
            return false;
        }
        return src.startsWith("https://") || src.startsWith("http://") || src.startsWith("/");
    }

    /** Before the end of the head; a page without one gets them before its body, or first. */
    static String inject(String html, String tags) {
        int at = html.indexOf("</head>");
        if (at < 0) {
            at = html.indexOf("<body");
        }
        return at < 0 ? tags + html : html.substring(0, at) + tags + html.substring(at);
    }

    @Override
    public void destroy() {
        // nothing to release
    }

    /** Captures the page so the scripts can be put into its head. */
    static final class Buffer extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private PrintWriter writer;
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override
            public void write(int b) {
                bytes.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                bytes.write(b, off, len);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // synchronous only
            }
        };

        Buffer(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8));
            }
            return writer;
        }

        @Override
        public void setContentLength(int len) {
            // the length changes
        }

        @Override
        public void setContentLengthLong(long len) {
            // the length changes
        }

        @Override
        public void flushBuffer() {
            flushWriter();
        }

        @Override
        public void resetBuffer() {
            flushWriter();
            bytes.reset();
        }

        void flushWriter() {
            if (writer != null) {
                writer.flush();
            }
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
