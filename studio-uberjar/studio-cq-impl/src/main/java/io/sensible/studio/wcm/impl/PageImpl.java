/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.commons.Filter;
import com.day.cq.tagging.Tag;
import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.WCMException;

import io.sensible.studio.wcm.shown.Shown;

/**
 * {@link Page} backed by a {@code cq:Page} resource. Instances are cheap views; they read
 * through to the resource on every call and hold no cached state.
 */
final class PageImpl extends SlingAdaptable implements Page {

    private static final String ADMIN = "admin";

    private final Resource resource;
    private final PageManagerImpl pageManager;

    PageImpl(Resource resource, PageManagerImpl pageManager) {
        this.resource = resource;
        this.pageManager = pageManager;
        // a page rendering got hold of this page: it may show its title, its link, its image
        Shown.page(resource.getPath());
    }

    @Override
    public String getPath() {
        return resource.getPath();
    }

    @Override
    public PageManager getPageManager() {
        return pageManager;
    }

    @Override
    public Resource getContentResource() {
        return resource.getChild(NameConstants.NN_CONTENT);
    }

    @Override
    public Resource getContentResource(String relPath) {
        Resource content = getContentResource();
        if (content == null) {
            return null;
        }
        if (relPath == null || relPath.isEmpty()) {
            return content;
        }
        return content.getChild(relPath);
    }

    @Override
    public Iterator<Page> listChildren() {
        return listChildren(null, false);
    }

    @Override
    public Iterator<Page> listChildren(Filter<Page> filter) {
        return listChildren(filter, false);
    }

    @Override
    public Iterator<Page> listChildren(Filter<Page> filter, boolean deep) {
        List<Page> result = new ArrayList<>();
        collectChildren(resource, filter, deep, result);
        return result.iterator();
    }

    private void collectChildren(Resource parent, Filter<Page> filter, boolean deep, List<Page> out) {
        Shown.children(parent.getPath());
        for (Resource child : parent.getChildren()) {
            if (!Pages.isPage(child)) {
                continue;
            }
            Page page = new PageImpl(child, pageManager);
            if (filter == null || filter.includes(page)) {
                out.add(page);
            }
            if (deep) {
                collectChildren(child, filter, true, out);
            }
        }
    }

    @Override
    public boolean hasChild(String name) {
        Shown.children(getPath());
        return name != null && Pages.isPage(resource.getChild(name));
    }

    @Override
    public int getDepth() {
        String path = getPath();
        if ("/".equals(path)) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    @Override
    public Page getParent() {
        return getParent(1);
    }

    @Override
    public Page getParent(int level) {
        return getAbsoluteParent(getDepth() - level - 1);
    }

    @Override
    public Page getAbsoluteParent(int level) {
        if (level < 0) {
            return null;
        }
        String[] segments = getPath().substring(1).split("/");
        if (level >= segments.length) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i <= level; i++) {
            path.append('/').append(segments[i]);
        }
        return pageManager.getPage(path.toString());
    }

    @Override
    public ValueMap getProperties() {
        Resource content = getContentResource();
        return content == null ? ValueMap.EMPTY : content.getValueMap();
    }

    @Override
    public ValueMap getProperties(String relPath) {
        Resource r = getContentResource(relPath);
        return r == null ? null : r.getValueMap();
    }

    @Override
    public String getName() {
        return resource.getName();
    }

    @Override
    public String getTitle() {
        return getProperties().get(NameConstants.PN_TITLE, String.class);
    }

    @Override
    public String getPageTitle() {
        return getProperties().get(NameConstants.PN_PAGE_TITLE, String.class);
    }

    @Override
    public String getNavigationTitle() {
        return getProperties().get(NameConstants.PN_NAV_TITLE, String.class);
    }

    @Override
    public String getDescription() {
        return getProperties().get(NameConstants.PN_DESCRIPTION, String.class);
    }

    @Override
    public boolean isHideInNav() {
        return getProperties().get(NameConstants.PN_HIDE_IN_NAV, false);
    }

    @Override
    public boolean hasContent() {
        return getContentResource() != null;
    }

    @Override
    public boolean isValid() {
        return timeUntilValid() == 0;
    }

    @Override
    public long timeUntilValid() {
        long now = System.currentTimeMillis();
        Calendar off = getOffTime();
        if (off != null && off.getTimeInMillis() <= now) {
            return off.getTimeInMillis() - now - 1; // strictly negative
        }
        Calendar on = getOnTime();
        if (on != null && on.getTimeInMillis() > now) {
            return on.getTimeInMillis() - now;
        }
        return 0;
    }

