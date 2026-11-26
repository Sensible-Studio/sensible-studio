/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Collections;
import javax.servlet.http.HttpServletResponse;
import org.apache.sling.models.factory.ExportException;
import org.apache.sling.models.factory.MissingExporterException;
import org.apache.sling.models.factory.ModelFactory;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import com.adobe.cq.export.json.ComponentExporter;
import com.adobe.cq.export.json.ExporterConstants;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.commons.WCMUtils;

/**
 * Sensible Studio's layout grid ({@code wcm/foundation/components/responsivegrid}). Renders the children of a
 * container in the 12-column grid markup that site CSS (e.g. WKND's grid clientlib) styles:
 * <pre>
 * &lt;div class="aem-Grid aem-Grid--12 aem-Grid--default--12"&gt;
 *   &lt;div class="title aem-GridColumn aem-GridColumn--default--12"&gt;...&lt;/div&gt;
 * &lt;/div&gt;
 * </pre>
 * Column widths, offsets and hiding per breakpoint come from each child's {@code cq:responsive}
 * node ({@code default}, {@code tablet}, {@code phone}, ... with {@code width}, {@code offset},
 * {@code behavior=hide}).
 */
@Component(
        service = Servlet.class,
        property = {
                // Registered by type only (the "default" servlet of the type): with explicit methods
                // or extensions Sling would rank it above Core's container.html, a script of a sub
                // type, and the cmp-container wrapper would never render. Other extensions (.json)
                // are handed to Sling's default servlets in doGet.
                "sling.servlet.resourceTypes=wcm/foundation/components/responsivegrid"
        })
