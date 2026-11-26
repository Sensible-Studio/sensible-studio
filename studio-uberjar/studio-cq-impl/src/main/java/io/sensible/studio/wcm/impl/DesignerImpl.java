/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.api.designer.Cell;
import com.day.cq.wcm.api.designer.Design;
import com.day.cq.wcm.api.designer.Designer;
import com.day.cq.wcm.api.designer.Style;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.commons.policy.ContentPolicyStyle;

/**
 * Styles come from content policies (editable templates). Static-template designs are not
 * supported yet: {@link #getDesign(Page)} returns {@code null} and design styles are empty.
 */
final class DesignerImpl implements Designer {

    static final String DEFAULT_DESIGN = "/libs/settings/wcm/designs/default";

    private final ResourceResolver resolver;
    private final ContentPolicyManagerImpl policies;
    private final PageManagerImpl pageManager;

    DesignerImpl(ResourceResolver resolver) {
        this.resolver = resolver;
        this.pageManager = new PageManagerImpl(resolver);
        this.policies = new ContentPolicyManagerImpl(resolver, pageManager);
    }

    @Override
    public Design getDesign(Page page) {
        if (page == null) {
            return null;
        }
        String path = page.getProperties().get(NameConstants.PN_DESIGN_PATH, String.class);
        return path == null ? null : getDesign(path);
    }

    @Override
    public Design getDesign(String id) {
        return hasDesign(id) ? new EmptyDesign(id) : null;
    }

    @Override
    public boolean hasDesign(String id) {
        return id != null && resolver.getResource(id) != null;
    }

    @Override
    public Design getDefaultDesign() {
        return new EmptyDesign(DEFAULT_DESIGN);
    }

    @Override
    public Style getStyle(Resource resource) {
        ContentPolicy policy = resource == null ? null : policies.getPolicy(resource);
        return new ContentPolicyStyle(policy, cellFor(resource));
    }

    @Override
    public Style getStyle(Resource resource, String cellPath) {
        ContentPolicy policy = resource == null ? null : policies.getPolicy(resource);
        return new ContentPolicyStyle(policy, cellPath == null ? cellFor(resource) : CellImpl.of(cellPath));
    }

    /** Cell path = path of the resource inside its page's {@code jcr:content}. */
    Cell cellFor(Resource resource) {
        if (resource == null) {
            return null;
        }
        Page page = pageManager.getContainingPage(resource);
        if (page == null) {
            return CellImpl.of(resource.getName());
        }
        String content = page.getPath() + "/" + NameConstants.NN_CONTENT;
        String path = resource.getPath();
        String rel = path.startsWith(content + "/") ? path.substring(content.length() + 1) : "";
        return CellImpl.of(rel.isEmpty() ? "page" : "page/" + rel);
    }

    // ------------------------------------------------------------------ value objects

    static final class CellImpl implements Cell {
        private final String path;

        private CellImpl(String path) {
            this.path = path;
        }

        static CellImpl of(String path) {
            return new CellImpl(path);
        }

        @Override
        public String getName() {
            return Pages.name("/" + path);
        }

        @Override
        public Cell getParent() {
            int idx = path.lastIndexOf('/');
            return idx < 0 ? null : new CellImpl(path.substring(0, idx));
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public Iterator<String> paths() {
            List<String> out = new ArrayList<>();
            for (Cell c = this; c != null; c = c.getParent()) {
                out.add(c.getPath());
            }
            return out.iterator();
        }

        @Override
        public String toString() {
            return path;
        }
    }

    private static final class EmptyDesign implements Design {
        private final String path;

        EmptyDesign(String path) {
            this.path = path;
        }

        @Override
        public String getId() {
            return path;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public Resource getContentResource() {
            return null;
        }

        @Override
        public Style getStyle(ComponentContext context) {
            return new ContentPolicyStyle(null, context == null ? null : context.getCell());
        }

        @Override
        public Style getStyle(Cell cell) {
            return new ContentPolicyStyle(null, cell);
        }

        @Override
        public Style getStyle(String path) {
            return new ContentPolicyStyle(null, CellImpl.of(path));
        }

        @Override
        public Style getStyle(Resource resource) {
            return new ContentPolicyStyle(null, null);
        }
    }
}
