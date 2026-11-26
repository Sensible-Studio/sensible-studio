/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.clientlibs;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.adobe.granite.ui.clientlibs.ClientLibrary;
import com.adobe.granite.ui.clientlibs.HtmlLibraryManager;
import com.adobe.granite.ui.clientlibs.LibraryType;

import io.sensible.studio.wcm.impl.EditorJson;

/**
 * {@code GET /bin/studio/clientlibs?type=js|css}: the client library categories, what Core's
 * {@code clientlibrarycategories} datasource offers in the page policy (Client Libraries fields): every category of
 * every library, with {@code type} only those that have libraries of that type. Sorted (Core's order is undefined).
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/clientlibs",
                "sling.servlet.methods=GET"
        })
public class ClientLibraryCategoriesServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient HtmlLibraryManager libraries;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        LibraryType type = typeOf(request.getParameter("type"));
        TreeSet<String> categories = new TreeSet<>();
        for (ClientLibrary lib : libraries.getLibraries().values()) {
            if (type != null && !hasType(lib, type)) {
                continue;
            }
            for (String c : lib.getCategories()) {
                if (c != null && !c.isEmpty()) {
                    categories.add(c);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", categories);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(out));
    }

    /** Whether the library serves {@code type} (its own files or what it embeds); types are known once built. */
    private boolean hasType(ClientLibrary lib, LibraryType type) {
        if (lib.getTypes().isEmpty()) {
            libraries.getLibrary(type, lib.getPath());
        }
        return lib.getTypes().contains(type);
    }

    static LibraryType typeOf(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        try {
            return LibraryType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
