/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.ByteArrayOutputStream;
import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sensible.studio.wcm.shown.Shown;

/**
 * On a publish instance, a page's answer says what the page shows besides itself
 * ({@code X-Studio-Depends}: paths, comma separated, {@code ,} and {@code %} escaped), so that the cache
 * in front takes out only the pages that show what was published ({@link Dependencies}): the media and
 * experience fragments its content names (worked out from the content, read with the replication service
 * user: a template's structure is not the visitor's to read), the pages above it, and the pages its
 * rendering showed or listed (recorded while it is rendered, {@link Shown}). For that the answer is kept
 * in memory until the page is rendered, then sent with the header. Only for GET and HEAD of a page or its
 * content, as html or json. When it cannot be worked out — a search or tags decided what the page lists,
 * too many paths — the page says nothing and the cache treats it as showing everything.
 */
@Component(service = Filter.class, property = {"sling.filter.scope=REQUEST", "service.ranking:Integer=-2000"})
public class DependsFilter implements Filter {

    static final String HEADER = "X-Studio-Depends";
    /** The most paths a rendering may record before it counts as showing everything. */
    static final int RECORDED = 2000;
    private static final Logger LOG = LoggerFactory.getLogger(DependsFilter.class);

    @Reference
    private SlingSettingsService settings;

    @Reference
    private AgentManagerImpl agents;

    private volatile boolean publish;

    @Activate
    void activate() {
        publish = settings.getRunModes().contains("publish");
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(publish && request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        SlingHttpServletRequest r = (SlingHttpServletRequest) request;
        String method = r.getMethod();
        String extension = r.getRequestPathInfo().getExtension();
        Resource page = pageOf(r.getResource());
        if (page == null || !("GET".equals(method) || "HEAD".equals(method)) || !("html".equals(extension) || "json".equals(extension))) {
            chain.doFilter(request, response);
            return;
        }
        Set<String> named = null;
        try (org.apache.sling.api.resource.ResourceResolver service = agents.serviceResolver()) {
            Resource seen = service.getResource(page.getPath());
            named = seen == null ? null : Dependencies.of(seen);
        } catch (org.apache.sling.api.resource.LoginException | RuntimeException e) {
            LOG.debug("No dependencies for {}: {}", page.getPath(), e.toString());
        }
        SlingHttpServletResponse real = (SlingHttpServletResponse) response;
        if (named == null) {
            // says nothing: shows anything
            real.setHeader(HEADER, unknown("its content or template cannot be read, or names too much"));
            chain.doFilter(request, response);
            return;
        }
        Buffered buffered = new Buffered(real);
        Set<String> rendered;
        String why;
        // more raw paths than a header holds: what a list covers is left out afterwards (Dependencies.combine)
        try (Shown.Recording recording = Shown.start(RECORDED, page.getPath())) {
            chain.doFilter(request, buffered);
            rendered = recording.isUnknown() ? null : recording.paths();
            why = recording.why();
        }
        if (!real.isCommitted()) {
            Set<String> all = Dependencies.combine(page, named, rendered);
            String value = Dependencies.header(all);
            if (value == null) {
                why = why != null ? why : all == null ? "more than " + Dependencies.MAX_PATHS + " paths" : "more than " + Dependencies.MAX_HEADER + " characters";
                LOG.debug("{} says nothing of what it shows: {}", page.getPath(), why);
                value = unknown(why);
            }
            real.setHeader(HEADER, value);
        }
        buffered.send();
    }

    /**
     * The header of a page that cannot say what it shows: not a path, which the cache reads as saying
     * nothing (so the page goes with every publication), with the reason for whoever asks the publish.
     */
    static String unknown(String why) {
        return "?" + why.replace(",", ";");
    }

    /** The page a request is for: a cq:Page, or its jcr:content. */
    static Resource pageOf(Resource r) {
        if (r == null) {
            return null;
        }
        if ("cq:Page".equals(r.getValueMap().get("jcr:primaryType", String.class))) {
            return r;
        }
        Resource parent = r.getParent();
        if ("jcr:content".equals(r.getName()) && parent != null && "cq:Page".equals(parent.getValueMap().get("jcr:primaryType", String.class))) {
            return parent;
        }
        return null;
    }

    /**
     * An answer kept until the page is rendered, so that a header can still be set: what is written goes
     * to memory, flushing does not commit. Redirects and errors go through at once (they commit the real
     * answer, and what was kept is then dropped).
     */
    static final class Buffered extends SlingHttpServletResponseWrapper {
        private final SlingHttpServletResponse real;
        private CharArrayWriter chars;
        private PrintWriter writer;
        private ByteArrayOutputStream bytes;
        private ServletOutputStream stream;

        Buffered(SlingHttpServletResponse real) {
            super(real);
            this.real = real;
        }

        @Override
        public PrintWriter getWriter() {
            if (stream != null) {
                throw new IllegalStateException("getOutputStream() was called already");
            }
            if (writer == null) {
                chars = new CharArrayWriter(16 * 1024);
                writer = new PrintWriter(chars);
            }
            return writer;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (writer != null) {
                throw new IllegalStateException("getWriter() was called already");
            }
            if (stream == null) {
                bytes = new ByteArrayOutputStream(16 * 1024);
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
                    public void setWriteListener(WriteListener listener) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
            return stream;
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public void setBufferSize(int size) {
            // everything is kept anyway
        }

        @Override
        public void resetBuffer() {
            if (real.isCommitted()) {
                throw new IllegalStateException("committed");
            }
            if (chars != null) {
                writer.flush();
                chars.reset();
            }
            if (bytes != null) {
                bytes.reset();
            }
        }

        @Override
        public void reset() {
            resetBuffer();
            real.reset();
        }

        /** Sends what was kept, unless the real answer went already (a redirect, an error). */
        void send() throws IOException {
            if (real.isCommitted()) {
                return;
            }
            if (writer != null) {
                writer.flush();
                PrintWriter out = real.getWriter();
                chars.writeTo(out);
                out.flush();
            } else if (stream != null) {
                ServletOutputStream out = real.getOutputStream();
                bytes.writeTo(out);
                out.flush();
            }
        }
    }

    @Override
    public void destroy() {
    }
}
