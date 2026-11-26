/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.TemplatedResource;

/**
 * A page content resource merged with the matching node of its editable template's structure
 * ({@code <template>/structure/jcr:content/<same relative path>}):
 * <ul>
 *   <li>children come in structure order; for each, the page's own child wins if it exists,
 *       otherwise the structure child is used as-is (locked template parts such as header and
 *       footer experience fragments);</li>
 *   <li>below a structure node marked {@code editable=true}, only the page's children count
 *       (that is where authors put content);</li>
 *   <li>page children not present in the structure are appended;</li>
 *   <li>an editable structure node the page has no content for yet answers at the page's path (with the
 *       structure's properties and no children), so the editor can add the first component to it;</li>
 *   <li>a locked structure component the page has a copy of (its initial content) takes its properties from the
 *       template, as in AEM: the root container's {@code layout=responsiveGrid} of the structure applies even though
 *       the page's {@code root} node does not have it. The page content node itself keeps its own properties.</li>
 * </ul>
 * Children are themselves merged, so the view is consistent at every level.
 */
final class TemplatedResourceImpl extends ResourceWrapper implements TemplatedResource {

    /** Properties a copy never takes from the template (the node's own identity and history). */
    private static final Set<String> OWN = new HashSet<>(Arrays.asList(
            "jcr:primaryType", "jcr:mixinTypes", "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:lastModified", "jcr:lastModifiedBy"));

    private final Resource structure;
    /** A locked part of the structure (not the page content node, not editable): the template's properties apply. */
    private final boolean locked;

    private TemplatedResourceImpl(Resource content, Resource structure) {
        this(content, structure, false);
    }

    private TemplatedResourceImpl(Resource content, Resource structure, boolean pageContent) {
        super(content);
        this.structure = structure;
        this.locked = !pageContent && structure != null && !structure.getValueMap().get("editable", false)
                && !(content instanceof Unauthored);
    }

    /**
     * A locked part of the template's structure rendered on a page (the root container, a locked header): as in
     * AEM, authors cannot select or change it there; only the template editor can.
     */
    boolean isLocked() {
        return locked;
    }

    /** @return whether {@code resource} is a locked structure part of its page's template (see {@link #isLocked()}) */
    static boolean isLocked(Resource resource) {
        if (resource == null) {
            return false;
        }
        TemplatedResource t = resource instanceof TemplatedResourceImpl ? (TemplatedResource) resource : of(resource);
        return t instanceof TemplatedResourceImpl && ((TemplatedResourceImpl) t).locked;
    }

    @Override
    public ValueMap getValueMap() {
        return locked ? templateProperties() : super.getValueMap();
    }

    /** The page's copy, with the template's properties over it. */
    private ValueMap templateProperties() {
        Map<String, Object> m = new HashMap<>(super.getValueMap());
        for (Map.Entry<String, Object> e : structure.getValueMap().entrySet()) {
            if (!OWN.contains(e.getKey())) {
                m.put(e.getKey(), e.getValue());
            }
        }
        return new ValueMapDecorator(m);
    }

    /**
     * @return the merged view of {@code resource}, or {@code null} if it is not inside a page using
     *         an editable template
     */
    static TemplatedResource of(Resource resource) {
        if (resource instanceof TemplatedResourceImpl) {
            return (TemplatedResource) resource;
        }
        PageManagerImpl pm = new PageManagerImpl(resource.getResourceResolver());
        Page page = pm.getContainingPage(resource);
        if (page == null) {
            return null;
        }
        Template template = page.getTemplate();
        if (template == null || template.getStructurePath() == null) {
            return null;
        }
        if (page.getPath().equals(template.getStructurePath())) {
            return null; // the structure itself (the template editor): nothing to merge, nothing locked
        }
        String contentRoot = page.getPath() + "/" + NameConstants.NN_CONTENT;
        String path = resource.getPath();
        String rel;
        if (path.equals(contentRoot)) {
            rel = "";
        } else if (path.startsWith(contentRoot + "/")) {
            rel = path.substring(contentRoot.length() + 1);
        } else {
            return null;
        }
        String structurePath = template.getStructurePath() + "/" + NameConstants.NN_CONTENT + (rel.isEmpty() ? "" : "/" + rel);
        Resource structure = resource.getResourceResolver().getResource(structurePath);
        return new TemplatedResourceImpl(resource, structure, rel.isEmpty());
    }

    @Override
    public String getResourceType() {
        Resource own = getResource();
        // a node Sling POST created for the page (the first component added to an unlocked container) has no type
        // of its own: it is the structure's
        if (structure != null && own.getValueMap().get("sling:resourceType", String.class) == null) {
            return structure.getResourceType();
        }
        return super.getResourceType();
    }

    @Override
    public Iterator<Resource> listChildren() {
        return children().iterator();
    }

    @Override
    public Iterable<Resource> getChildren() {
        return children();
    }

    @Override
    public boolean hasChildren() {
        return !children().isEmpty();
    }

    @Override
    public Resource getChild(String relPath) {
        if (relPath.indexOf('/') < 0) {
            for (Resource c : children()) {
                if (c.getName().equals(relPath)) {
                    return c;
                }
            }
            return null;
        }
        return super.getChild(relPath);
    }

    private List<Resource> children() {
        Resource content = getResource();
        Map<String, Resource> out = new LinkedHashMap<>();
        boolean editable = structure != null && structure.getValueMap().get("editable", false);
        if (structure != null && !editable) {
            for (Resource s : structure.getChildren()) {
                if (s.getName().startsWith("cq:") || s.getName().startsWith("jcr:")) {
                    continue;
                }
                Resource own = content.getChild(s.getName());
                if (own != null) {
                    out.put(s.getName(), new TemplatedResourceImpl(own, s));
                } else if (s.getValueMap().get("editable", false)) {
                    // an unlocked part the page has no content for yet: it is the page's (authors add to it),
                    // so it answers at the page's path, with the template's properties and no children
                    out.put(s.getName(), new TemplatedResourceImpl(new Unauthored(content, s), s));
                } else {
                    out.put(s.getName(), s);
                }
            }
        }
        for (Resource own : content.getChildren()) {
            if (!out.containsKey(own.getName())) {
                Resource s = structure == null ? null : structure.getChild(own.getName());
                out.put(own.getName(), new TemplatedResourceImpl(own, s));
            }
        }
        return new ArrayList<>(out.values());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if (type == TemplatedResource.class) {
            return (AdapterType) this;
        }
        if (type == ValueMap.class && locked) {
            return (AdapterType) templateProperties();
        }
        return super.adaptTo(type);
    }

    /** An unlocked structure node at the page's path, before the page has content of its own there. */
    static final class Unauthored extends ResourceWrapper {
        private final Resource parent;

        Unauthored(Resource parent, Resource structure) {
            super(structure);
            this.parent = parent;
        }

        @Override
        public String getPath() {
            return parent.getPath() + "/" + getName();
        }

        @Override
        public Resource getParent() {
            return parent;
        }

        @Override
        public Iterator<Resource> listChildren() {
            return java.util.Collections.emptyIterator();
        }

        @Override
        public Iterable<Resource> getChildren() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public Resource getChild(String relPath) {
            return null;
        }

        @Override
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type.getName().startsWith("javax.jcr.")) {
                return null; // the node is the template's: nothing writes to it through the page
            }
            return super.adaptTo(type);
        }
    }
}
