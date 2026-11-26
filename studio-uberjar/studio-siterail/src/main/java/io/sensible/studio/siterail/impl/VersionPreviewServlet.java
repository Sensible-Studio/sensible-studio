/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.adapter.AdapterManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.engine.SlingRequestProcessor;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /bin/studio/versionpreview.html?path=<page>&version=<id>}: Preview Version. The page is rendered by Sling as it is
 * rendered normally ({@code wcmmode=disabled}), with a resolver in which its content is the version's frozen content.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/versionpreview",
        "sling.servlet.methods=GET"
})
public class VersionPreviewServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(VersionPreviewServlet.class);

    @Reference
    private transient SlingRequestProcessor processor;

    @Reference
    private transient AdapterManager adapters;

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        String path = request.getParameter("path");
        String version = request.getParameter("version");
        Resource page = path == null || !path.startsWith("/content/") ? null : request.getResourceResolver().getResource(path);
        if (page != null && !SiteRail.isPage(page)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Not a page: " + path);
            return;
        }
        if (page == null && !parentReadable(request.getResourceResolver(), path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Not a page: " + path);
            return;
        }
        // a deleted page's version is found through its record, which only the service user reads
        ResourceResolver service = page == null ? service() : null;
        try {
            Node frozen;
            try {
                frozen = VersionPreview.frozenContent(request.getResourceResolver(), service == null ? request.getResourceResolver() : service,
                        path, version);
            } catch (IllegalArgumentException e) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
                return;
            } catch (RepositoryException e) {
                LOG.warn("Could not read version {} of {}", version, path, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                return;
            }
            response.setHeader("Cache-Control", "no-store");
            ResourceResolver overlay = VersionPreview.resolver(request.getResourceResolver(), path, frozen, adapters);
            String res = request.getParameter("res");
            if (res != null) {
                // a resource of the page as it was (its images): served through the same overlay
                String r = VersionPreview.Overlay.unmangle(res);
                if (!r.startsWith(path + "/jcr:content/")) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not inside " + path + ": " + res);
                    return;
                }
                processor.processRequest(new PageRequest(request, r), response, overlay);
                return;
            }
            Capture rendered = new Capture(response);
            processor.processRequest(new PageRequest(request, path + ".html"), rendered, overlay);
            rendered.writeTo(response, html -> rewrite(html, path, version, request.getContextPath(),
                    request.getResourceResolver().map(request, path)));
        } finally {
            if (service != null) {
                service.close();
            }
        }
    }

    /**
     * The page's own resources in its HTML ({@code <page>/_jcr_content/…}: its images, mostly) point back into the
     * preview, so they are the version's too, and are found when the page was deleted. {@code {.width}} placeholders
     * of responsive images are kept as they are, so the page scripts can still fill them in.
     */
    static String rewrite(String html, String path, String version, String contextPath, String mapped) {
        String raw = contextPath + path;
        String prefixes = Pattern.quote(raw) + (mapped == null || mapped.equals(raw) ? "" : "|" + Pattern.quote(mapped));
        Pattern own = Pattern.compile("(?<=[\"'\\s,(=])(?:https?://[^/\"'\\s]+)?(?:" + prefixes + ")/(?:_jcr_content|jcr:content)(/[^\"'\\s,()<>&]*)");
        String base = contextPath + "/bin/studio/versionpreview.html?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) + "&amp;version="
                + URLEncoder.encode(version, StandardCharsets.UTF_8) + "&amp;res=";
        Matcher m = own.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 1024);
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(base + keepPlaceholders(path + "/jcr:content" + percentDecoded(m.group(1)))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The path as the server sees it ({@code a%20b} is {@code a b}); a {@code +} stays a plus in a path. */
    private static String percentDecoded(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                b.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] x = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(x, 0, x.length);
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Percent-encodes a query value, except for what a path and a {@code {.width}} placeholder need. */
    private static String keepPlaceholders(String value) {
        StringBuilder b = new StringBuilder();
        for (byte x : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (x & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "/._-~:{}".indexOf(c) >= 0)) {
                b.append(c);
            } else {
                b.append('%').append(String.format("%02X", x & 0xff));
            }
        }
        return b.toString();
    }

    /** The rendered page, kept so it can be rewritten before it is sent. */
    static final class Capture extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private PrintWriter writer;
        private ServletOutputStream stream;
        private String charset;

        Capture(HttpServletResponse response) {
            super(response);
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                charset = getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding();
                writer = new PrintWriter(new OutputStreamWriter(bytes, charset));
            }
            return writer;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (stream == null) {
                stream = new ServletOutputStream() {
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
                    public void setWriteListener(javax.servlet.WriteListener listener) {
                        // written synchronously
                    }
                };
            }
            return stream;
        }

        @Override
        public void setContentLength(int len) {
            // the length changes with the rewrite
        }

        @Override
        public void setContentLengthLong(long len) {
            // the length changes with the rewrite
        }

        @Override
        public void setHeader(String name, String value) {
            if (!"Content-Length".equalsIgnoreCase(name)) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!"Content-Length".equalsIgnoreCase(name)) {
                super.addHeader(name, value);
            }
        }

        @Override
        public void flushBuffer() {
            // sent once rewritten
        }

        @Override
        public void resetBuffer() {
            bytes.reset();
            writer = null;
            stream = null;
            super.resetBuffer();
        }

        @Override
        public void reset() {
            bytes.reset();
            writer = null;
            stream = null;
            super.reset();
        }

        void writeTo(HttpServletResponse response, java.util.function.UnaryOperator<String> html) throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (bytes.size() == 0) {
                return;
            }
            String type = getContentType();
            byte[] body = bytes.toByteArray();
            if (type != null && type.startsWith("text/html")) {
                String cs = charset != null ? charset : getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding();
                body = html.apply(new String(body, cs)).getBytes(cs);
            }
            response.getOutputStream().write(body);
        }
    }

    /** A deleted page can be previewed by whoever can read where it was (its nearest existing ancestor). */
    static boolean parentReadable(ResourceResolver resolver, String path) {
        if (path == null) {
            return false;
        }
        for (String p = path.substring(0, Math.max(0, path.lastIndexOf('/'))); p.lastIndexOf('/') > 0; p = p.substring(0, p.lastIndexOf('/'))) {
            if (resolver.getResource(p) != null) {
                return true;
            }
        }
        return false;
    }

    private ResourceResolver service() {
        try {
            return resolverFactory.getServiceResourceResolver(java.util.Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SiteRailServlet.SUBSERVICE));
        } catch (org.apache.sling.api.resource.LoginException e) {
            return null;
        }
    }

    /** A plain GET of the page, without this request's attributes and parameters. */
    static final class PageRequest extends HttpServletRequestWrapper {

        private final String uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private final Map<String, String[]> parameters = Collections.singletonMap("wcmmode", new String[] {"disabled"});

        PageRequest(HttpServletRequest request, String uri) {
            super(request);
            this.uri = uri;
        }

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public String getRequestURI() {
            return getContextPath() + uri;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            url.append(getScheme()).append("://").append(getServerName()).append(':').append(getServerPort()).append(getRequestURI());
            return url;
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public String getPathInfo() {
            return uri;
        }

        @Override
        public String getPathTranslated() {
            return null;
        }

        @Override
        public String getQueryString() {
            return "wcmmode=disabled";
        }

        @Override
        public String getParameter(String name) {
            String[] v = parameters.get(name);
            return v == null ? null : v[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.enumeration(attributes.keySet());
        }

        @Override
        public void setAttribute(String name, Object o) {
            if (o == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, o);
            }
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
        }
    }
}
