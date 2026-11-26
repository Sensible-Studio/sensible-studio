/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Serves the author UI app (the {@code studio-editor} package, which also contains the Assets
 * screens) at AEM's Assets URLs:
 * <pre>
 * /assets.html/content/dam/...          folder browser
 * /assetdetails.html/content/dam/...    one asset
 * /mnt/overlay/dam/gui/content/assets/assetedit.html/content/dam/...   the image editor
 * /mnt/overlay/dam/gui/content/processingprofilepage.html/conf/global/settings/dam/processing
 *                                       Tools &gt; Assets &gt; Processing Profiles
 * /mnt/overlay/dam/gui/content/metadataschemaeditor/schemalist.html/conf/...   Metadata Schemas
 * /mnt/overlay/dam/gui/content/collections.html/content/dam/collections                the collections
 * /mnt/overlay/dam/gui/content/collections/collectiondetails.html/content/dam/collections/...   one collection
 * /mnt/overlay/dam/gui/content/reports/reportspage.html/var/studio/dam/reports[/ID]   Asset Reports, one report
 * /mnt/overlay/dam/gui/content/smarttags.html/conf/global/settings/dam/smarttags   Smart Tags
 * </pre>
 * The app reads the path from the URL and talks to {@link DamApiServlet}.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/assets",
        "sling.servlet.paths=/assetdetails",
        "sling.servlet.paths=" + AssetsPageServlet.EDITOR,
        "sling.servlet.paths=" + AssetsPageServlet.PROFILES,
        "sling.servlet.paths=" + AssetsPageServlet.COLLECTIONS,
        "sling.servlet.paths=" + AssetsPageServlet.SCHEMAS,
        "sling.servlet.paths=" + AssetsPageServlet.FOLDER_SCHEMAS,
        "sling.servlet.paths=" + AssetsPageServlet.METADATA_PROFILES,
        "sling.servlet.paths=" + AssetsPageServlet.SHARED_LINKS,
        "sling.servlet.paths=" + AssetsPageServlet.SEARCH_FORMS,
        "sling.servlet.paths=" + AssetsPageServlet.COLLECTION,
        "sling.servlet.paths=" + AssetsPageServlet.REPORTS,
        "sling.servlet.paths=" + AssetsPageServlet.SMARTTAGS,
        "sling.servlet.paths=" + AssetsPageServlet.IMAGEPRESETS,
        "sling.servlet.paths=" + AssetsPageServlet.IMAGEPROFILES,
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/list",
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/editors/uploadrestrictions",
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/editors/digitalrightsmanagement",
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/editors/watermark",
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/editors/assetsomnisearch",
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/editors/assetduplicationdetector",
        "sling.servlet.paths=" + AssetsPageServlet.ASSETS_CONFIG + "/editors/linkshare",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=HEAD"})
public class AssetsPageServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    /**
     * The shared author UI app (editor, Start, Sites, Assets): one build, the studio-editor package.
     * (Until 20.10.2026 a second build at /libs/studio/assets/app was preferred when present; two
     * packages on that path overwrote each other, so the console now always comes from here.)
     */
    static final String APP = "/libs/studio/editor/app";
    static final String EDITOR = "/mnt/overlay/dam/gui/content/assets/assetedit";
    static final String PROFILES = "/mnt/overlay/dam/gui/content/processingprofilepage";
    static final String COLLECTIONS = "/mnt/overlay/dam/gui/content/collections";
    static final String SCHEMAS = "/mnt/overlay/dam/gui/content/metadataschemaeditor/schemalist";
    static final String FOLDER_SCHEMAS = "/mnt/overlay/dam/gui/content/foldermetadataschemaeditor/schemalist";
    static final String METADATA_PROFILES = "/mnt/overlay/dam/gui/content/processingprofilepage/metadataprofiles";
    /** Navigation › Assets › Shared Links: the links the user shared (Sensible Studio's own page). */
    static final String SHARED_LINKS = "/mnt/overlay/dam/gui/content/sharedlinks";
    /** Tools › General › Search Forms: the filters of the Media search ({@link DamSearchForms}). */
    static final String SEARCH_FORMS = "/libs/cq/core/content/tools/customsearch";
    static final String COLLECTION = "/mnt/overlay/dam/gui/content/collections/collectiondetails";
    static final String REPORTS = "/mnt/overlay/dam/gui/content/reports/reportspage";
    static final String SMARTTAGS = "/mnt/overlay/dam/gui/content/smarttags";
    /** Dynamic Media image presets (Tools › Assets › Image Presets). */
    static final String IMAGEPRESETS = "/mnt/overlay/dam/gui/content/s7dam/presets/imagepresets";
    /** Image profiles: Smart Crop ratios a folder applies. */
    static final String IMAGEPROFILES = "/mnt/overlay/dam/gui/content/imageprofiles";
    /** Tools › Assets › Assets Configurations: the list and one form per setting ({@link DamAssetsConfig}). */
    static final String ASSETS_CONFIG = "/mnt/overlay/dam/gui/content/assetsconfigurations";
    static final String LOGIN = "/system/sling/form/login";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if ("anonymous".equals(request.getResourceResolver().getUserID())) {
            String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect(request.getContextPath() + LOGIN + "?resource="
                    + URLEncoder.encode(target, StandardCharsets.UTF_8.name()));
            return;
        }
        String suffix = request.getRequestPathInfo().getSuffix();
        // Assets Configurations: AEM's addresses have no suffix (the list and one form per setting)
        boolean noSuffix = request.getRequestPathInfo().getResourcePath().startsWith(ASSETS_CONFIG + "/");
        if (suffix == null && !noSuffix) {
            // /assets.html → the DAM root
            String page = request.getRequestPathInfo().getResourcePath();
            String root = PROFILES.equals(page) ? DamProfiles.ROOT : SCHEMAS.equals(page) ? DamSchemas.ROOT
                    : COLLECTIONS.equals(page) ? DamCollections.ROOT : REPORTS.equals(page) ? DamReports.ROOT : SMARTTAGS.equals(page) ? DamSmartTags.CONF
                    : IMAGEPRESETS.equals(page) ? DynamicMedia.ROOT : IMAGEPROFILES.equals(page) ? DamSmartCrop.ROOT
                    : SHARED_LINKS.equals(page) ? DamLinkShare.ROOT
                    : SEARCH_FORMS.equals(page) ? DamSearchForms.STORE.substring(0, DamSearchForms.STORE.lastIndexOf('/')) : DamItems.ROOT;
            response.sendRedirect(request.getContextPath() + (COLLECTION.equals(page) ? COLLECTIONS : page) + ".html" + root);
            return;
        }
        Resource index = request.getResourceResolver().getResource(APP + "/index.html");
        InputStream in = index == null ? null : index.adaptTo(InputStream.class);
        if (in == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "The Sensible Studio author UI is not installed (" + APP + "/index.html). Install the studio-editor content package.");
            return;
        }
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        try (InputStream body = in; OutputStream out = response.getOutputStream()) {
            body.transferTo(out);
        }
    }
}
