/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.script.Bindings;
import javax.servlet.RequestDispatcher;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.extension.RuntimeExtension;
import org.apache.sling.scripting.sightly.render.RenderContext;
import org.apache.sling.scripting.sightly.render.RuntimeObjectModel;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.TemplatedResource;
import com.day.cq.wcm.api.WCMMode;
import com.day.cq.wcm.api.components.ComponentContext;

/**
 * {@code data-sly-resource} with the WCM options AEM components use, replacing Sling's default
 * implementation (higher service ranking):
 * <ul>
 *   <li>{@code decorationTagName}: element wrapping the included component ({@code ''} for none);</li>
 *   <li>{@code cssClassName}: classes added to that element;</li>
 *   <li>{@code decoration=false}: include without a wrapper element or component context;</li>
 *   <li>{@code wcmmode}: render the include in another mode, e.g. {@code 'disabled'};</li>
 * </ul>
 * plus the standard {@code prependPath}, {@code appendPath}, {@code selectors}, {@code addSelectors},
 * {@code removeSelectors} and {@code resourceType}.
 *
 * <p>Including a resource at the request's own path is refused only if it would render with the
 * same resource type and selectors (true recursion). Delegating components (a teaser rendering its
 * image through a wrapper of itself typed as the image component) are therefore allowed.
 */
@Component(
        service = RuntimeExtension.class,
        property = {
                RuntimeExtension.NAME + "=" + RuntimeExtension.RESOURCE,
                "service.ranking:Integer=1000"
        })
public class WcmResourceExtension implements RuntimeExtension {

    /** Set for the next decorated include: overrides the decoration element ({@code ""} = none). */
    static final String ATTR_DECORATION_TAG = WcmResourceExtension.class.getName() + ".decorationTagName";
    /** Set for the next decorated include: extra classes for the decoration element. */
    static final String ATTR_DECORATION_CLASS = WcmResourceExtension.class.getName() + ".cssClassName";

