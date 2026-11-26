/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;

/**
 * {@code POST /bin/studio/dam/upload} (multipart): {@code folder} = target folder under
 * {@code /content/dam}, {@code file} = the binary. Creates or replaces the asset as the
 * requesting user, so repository ACLs decide who may upload where. Processing starts
 * asynchronously; the response returns immediately with {@code "state":"processing"}.
 *
 * <p>Requires the header {@code X-Studio-CSRF: 1}. A cross-site form cannot send custom
 * headers without a CORS preflight, which makes this a cheap CSRF guard.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/upload",
        "sling.servlet.methods=POST"})
public class AssetUploadServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String CSRF_HEADER = DamApiServlet.CSRF_HEADER;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!"1".equals(request.getHeader(CSRF_HEADER))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "missing " + CSRF_HEADER + " header");
            return;
        }
        ResourceResolver resolver = request.getResourceResolver();
        // Anonymous has no write access anyway; this only gives a clearer answer than 403.
        if ("anonymous".equals(resolver.getUserID())) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "login required");
            return;
        }
        String folder = param(request, "folder");
        RequestParameter file = request.getRequestParameter("file");
        if (folder == null || !(folder.equals("/content/dam") || folder.startsWith("/content/dam/"))
                || folder.contains("..") || folder.contains("//") || file == null || file.isFormField()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "expected 'folder' under /content/dam and a 'file' part");
            return;
        }
        String name = safeName(file.getFileName());
        if (name == null) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "invalid file name");
            return;
        }
        AssetManager manager = resolver.adaptTo(AssetManager.class);
        if (manager == null) {
            error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "AssetManager not available");
            return;
        }
        String mime = mimeFor(name);
        if (mime == null) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "file type not accepted: " + name);
            return;
        }
        if (!DamAssetsConfig.mimeAllowed(DamAssetsConfig.allowedMimeTypes(resolver), mime)) {
            // Assets Configurations › Upload Restrictions
            error(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, name + ": " + mime + " files may not be uploaded (Upload Restrictions)");
            return;
        }
        String path = (folder.endsWith("/") ? folder : folder + "/") + name;
        Resource current = resolver.getResource(path);
        if (current != null && !"rename".equals(param(request, "conflict"))) {
            try {
                DamLocks.checkWritable(resolver, current);
            } catch (DamApiServlet.ApiException e) {
                error(response, e.status, e.getMessage());
                return;
            }
        }
        if (current != null) {
            // Like AEM's upload: the author decides (keep both, replace, or replace keeping a version).
            String conflict = param(request, "conflict");
            if ("rename".equals(conflict)) {
                Resource parent = resolver.getResource(folder);
                if (parent == null) {
                    error(response, HttpServletResponse.SC_NOT_FOUND, "no folder " + folder);
                    return;
                }
                path = parent.getPath() + "/" + DamApiServlet.uniqueName(parent, name);
            } else if ("version".equals(conflict)) {
                try {
                    DamApiServlet.versionBefore(resolver, path, "Before upload of a new version");
                } catch (DamApiServlet.ApiException e) {
                    error(response, e.status, e.getMessage());
                    return;
                }
            } else if (!"replace".equals(conflict)) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"error\":" + Json.quote(name + " already exists") + ",\"conflict\":true,\"path\":"
                        + Json.quote(path) + "}");
                return;
            }
        }
        Asset asset;
        try (InputStream in = file.getInputStream()) {
            // The type comes from the extension, never from the client: a file stored as text/html
            // would run script when opened from the repository (SVG is served sandboxed).
            asset = manager.createAsset(path, in, mime, true);
        }
        if (asset == null) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "cannot create " + path);
            return;
        }
        response.setStatus(HttpServletResponse.SC_CREATED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"path\":" + Json.quote(asset.getPath()) + ",\"state\":\"processing\"}");
    }

    private static final java.util.Map<String, String> TYPES = new java.util.HashMap<>();

    static {
        String[][] t = {
            {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"png", "image/png"}, {"webp", "image/webp"},
            {"gif", "image/gif"}, {"tif", "image/tiff"}, {"tiff", "image/tiff"}, {"avif", "image/avif"},
            {"heic", "image/heic"}, {"psd", "image/vnd.adobe.photoshop"}, {"pdf", "application/pdf"},
            {"mp4", "video/mp4"}, {"mov", "video/quicktime"}, {"webm", "video/webm"}, {"mp3", "audio/mpeg"},
            {"wav", "audio/wav"}, {"zip", "application/zip"},
            {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
            {"pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
            {"woff2", "font/woff2"}, {"txt", "text/plain"}, {"csv", "text/csv"},
            // Served sandboxed (ActiveContentFilter), so a script inside cannot run in Sensible Studio's origin.
            {"svg", "image/svg+xml"},
        };
        for (String[] e : t) {
            TYPES.put(e[0], e[1]);
        }
    }

    /**
     * @return the mime type for an accepted extension, {@code null} otherwise. HTML, XML and
     *         scripts are refused; SVG is accepted because DAM binaries of active types are served
     *         with a sandboxing CSP ({@link ActiveContentFilter}).
     */
    static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /** Base name only, accents removed, anything outside [A-Za-z0-9._-] replaced by '-'. */
    static String safeName(String raw) {
        if (raw == null) {
            return null;
        }
        String base = raw.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).trim();
        base = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        base = base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        base = base.replaceAll("^[-.]+", "");
        if (base.isEmpty() || base.length() > 150) {
            return null;
        }
        return base;
    }

    private static String param(SlingHttpServletRequest request, String name) {
        RequestParameter p = request.getRequestParameter(name);
        return p == null ? null : p.getString();
    }

    private static void error(SlingHttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":" + Json.quote(message) + "}");
    }
}
