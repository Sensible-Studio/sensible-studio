/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Share Link ({@link DamLinkShare}):
 * <pre>
 * POST /bin/studio/dam/linkshare.json  op=create  path=... (repeated), amount + unit (or days), originals, renditions   (logged in, X-Studio-CSRF;
 *      Assets Configurations › Link Share decides who may, what may be chosen and the longest validity)
 * POST /bin/studio/dam/linkshare.json  op=revoke  id=...
 * GET  /bin/studio/dam/linkshare.json  [path=...]   the user's links (without tokens)
 * GET  /linkshare.html?sh=TOKEN                  the page anyone with the link sees
 * GET  /bin/studio/dam/linkshare.zip?sh=TOKEN       everything shared, as one ZIP
 * GET  /bin/studio/dam/linkshare.file?sh=TOKEN&amp;path=ASSET&amp;r=original|thumbnail|web
 * </pre>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/linkshare",
        "sling.servlet.paths=/linkshare",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class LinkShareServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final int MAX_LISTED = 500;

    @Reference
    private transient ResourceResolverFactory factory;

    public LinkShareServlet() {
    }

    LinkShareServlet(ResourceResolverFactory factory) {
        this.factory = factory;
    }

    private ResourceResolver service() throws LoginException {
        return factory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, DamLinkShare.SUBSERVICE));
    }

    // ------------------------------------------------------------------------------------- owner API

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!"1".equals(request.getHeader(DamApiServlet.CSRF_HEADER))) {
            json(response, 403, Collections.singletonMap("error", "missing " + DamApiServlet.CSRF_HEADER + " header"));
            return;
        }
        ResourceResolver user = request.getResourceResolver();
        String owner = user.getUserID();
        if (owner == null || "anonymous".equals(owner)) {
            json(response, 401, Collections.singletonMap("error", "login required"));
            return;
        }
        String op = param(request, "op");
        try (ResourceResolver service = service()) {
            if ("create".equals(op)) {
                List<String> paths = new ArrayList<>();
                RequestParameter[] ps = request.getRequestParameters("path");
                if (ps != null) {
                    for (RequestParameter p : ps) {
                        if (!paths.contains(p.getString())) {
                            paths.add(p.getString());
                        }
                    }
                }
                // Assets Configurations › Link Share: who may share, what may be chosen, for how long
                if (!DamAssetsConfig.mayShare(user)) {
                    throw new DamApiServlet.ApiException(403, "Your groups may not share links (Assets Configurations › Link Share)");
                }
                Map<String, Object> conf = DamAssetsConfig.linkShare(user);
                boolean originals = choice(conf, "Originals", param(request, "originals"));
                boolean renditions = choice(conf, "Renditions", param(request, "renditions"));
                java.util.Calendar expires = expiry(request, conf);
                DamLinkShare.purge(service);
                json(response, 200, DamLinkShare.create(user, service, paths, expires, originals, renditions));
            } else if ("revoke".equals(op)) {
                DamLinkShare.revoke(service, owner, DamAccess.isAdmin(request.getResourceResolver()), param(request, "id"));
                json(response, 200, Collections.singletonMap("revoked", param(request, "id")));
            } else {
                json(response, 400, Collections.singletonMap("error", "unknown op"));
            }
        } catch (DamApiServlet.ApiException e) {
            json(response, e.status, Collections.singletonMap("error", e.getMessage()));
        } catch (LoginException e) {
            json(response, 503, Collections.singletonMap("error", "link sharing is not set up (service user studio-dam-linkshare)"));
        } catch (PersistenceException e) {
            json(response, 500, Collections.singletonMap("error", "cannot save the link: " + e.getMessage()));
        }
    }

    /** What the asker chose, unless the setting is read-only or hidden: then the setting's value. */
    static boolean choice(Map<String, Object> conf, String what, String asked) {
        boolean dflt = Boolean.TRUE.equals(conf.get("include" + what));
        if (!"editable".equals(conf.get(what.toLowerCase(java.util.Locale.ROOT) + "Mode"))) {
            return dflt;
        }
        return asked == null ? dflt : "true".equals(asked);
    }

    /**
     * When the link expires: {@code amount} + {@code unit} (hours, days, months, years), or {@code days}, else the
     * configured validity; at most the longer of 90 days and the configured validity.
     */
    static java.util.Calendar expiry(SlingHttpServletRequest request, Map<String, Object> conf) throws DamApiServlet.ApiException {
        long dfltAmount = ((Number) conf.get("validityAmount")).longValue();
        String dfltUnit = String.valueOf(conf.get("validityUnit"));
        java.util.Calendar max = DamAssetsConfig.expiry(DamLinkShare.MAX_DAYS, "days");
        java.util.Calendar dflt = DamAssetsConfig.expiry(dfltAmount, dfltUnit);
        if (dflt.after(max)) {
            max = dflt;
        }
        java.util.Calendar out;
        String amount = param(request, "amount");
        String days = param(request, "days");
        try {
            if (amount != null) {
                String unit = String.valueOf(param(request, "unit"));
                if (!DamAssetsConfig.UNITS.contains(unit)) {
                    throw new DamApiServlet.ApiException(400, "unit: one of " + DamAssetsConfig.UNITS);
                }
                out = DamAssetsConfig.expiry(Long.parseLong(amount.trim()), unit);
                if (Long.parseLong(amount.trim()) < 1) {
                    throw new DamApiServlet.ApiException(400, "a link is valid for at least one " + unit.replaceAll("s$", ""));
                }
            } else if (days != null) {
                long d = Long.parseLong(days.trim());
                if (d < 1) {
                    throw new DamApiServlet.ApiException(400, "a link is valid for at least one day");
                }
                out = DamAssetsConfig.expiry(d, "days");
            } else {
                out = dflt;
            }
        } catch (NumberFormatException e) {
            throw new DamApiServlet.ApiException(400, "not a number: " + (amount != null ? amount : days));
        }
        if (out.after(max)) {
            throw new DamApiServlet.ApiException(400, "a link is valid for at most " + (max == dflt ? dfltAmount + " " + dfltUnit : DamLinkShare.MAX_DAYS + " days"));
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------- GET

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String ext = request.getRequestPathInfo().getExtension();
        String resourcePath = request.getRequestPathInfo().getResourcePath();
        if ("json".equals(ext) && resourcePath.startsWith("/bin/")) {
            listLinks(request, response);
            return;
        }
        // Everything else is for whoever holds the token: no caching, no referrer leaking it.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (ResourceResolver service = service()) {
            Resource link = DamLinkShare.find(service, param(request, "sh"));
            if (link == null) {
                if ("html".equals(ext)) {
                    page(response, 404, "<h1>This link has expired</h1><p>Ask the person who shared it for a new one.</p>");
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
                return;
            }
            if ("html".equals(ext)) {
                sharePage(request, response, service, link);
            } else if ("zip".equals(ext)) {
                List<Resource> roots = new ArrayList<>();
                for (String p : DamLinkShare.paths(link)) {
                    Resource r = service.getResource(p);
                    if (DamItems.isAsset(r) || DamItems.isFolder(r)) {
                        roots.add(r);
                    }
                }
                boolean renditions = link.getValueMap().get("studio:renditions", false);
                boolean originals = link.getValueMap().get("studio:originals", true);
                List<String[]> files = AssetDownloadServlet.collect(roots, originals, renditions, renditions, AssetDownloadServlet.MAX_FILES, null);
                if (files == null) {
                    response.sendError(413, "too many files for one archive");
                    return;
                }
                AssetDownloadServlet.writeZip(service, files, roots.size() == 1 ? roots.get(0).getName() + ".zip" : "shared.zip", response);
            } else if ("file".equals(ext)) {
                file(request, response, service, link);
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (LoginException e) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "link sharing is not set up");
        }
    }

    private void listLinks(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String owner = request.getResourceResolver().getUserID();
        if (owner == null || "anonymous".equals(owner)) {
            json(response, 401, Collections.singletonMap("error", "login required"));
            return;
        }
        try (ResourceResolver service = service()) {
            json(response, 200, Collections.singletonMap("links", DamLinkShare.list(service, owner, param(request, "path"))));
        } catch (LoginException e) {
            json(response, 503, Collections.singletonMap("error", "link sharing is not set up (service user studio-dam-linkshare)"));
        }
    }

    private static void file(SlingHttpServletRequest request, SlingHttpServletResponse response, ResourceResolver service, Resource link)
            throws IOException {
        String path = param(request, "path");
        Resource asset = DamLinkShare.covers(link, path) ? service.getResource(path) : null;
        if (!DamItems.isAsset(asset)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String which = param(request, "r");
        Resource binary;
        if ("thumbnail".equals(which)) {
            binary = asset.getChild("jcr:content/renditions/" + DamItems.THUMBNAIL);
        } else if ("web".equals(which)) {
            binary = DamItems.webRendition(asset);
        } else if (link.getValueMap().get("studio:originals", true)) {
            binary = asset.getChild("jcr:content/renditions/original");
        } else {
            // a link without the originals gives the largest rendition there is instead
            binary = DamItems.webRendition(asset);
        }
        InputStream in = binary == null ? null : binary.adaptTo(InputStream.class);
        if (in == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Resource c = binary.getChild("jcr:content");
        String mime = c == null ? null : c.getValueMap().get("jcr:mimeType", String.class);
        boolean preview = "thumbnail".equals(which) || "web".equals(which);
        String fileName = asset.getName();
        if (!preview && !"original".equals(binary.getName())) {
            fileName = binary.getName();
        }
        // Previews are images we made; originals always download, so nothing uploaded runs in the page.
        response.setContentType(preview && mime != null && mime.startsWith("image/") ? mime : "application/octet-stream");
        if (!preview) {
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        }
        try (InputStream body = in; OutputStream out = response.getOutputStream()) {
            body.transferTo(out);
        }
    }

    /** The page of a link: the shared assets with previews, one download each, and a ZIP of all. */
    private static void sharePage(SlingHttpServletRequest request, SlingHttpServletResponse response, ResourceResolver service, Resource link)
            throws IOException {
        String token = param(request, "sh");
        List<Map<String, Object>> assets = new ArrayList<>();
        Deque<Resource> queue = new ArrayDeque<>();
        for (String p : DamLinkShare.paths(link)) {
            Resource r = service.getResource(p);
            if (r != null) {
                queue.add(r);
            }
        }
        while (!queue.isEmpty() && assets.size() < MAX_LISTED) {
            Resource r = queue.poll();
            if (DamItems.isAsset(r)) {
                assets.add(DamItems.asset(r));
            } else if (DamItems.isFolder(r)) {
                for (Resource c : r.getChildren()) {
                    if (DamItems.isAsset(c) || DamItems.isFolder(c)) {
                        queue.add(c);
                    }
                }
            }
        }
        String t = esc(java.net.URLEncoder.encode(token, "UTF-8"));
        StringBuilder b = new StringBuilder();
        b.append("<header><h1>Shared assets</h1><p>").append(assets.size()).append(assets.size() == 1 ? " asset" : " assets")
                .append(" · available until ").append(esc(String.valueOf(link.getValueMap().get("studio:expires", java.util.Calendar.class).toInstant())))
                .append("</p><a class=\"btn\" href=\"/bin/studio/dam/linkshare.zip?sh=").append(t).append("\">Download all (ZIP)</a></header><ul>");
        for (Map<String, Object> a : assets) {
            String p = esc(java.net.URLEncoder.encode(String.valueOf(a.get("path")), "UTF-8"));
            b.append("<li>");
            if (a.get("thumbnail") != null) {
                b.append("<img alt=\"\" loading=\"lazy\" src=\"/bin/studio/dam/linkshare.file?sh=").append(t).append("&amp;path=").append(p)
                        .append("&amp;r=thumbnail\">");
            } else {
                b.append("<span class=\"noimg\"></span>");
            }
            b.append("<strong>").append(esc(String.valueOf(a.get("title")))).append("</strong><small>").append(esc(String.valueOf(a.get("name"))))
                    .append("</small><a href=\"/bin/studio/dam/linkshare.file?sh=").append(t).append("&amp;path=").append(p)
                    .append("&amp;r=original\">Download</a></li>");
        }
        b.append("</ul>");
        page(response, 200, b.toString());
    }

    private static void page(SlingHttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'");
        response.setHeader("X-Frame-Options", "DENY");
        response.getWriter().write("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><meta name=\"referrer\" content=\"no-referrer\"><title>Shared assets</title><style>"
                + "body{font:14px/1.4 system-ui,sans-serif;margin:0;background:#f5f5f5;color:#222}header{padding:24px;background:#fff;"
                + "border-bottom:1px solid #ddd}h1{margin:0 0 4px;font-size:22px}.btn{display:inline-block;margin-top:8px;padding:6px 14px;"
                + "border-radius:16px;background:#1473e6;color:#fff;text-decoration:none}ul{list-style:none;margin:0;padding:24px;display:grid;"
                + "grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:16px}li{background:#fff;border-radius:4px;padding:8px;"
                + "display:flex;flex-direction:column;gap:4px}li img,.noimg{width:100%;aspect-ratio:1;object-fit:contain;background:#eee;"
                + "display:block}small{color:#666;word-break:break-all}li a{color:#1473e6}</style></head><body>" + body + "</body></html>");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String param(SlingHttpServletRequest request, String name) {
        RequestParameter p = request.getRequestParameter(name);
        return p == null ? null : p.getString();
    }

    private static void json(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonOut.write(body));
    }
}
