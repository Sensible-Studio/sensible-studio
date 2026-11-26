/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Metadata Export: {@code GET /bin/studio/dam/metadata.csv?path=<folder>[&recursive=true][&property=dc:title...]}.
 * The import is {@code op=importMetadata} of {@link DamApiServlet}.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/metadata",
        "sling.servlet.extensions=csv",
        "sling.servlet.methods=GET"})
public class DamCsvServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        RequestParameter pp = request.getRequestParameter("path");
        String path = pp == null ? DamItems.ROOT : pp.getString();
        Resource r = DamItems.inDam(path) ? request.getResourceResolver().getResource(path) : null;
        if (r == null || !(DamItems.isFolder(r) || DamItems.isAsset(r) || DamItems.ROOT.equals(path))) {
            response.sendError(404, "not a DAM folder: " + path);
            return;
        }
        List<String> properties = new ArrayList<>();
        RequestParameter[] ps = request.getRequestParameters("property");
        if (ps != null) {
            for (RequestParameter p : ps) {
                String n = p.getString().trim();
                if (DamCsv.writable(n) && !properties.contains(n)) {
                    properties.add(n);
                }
            }
        }
        RequestParameter rec = request.getRequestParameter("recursive");
        String csv = DamCsv.export(DamCsv.assets(r, rec != null && "true".equals(rec.getString())), properties);
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        String name = (DamItems.ROOT.equals(path) ? "assets" : r.getName()).replaceAll("[^A-Za-z0-9._-]", "_");
        response.setHeader("Content-Disposition", "attachment; filename=\"metadata-" + name + ".csv\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        byte[] bytes = ("﻿" + csv).getBytes(StandardCharsets.UTF_8); // BOM: Excel reads it as UTF-8
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
