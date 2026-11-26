/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.commons.RangeIterator;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;

/**
 * Tags below {@code /content/cq:tags} (or legacy {@code /etc/tags}); tagged content is found
 * with a repository query on {@code cq:tags}. Sub-tags match their parents: content tagged
 * {@code wknd:activity/surfing} is found when searching for {@code wknd:activity}.
 */
final class TagManagerImpl implements TagManager {

    private final ResourceResolver resolver;

    TagManagerImpl(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Tag resolve(String tagID) {
        return Pages.resolveTag(resolver, tagID);
    }

    @Override
    public Tag[] getTags(Resource resource) {
        if (resource == null) {
            return new Tag[0];
        }
        List<Tag> out = new ArrayList<>();
        for (String id : resource.getValueMap().get("cq:tags", new String[0])) {
            Tag t = resolve(id);
            if (t != null) {
                out.add(t);
            }
        }
        return out.toArray(new Tag[0]);
    }

    @Override
    public RangeIterator<Resource> find(String basePath, String[] tagIDs) {
        return find(basePath, tagIDs, false);
    }

    @Override
    public RangeIterator<Resource> find(String basePath, String[] tagIDs, boolean oneMatchIsEnough) {
        // what carries a tag changes with a publication where it looks: a page that lists such things shows that
        io.sensible.studio.wcm.shown.Shown.search(basePath, "content found by its tags");
        List<String> ids = new ArrayList<>();
        for (String id : tagIDs == null ? new String[0] : tagIDs) {
            Tag t = resolve(id);
            ids.add(t == null ? id : t.getTagID());
        }
        if (ids.isEmpty()) {
            return new ListRangeIterator(new ArrayList<>());
        }
        StringBuilder cond = new StringBuilder();
        for (String id : ids) {
            if (cond.length() > 0) {
                cond.append(oneMatchIsEnough ? " or " : " and ");
            }
            String esc = id.replace("'", "''");
            cond.append("(@cq:tags = '").append(esc).append("' or jcr:like(@cq:tags, '")
                    .append(esc.endsWith(":") ? esc : esc + "/").append("%'))");
        }
        String base = basePath == null || basePath.isEmpty() || "/".equals(basePath) ? "" : basePath;
        String xpath = "/jcr:root" + escapePath(base) + "//*[" + cond + "]";
        Map<String, Resource> found = new LinkedHashMap<>();
        Iterator<Resource> it = resolver.findResources(xpath, "xpath");
        while (it.hasNext()) {
            Resource r = it.next();
            found.putIfAbsent(r.getPath(), r);
        }
        return new ListRangeIterator(new ArrayList<>(found.values()));
    }

    private static String escapePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append('/');
            sb.append(Character.isDigit(seg.charAt(0)) ? "_x003" + seg.charAt(0) + "_" + seg.substring(1) : seg);
        }
        return sb.toString();
    }

    @Override
    public Tag[] getNamespaces() {
        for (String root : Pages.TAG_ROOTS) {
            Resource r = resolver.getResource(root);
            if (r != null) {
                List<Tag> out = new ArrayList<>();
                for (Resource ns : r.getChildren()) {
                    Tag t = Pages.resolveTag(resolver, ns.getPath());
                    if (t != null) {
                        out.add(t);
                    }
                }
                return out.toArray(new Tag[0]);
            }
        }
        return new Tag[0];
    }

    static final class ListRangeIterator implements RangeIterator<Resource> {
        private final List<Resource> items;
        private int pos;

        ListRangeIterator(List<Resource> items) {
            this.items = items;
        }

        @Override
        public boolean hasNext() {
            return pos < items.size();
        }

        @Override
        public Resource next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return items.get(pos++);
        }

        @Override
        public void skip(long skipNum) {
            if (pos + skipNum > items.size()) {
                throw new NoSuchElementException();
            }
            pos += (int) skipNum;
        }

        @Override
        public long getSize() {
            return items.size();
        }

        @Override
        public long getPosition() {
            return pos;
        }
    }
}
