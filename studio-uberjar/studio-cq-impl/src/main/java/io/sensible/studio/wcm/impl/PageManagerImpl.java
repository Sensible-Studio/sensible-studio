/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.WCMException;

/**
 * {@link PageManager} over a {@link ResourceResolver}. All writes go through the generic Sling
 * CRUD API, so it works on any writable resource provider (Oak via JCR, or a mock in tests).
 *
 * <p>Copies are done node by node rather than with {@code Workspace.copy}; that keeps the code
 * store-agnostic but means copies get fresh identifiers and no version history.
 */
final class PageManagerImpl implements PageManager {

    private final ResourceResolver resolver;

    PageManagerImpl(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    ResourceResolver getResolver() {
        return resolver;
    }

    // ---------------------------------------------------------------- read

    @Override
    public Page getPage(String pagePath) {
        if (pagePath == null) {
            return null;
        }
        Resource r = resolver.getResource(pagePath);
        return Pages.isPage(r) ? new PageImpl(r, this) : null;
    }

    @Override
    public Page getContainingPage(Resource resource) {
        for (Resource r = resource; r != null; r = r.getParent()) {
            if (Pages.isPage(r)) {
                return new PageImpl(r, this);
            }
        }
        return null;
    }

    @Override
    public Page getContainingPage(String path) {
        if (path == null) {
            return null;
        }
        // walk up string-wise: intermediate nodes may be unreadable or missing
        for (String p = path; ; p = Pages.parentPath(p)) {
            Page page = getPage(p);
            if (page != null) {
                return page;
            }
            if ("/".equals(p)) {
                return null;
            }
        }
    }

    @Override
    public Template getTemplate(String templatePath) {
        if (templatePath == null) {
            return null;
        }
        Resource r = resolver.getResource(templatePath);
        return Pages.isTemplate(r) ? new TemplateImpl(r) : null;
    }

    // ---------------------------------------------------------------- create

    @Override
    public Page create(String parentPath, String pageName, String template, String title) throws WCMException {
        return create(parentPath, pageName, template, title, true);
    }

    @Override
    public Page create(String parentPath, String pageName, String template, String title, boolean autoSave)
            throws WCMException {
        Resource parent = parentPath == null ? null : resolver.getResource(parentPath);
        if (parent == null) {
            throw new WCMException("Parent " + parentPath + " does not exist");
        }

        TemplateImpl tmpl = null;
        if (template != null && !template.isEmpty()) {
            tmpl = (TemplateImpl) getTemplate(template);
            if (tmpl == null) {
                throw new WCMException("Template " + template + " does not exist");
            }
            if (!tmpl.isAllowed(parentPath)) {
                throw new WCMException("Template " + template + " is not allowed below " + parentPath);
            }
        }

        String name;
        if (pageName == null || pageName.isEmpty()) {
            name = uniqueName(parent, Pages.toValidName(title));
        } else {
            if (parent.getChild(pageName) != null) {
                throw new WCMException("Page " + Pages.child(parentPath, pageName) + " already exists");
            }
            name = pageName;
        }

        try {
            Resource pageRes = resolver.create(parent, name, props(Pages.PN_PRIMARY_TYPE, NameConstants.NT_PAGE));
            Resource content = createContent(pageRes, tmpl);
            ModifiableValueMap mvm = writable(content);
            if (tmpl != null) {
                mvm.put(NameConstants.PN_TEMPLATE, tmpl.getPath());
            }
            if (title != null) {
                mvm.put(NameConstants.PN_TITLE, title);
            }
            stamp(mvm);
            if (autoSave) {
                resolver.commit();
            }
            return new PageImpl(pageRes, this);
        } catch (PersistenceException e) {
            resolver.revert();
            throw new WCMException("Could not create page " + name + " below " + parentPath, e);
        }
    }

    /**
     * Editable templates copy {@code initial/jcr:content}; static templates copy their own
     * {@code jcr:content} (which carries the page component's {@code sling:resourceType}).
     */
    private Resource createContent(Resource pageRes, TemplateImpl tmpl) throws PersistenceException {
        Resource source = null;
        if (tmpl != null) {
            Resource tr = tmpl.adaptTo(Resource.class);
            source = tr.getChild(TemplateImpl.NN_INITIAL + "/" + NameConstants.NN_CONTENT);
            if (source == null && !tmpl.hasStructureSupport()) {
                source = tr.getChild(NameConstants.NN_CONTENT);
            }
        }
        if (source != null) {
            return copyTree(source, pageRes, NameConstants.NN_CONTENT, false);
        }
        return resolver.create(pageRes, NameConstants.NN_CONTENT,
                props(Pages.PN_PRIMARY_TYPE, NameConstants.NT_PAGE_CONTENT));
    }

    // ---------------------------------------------------------------- delete

    @Override
    public void delete(Page page, boolean shallow) throws WCMException {
        delete(page, shallow, true);
    }

    @Override
    public void delete(Page page, boolean shallow, boolean autoSave) throws WCMException {
        if (page == null) {
            throw new WCMException("Page is null");
        }
        delete(page.adaptTo(Resource.class), shallow, autoSave);
    }

    @Override
    public void delete(Resource resource, boolean shallow, boolean autoSave) throws WCMException {
        if (resource == null) {
            throw new WCMException("Resource is null");
        }
        try {
            if (shallow && Pages.isPage(resource)) {
                // keep the page node so child pages survive; drop everything that is not a page
                for (Resource child : resource.getChildren()) {
                    if (!Pages.isPage(child)) {
                        resolver.delete(child);
                    }
                }
            } else {
                resolver.delete(resource);
            }
            if (autoSave) {
                resolver.commit();
            }
        } catch (PersistenceException e) {
            resolver.revert();
            throw new WCMException("Could not delete " + resource.getPath(), e);
        }
    }

    // ---------------------------------------------------------------- copy / move / order

    @Override
    public Page copy(Page page, String destination, String beforeName, boolean shallow, boolean resolveConflict)
            throws WCMException {
        return copy(page, destination, beforeName, shallow, resolveConflict, true);
    }

    @Override
    public Page copy(Page page, String destination, String beforeName, boolean shallow, boolean resolveConflict,
            boolean autoSave) throws WCMException {
        Resource source = requirePage(page);
        if (!shallow && (destination.equals(source.getPath()) || destination.startsWith(source.getPath() + "/"))) {
            throw new WCMException("Cannot copy " + source.getPath() + " into itself");
        }
        Resource destParent = resolver.getResource(Pages.parentPath(destination));
        if (destParent == null) {
            throw new WCMException("Destination parent " + Pages.parentPath(destination) + " does not exist");
        }
        String name = Pages.name(destination);
        if (destParent.getChild(name) != null) {
            if (!resolveConflict) {
                throw new WCMException("Destination " + destination + " already exists");
            }
            name = uniqueName(destParent, name);
        }
        try {
            Resource copy = copyTree(source, destParent, name, shallow);
            io.sensible.studio.wcm.impl.msm.MsmCopies.afterCopy(copy);
            Resource content = copy.getChild(NameConstants.NN_CONTENT);
            if (content != null) {
                ModifiableValueMap mvm = writable(content);
                mvm.remove(Pages.PN_LOCK_OWNER);
                stamp(mvm);
            }
            if (beforeName != null) {
                resolver.orderBefore(destParent, name, beforeName);
            }
            if (autoSave) {
                resolver.commit();
            }
            return new PageImpl(copy, this);
        } catch (PersistenceException | RuntimeException e) {
            resolver.revert();
            throw new WCMException("Could not copy " + source.getPath() + " to " + destination, e);
        }
    }

    @Override
    public Page move(Page page, String destination, String beforeName, boolean shallow, boolean resolveConflict,
            String[] adjustRefs) throws WCMException {
        Resource source = requirePage(page);
        String sourcePath = source.getPath();
        if (destination == null || destination.equals(sourcePath)) {
            order(page, beforeName);
            return getPage(sourcePath);
        }
        if (destination.startsWith(sourcePath + "/")) {
            throw new WCMException("Cannot move " + sourcePath + " into itself");
        }
        List<References.Ref> refs = adjustRefs == null ? java.util.Collections.emptyList()
                : References.find(resolver, sourcePath, References.DEFAULT_ROOTS, true).stream()
                        .filter(r -> inside(r.resourcePath, sourcePath) || java.util.Arrays.asList(adjustRefs).contains(r.pagePath))
                        .collect(java.util.stream.Collectors.toList());
        Page moved = copy(page, destination, beforeName, shallow, resolveConflict, false);
        delete(source, shallow, false);
        try {
            References.adjust(resolver, refs, sourcePath, moved.getPath());
            resolver.commit();
        } catch (PersistenceException e) {
            resolver.revert();
            throw new WCMException("Could not move " + sourcePath + " to " + destination, e);
        }
        return getPage(moved.getPath());
    }

    @Override
    public void order(Page page, String beforeName) throws WCMException {
        Resource source = requirePage(page);
        Resource parent = source.getParent();
        if (parent == null) {
            throw new WCMException("Page " + source.getPath() + " has no parent");
        }
        try {
            resolver.orderBefore(parent, source.getName(), beforeName);
            resolver.commit();
        } catch (PersistenceException | RuntimeException e) {
            resolver.revert();
            throw new WCMException("Could not order " + source.getPath(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    static boolean inside(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    // ---------------------------------------------------------------- versions

    @Override
    public Revision createRevision(Page page) throws WCMException {
        return createRevision(page, null, null);
    }

    @Override
    public Revision createRevision(Page page, String label, String comment) throws WCMException {
        return new PageRevisions(resolver).create(requirePage(page).getPath(), label, comment);
    }

    @Override
    public Collection<Revision> getRevisions(String path, Calendar cal) throws WCMException {
        return new PageRevisions(resolver).list(path, cal);
    }

    @Override
    public Page restore(String path, String revisionId) throws WCMException {
        new PageRevisions(resolver).restore(path, revisionId);
        resolver.refresh();
        return getPage(path);
    }

    private Resource requirePage(Page page) throws WCMException {
        Resource r = page == null ? null : page.adaptTo(Resource.class);
        if (r == null || !Pages.isPage(r)) {
            throw new WCMException("Not a page: " + page);
        }
        return r;
    }

    /**
     * Recursively copies {@code source} to {@code destParent/name}. With {@code skipPages}, child
     * pages are left out (shallow page copy).
     */
    private Resource copyTree(Resource source, Resource destParent, String name, boolean skipPages)
            throws PersistenceException {
        Map<String, Object> props = new HashMap<>();
        for (Map.Entry<String, Object> e : source.getValueMap().entrySet()) {
            if (!Pages.NON_COPYABLE.contains(e.getKey())) {
                props.put(e.getKey(), e.getValue());
            }
        }
        Resource target = resolver.create(destParent, name, props);
        for (Resource child : source.getChildren()) {
            if (skipPages && Pages.isPage(child)) {
                continue;
            }
            copyTree(child, target, child.getName(), false);
        }
        return target;
    }

    private String uniqueName(Resource parent, String base) {
        if (parent.getChild(base) == null) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = base + "-" + i;
            if (parent.getChild(candidate) == null) {
                return candidate;
            }
        }
    }

    private void stamp(ModifiableValueMap mvm) {
        mvm.put(NameConstants.PN_PAGE_LAST_MOD, Calendar.getInstance());
        String user = resolver.getUserID();
        mvm.put(NameConstants.PN_PAGE_LAST_MOD_BY, user == null ? "anonymous" : user);
    }

    private static ModifiableValueMap writable(Resource resource) throws PersistenceException {
        ModifiableValueMap mvm = resource.adaptTo(ModifiableValueMap.class);
        if (mvm == null) {
            throw new PersistenceException("Resource " + resource.getPath() + " is not writable");
        }
        return mvm;
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