    @Override
    @SuppressWarnings("unchecked")
    public Object call(RenderContext renderContext, Object... arguments) {
        if (arguments.length != 2) {
            throw new SightlyException("resource extension expects 2 arguments");
        }
        Bindings bindings = renderContext.getBindings();
        RuntimeObjectModel model = renderContext.getObjectModel();
        SlingHttpServletRequest request = (SlingHttpServletRequest) bindings.get(SlingBindings.REQUEST);
        SlingHttpServletResponse response = (SlingHttpServletResponse) bindings.get(SlingBindings.RESPONSE);
        Map<String, Object> options = arguments[1] == null ? new java.util.HashMap<>() : new java.util.HashMap<>((Map<String, Object>) arguments[1]);
        Object target = arguments[0];

        Resource include;
        Set<String> selectors = new LinkedHashSet<>();
        if (target instanceof Resource) {
            include = (Resource) target;
        } else {
            String path = buildPath(model.toString(target), options, model, request.getResource());
            include = path == null ? request.getResource() : request.getResourceResolver().getResource(path);
            if (include == null) {
                // "child.selector" or a resource that does not exist (yet): synthetic, like Sling
                int slash = path.lastIndexOf('/');
                int dot = path.indexOf('.', slash + 1);
                String resourcePath = path;
                if (dot > 0) {
                    resourcePath = path.substring(0, dot);
                    selectors.addAll(Arrays.asList(path.substring(dot + 1).split("\\.")));
                }
                include = request.getResourceResolver().getResource(resourcePath);
                if (include == null) {
                    String type = options.containsKey("resourceType") ? model.toString(options.get("resourceType"))
                            : request.getResource().getResourceType();
                    include = new SyntheticResource(request.getResourceResolver(), resourcePath, type);
                }
            }
        }

        // AEM renders page content merged with the editable template (locked parts take the template's
        // properties): the included resource is the templated one, so models read what AEM's would
        if (!(include instanceof SyntheticResource)) {
            TemplatedResource templated = TemplatedResourceImpl.of(include);
            if (templated instanceof Resource) {
                include = (Resource) templated;
            }
        }

        RequestDispatcherOptions dispatch = dispatcherOptions(request, selectors, options, model);
        if (isRecursion(request, include, dispatch)) {
            return "";
        }

        Map<String, Object> savedAttributes = new java.util.HashMap<>();
        Object attrs = options.get("requestAttributes");
        if (attrs instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) attrs).entrySet()) {
                String name = String.valueOf(e.getKey());
                savedAttributes.put(name, request.getAttribute(name));
                request.setAttribute(name, e.getValue());
            }
        }
        StringWriter buffer = new StringWriter();
        PrintWriter out = new PrintWriter(buffer);
        Object previousMode = request.getAttribute(WCMMode.REQUEST_ATTRIBUTE_NAME);
        Object previousTag = request.getAttribute(ATTR_DECORATION_TAG);
        Object previousClass = request.getAttribute(ATTR_DECORATION_CLASS);
        try {
            if (options.containsKey("wcmmode")) {
                WCMMode mode = WcmComponentFilter.modeFromParameter(model.toString(options.get("wcmmode")));
                mode.toRequest(request);
            }
            if (options.containsKey("decoration") && !model.toBoolean(options.get("decoration"))) {
                request.setAttribute(ComponentContext.BYPASS_COMPONENT_HANDLING_ON_INCLUDE_ATTRIBUTE, Boolean.TRUE);
            }
            if (options.containsKey("decorationTagName")) {
                request.setAttribute(ATTR_DECORATION_TAG, model.toString(options.get("decorationTagName")));
            } else if (!options.containsKey("decoration") && WCMMode.fromRequest(request) == WCMMode.DISABLED) {
                // AEM: an HTL include that names no decoration element gets none while the WCM mode is
                // disabled (experience fragment roots, a teaser's image, ...); Core's page component sets
                // decorationTagName='div' for the page root on purpose
                request.setAttribute(ATTR_DECORATION_TAG, "");
            }
            if (options.containsKey("cssClassName")) {
                request.setAttribute(ATTR_DECORATION_CLASS, model.toString(options.get("cssClassName")));
            }
            RequestDispatcher dispatcher = request.getRequestDispatcher(include, dispatch);
            if (dispatcher == null) {
                throw new SightlyException("Failed to include resource " + include.getPath());
            }
            dispatcher.include(request, new WriterResponse(response, out));
        } catch (SightlyException e) {
            throw e;
        } catch (Exception e) {
            throw new SightlyException("Failed to include resource " + include.getPath(), e);
        } finally {
            restore(request, WCMMode.REQUEST_ATTRIBUTE_NAME, previousMode);
            restore(request, ATTR_DECORATION_TAG, previousTag);
            restore(request, ATTR_DECORATION_CLASS, previousClass);
            request.removeAttribute(ComponentContext.BYPASS_COMPONENT_HANDLING_ON_INCLUDE_ATTRIBUTE);
            for (Map.Entry<String, Object> e : savedAttributes.entrySet()) {
                restore(request, e.getKey(), e.getValue());
            }
        }
        out.flush();
        return buffer.toString();
    }

    static boolean isRecursion(SlingHttpServletRequest request, Resource include, RequestDispatcherOptions dispatch) {
        Resource current = request.getResource();
        if (!current.getPath().equals(include.getPath())) {
            return false;
        }
        String type = dispatch.getForceResourceType() != null ? dispatch.getForceResourceType() : include.getResourceType();
        if (!current.getResourceType().equals(type)) {
            return false;
        }
        String requestSelectors = request.getRequestPathInfo().getSelectorString();
        String added = dispatch.getAddSelectors();
        return requestSelectors == null ? added == null || added.isEmpty() : requestSelectors.equals(added);
    }

    /** Same rules as Sling: {@code path} option overrides the argument; prepend and append with slashes. */
    static String buildPath(String path, Map<String, Object> options, RuntimeObjectModel model, Resource current) {
        String prepend = options.containsKey("prependPath") ? model.toString(options.remove("prependPath")) : "";
        String append = options.containsKey("appendPath") ? model.toString(options.remove("appendPath")) : "";
        String p = options.containsKey("path") ? model.toString(options.remove("path")) : (path == null ? "" : path);
        if (!prepend.isEmpty()) {
            if (!prepend.startsWith("/")) {
                prepend = "/" + prepend;
            }
            if (!prepend.endsWith("/")) {
                prepend += "/";
            }
        }
        if (!append.isEmpty() && !append.startsWith("/")) {
            append = "/" + append;
        }
        String full = prepend + p + append;
        if (full.isEmpty()) {
            return null;
        }
        if (!full.startsWith("/")) {
            full = current.getPath() + "/" + full;
        }
        return ResourceUtil.normalize(full);
    }

    private static RequestDispatcherOptions dispatcherOptions(SlingHttpServletRequest request, Set<String> fromPath,
            Map<String, Object> options, RuntimeObjectModel model) {
        RequestDispatcherOptions o = new RequestDispatcherOptions();
        Set<String> selectors = new LinkedHashSet<>(fromPath);
        if (selectors.isEmpty()) {
            selectors.addAll(Arrays.asList(request.getRequestPathInfo().getSelectors()));
        }
        if (options.containsKey("selectors")) {
            selectors.clear();
            selectors.addAll(values(options.get("selectors"), model));
        }
        if (options.containsKey("addSelectors")) {
            selectors.addAll(values(options.get("addSelectors"), model));
        }
        if (options.containsKey("removeSelectors")) {
            Object remove = options.get("removeSelectors");
            if (remove == null) {
                selectors.clear();
            } else {
                selectors.removeAll(values(remove, model));
            }
        }
        o.setReplaceSelectors("");
        if (!selectors.isEmpty()) {
            // an empty add-selectors string would yield selector string "" and miss every script
            o.setAddSelectors(String.join(".", selectors));
        }
        if (options.containsKey("resourceType")) {
            String type = model.toString(options.get("resourceType"));
            if (!type.isEmpty()) {
                o.setForceResourceType(type);
            }
        }
        return o;
    }

    private static Collection<String> values(Object v, RuntimeObjectModel model) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof String) {
            for (String s : ((String) v).split("\\.")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        } else if (v != null) {
            for (Object o : model.toCollection(v)) {
                String s = model.toString(o);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static void restore(SlingHttpServletRequest request, String name, Object value) {
        if (value == null) {
            request.removeAttribute(name);
        } else {
            request.setAttribute(name, value);
        }
    }

    /** Collects the include's output in the HTL buffer. */
    static final class WriterResponse extends SlingHttpServletResponseWrapper {
        private final PrintWriter writer;

        WriterResponse(SlingHttpServletResponse wrapped, PrintWriter writer) {
            super(wrapped);
            this.writer = writer;
        }

        @Override
        public PrintWriter getWriter() {
            return writer;
        }
    }
}
