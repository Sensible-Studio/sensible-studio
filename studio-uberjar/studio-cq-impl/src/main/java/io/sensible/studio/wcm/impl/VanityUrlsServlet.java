/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * The vanity URLs a visitor may use, for the cache in front of the publish (AEM's
 * {@code /libs/granite/dispatcher/content/vanityUrls.html}, which a dispatcher's {@code /vanity_urls}
 * reads): every {@code sling:vanityPath} below {@code /content} that the caller may read, with the page
 * it leads to. {@code .json}: {@code {"vanityUrls":[{"path","target","redirect","status"}]}}; any
 * other extension: one path a line, as AEM writes it.
 *
 * <p>Read with the caller's own rights, so a page of a closed user group gives an anonymous caller
 * (the cache) no vanity URL to let through.</p>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/libs/granite/dispatcher/content/vanityUrls",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class VanityUrlsServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final String QUERY =
            "SELECT * FROM [nt:base] AS n WHERE n.[sling:vanityPath] IS NOT NULL AND ISDESCENDANTNODE(n, '/content')";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        List<Map<String, Object>> all = vanityUrls(request.getResourceResolver());
        response.setHeader("Cache-Control", "no-store");
        response.setCharacterEncoding("UTF-8");
        if ("json".equals(request.getRequestPathInfo().getExtension())) {
            response.setContentType("application/json");
            StringBuilder out = new StringBuilder("{\"vanityUrls\":[");
            for (int i = 0; i < all.size(); i++) {
                Map<String, Object> v = all.get(i);
                out.append(i == 0 ? "" : ",").append("{\"path\":").append(quote((String) v.get("path")))
                        .append(",\"target\":").append(quote((String) v.get("target")))
                        .append(",\"redirect\":").append(v.get("redirect"))
                        .append(",\"status\":").append(v.get("status")).append('}');
            }
            response.getWriter().write(out.append("]}").toString());
            return;
        }
        response.setContentType("text/plain");
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> v : all) {
            out.append(v.get("path")).append('\n');
        }
        response.getWriter().write(out.toString());
    }

    /** Every vanity path the resolver can read, sorted, the first page that claims one winning. */
    static List<Map<String, Object>> vanityUrls(ResourceResolver resolver) {
        Map<String, Map<String, Object>> byPath = new TreeMap<>();
        Iterator<Resource> found = resolver.findResources(QUERY, "JCR-SQL2");
        while (found.hasNext()) {
            Resource r = found.next();
            ValueMap props = r.getValueMap();
            String[] paths = props.get("sling:vanityPath", String[].class);
            if (paths == null) {
                continue;
            }
            Resource page = "jcr:content".equals(r.getName()) && r.getParent() != null ? r.getParent() : r;
            boolean redirect = props.get("sling:redirect", false);
            int status = props.get("sling:redirectStatus", 302);
            for (String p : paths) {
                String path = normalise(p);
                if (path == null || byPath.containsKey(path)) {
                    continue;
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("path", path);
                v.put("target", page.getPath());
                v.put("redirect", redirect);
                v.put("status", status == 301 || status == 302 || status == 303 || status == 307 || status == 308 ? status : 302);
                byPath.put(path, v);
            }
        }
        return new ArrayList<>(byPath.values());
    }

    /** A vanity path as a request path: with a leading slash, without one at the end; null for one that is no path. */
    static String normalise(String vanity) {
        if (vanity == null) {
            return null;
        }
        String p = vanity.trim();
        if (p.isEmpty() || p.contains("://") || p.contains("..") || p.indexOf(';') >= 0 || p.indexOf('?') >= 0 || p.indexOf('#') >= 0) {
            return null;
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.length() > 1 ? p : null;
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
