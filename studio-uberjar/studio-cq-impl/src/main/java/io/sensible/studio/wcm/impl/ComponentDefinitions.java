/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * Component definitions as seen by the platform, independent of the visitor's permissions.
 * Anonymous visitors usually cannot read {@code /apps}, yet rendering needs the definitions
 * (for {@code component} script variables and decoration). Definitions are read through the
 * {@code studio-wcm-service} user, copied into immutable snapshots and cached until {@code /apps}
 * or {@code /libs} change.
 */
@Component(
        service = {ComponentDefinitions.class, ResourceChangeListener.class},
        property = {
                ResourceChangeListener.PATHS + "=/apps",
                ResourceChangeListener.PATHS + "=/libs"
        })
public class ComponentDefinitions implements ResourceChangeListener {

    static final String SUBSERVICE = "clientlibs"; // same read-only mapping as the clientlib manager

    private static volatile ComponentDefinitions instance;

    @Reference
    private ResourceResolverFactory resolverFactory;

    private final Map<String, Optional<Snapshot>> cache = new ConcurrentHashMap<>();

    @Activate
    void activate() {
        instance = this;
    }

    @Deactivate
    void deactivate() {
        if (instance == this) {
            instance = null;
        }
        cache.clear();
    }

    static ComponentDefinitions get() {
        return instance;
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        cache.clear();
    }

    /** @return a snapshot of the definition for a resource type or absolute path, or {@code null} */
    com.day.cq.wcm.api.components.Component lookup(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        return cache.computeIfAbsent(type, this::load).orElse(null);
    }

    private Optional<Snapshot> load(String type) {
        try (ResourceResolver rr = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            ComponentManagerImpl cm = new ComponentManagerImpl(rr);
            Resource def = cm.resolveType(type);
            if (!ComponentManagerImpl.isComponent(def)) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(def, cm.toResourceType(def.getPath())));
        } catch (LoginException e) {
            return Optional.empty();
        }
    }

    /** First drop target of a definition ({@code cq:editConfig/cq:dropTargets/*}). */
    static final class DropTarget {
        final String propertyName;
        final String[] accept;
        final String[] groups;

        private DropTarget(String propertyName, String[] accept, String[] groups) {
            this.propertyName = propertyName;
            this.accept = accept;
            this.groups = groups;
        }

        static DropTarget of(Resource def) {
            Resource targets = def == null ? null : def.getChild("cq:editConfig/cq:dropTargets");
            if (targets == null) {
                return null;
            }
            for (Resource t : targets.getChildren()) {
                ValueMap vm = t.getValueMap();
                String prop = vm.get("propertyName", String.class);
                if (prop != null) {
                    return new DropTarget(prop, vm.get("accept", new String[0]), vm.get("groups", new String[0]));
                }
            }
            return null;
        }
    }

    /** Detached copy of a component definition. */
    static final class Snapshot extends SlingAdaptable implements com.day.cq.wcm.api.components.Component {
        private final String path;
        private final String name;
        private final String resourceType;
        private final ValueMap props;
        private final boolean editable;
        final String htmlTagName;
        final String htmlTagClass;
        final boolean hasHtmlTag;
        final boolean hasDesignDialog;
        /** {@code cq:editConfig/cq:dropTargets/*}: property, accept, groups; {@code null} if none */
        final DropTarget dropTarget;
        /** {@code cq:editConfig}: in-place editing and toolbar actions; {@code null} if none */
        final EditConfig editConfig;

        Snapshot(Resource def, String resourceType) {
            this.path = def.getPath();
            this.name = def.getName();
            this.resourceType = resourceType;
            this.props = new ValueMapDecorator(new HashMap<>(def.getValueMap()));
            this.editable = def.getChild("cq:dialog") != null || def.getChild("dialog") != null;
            Resource tag = def.getChild("cq:htmlTag");
            this.hasHtmlTag = tag != null;
            this.htmlTagName = tag == null ? null : tag.getValueMap().get("cq:tagName", String.class);
            this.htmlTagClass = tag == null ? null : tag.getValueMap().get("class", String.class);
            this.hasDesignDialog = def.getChild("cq:design_dialog") != null;
            this.dropTarget = DropTarget.of(def);
            this.editConfig = EditConfig.of(def);
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getName() {
            return name;
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
            return resourceType;
        }

        @Override
        public com.day.cq.wcm.api.components.Component getSuperComponent() {
            String st = props.get("sling:resourceSuperType", String.class);
            ComponentDefinitions defs = get();
            return st == null || defs == null ? null : defs.lookup(st);
        }

        @Override
        public String getComponentGroup() {
            return props.get("componentGroup", String.class);
        }

        @Override
        public boolean isAccessible() {
            return true;
        }

        @Override
        public boolean isContainer() {
            return props.get("cq:isContainer", false);
        }

        @Override
        public boolean isEditable() {
            return editable;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type == ValueMap.class) {
                return (AdapterType) props;
            }
            return super.adaptTo(type);
        }

        @Override
        public String toString() {
            return "Component{" + path + " (snapshot)}";
        }
    }
}
