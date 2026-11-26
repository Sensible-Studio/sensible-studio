/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentManager;

/**
 * Resolves resource types to {@code cq:Component} definitions using the resolver's search path
 * ({@code /apps}, then {@code /libs}), exactly as Sling resolves scripts.
 */
final class ComponentManagerImpl implements ComponentManager {

    static final String NT_COMPONENT = "cq:Component";

    private final ResourceResolver resolver;

    ComponentManagerImpl(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Component getComponent(String path) {
        Resource r = resolveType(path);
        if (isComponent(r)) {
            return new ComponentImpl(r, this);
        }
        // the caller may lack read access to /apps; fall back to the platform's view
        ComponentDefinitions defs = ComponentDefinitions.get();
        return defs == null ? null : defs.lookup(path);
    }

    @Override
    public Component getComponentOfResource(Resource resource) {
        if (resource == null) {
            return null;
        }
        return getComponent(resource.getResourceType());
    }

    @Override
    public Collection<Component> getComponents() {
        List<Component> result = new ArrayList<>();
        for (String root : resolver.getSearchPath()) {
            Resource r = resolver.getResource(root);
            if (r != null) {
                collect(r, result, 0);
            }
        }
        return result;
    }

    private void collect(Resource parent, List<Component> out, int depth) {
        if (depth > 12) {
            return;
        }
        for (Resource child : parent.getChildren()) {
            if (isComponent(child)) {
                out.add(new ComponentImpl(child, this));
            } else if (!child.getName().startsWith("jcr:")) {
                collect(child, out, depth + 1);
            }
        }
    }

    /** Absolute paths as-is; relative types are tried against each search path entry. */
    Resource resolveType(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        if (type.startsWith("/")) {
            return resolver.getResource(type);
        }
        for (String root : resolver.getSearchPath()) {
            Resource r = resolver.getResource(root + type);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    static boolean isComponent(Resource r) {
        return r != null && NT_COMPONENT.equals(r.getValueMap().get(Pages.PN_PRIMARY_TYPE, String.class));
    }

    /** Relative resource type for an absolute component path: {@code /apps/a/b} to {@code a/b}. */
    String toResourceType(String path) {
        for (String root : resolver.getSearchPath()) {
            if (path.startsWith(root)) {
                return path.substring(root.length());
            }
        }
        return path;
    }

    /** Stand-in used for decoration when the definition is unreadable: named after the resource type. */
    static final class NamedComponent extends SlingAdaptable implements Component {
        private final String type;

        NamedComponent(Resource resource) {
            this.type = resource.getResourceType();
        }

        @Override
        public String getPath() {
            return type;
        }

        @Override
        public String getName() {
            return type.substring(type.lastIndexOf('/') + 1);
        }

        @Override
        public String getTitle() {
            return null;
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public ValueMap getProperties() {
            return ValueMap.EMPTY;
        }

        @Override
        public String getResourceType() {
            return type;
        }

        @Override
        public Component getSuperComponent() {
            return null;
        }

        @Override
        public String getComponentGroup() {
            return null;
        }

        @Override
        public boolean isAccessible() {
            return false;
        }

        @Override
        public boolean isContainer() {
            return false;
        }

        @Override
        public boolean isEditable() {
            return false;
        }
    }

    // ------------------------------------------------------------------ component

    static final class ComponentImpl extends SlingAdaptable implements Component {
        private final Resource resource;
        private final ComponentManagerImpl manager;
        private final ValueMap props;

        ComponentImpl(Resource resource, ComponentManagerImpl manager) {
            this.resource = resource;
            this.manager = manager;
            this.props = resource.getValueMap();
        }

        @Override
        public String getPath() {
            return resource.getPath();
        }

        @Override
        public String getName() {
            return resource.getName();
        }

        @Override
        public String getTitle() {
            return props.get("jcr:title", String.class);
        }

        @Override
        public String getDescription() {
            return props.get("jcr:description", String.class);
        }

        @Override
        public ValueMap getProperties() {
            return props;
        }

        @Override
        public String getResourceType() {
            return manager.toResourceType(resource.getPath());
        }

        @Override
        public Component getSuperComponent() {
            String superType = props.get("sling:resourceSuperType", String.class);
            return superType == null ? null : manager.getComponent(superType);
        }

        @Override
        public String getComponentGroup() {
            return props.get("componentGroup", String.class);
        }

        @Override
        public boolean isAccessible() {
            return true; // the resolver could read the definition
        }

        @Override
        public boolean isContainer() {
            return props.get("cq:isContainer", false);
        }

        @Override
        public boolean isEditable() {
            return resource.getChild("cq:dialog") != null || resource.getChild("dialog") != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type == Resource.class) {
                return (AdapterType) resource;
            }
            if (type == ValueMap.class) {
                return (AdapterType) props;
            }
            return super.adaptTo(type);
        }

        @Override
        public String toString() {
            return "Component{" + getPath() + "}";
        }
    }
}
