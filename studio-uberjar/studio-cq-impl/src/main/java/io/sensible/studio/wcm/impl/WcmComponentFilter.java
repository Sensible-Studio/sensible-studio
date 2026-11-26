/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.WCMMode;
import com.day.cq.wcm.api.components.ComponentContext;

/**
 * Maintains the {@link ComponentContext} stack for page rendering: every request or include of a
 * resource inside a page gets a context whose parent is the including component's, restored when
 * the include returns. Also sets the {@link WCMMode} from {@code ?wcmmode=} (default disabled).
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.scope=INCLUDE",
                "service.ranking:Integer=-2500"
        })
public class WcmComponentFilter implements Filter {

    /** Set by containers that wrote the decoration themselves for the next include of that path. */
    static final String ATTR_DECORATED = WcmComponentFilter.class.getName() + ".decorated";

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!(req instanceof SlingHttpServletRequest)) {
            chain.doFilter(req, res);
            return;
        }
        SlingHttpServletRequest request = (SlingHttpServletRequest) req;
        // decoration options of the data-sly-resource that made this include; they apply to it only
        Object tagOption = request.getAttribute(WcmResourceExtension.ATTR_DECORATION_TAG);
        Object classOption = request.getAttribute(WcmResourceExtension.ATTR_DECORATION_CLASS);
        request.removeAttribute(WcmResourceExtension.ATTR_DECORATION_TAG);
        request.removeAttribute(WcmResourceExtension.ATTR_DECORATION_CLASS);

        if (Boolean.TRUE.equals(request.getAttribute(ComponentContext.BYPASS_COMPONENT_HANDLING_ON_INCLUDE_ATTRIBUTE))) {
            request.removeAttribute(ComponentContext.BYPASS_COMPONENT_HANDLING_ON_INCLUDE_ATTRIBUTE);
            chain.doFilter(req, res);
            return;
        }

        if (request.getAttribute(WCMMode.REQUEST_ATTRIBUTE_NAME) == null) {
            modeFromParameter(request.getParameter("wcmmode")).toRequest(request);
        }
        handle(request, res, chain, tagOption, classOption);
    }

    private void handle(SlingHttpServletRequest request, ServletResponse res, FilterChain chain,
            Object tagOption, Object classOption)
            throws IOException, ServletException {
        ServletRequest req = request;

        Object previous = request.getAttribute(ComponentContext.CONTEXT_ATTR_NAME);
        ComponentContext parent = previous instanceof ComponentContext ? (ComponentContext) previous : null;
        if (parent != null && parent.getResource().getPath().equals(request.getResource().getPath())) {
            chain.doFilter(req, res); // script include on the same resource: same context
            return;
        }
        ComponentContext context = createContext(request, parent);
        if (context == null) {
            chain.doFilter(req, res);
            return;
        }
        request.setAttribute(ComponentContext.CONTEXT_ATTR_NAME, context);
        Decoration deco = null;
        Object predecorated = request.getAttribute(ATTR_DECORATED);
        if (predecorated != null && predecorated.equals(request.getResource().getPath())) {
            request.removeAttribute(ATTR_DECORATED);
        } else if (parent != null && context.getComponent() != null && isComponentContent(request.getResource(), context)
                && "html".equals(request.getRequestPathInfo().getExtension())) {
            // resources without a component definition (e.g. platform script includes in <head>) stay bare
            deco = Decoration.ofInclude(request.getResource(), context.getComponent(), context);
            if (deco != null && tagOption != null) {
                String tag = String.valueOf(tagOption).trim();
                deco = tag.isEmpty() ? null : deco.withTag(tag);
            }
            if (deco != null && classOption != null) {
                deco = deco.withClasses(String.valueOf(classOption));
            }
            if (deco != null && EditMeta.isEdit(request) && EditMeta.inEditedPage(request.getResource().getPath(), context)
                    && !TemplatedResourceImpl.isLocked(request.getResource())) {
                deco = deco.withEditMeta(EditMeta.of(request.getResource(), context.getComponent()));
            }
        }
        try {
            if (deco != null) {
                res.getWriter().write(deco.open());
            }
            chain.doFilter(req, res);
            if (deco != null) {
                res.getWriter().write(deco.close());
            }
        } finally {
            if (previous == null) {
                request.removeAttribute(ComponentContext.CONTEXT_ATTR_NAME);
            } else {
                request.setAttribute(ComponentContext.CONTEXT_ATTR_NAME, previous);
            }
        }
    }

    static WCMMode modeFromParameter(String value) {
        if (value == null) {
            return WCMMode.DISABLED;
        }
        try {
            return WCMMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return WCMMode.DISABLED;
        }
    }

    static ComponentContext createContext(SlingHttpServletRequest request, ComponentContext parent) {
        Resource resource = request.getResource();
        if (resource == null) {
            return null;
        }
        ResourceResolver resolver = resource.getResourceResolver();
        PageManagerImpl pm = new PageManagerImpl(resolver);
        Page page = pm.getContainingPage(resource);
        if (page == null) {
            return null; // not page content: no component handling
        }
        ComponentManagerImpl cm = new ComponentManagerImpl(resolver);
        DesignerImpl designer = new DesignerImpl(resolver);
        return new ComponentContextImpl(parent, resource, cm.getComponentOfResource(resource), page,
                designer.cellFor(resource));
    }

    /** Components inside a page's content are decorated; the page content resource itself is not. */
    static boolean isComponentContent(Resource resource, ComponentContext context) {
        Page page = context.getPage();
        return page != null && resource.getPath().startsWith(page.getPath() + "/jcr:content/");
    }

    @Override
    public void destroy() {
        // nothing to do
    }
}
