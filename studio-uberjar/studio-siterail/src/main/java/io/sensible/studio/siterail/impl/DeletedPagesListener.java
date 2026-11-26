/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notices pages deleted without a record — through CRX tools, the JCR API, a package — and records the ones that had
 * versions, so Restore Version finds them. At startup the whole of {@code /content} is looked at once, for deletions
 * made before. Runs as the bundle's service user; without it, it does nothing.
 */
@Component(service = ResourceChangeListener.class, property = {
        ResourceChangeListener.PATHS + "=/content",
        ResourceChangeListener.CHANGES + "=REMOVED"
})
public class DeletedPagesListener implements ResourceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(DeletedPagesListener.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    private ExecutorService worker;

    @Activate
    void activate() {
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "studio-siterail deleted pages");
            t.setDaemon(true);
            return t;
        });
        worker.submit(() -> record("/content"));
    }

    @Deactivate
    void deactivate() throws InterruptedException {
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        Set<String> paths = new LinkedHashSet<>();
        for (ResourceChange c : changes) {
            String p = c.getPath();
            if (p.startsWith("/content/") && !p.startsWith("/content/dam") && !p.contains("/jcr:content/")) {
                paths.add(p.endsWith("/jcr:content") ? p.substring(0, p.length() - "/jcr:content".length()) : p);
            }
        }
        for (String p : paths) {
            worker.submit(() -> record(p));
        }
    }

    void record(String prefix) {
        ResourceResolver service;
        try {
            service = resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SiteRailServlet.SUBSERVICE));
        } catch (LoginException e) {
            LOG.debug("No service user: deleted pages below {} are not recorded", prefix);
            return;
        }
        try {
            // the removed page itself and the pages that were below it
            int made = new VersionRestore(service, service).recordDeleted(prefix);
            if (made > 0) {
                LOG.info("Recorded {} deleted page(s) below {} ({})", made, prefix, VersionHistories.lastScan);
            }
        } catch (Exception e) {
            LOG.warn("Could not record the pages deleted below {}", prefix, e);
        } finally {
            service.close();
        }
    }
}
