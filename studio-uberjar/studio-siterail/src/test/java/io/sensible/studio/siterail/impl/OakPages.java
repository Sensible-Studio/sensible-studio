/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.lang.reflect.Proxy;
import java.util.Calendar;

import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;

import io.sensible.studio.wcm.impl.PageRevisions;

/** The page versions of studio-cq-impl behind a {@link PageManager}, for tests on Oak (the rest is unsupported). */
final class OakPages {

    private OakPages() {
    }

    static PageManager pageManager(ResourceResolver r) {
        PageRevisions revisions = new PageRevisions(r);
        return (PageManager) Proxy.newProxyInstance(OakPages.class.getClassLoader(), new Class<?>[] {PageManager.class}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "getPage":
                    return r.getResource((String) args[0]) == null ? null : page((String) args[0]);
                case "createRevision":
                    return revisions.create(((Page) args[0]).getPath(), args.length > 1 ? (String) args[1] : null,
                            args.length > 2 ? (String) args[2] : null);
                case "getRevisions":
                    return revisions.list((String) args[0], (Calendar) args[1]);
                case "restore":
                    revisions.restore((String) args[0], (String) args[1]);
                    return null;
                default:
                    throw new UnsupportedOperationException(m.getName());
            }
        });
    }

    static Page page(String path) {
        return (Page) Proxy.newProxyInstance(OakPages.class.getClassLoader(), new Class<?>[] {Page.class}, (proxy, m, args) -> {
            if ("getPath".equals(m.getName())) {
                return path;
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }
}
