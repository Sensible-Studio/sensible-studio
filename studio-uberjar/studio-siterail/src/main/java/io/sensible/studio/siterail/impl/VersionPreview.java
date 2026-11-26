/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.adapter.AdapterManager;
import org.apache.sling.api.resource.AbstractResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.api.wrappers.ValueMapDecorator;

/**
 * A page as it was in one of its versions, for Preview Version and Compare to Current.
 *
 * <p>{@link #resolver} is a resolver in which {@code <page>/jcr:content} and everything below it is the version's
 * frozen content, read only; the rest of the repository is as it is. Rendering the page with it (through Sling's
 * request processor) gives the page as it looked then, with today's components, templates and assets.</p>
 */
final class VersionPreview {

    private static final String CONTENT = "jcr:content";

    private VersionPreview() {
    }

    /** The frozen content node of a version of the page (by version identifier or name). */
    static Node frozenContent(ResourceResolver resolver, String pagePath, String version) throws RepositoryException {
        return frozenContent(resolver, resolver, pagePath, version);
    }

    /**
     * The frozen content of a version; a deleted page's version is found through its deletion record, read with
     * {@code store} (the service resolver: authors cannot read {@code /var/studio/deleted}).
     */
    static Node frozenContent(ResourceResolver resolver, ResourceResolver store, String pagePath, String version) throws RepositoryException {
        Session s = resolver.adaptTo(Session.class);
        if (s == null) {
            throw new IllegalStateException("Page versions need a JCR repository");
        }
        String content = pagePath + "/" + CONTENT;
        if (version != null && version.startsWith(KeptPages.PREFIX) && !s.nodeExists(pagePath)) {
            // a page deleted outside the console, kept as it was
            Session ss = store.adaptTo(Session.class);
            Node kept = ss == null || !VersionPreviewServlet.parentReadable(resolver, pagePath) ? null : KeptPages.node(ss, pagePath, version);
            if (kept != null && kept.hasNode(CONTENT)) {
                return kept.getNode(CONTENT);
            }
            throw new IllegalArgumentException("No version " + version + " of " + pagePath);
        }
        VersionHistory history = null;
        if (s.nodeExists(content) && s.getNode(content).isNodeType("mix:versionable")) {
            history = s.getWorkspace().getVersionManager().getVersionHistory(content);
        } else if (!s.nodeExists(pagePath)) {
            Resource record = store.getResource(VersionRestore.DELETED + pagePath);
            String id = record == null ? null : record.getValueMap().get(VersionRestore.HISTORY, String.class);
            Session ss = store.adaptTo(Session.class);
            Node n = id == null || ss == null ? null : ss.getNodeByIdentifier(id);
            history = n instanceof VersionHistory ? (VersionHistory) n : null;
        }
        if (history != null && version != null) {
            for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
                Version v = it.nextVersion();
                if (!"jcr:rootVersion".equals(v.getName()) && (v.getIdentifier().equals(version) || v.getName().equals(version))) {
                    return v.getFrozenNode();
                }
            }
        }
        throw new IllegalArgumentException("No version " + version + " of " + pagePath);
    }

    /** A resolver that shows {@code frozen} as the content of {@code pagePath}. */
    static ResourceResolver resolver(ResourceResolver delegate, String pagePath, Node frozen, AdapterManager adapters) {
        return new Overlay(delegate, pagePath + "/" + CONTENT, frozen, adapters);
    }

    // ------------------------------------------------------------------------------------------ the overlay

    static final class Overlay extends ResourceResolverWrapper {

        private final ResourceResolver delegate;
        private final String contentPath;
        private final Node frozen;
        private final AdapterManager adapters;

        /** A deleted page: the overlay shows a page node for it too. */
        private final String ghostPath;

        /** The content is a kept copy of a deleted page (ordinary nodes), not a version's frozen nodes. */
        final boolean kept;

        Overlay(ResourceResolver delegate, String contentPath, Node frozen, AdapterManager adapters) {
            super(delegate);
            this.delegate = delegate;
            this.contentPath = contentPath;
            this.frozen = frozen;
            this.adapters = adapters;
            String page = contentPath.substring(0, contentPath.length() - CONTENT.length() - 1);
            this.ghostPath = delegate.getResource(page) == null ? page : null;
            boolean frozenNode;
            try {
                frozenNode = frozen.isNodeType("nt:frozenNode");
            } catch (RepositoryException e) {
                frozenNode = true;
            }
            this.kept = !frozenNode;
        }

        private Resource ghost() {
            return new Ghost(this, ghostPath);
        }

        private boolean inside(String path) {
            return path != null && (path.equals(contentPath) || path.startsWith(contentPath + "/"));
        }

        /** The frozen resource at an absolute path inside the content (null when the version has nothing there). */
        private Resource frozenAt(String path) {
            try {
                String rel = path.length() == contentPath.length() ? "" : path.substring(contentPath.length() + 1);
                Node n = rel.isEmpty() ? frozen : frozen.hasNode(rel) ? frozen.getNode(rel) : null;
                return n == null || !kept && !n.isNodeType("nt:frozenNode") ? null : new Frozen(this, path, n);
            } catch (RepositoryException e) {
                return null;
            }
        }

        private static String normalize(String path) {
            Deque segments = new Deque();
            for (String s : path.split("/")) {
                if (s.isEmpty() || ".".equals(s)) {
                    continue;
                }
                if ("..".equals(s)) {
                    segments.pop();
                } else {
                    segments.push(s);
                }
            }
            return "/" + String.join("/", segments.items);
        }

        private static final class Deque {
            final List<String> items = new ArrayList<>();

            void push(String s) {
                items.add(s);
            }

            void pop() {
                if (!items.isEmpty()) {
                    items.remove(items.size() - 1);
                }
            }
        }

        /** A resource outside the version, which reaches its children and adapters through the overlay. */
        Resource live(Resource r) {
            return r == null || r instanceof Frozen || r instanceof Live ? r : new Live(this, r);
        }

        @Override
        public Resource getResource(String path) {
            if (path != null && path.startsWith("/")) {
                String p = normalize(path);
                if (inside(p)) {
                    return frozenAt(p);
                }
                if (p.equals(ghostPath)) {
                    return ghost();
                }
            }
            return live(super.getResource(path));
        }

        @Override
        public Resource getResource(Resource base, String path) {
            if (path != null && !path.startsWith("/") && base != null) {
                return getResource(base.getPath() + "/" + path);
            }
            return getResource(path);
        }

        private Resource swap(Resource r) {
            if (ghostPath != null && r != null && org.apache.sling.api.resource.ResourceUtil.isNonExistingResource(r)
                    && (r.getPath().equals(ghostPath) || r.getPath().startsWith(ghostPath + "."))) {
                // the request for the deleted page itself (<page>.html): the page as it was
                Resource g = ghost();
                g.getResourceMetadata().setResolutionPath(ghostPath);
                g.getResourceMetadata().setResolutionPathInfo(r.getPath().substring(ghostPath.length()));
                return g;
            }
            if (r != null && org.apache.sling.api.resource.ResourceUtil.isNonExistingResource(r)) {
                Resource f = deepest(unmangle(r.getPath()));
                if (f != null) {
                    return f;
                }
            }
            if (r == null || !inside(r.getPath())) {
                return live(r);
            }
            Resource f = frozenAt(r.getPath());
            if (f != null) {
                f.getResourceMetadata().setResolutionPath(r.getResourceMetadata().getResolutionPath());
                f.getResourceMetadata().setResolutionPathInfo(r.getResourceMetadata().getResolutionPathInfo());
            }
            return f == null ? live(r) : f;
        }

        /**
         * A request below the version's content that the repository does not have (an image of a deleted page, or one
         * removed since): the deepest frozen resource, the rest of the path as path info, as Sling resolves.
         */
        private Resource deepest(String path) {
            for (String p = path; inside(p);) {
                Resource f = frozenAt(p);
                if (f != null) {
                    f.getResourceMetadata().setResolutionPath(p);
                    f.getResourceMetadata().setResolutionPathInfo(path.substring(p.length()));
                    return f;
                }
                int dot = p.lastIndexOf('.');
                if (dot <= contentPath.length()) {
                    return null;
                }
                p = p.substring(0, dot);
            }
            return null;
        }

        /** {@code /_jcr_content} as it appears in URLs, {@code /jcr:content} as it is named. */
        static String unmangle(String path) {
            return path.replaceAll("/_([a-z]+)_", "/$1:");
        }

        @Override
        public Resource resolve(HttpServletRequest request, String absPath) {
            return swap(super.resolve(request, absPath));
        }

        @Override
        public Resource resolve(String absPath) {
            return swap(super.resolve(absPath));
        }

        @Override
        @SuppressWarnings("deprecation")
        public Resource resolve(HttpServletRequest request) {
            return swap(super.resolve(request));
        }

        @Override
        public Iterator<Resource> listChildren(Resource parent) {
            return children(parent).iterator();
        }

        @Override
        public Iterable<Resource> getChildren(Resource parent) {
            return children(parent);
        }

        @Override
        public boolean hasChildren(Resource resource) {
            return !children(resource).isEmpty();
        }

        private List<Resource> children(Resource parent) {
            List<Resource> out = new ArrayList<>();
            if (parent instanceof Ghost) {
                Resource f = frozenAt(contentPath);
                if (f != null) {
                    out.add(f);
                }
                return out;
            }
            if (parent instanceof Frozen) {
                ((Frozen) parent).children(out);
                return out;
            }
            for (Iterator<Resource> it = super.listChildren(parent); it.hasNext();) {
                Resource c = it.next();
                if (contentPath.equals(c.getPath())) {
                    Resource f = frozenAt(contentPath);
                    if (f != null) {
                        out.add(f);
                    }
                } else {
                    out.add(live(c));
                }
            }
            return out;
        }

        @Override
        public Resource getParent(Resource child) {
            if (child instanceof Frozen) {
                String p = child.getPath();
                String parent = p.substring(0, p.lastIndexOf('/'));
                return getResource(parent.isEmpty() ? "/" : parent);
            }
            return live(super.getParent(child));
        }

        @Override
        public <T> T adaptTo(Class<T> type) {
            // the page manager and friends must read through the overlay, not the plain resolver
            T t = adapters == null ? null : adapters.getAdapter(this, type);
            return t != null ? t : delegate.adaptTo(type);
        }

        @Override
        public void commit() {
            throw new UnsupportedOperationException("A version preview is read only");
        }
    }

    // ------------------------------------------------------------------------------------------ a live resource

    /** A resource of the repository as it is, seen through the overlay (its jcr:content child is the version's). */
    static final class Live extends ResourceWrapper {

        private final Overlay resolver;

        Live(Overlay resolver, Resource resource) {
            super(resource);
            this.resolver = resolver;
        }

        @Override
        public ResourceResolver getResourceResolver() {
            return resolver;
        }

        @Override
        public Resource getChild(String relPath) {
            return resolver.getResource(this, relPath);
        }

        @Override
        public Iterator<Resource> listChildren() {
            return resolver.listChildren(this);
        }

        @Override
        public Iterable<Resource> getChildren() {
            return resolver.getChildren(this);
        }

        @Override
        public boolean hasChildren() {
            return resolver.hasChildren(this);
        }

        @Override
        public Resource getParent() {
            return resolver.getParent(this);
        }

        @Override
        public <T> T adaptTo(Class<T> type) {
            // a Page made from this resource must read its content through the overlay
            T t = resolver.adapters == null ? null : resolver.adapters.getAdapter(this, type);
            return t != null ? t : super.adaptTo(type);
        }
    }

    // ------------------------------------------------------------------------------------------ a deleted page

    /** The {@code cq:Page} node of a deleted page, holding the version's content. */
    static final class Ghost extends AbstractResource {

        private final Overlay resolver;
        private final String path;
        private final ResourceMetadata metadata = new ResourceMetadata();

        Ghost(Overlay resolver, String path) {
            this.resolver = resolver;
            this.path = path;
            metadata.setResolutionPath(path);
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getResourceType() {
            return "cq:Page";
        }

        @Override
        public String getResourceSuperType() {
            return null;
        }

        @Override
        public ResourceMetadata getResourceMetadata() {
            return metadata;
        }

        @Override
        public ResourceResolver getResourceResolver() {
            return resolver;
        }

        @Override
        public ValueMap getValueMap() {
            return new ValueMapDecorator(Collections.singletonMap("jcr:primaryType", "cq:Page"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T adaptTo(Class<T> type) {
            if (type == ValueMap.class || type == Map.class) {
                return (T) getValueMap();
            }
            if (type == Node.class) {
                return null;
            }
            T t = resolver.adapters == null ? null : resolver.adapters.getAdapter(this, type);
            return t != null ? t : super.adaptTo(type);
        }
    }

    // ------------------------------------------------------------------------------------------ a frozen resource

    static final class Frozen extends AbstractResource {

        private final Overlay resolver;
        private final String path;
        private final Node node;
        private final ResourceMetadata metadata = new ResourceMetadata();
        private ValueMap properties;

        Frozen(Overlay resolver, String path, Node node) {
            this.resolver = resolver;
            this.path = path;
            this.node = node;
            metadata.setResolutionPath(path);
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getResourceType() {
            String t = getValueMap().get("sling:resourceType", String.class);
            return t != null ? t : getValueMap().get("jcr:primaryType", "nt:unstructured");
        }

        @Override
        public String getResourceSuperType() {
            return getValueMap().get("sling:resourceSuperType", String.class);
        }

        @Override
        public ResourceMetadata getResourceMetadata() {
            return metadata;
        }

        @Override
        public ResourceResolver getResourceResolver() {
            return resolver;
        }

        @Override
        public ValueMap getValueMap() {
            if (properties == null) {
                properties = new FrozenValueMap(node, read(node));
            }
            return properties;
        }

        void children(List<Resource> out) {
            try {
                for (NodeIterator it = node.getNodes(); it.hasNext();) {
                    Node c = it.nextNode();
                    if (resolver.kept || c.isNodeType("nt:frozenNode")) {
                        out.add(new Frozen(resolver, path + "/" + c.getName(), c));
                    }
                }
            } catch (RepositoryException e) {
                // nothing more to show
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T adaptTo(Class<T> type) {
            if (type == ValueMap.class || type == Map.class) {
                return (T) getValueMap();
            }
            if (type == Node.class) {
                return null; // read only: no JCR node to change
            }
            if (type == java.io.InputStream.class) {
                return (T) stream(node); // an uploaded file (nt:file, nt:resource) as it was
            }
            return super.adaptTo(type);
        }

        @Override
        public String toString() {
            return "Frozen[" + path + "]";
        }
    }

    /** The data of a frozen file: its own {@code jcr:data}, or its {@code jcr:content}'s; null when it has none. */
    static java.io.InputStream stream(Node frozen) {
        try {
            Node n = frozen.hasProperty("jcr:data") ? frozen : frozen.hasNode("jcr:content") ? frozen.getNode("jcr:content") : null;
            return n == null || !n.hasProperty("jcr:data") ? null : n.getProperty("jcr:data").getBinary().getStream();
        } catch (RepositoryException e) {
            return null;
        }
    }

    /** Stands for a binary property in the values read from a frozen node. */
    static final Object BINARY = new Object() {
        @Override
        public String toString() {
            return "(binary)";
        }
    };

    /**
     * The properties of a frozen node. Binaries are listed like the repository lists them (an image servlet asks
     * whether {@code jcr:data} is there) and opened as a stream when read, each time anew.
     */
    static final class FrozenValueMap extends ValueMapDecorator {

        private final Node node;

        FrozenValueMap(Node node, Map<String, Object> values) {
            super(Collections.unmodifiableMap(values));
            this.node = node;
        }

        @Override
        public Object get(Object key) {
            Object v = super.get(key);
            return v == BINARY ? get(String.valueOf(key), java.io.InputStream.class) : v;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String name, Class<T> type) {
            if (type == java.io.InputStream.class) {
                try {
                    if (node.hasProperty(name) && node.getProperty(name).getType() == PropertyType.BINARY) {
                        return (T) node.getProperty(name).getBinary().getStream();
                    }
                } catch (RepositoryException e) {
                    return null;
                }
            }
            if (super.get(name) == BINARY) {
                return null; // a binary is only given as a stream
            }
            return super.get(name, type);
        }
    }

    /** The properties of a frozen node as the live node had them. */
    static Map<String, Object> read(Node frozen) {
        Map<String, Object> m = new HashMap<>();
        try {
            for (PropertyIterator it = frozen.getProperties(); it.hasNext();) {
                Property p = it.nextProperty();
                String n = p.getName();
                if (n.startsWith("jcr:frozen") || "jcr:uuid".equals(n) || "jcr:primaryType".equals(n) || "jcr:mixinTypes".equals(n)) {
                    continue;
                }
                if (p.getType() == PropertyType.BINARY) {
                    if (!p.isMultiple()) {
                        m.put(n, BINARY); // listed, and opened as a stream when read (FrozenValueMap)
                    }
                    continue;
                }
                m.put(n, p.isMultiple() ? values(p.getValues()) : value(p.getValue()));
            }
            if (frozen.hasProperty("jcr:frozenPrimaryType")) {
                m.put("jcr:primaryType", frozen.getProperty("jcr:frozenPrimaryType").getString());
            } else if (!frozen.isNodeType("nt:frozenNode")) {
                // a kept page (KeptPages): an ordinary node
                m.put("jcr:primaryType", frozen.getPrimaryNodeType().getName());
            }
            if (frozen.hasProperty("jcr:frozenMixinTypes")) {
                m.put("jcr:mixinTypes", values(frozen.getProperty("jcr:frozenMixinTypes").getValues()));
            } else if (!frozen.isNodeType("nt:frozenNode") && frozen.getMixinNodeTypes().length > 0) {
                String[] mixins = java.util.Arrays.stream(frozen.getMixinNodeTypes()).map(javax.jcr.nodetype.NodeType::getName).toArray(String[]::new);
                m.put("jcr:mixinTypes", mixins);
            }
        } catch (RepositoryException e) {
            // what was read is what there is
        }
        return m;
    }

    private static Object value(Value v) throws RepositoryException {
        switch (v.getType()) {
            case PropertyType.LONG:
                return v.getLong();
            case PropertyType.DOUBLE:
                return v.getDouble();
            case PropertyType.DECIMAL:
                return v.getDecimal();
            case PropertyType.BOOLEAN:
                return v.getBoolean();
            case PropertyType.DATE:
                return v.getDate();
            default:
                return v.getString();
        }
    }

    private static Object values(Value[] vs) throws RepositoryException {
        if (vs.length == 0) {
            return new String[0];
        }
        Object first = value(vs[0]);
        Object[] out = first instanceof Long ? new Long[vs.length] : first instanceof Double ? new Double[vs.length]
                : first instanceof BigDecimal ? new BigDecimal[vs.length] : first instanceof Boolean ? new Boolean[vs.length]
                : first instanceof Calendar ? new Calendar[vs.length] : new String[vs.length];
        for (int i = 0; i < vs.length; i++) {
            out[i] = value(vs[i]);
        }
        return out;
    }
}
