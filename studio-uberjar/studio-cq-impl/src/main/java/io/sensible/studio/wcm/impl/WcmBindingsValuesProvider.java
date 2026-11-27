/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import javax.script.Bindings;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.api.BindingsValuesProvider;


import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMMode;
import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.api.designer.Design;
import com.day.cq.wcm.commons.WCMUtils;
import com.day.cq.wcm.commons.policy.ContentPolicyStyle;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;

/**
 * Adds the WCM script variables that HTL scripts and {@code @ScriptVariable} injections in Sling
 * Models expect: {@code pageManager}, {@code currentPage}, {@code resourcePage},
 * {@code pageProperties}, {@code properties}, {@code currentContentPolicy}, {@code currentStyle},
 * {@code component}, {@code componentContext}, {@code wcmmode}, {@code currentDesign} and
 * {@code resourceDesign} (the default design when a page sets none).
 *
 * <p>{@code currentPage} is the page of the top-level request, even inside includes of
 * resources that live on other pages (experience fragments, inherited header/footer). It is
 * pinned on the request the first time bindings are built.
 */
@org.osgi.service.component.annotations.Component(service = BindingsValuesProvider.class)
public class WcmBindingsValuesProvider implements BindingsValuesProvider {

    static final String PAGE_MANAGER = "pageManager";
    static final String CURRENT_PAGE = "currentPage";
    static final String RESOURCE_PAGE = "resourcePage";
    static final String PAGE_PROPERTIES = "pageProperties";
    static final String PROPERTIES = "properties";
    static final String CURRENT_CONTENT_POLICY = "currentContentPolicy";
    static final String CURRENT_STYLE = "currentStyle";
    static final String COMPONENT = "component";
    static final String COMPONENT_CONTEXT = "componentContext";
    static final String WCMMODE = "wcmmode";
    static final String CURRENT_DESIGN = "currentDesign";
    static final String RESOURCE_DESIGN = "resourceDesign";

    private static final String ATTR_CURRENT_PAGE = WcmBindingsValuesProvider.class.getName() + ".currentPage";

    @Override
    public void addBindings(Bindings bindings) {
        Resource resource = (Resource) bindings.get(SlingBindings.RESOURCE);
        if (resource == null) {
            return;
        }
        ResourceResolver resolver = resource.getResourceResolver();
        PageManager pageManager = resolver.adaptTo(PageManager.class);
        if (pageManager == null) {
            return;
        }
        Page resourcePage = pageManager.getContainingPage(resource);

        Page currentPage = resourcePage;
        Object req = bindings.get(SlingBindings.REQUEST);
        if (req instanceof SlingHttpServletRequest) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            Object pinned = request.getAttribute(ATTR_CURRENT_PAGE);
            if (pinned instanceof Page) {
                currentPage = (Page) pinned;
            } else {
                // Nothing rendered yet. A servlet may be building a model for a component that lives elsewhere
                // (Core's search servlet, asked at /content/site/us/en.searchresults.json for a search box kept in
                // an experience fragment): the current page is the one the address names, not the fragment. With
                // the fragment as current page the search of every country looked in the language masters.
                Page asked = pageManager.getContainingPage(request.getRequestPathInfo().getResourcePath());
                if (asked != null) {
                    currentPage = asked;
                }
                if (currentPage != null) {
                    request.setAttribute(ATTR_CURRENT_PAGE, currentPage);
                }
            }
        }

        putIfAbsent(bindings, PAGE_MANAGER, pageManager);
        putIfAbsent(bindings, RESOURCE_PAGE, resourcePage);
        putIfAbsent(bindings, CURRENT_PAGE, currentPage);
        putIfAbsent(bindings, PAGE_PROPERTIES, currentPage == null ? ValueMap.EMPTY : currentPage.getProperties());
        putIfAbsent(bindings, PROPERTIES, resource.getValueMap());

        ContentPolicyManager policies = resolver.adaptTo(ContentPolicyManager.class);
        ContentPolicy policy = policies == null ? null : policies.getPolicy(resource);
        putIfAbsent(bindings, CURRENT_CONTENT_POLICY, policy);

        DesignerImpl designer = new DesignerImpl(resolver);
        Design pageDesign = currentPage == null ? null : designer.getDesign(currentPage);
        putIfAbsent(bindings, CURRENT_DESIGN, pageDesign != null ? pageDesign : designer.getDefaultDesign());
        Design resDesign = resourcePage == null ? null : designer.getDesign(resourcePage);
        putIfAbsent(bindings, RESOURCE_DESIGN, resDesign != null ? resDesign : designer.getDefaultDesign());
        putIfAbsent(bindings, CURRENT_STYLE, new ContentPolicyStyle(policy, designer.cellFor(resource)));

        ComponentContext context = req instanceof SlingHttpServletRequest
                ? WCMUtils.getComponentContext((SlingHttpServletRequest) req) : null;
        putIfAbsent(bindings, COMPONENT_CONTEXT, context);
        Component component = context != null && resource.getPath().equals(context.getResource().getPath())
                ? context.getComponent() : new ComponentManagerImpl(resolver).getComponentOfResource(resource);
        putIfAbsent(bindings, COMPONENT, component);
        putIfAbsent(bindings, WCMMODE, new WcmModeBinding(
                req instanceof SlingHttpServletRequest ? WCMMode.fromRequest((SlingHttpServletRequest) req) : WCMMode.DISABLED));
    }

    private static void putIfAbsent(Bindings bindings, String name, Object value) {
        if (value != null && !bindings.containsKey(name)) {
            bindings.put(name, value);
        }
    }
}