    @Override
    public Calendar getOnTime() {
        return getProperties().get(NameConstants.PN_ON_TIME, Calendar.class);
    }

    @Override
    public Calendar getOffTime() {
        return getProperties().get(NameConstants.PN_OFF_TIME, Calendar.class);
    }

    @Override
    public Calendar getLastModified() {
        ValueMap props = getProperties();
        Calendar cal = props.get(NameConstants.PN_PAGE_LAST_MOD, Calendar.class);
        return cal != null ? cal : props.get(NameConstants.PN_JCR_LAST_MODIFIED, Calendar.class);
    }

    @Override
    public String getLastModifiedBy() {
        ValueMap props = getProperties();
        String by = props.get(NameConstants.PN_PAGE_LAST_MOD_BY, String.class);
        return by != null ? by : props.get(NameConstants.PN_JCR_LAST_MODIFIED_BY, String.class);
    }

    @Override
    public String getVanityUrl() {
        String[] values = getProperties().get(NameConstants.PN_SLING_VANITY_PATH, String[].class);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Tag[] getTags() {
        String[] ids = getProperties().get(NameConstants.PN_TAGS, new String[0]);
        List<Tag> tags = new ArrayList<>(ids.length);
        for (String id : ids) {
            Tag tag = Pages.resolveTag(resource.getResourceResolver(), id);
            if (tag != null) {
                tags.add(tag);
            }
        }
        return tags.toArray(new Tag[0]);
    }

    @Override
    public void lock() throws WCMException {
        if (isLocked()) {
            throw new WCMException("Page " + getPath() + " is already locked by " + getLockOwner());
        }
        writeLock(currentUser());
    }

    @Override
    public boolean isLocked() {
        return getLockOwner() != null;
    }

    @Override
    public String getLockOwner() {
        return getProperties().get(Pages.PN_LOCK_OWNER, String.class);
    }

    @Override
    public boolean canUnlock() {
        String owner = getLockOwner();
        if (owner == null) {
            return false;
        }
        String user = currentUser();
        return owner.equals(user) || ADMIN.equals(user);
    }

    @Override
    public void unlock() throws WCMException {
        if (!isLocked()) {
            return;
        }
        if (!canUnlock()) {
            throw new WCMException("User " + currentUser() + " may not unlock " + getPath());
        }
        writeLock(null);
    }

    private void writeLock(String owner) throws WCMException {
        Resource content = getContentResource();
        ModifiableValueMap mvm = content == null ? null : content.adaptTo(ModifiableValueMap.class);
        if (mvm == null) {
            throw new WCMException("Page " + getPath() + " has no writable content");
        }
        if (owner == null) {
            mvm.remove(Pages.PN_LOCK_OWNER);
        } else {
            mvm.put(Pages.PN_LOCK_OWNER, owner);
        }
        try {
            resource.getResourceResolver().commit();
        } catch (PersistenceException e) {
            throw new WCMException("Could not change lock on " + getPath(), e);
        }
    }

    private String currentUser() {
        String user = resource.getResourceResolver().getUserID();
        return user == null ? "anonymous" : user;
    }

    @Override
    public Template getTemplate() {
        String path = getProperties().get(NameConstants.PN_TEMPLATE, String.class);
        return path == null ? null : pageManager.getTemplate(path);
    }

    @Override
    public Locale getLanguage() {
        return getLanguage(false);
    }

    @Override
    public Locale getLanguage(boolean ignoreContent) {
        if (!ignoreContent) {
            for (Resource r = resource; r != null; r = r.getParent()) {
                Resource content = r.getChild(NameConstants.NN_CONTENT);
                if (content != null) {
                    Locale locale = Pages.parseLanguage(content.getValueMap().get(Pages.PN_LANGUAGE, String.class));
                    if (locale != null) {
                        return locale;
                    }
                }
            }
        }
        String[] segments = getPath().substring(1).split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            Locale locale = Pages.parseLanguage(segments[i]);
            if (locale != null) {
                return locale;
            }
        }
        return Locale.getDefault();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if (type == Resource.class) {
            return (AdapterType) resource;
        }
        if (type == ValueMap.class) {
            return (AdapterType) getProperties();
        }
        AdapterType adapted = super.adaptTo(type);
        return adapted != null ? adapted : resource.adaptTo(type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PageImpl && ((PageImpl) o).getPath().equals(getPath());
    }

    @Override
    public int hashCode() {
        return getPath().hashCode();
    }

    @Override
    public String toString() {
        return "Page{" + getPath() + "}";
    }
}
