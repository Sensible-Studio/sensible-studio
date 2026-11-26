/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
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
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds ContextHub to pages that need it beyond their targeted components: a page with audience
 * redirects ({@code audienceRedirects} on its jcr:content) gets the rules and the client script in
 * {@code <head>}, and {@code ?contexthub=ui} (or the {@code studio-contexthub-ui} cookie) gets ContextHub's
 * own bar rendered in the page, like AEM's ContextHub UI in Preview. Everything else passes through
 * untouched.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/content/.*",
                "service.ranking:Integer=-1800"
        })
public class PersonalizationFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(PersonalizationFilter.class);
    static final String SCRIPT = "/libs/studio/contexthub/contexthub.js";
    static final String ATTR_SCRIPT = "studio.contexthub.script";
    static final String UI_PARAM = "contexthub";
    static final String UI_COOKIE = "studio-contexthub-ui";

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof SlingHttpServletRequest) || !(res instanceof HttpServletResponse)) {
            chain.doFilter(req, res);
            return;
        }
        SlingHttpServletRequest request = (SlingHttpServletRequest) req;
        if (!"html".equals(request.getRequestPathInfo().getExtension()) || !"GET".equals(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        Resource page = request.getResource();
        Resource content = page.getChild("jcr:content");
        if (content == null) {
            chain.doFilter(req, res);
            return;
        }
        String uiParam = request.getParameter(UI_PARAM);
        boolean ui = "ui".equals(uiParam) || ("on".equals(uiParam)) || (uiParam == null && hasCookie(request));
        if ("off".equals(uiParam)) {
            ui = false;
        }
        List<Map<String, Object>> redirects = new ArrayList<>();
        try {
            Session session = request.getResourceResolver().adaptTo(Session.class);
            Node jc = session.getNode(content.getPath());
            for (Map<String, Object> r : Targeting.redirects(jc)) {
                Segments.Segment s = Segments.read(request.getResourceResolver().getResource(String.valueOf(r.get("segment"))));
                if (s == null || s.expression == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("segment", r.get("segment"));
                m.put("target", r.get("target"));
                m.put("expression", s.expression);
                m.put("boost", s.boost);
                redirects.add(m);
            }
        } catch (RepositoryException e) {
            LOG.warn("Audience redirects of {}: {}", page.getPath(), e.getMessage());
        }
        boolean authoring = request.getParameter("wcmmode") != null && !"disabled".equals(request.getParameter("wcmmode"));
        if ((!ui && redirects.isEmpty()) || authoring) {
            chain.doFilter(req, res);
            return;
        }
        // the Target component includes the script once per request; here it is included first
        request.setAttribute(ATTR_SCRIPT, Boolean.TRUE);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("page", page.getPath());
        config.put("redirects", redirects);
        config.put("ui", ui);
        config.put("personas", "/bin/studio/targeting.json?op=personas&path=" + page.getPath());
        config.put("contexthub", ContextHubConfig.forClient(ContextHubConfig.effective(request.getResourceResolver(), page.getPath())));
        String head = "<script>window.StudioContextHubConfig=" + Json.write(config).replace("</", "<\\/") + ";</script>\n<script src=\"" + SCRIPT + "\"></script>\n";

        Buffer buffer = new Buffer((HttpServletResponse) res);
        chain.doFilter(req, buffer);
        buffer.flushWriter();
        byte[] bytes = buffer.bytes();
        String type = buffer.getContentType();
        HttpServletResponse response = (HttpServletResponse) res;
        if (type != null && type.contains("text/html") && buffer.getStatus() < 300) {
            String html = new String(bytes, StandardCharsets.UTF_8);
            int at = html.indexOf("</head>");
            if (at < 0) {
                at = html.indexOf("<body");
            }
            String out = at < 0 ? head + html : html.substring(0, at) + head + html.substring(at);
            byte[] data = out.getBytes(StandardCharsets.UTF_8);
            response.setContentLength(data.length);
            response.getOutputStream().write(data);
        } else {
            response.getOutputStream().write(bytes);
        }
    }

    private static boolean hasCookie(SlingHttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        for (javax.servlet.http.Cookie c : request.getCookies()) {
            if (UI_COOKIE.equals(c.getName()) && "1".equals(c.getValue())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // nothing to do
    }

    /** Captures the page so the script can be put into its head. */
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
            // decided after the injection
        }

        @Override
        public void setContentLengthLong(long len) {
            // decided after the injection
        }

        @Override
        public void flushBuffer() {
            // kept until the end
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
