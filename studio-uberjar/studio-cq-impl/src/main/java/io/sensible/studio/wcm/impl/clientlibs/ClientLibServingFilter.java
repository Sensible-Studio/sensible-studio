/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.clientlibs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.adobe.granite.ui.clientlibs.HtmlLibrary;
import com.adobe.granite.ui.clientlibs.HtmlLibraryManager;
import com.adobe.granite.ui.clientlibs.LibraryType;

/**
 * Serves client libraries:
 * <ul>
 *   <li>{@code /etc.clientlibs/<path>[.min].css|js}: built output of a proxied library;</li>
 *   <li>{@code /etc.clientlibs/<path>/<file>}: a static file inside a proxied library
 *       (fonts, images under {@code resources/});</li>
 *   <li>{@code /apps|/libs/<path>[.min].css|js}: built output of a non-proxied library.</li>
 * </ul>
 * Reads go through the clientlibs service user, so anonymous visitors need no access to
 * {@code /apps} or {@code /libs}.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "service.ranking:Integer=10000"
        })
public class ClientLibServingFilter implements Filter {

    @Reference(target = "(component.name=io.sensible.studio.wcm.impl.clientlibs.HtmlLibraryManagerImpl)")
    private HtmlLibraryManager manager;

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        boolean proxy = uri.startsWith(ClientLibraryImpl.PROXY_PREFIX);
        boolean direct = uri.startsWith("/apps/") || uri.startsWith("/libs/");
        if (!proxy && !direct || !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        if (serveLibrary(uri, request, response) || (proxy && serveFile(uri, response))) {
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean serveLibrary(String uri, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LibraryType type = uri.endsWith(".css") ? LibraryType.CSS : uri.endsWith(".js") ? LibraryType.JS : null;
        if (type == null) {
            return false;
        }
        String base = uri.substring(0, uri.length() - type.extension.length());
        if (base.endsWith(".min")) {
            base = base.substring(0, base.length() - 4);
        }
        HtmlLibrary lib = manager.getLibrary(type, base);
        if (lib == null) {
            return false;
        }
        long since = request.getDateHeader("If-Modified-Since");
        if (lib.getLastModified() > 0 && since >= lib.getLastModified() / 1000 * 1000) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return true;
        }
        response.setContentType(type.contentType);
        response.setCharacterEncoding("UTF-8");
        if (lib.getLastModified() > 0) {
            response.setDateHeader("Last-Modified", lib.getLastModified());
        }
        response.setHeader("Cache-Control", "public, max-age=300");
        if (!"HEAD".equals(request.getMethod())) {
            copy(lib.getInputStream(false), response.getOutputStream());
        }
        return true;
    }

    private boolean serveFile(String uri, HttpServletResponse response) throws IOException {
        HtmlLibraryManagerImpl impl = (HtmlLibraryManagerImpl) manager;
        String rest = uri.substring(ClientLibraryImpl.PROXY_PREFIX.length());
        ClientLibraryIndex idx = impl.index();
        for (String root : new String[] {"/apps/", "/libs/"}) {
            String path = root + rest;
            ClientLibraryImpl owner = idx.owning(path);
            if (owner == null || !owner.allowProxy() || owner.getPath().equals(path)) {
                continue;
            }
            Boolean served = impl.withResolver(rr -> {
                Resource file = rr.getResource(path);
                InputStream in = file == null ? null : file.adaptTo(InputStream.class);
                if (in == null) {
                    return false;
                }
                String mime = rr.getResource(path + "/jcr:content") == null ? null
                        : rr.getResource(path + "/jcr:content").getValueMap().get("jcr:mimeType", String.class);
                response.setContentType(mime != null ? mime : guessMime(path));
                response.setHeader("Cache-Control", "public, max-age=3600");
                copy(in, response.getOutputStream());
                return true;
            });
            if (served) {
                return true;
            }
        }
        return false;
    }

    static String guessMime(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (p.endsWith(".woff")) {
            return "font/woff";
        }
        if (p.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (p.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".gif")) {
            return "image/gif";
        }
        if (p.endsWith(".json")) {
            return "application/json";
        }
        if (p.endsWith(".css")) {
            return "text/css";
        }
        if (p.endsWith(".js")) {
            return "application/javascript";
        }
        return "application/octet-stream";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in) {
            i.transferTo(out);
        }
    }

    @Override
    public void destroy() {
        // nothing to do
    }
}
