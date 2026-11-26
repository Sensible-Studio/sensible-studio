/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.api.components.EditContext;
import com.day.cq.wcm.api.designer.Cell;

/**
 * Context of one component include. Created by {@link WcmComponentFilter}, which keeps a stack of
 * them on the request (parent = including component).
 */
final class ComponentContextImpl implements ComponentContext {

    private final ComponentContext parent;
    private final Resource resource;
    private final Component component;
    private final Page page;
    private final Cell cell;
    private final Set<String> cssClassNames = new LinkedHashSet<>();
    private final Map<String, Object> attributes = new HashMap<>();

    ComponentContextImpl(ComponentContext parent, Resource resource, Component component, Page page, Cell cell) {
        this.parent = parent;
        this.resource = resource;
        this.component = component;
        this.page = page;
        this.cell = cell;
        // AEM: the component's name and its super components' ("page basicpage"); Core's Page
        // model puts them on <body>
        this.cssClassNames.addAll(Decoration.componentChain(component));
    }

    @Override
    public ComponentContext getParent() {
        return parent;
    }

    @Override
    public ComponentContext getRoot() {
        ComponentContext c = this;
        while (c.getParent() != null) {
            c = c.getParent();
        }
        return c;
    }

    @Override
    public boolean isRoot() {
        return parent == null;
    }

    @Override
    public Resource getResource() {
        return resource;
    }

    @Override
    public Component getComponent() {
        return component;
    }

    @Override
    public Page getPage() {
        return page;
    }

    @Override
    public Cell getCell() {
        return cell;
    }

    @Override
    public EditContext getEditContext() {
        return null; // Sensible Studio's authoring UI does not decorate server-side output
    }

    @Override
    public Set<String> getCssClassNames() {
        return cssClassNames;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Object setAttribute(String name, Object value) {
        return value == null ? attributes.remove(name) : attributes.put(name, value);
    }

    @Override
    public String toString() {
        return "ComponentContext{" + (resource == null ? "?" : resource.getPath()) + "}";
    }
}
