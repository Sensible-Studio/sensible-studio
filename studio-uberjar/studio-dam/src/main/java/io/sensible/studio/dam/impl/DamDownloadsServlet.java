/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Downloads prepared in the background ({@link DamDownloads}):
 * <pre>
 * GET  /bin/studio/dam/downloads.json                 the user's downloads, newest first
 * GET  /bin/studio/dam/downloads.zip?id=ID            the archive of a ready one
 * POST /bin/studio/dam/downloads.json  op=create  path (repeated), renditions, separate   (X-Studio-CSRF)
 * POST /bin/studio/dam/downloads.json  op=delete  id
 * </pre>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/downloads",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class DamDownloadsServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient DamDownloads downloads;

    public DamDownloadsServlet() {
    }

    DamDownloadsServlet(DamDownloads downloads) {
        this.downloads = downloads;
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getResourceResolver().getUserID();
        try {
            if ("zip".equals(request.getRequestPathInfo().getExtension())) {
                DamDownloads.Archive a = downloads.archive(user, param(request, "id"));
                if (a == null) {
                    json(response, 404, error("No such download, or it is not ready"));
                    return;
                }
                response.setContentType("application/zip");
                response.setHeader("Content-Disposition", "attachment; filename=\"" + a.name().replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
                response.setHeader("X-Content-Type-Options", "nosniff");
                a.copyTo(response.getOutputStream());
                return;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("downloads", downloads.list(user));
            json(response, 200, out);
        } catch (LoginException e) {
            json(response, 503, error("Downloads are not available: " + e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!"1".equals(request.getHeader(DamApiServlet.CSRF_HEADER))) {
            json(response, 403, error("missing " + DamApiServlet.CSRF_HEADER + " header"));
            return;
        }
        ResourceResolver resolver = request.getResourceResolver();
        String user = resolver.getUserID();
        String op = param(request, "op");
        try {
            if ("delete".equals(op)) {
                downloads.remove(user, param(request, "id"));
                json(response, 200, Collections.singletonMap("deleted", param(request, "id")));
                return;
            }
            if (!"create".equals(op)) {
                json(response, 400, error("unknown op"));
                return;
            }
            List<Resource> roots = new ArrayList<>();
            RequestParameter[] ps = request.getRequestParameters("path");
            if (ps != null) {
                for (RequestParameter p : ps) {
                    String path = p.getString();
                    Resource r = DamItems.inDam(path) && !path.contains("..") ? resolver.getResource(path) : null;
                    if (r == null || !(DamItems.isAsset(r) || DamItems.isFolder(r))) {
                        json(response, 404, error("not found: " + path));
                        return;
                    }
                    roots.add(r);
                }
            }
            if (roots.isEmpty()) {
                json(response, 400, error("nothing to download"));
                return;
            }
            boolean renditions = "true".equals(param(request, "renditions"));
            boolean separate = renditions || "true".equals(param(request, "separate"));
            // the asker's resolver: only what they may read goes in
            java.util.function.Predicate<Resource> unlicensed = AssetDownloadServlet.notAgreed(request);
            List<String[]> files = AssetDownloadServlet.collect(roots, true, renditions, separate, DamDownloads.MAX_FILES, unlicensed);
            if (files == null) {
                json(response, 413, error("more than " + DamDownloads.MAX_FILES + " files; download smaller folders"));
                return;
            }
            if (files.isEmpty() && unlicensed != null && !AssetDownloadServlet.collect(roots, true, renditions, separate, DamDownloads.MAX_FILES, null).isEmpty()) {
                json(response, 403, error("Agree to the license of the files first (DRM)"));
                return;
            }
            if (files.isEmpty()) {
                json(response, 422, error("There are no files to download there"));
                return;
            }
            String name = roots.size() == 1 ? roots.get(0).getName().replaceAll("\\.[^.]*$", "") + ".zip" : "assets.zip";
            json(response, 200, downloads.start(user, name, files));
        } catch (IllegalArgumentException e) {
            json(response, 404, error(e.getMessage()));
        } catch (LoginException e) {
            json(response, 503, error("Downloads are not available: " + e.getMessage()));
        }
    }

    private static String param(SlingHttpServletRequest request, String name) {
        RequestParameter p = request.getRequestParameter(name);
        return p == null ? null : p.getString();
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void json(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonOut.write(body));
    }
}