public class ResponsiveGridServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile transient ModelFactory modelFactory;
    static final int COLUMNS = 12;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        String ext = request.getRequestPathInfo().getExtension();
        // the grid's JSON model, as AEM's own responsive grid exporter gives it: the container's Sling Model
        // (Core's LayoutContainerImpl for a container) exported by Jackson, with its children's
        if ("json".equals(ext) && "model".equals(request.getRequestPathInfo().getSelectorString()) && exportModel(request, response)) {
            return;
        }
        if (ext != null && !"html".equals(ext)) {
            // to Sling's default servlets, under the node's own JCR type: forced to sling/servlet/default, a
            // failure there (a selector the JSON renderer refuses) was answered by the WebDAV servlet,
            // the error handler of that type, as a 401 asking for a sign-in
            RequestDispatcherOptions o = new RequestDispatcherOptions();
            o.setForceResourceType(request.getResource().getValueMap().get("jcr:primaryType", "nt:unstructured"));
            RequestDispatcher rd = request.getRequestDispatcher(request.getResource(), o);
            if (rd != null) {
                rd.forward(request, response);
                return;
            }
        }
        Resource grid = request.getResource();
        PrintWriter out = response.getWriter();
        ComponentContext parent = WCMUtils.getComponentContext(request);
        boolean editPage = EditMeta.isEdit(request) && isPageContent(grid, parent);
        // a locked structure grid (a template's root container on a page) takes no components there, as in AEM;
        // its unlocked children are still edited
        boolean edit = editPage && !TemplatedResourceImpl.isLocked(grid);
        out.write("<div class=\"" + gridClasses(grid) + "\""
                + (edit ? " data-studio-grid=\"" + Decoration.escape(grid.getPath()) + "\"" : "") + ">");
        ComponentManagerImpl cm = new ComponentManagerImpl(grid.getResourceResolver());
        Resource merged = TemplatedResourceImpl.of(grid);
        for (Resource child : (merged != null ? merged : grid).getChildren()) {
            String name = child.getName();
            if (name.startsWith("cq:") || name.startsWith("jcr:") || name.startsWith("rep:")) {
                continue;
            }
            Decoration deco = Decoration.of(child, cm.getComponentOfResource(child), null, columnClasses(child));
            if (deco == null) {
                deco = Decoration.of(child, new ComponentManagerImpl.NamedComponent(child), null, columnClasses(child));
            }
            if (editPage && child.getPath().startsWith(grid.getPath() + "/") && !TemplatedResourceImpl.isLocked(child)) {
                deco = deco.withEditMeta(EditMeta.of(child, cm.getComponentOfResource(child)));
            }
            out.write(deco.open());
            out.flush();
            RequestDispatcher rd = request.getRequestDispatcher(child);
            if (rd != null) {
                request.setAttribute(WcmComponentFilter.ATTR_DECORATED, child.getPath());
                rd.include(request, response);
            }
            out.write(deco.close());
        }
        if (edit) {
            out.write(EditMeta.placeholder(grid.getPath()));
        }
        out.write("</div>");
    }

    /** Writes the JSON model of the grid; false when there is no model to export (the default servlet answers then). */
    private boolean exportModel(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ModelFactory models = modelFactory;
        if (models == null) {
            return false;
        }
        // the model registered for the grid's resource type (or a super type): asked for "a ComponentExporter",
        // Sling Models would take any implementation of it
        Object model;
        try {
            model = models.getModelFromRequest(request);
        } catch (RuntimeException e) {
            return false;
        }
        if (!(model instanceof ComponentExporter)) {
            return false;
        }
        try {
            String json = models.exportModel(model, ExporterConstants.SLING_MODEL_EXPORTER_NAME, String.class, Collections.emptyMap());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json);
            return true;
        } catch (ExportException | MissingExporterException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "The model cannot be exported");
            return true;
        }
    }

    /** Only grids stored in the page itself are editable; structure-only grids belong to the template. */
    static boolean isPageContent(Resource grid, ComponentContext context) {
        return context != null && EditMeta.inEditedPage(grid.getPath(), context);
    }

    static String gridClasses(Resource grid) {
        List<String> cls = new ArrayList<>();
        cls.add("aem-Grid");
        cls.add("aem-Grid--" + COLUMNS);
        boolean hasDefault = false;
        Resource responsive = grid.getChild("cq:responsive");
        if (responsive != null) {
            for (Resource bp : responsive.getChildren()) {
                int width = intProp(bp.getValueMap(), "width", COLUMNS);
                cls.add("aem-Grid--" + bp.getName() + "--" + width);
                hasDefault |= "default".equals(bp.getName());
            }
        }
        if (!hasDefault) {
            cls.add("aem-Grid--default--" + COLUMNS);
        }
        return String.join(" ", cls);
    }

    static String columnClasses(Resource child) {
        List<String> cls = new ArrayList<>();
        cls.add("aem-GridColumn");
        boolean hasDefault = false;
        Resource responsive = child.getChild("cq:responsive");
        if (responsive != null) {
            for (Resource bp : responsive.getChildren()) {
                ValueMap vm = bp.getValueMap();
                String b = bp.getName();
                hasDefault |= "default".equals(b);
                if ("hide".equals(vm.get("behavior", String.class))) {
                    cls.add("aem-GridColumn--" + b + "--hide");
                    continue;
                }
                cls.add("aem-GridColumn--" + b + "--" + intProp(vm, "width", COLUMNS));
                // An offset of 0 gets its class too: the rules for `default` sit outside any media query, so a
                // breakpoint without an offset class keeps the default's indent. AEM writes the 0 for the same reason.
                if (vm.containsKey("offset")) {
                    cls.add("aem-GridColumn--offset--" + b + "--" + intProp(vm, "offset", 0));
                }
                if ("newline".equals(vm.get("behavior", String.class))) {
                    cls.add("aem-GridColumn--" + b + "--newline");
                }
            }
        }
        if (!hasDefault) {
            cls.add("aem-GridColumn--default--" + COLUMNS);
        }
        return String.join(" ", cls);
    }

    private static int intProp(ValueMap vm, String name, int def) {
        Object v = vm.get(name);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
