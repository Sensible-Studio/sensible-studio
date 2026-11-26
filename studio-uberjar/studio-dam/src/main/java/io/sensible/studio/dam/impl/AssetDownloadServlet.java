/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * The Download dialog of the Assets console: a ZIP of the chosen assets and folders.
 *
 * <pre>
 * GET /bin/studio/dam/download.zip?path=...&amp;path=...[&amp;renditions=true][&amp;separate=true]
 * </pre>
 * Originals always; with {@code renditions} the renditions too. Folders keep their structure.
 * With {@code separate} (or with renditions) each asset gets its own folder, as AEM's "Create
 * separate folder for each asset". Read access is the requesting user's.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/download",
        "sling.servlet.extensions=zip",
        "sling.servlet.methods=GET"})
public class AssetDownloadServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    /** Files per archive at most. */
    static final int MAX_FILES = 5000;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        List<Resource> roots = new ArrayList<>();
        RequestParameter[] ps = request.getRequestParameters("path");
        if (ps != null) {
            for (RequestParameter p : ps) {
                String path = p.getString();
                Resource r = DamItems.inDam(path) && !path.contains("..") ? resolver.getResource(path) : null;
                if (r == null || !(DamItems.isAsset(r) || DamItems.isFolder(r))) {
                    send(response, HttpServletResponse.SC_NOT_FOUND, "not found: " + path);
                    return;
                }
                roots.add(r);
            }
        }
        if (roots.isEmpty()) {
            send(response, HttpServletResponse.SC_BAD_REQUEST, "nothing to download");
            return;
        }
        boolean renditions = "true".equals(param(request, "renditions"));
        boolean separate = renditions || "true".equals(param(request, "separate"));

        java.util.function.Predicate<Resource> unlicensed = notAgreed(request);
        List<String[]> files = collect(roots, true, renditions, separate, MAX_FILES, unlicensed);
        if (files == null) {
            send(response, 413, "more than " + MAX_FILES + " files; download smaller folders");
            return;
        }
        if (files.isEmpty() && unlicensed != null) {
            send(response, HttpServletResponse.SC_FORBIDDEN, "Agree to the license of the files first (DRM)");
            return;
        }
        String zipName = roots.size() == 1 ? roots.get(0).getName().replaceAll("\\.[^.]*$", "") + ".zip" : "assets.zip";
        writeZip(resolver, files, zipName, response);
    }

    /**
     * Entries of the archive as {zip entry name, path of the binary}, collected first so a request
     * that is too large is refused before any byte is sent; null when over {@link #MAX_FILES}.
     */
    static List<String[]> collect(List<Resource> roots, boolean renditions, boolean separate) {
        return collect(roots, renditions, separate, MAX_FILES);
    }

    /** {@link #collect(List, boolean, boolean)} with another limit (null above {@code max} files). */
    static List<String[]> collect(List<Resource> roots, boolean renditions, boolean separate, int max) {
        return collect(roots, true, renditions, separate, max, null);
    }

    /**
     * The files of the roots: originals and/or renditions; an asset {@code skip} says yes to is left out
     * (DRM: a license not agreed to). Null above {@code max} files.
     */
    static List<String[]> collect(List<Resource> roots, boolean originals, boolean renditions, boolean separate, int max,
            java.util.function.Predicate<Resource> skip) {
        List<String[]> files = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Resource root : roots) {
            Deque<Object[]> queue = new ArrayDeque<>();
            queue.add(new Object[] {root, ""});
            while (!queue.isEmpty()) {
                Object[] e = queue.poll();
                Resource r = (Resource) e[0];
                String prefix = (String) e[1];
                if (DamItems.isAsset(r)) {
                    if (skip == null || !skip.test(r)) {
                        addAsset(r, prefix, originals, renditions, separate, files, names);
                    }
                } else if (DamItems.isFolder(r)) {
                    String dir = prefix + r.getName() + "/";
                    for (Resource c : r.getChildren()) {
                        if (!"jcr:content".equals(c.getName())) {
                            queue.add(new Object[] {c, dir});
                        }
                    }
                }
                if (files.size() > max) {
                    return null;
                }
            }
        }
        return files;
    }

    /**
     * DRM (Assets Configurations): when it is on, the licensed files whose license was not agreed to in this
     * request ({@code agree} = their paths) are left out; null when DRM is off.
     */
    static java.util.function.Predicate<Resource> notAgreed(SlingHttpServletRequest request) {
        if (!DamAssetsConfig.drmEnabled(request.getResourceResolver())) {
            return null;
        }
        Set<String> agreed = new HashSet<>();
        RequestParameter[] a = request.getRequestParameters("agree");
        if (a != null) {
            for (RequestParameter p : a) {
                agreed.add(p.getString());
            }
        }
        return asset -> !agreed.contains(asset.getPath()) && DamAssetsConfig.license(asset) != null;
    }

    static void writeZip(ResourceResolver resolver, List<String[]> files, String zipName, SlingHttpServletResponse response)
            throws IOException {
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + zipName.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            for (String[] f : files) {
                Resource binary = resolver.getResource(f[1]);
                try (InputStream in = binary == null ? null : binary.adaptTo(InputStream.class)) {
                    if (in == null) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(f[0]));
                    in.transferTo(zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static void addAsset(Resource asset, String prefix, boolean originals, boolean renditions, boolean separate,
            List<String[]> files, Set<String> names) {
        Resource original = asset.getChild("jcr:content/renditions/original");
        String base = separate ? prefix + asset.getName() + "/" : prefix;
        if (original != null && originals) {
            add(files, names, base + asset.getName(), original.getPath());
        }
        if (renditions) {
            Resource rends = asset.getChild("jcr:content/renditions");
            if (rends != null) {
                for (Resource r : rends.getChildren()) {
                    if (!"original".equals(r.getName())) {
                        add(files, names, base + r.getName(), r.getPath());
                    }
                }
            }
        }
    }

    /** Same entry name twice (two folders downloaded flat): the second gets a number. */
    private static void add(List<String[]> files, Set<String> names, String name, String path) {
        String n = name;
        for (int i = 1; !names.add(n); i++) {
            int dot = name.lastIndexOf('.');
            n = dot > name.lastIndexOf('/') ? name.substring(0, dot) + "-" + i + name.substring(dot) : name + "-" + i;
        }
        files.add(new String[] {n, path});
    }

    private static String param(SlingHttpServletRequest request, String name) {
        RequestParameter p = request.getRequestParameter(name);
        return p == null ? null : p.getString();
    }

    private static void send(SlingHttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":" + Json.quote(message) + "}");
    }
}
