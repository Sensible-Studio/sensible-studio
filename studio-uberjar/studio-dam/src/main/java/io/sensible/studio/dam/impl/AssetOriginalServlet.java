/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * An asset's own URL, {@code /content/dam/wknd/surf.jpg}, answers with its original, as in AEM: a
 * download link or an image that points at the asset works. Without this, Sling's default servlet
 * took the asset for a folder (a redirect to {@code …/surf.jpg/}, then 403).
 *
 * <p>The original rendition is streamed by Sling's own file handling, with its type, length, date
 * and ranges. Read access is the reader's own: what a closed user group hides stays hidden. A
 * request with an extension or selectors ({@code surf.jpg.json}, a thumbnail) is not the asset's
 * URL and goes on to whatever handles it otherwise.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=dam:Asset",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class AssetOriginalServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    /** A resource type no servlet registers: the request lands with Sling's default servlets. */
    static final String DEFAULT_HANDLING = "studio/dam/asset/node";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        RequestPathInfo info = request.getRequestPathInfo();
        Resource asset = request.getResource();
        if (info.getExtension() != null || info.getSelectorString() != null || info.getSuffix() != null) {
            RequestDispatcherOptions options = new RequestDispatcherOptions();
            options.setForceResourceType(DEFAULT_HANDLING);
            forward(request, response, asset, options);
            return;
        }
        Resource original = original(asset);
        if (original == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "The asset has no original");
            return;
        }
        forward(request, response, original, null);
    }

    private static void forward(SlingHttpServletRequest request, SlingHttpServletResponse response, Resource target,
            RequestDispatcherOptions options) throws ServletException, IOException {
        RequestDispatcher dispatcher = options == null ? request.getRequestDispatcher(target) : request.getRequestDispatcher(target, options);
        if (dispatcher == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        dispatcher.forward(request, response);
    }

    /** The asset's original rendition, a file, if it has one. */
    static Resource original(Resource asset) {
        Resource original = asset == null ? null : asset.getChild("jcr:content/renditions/original");
        return original != null && original.getChild("jcr:content") != null ? original : null;
    }
}
