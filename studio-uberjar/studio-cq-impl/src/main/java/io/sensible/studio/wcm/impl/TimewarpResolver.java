/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.api.wrappers.ValueMapDecorator;

/**
 * Timewarp: a resolver that serves a page's {@code jcr:content} subtree from the frozen node of one of
 * its versions, at the page's own paths. Everything else resolves as usual. Components, models and
 * scripts rendering the page see the old content without knowing.
 */
final class TimewarpResolver extends ResourceResolverWrapper {

    private final String contentPath;
    private final String frozenPath;

    TimewarpResolver(ResourceResolver wrapped, String contentPath, String frozenPath) {
        super(wrapped);
        this.contentPath = contentPath;
        this.frozenPath = frozenPath;
    }

    /** @return the frozen path for a path in the page content, or {@code null} if it is outside */
    private String toFrozen(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals(contentPath)) {
            return frozenPath;
        }
        if (path.startsWith(contentPath + "/")) {
            return frozenPath + path.substring(contentPath.length());
        }
        return null;
    }

    private String toLive(String frozen) {
        if (frozen.equals(frozenPath)) {
            return contentPath;
        }
        return contentPath + frozen.substring(frozenPath.length());
    }

    /** The resource at a page content path, read from the version; null if the version has no such node. */
    Resource frozen(String livePath) {
        String f = toFrozen(livePath);
        if (f == null) {
            return null;
        }
        Resource r = super.getResource(f);
        return r == null ? null : new FrozenResource(r, livePath);
    }

    /** The WCM services (page manager, policies, designer) work on this resolver, so they see the version. */
    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        Object o = WcmAdapterFactory.fromResolver(this, type);
        return o != null ? (AdapterType) o : super.adaptTo(type);
    }

    /** @return {@code true} if the resource, or a wrapper around it (Sling's type override, say), is already warped */
    static boolean isWarped(Resource r) {
        for (Resource x = r; x != null; x = x instanceof ResourceWrapper ? ((ResourceWrapper) x).getResource() : null) {
            if (x instanceof FrozenResource || x instanceof LiveResource) {
                return true;
            }
        }
        return false;
    }

    /** Live resources are wrapped too, so that navigating from them (children, parent) stays in the warp. */
    Resource wrap(Resource r) {
        if (r == null || isWarped(r)) {
            return r;
        }
        String f = toFrozen(r.getPath());
        if (f == null) {
            return new LiveResource(r);
        }
        Resource fr = super.getResource(f);
        // the version has no such node: it did not exist at that time
        return fr == null ? null : new FrozenResource(fr, r.getPath());
    }

    @Override
    public Resource getResource(String path) {
        if (path == null) {
            return null;
        }
        String normalized = ResourceUtil.normalize(path);
        if (normalized != null && toFrozen(normalized) != null) {
            return frozen(normalized);
        }
        return wrap(super.getResource(path));
    }

    @Override
    public Resource getResource(Resource base, String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            return getResource(path);
        }
        String abs = ResourceUtil.normalize((base == null ? "" : base.getPath()) + "/" + path);
        if (abs != null && toFrozen(abs) != null) {
            return frozen(abs);
        }
        Resource unwrapped = base instanceof FrozenResource ? ((FrozenResource) base).frozen : base instanceof LiveResource ? ((LiveResource) base).live : base;
        return wrap(super.getResource(unwrapped, path));
    }

    @Override
    public Resource resolve(HttpServletRequest request, String absPath) {
        return wrap(super.resolve(request, absPath));
    }

    @Override
    public Resource resolve(String absPath) {
        return wrap(super.resolve(absPath));
    }

    @Override
    public Resource getParent(Resource child) {
        if (child instanceof FrozenResource || child instanceof LiveResource) {
            String parent = ResourceUtil.getParent(child.getPath());
            return parent == null ? null : getResource(parent);
        }
        return wrap(super.getParent(child));
    }

    @Override
    public Iterator<Resource> listChildren(Resource parent) {
        return getChildren(parent).iterator();
    }

    @Override
    public Iterable<Resource> getChildren(Resource parent) {
        List<Resource> out = new ArrayList<>();
        if (parent instanceof FrozenResource) {
            FrozenResource fp = (FrozenResource) parent;
            for (Resource c : super.getChildren(fp.frozen)) {
                out.add(new FrozenResource(c, fp.getPath() + "/" + c.getName()));
            }
            return out;
        }
        String parentPath = parent.getPath();
        for (Resource c : super.getChildren(parent instanceof LiveResource ? ((LiveResource) parent).live : parent)) {
            String f = toFrozen(c.getPath());
            if (f == null) {
                out.add(new LiveResource(c));
            } else {
                Resource fr = super.getResource(f);
                if (fr != null) {
                    out.add(new FrozenResource(fr, c.getPath()));
                }
            }
        }
        if (ResourceUtil.getParent(contentPath).equals(parentPath) && out.stream().noneMatch(r -> r.getPath().equals(contentPath))) {
            Resource fr = super.getResource(frozenPath);
            if (fr != null) {
                out.add(new FrozenResource(fr, contentPath));
            }
        }
        return out;
    }

    @Override
    public boolean hasChildren(Resource resource) {
        return getChildren(resource).iterator().hasNext();
    }

    /** A resource outside the page content, whose navigation goes through this resolver. */
    final class LiveResource extends ResourceWrapper {
        final Resource live;

        LiveResource(Resource live) {
            super(live);
            this.live = live;
        }

        @Override
        public Resource getParent() {
            return TimewarpResolver.this.getParent(this);
        }

        @Override
        public Resource getChild(String relPath) {
            return TimewarpResolver.this.getResource(this, relPath);
        }

        @Override
        public Iterator<Resource> listChildren() {
            return TimewarpResolver.this.listChildren(this);
        }

        @Override
        public Iterable<Resource> getChildren() {
            return TimewarpResolver.this.getChildren(this);
        }

        @Override
        public boolean hasChildren() {
            return TimewarpResolver.this.hasChildren(this);
        }

        @Override
        public ResourceResolver getResourceResolver() {
            return TimewarpResolver.this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            Object o = WcmAdapterFactory.fromResource(this, type);
            return o != null ? (AdapterType) o : super.adaptTo(type);
        }
    }

    /**
     * A node of the version, presented at the live path: its type comes from the frozen
     * {@code sling:resourceType}, {@code jcr:primaryType} from {@code jcr:frozenPrimaryType}.
     */
    final class FrozenResource extends ResourceWrapper {
        final Resource frozen;
        private final String path;
        private final ResourceMetadata metadata;

        FrozenResource(Resource frozen, String path) {
            super(frozen);
            this.frozen = frozen;
            this.path = path;
            this.metadata = new ResourceMetadata();
            this.metadata.putAll(frozen.getResourceMetadata());
            this.metadata.setResolutionPath(path);
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getName() {
            return ResourceUtil.getName(path);
        }

        @Override
        public Resource getParent() {
            return TimewarpResolver.this.getParent(this);
        }

        @Override
        public Resource getChild(String relPath) {
            return TimewarpResolver.this.getResource(this, relPath);
        }

        @Override
        public Iterator<Resource> listChildren() {
            return TimewarpResolver.this.listChildren(this);
        }

        @Override
        public Iterable<Resource> getChildren() {
            return TimewarpResolver.this.getChildren(this);
        }

        @Override
        public boolean hasChildren() {
            return TimewarpResolver.this.hasChildren(this);
        }

        @Override
        public ResourceResolver getResourceResolver() {
            return TimewarpResolver.this;
        }

        @Override
        public ResourceMetadata getResourceMetadata() {
            return metadata;
        }

        @Override
        public String getResourceType() {
            ValueMap vm = frozen.getValueMap();
            String type = vm.get("sling:resourceType", String.class);
            if (type != null) {
                return type;
            }
            String primary = vm.get("jcr:frozenPrimaryType", String.class);
            return primary != null ? primary : frozen.getResourceType();
        }

        @Override
        public String getResourceSuperType() {
            return frozen.getValueMap().get("sling:resourceSuperType", String.class);
        }

        @Override
        public boolean isResourceType(String resourceType) {
            return TimewarpResolver.this.isResourceType(this, resourceType);
        }

        @Override
        public ValueMap getValueMap() {
            Map<String, Object> m = new HashMap<>(frozen.getValueMap());
            Object primary = m.remove("jcr:frozenPrimaryType");
            if (primary != null) {
                m.put("jcr:primaryType", primary);
            }
            Object mixins = m.remove("jcr:frozenMixinTypes");
            if (mixins != null) {
                m.put("jcr:mixinTypes", mixins);
            }
            m.remove("jcr:frozenUuid");
            return new ValueMapDecorator(Collections.unmodifiableMap(m));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type == ValueMap.class) {
                return (AdapterType) getValueMap();
            }
            if (type == Map.class) {
                return (AdapterType) getValueMap();
            }
            // pages, templated views and styles must be built on this (warped) resource, not the frozen node
            Object o = WcmAdapterFactory.fromResource(this, type);
            return o != null ? (AdapterType) o : super.adaptTo(type);
        }

        @Override
        public String toString() {
            return "FrozenResource{" + path + " <- " + frozen.getPath() + "}";
        }
    }
}
