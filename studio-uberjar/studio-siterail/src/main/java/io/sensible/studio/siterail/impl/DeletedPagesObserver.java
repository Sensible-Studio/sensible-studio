/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.jcr.Session;

import org.apache.jackrabbit.oak.spi.commit.BackgroundObserver;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.Observer;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps pages deleted outside the Sites console, so that pages which never had a version can come back too.
 *
 * <p>An Oak observer: after each commit the repository hands it the new state, and the deleted pages are read from the
 * previous one. This runs after the commit and in the background (queued), so it never slows down or blocks a save;
 * the copy is written by the service user ({@link KeptPages}). What is kept longer than {@code keepDays} goes.</p>
 *
 * <p>In a cluster (a document node store) every instance sees every deletion: only those committed on this instance
 * are kept, so each is kept once. On a single instance (the segment store) everything is looked at — the observer
 * queue merges commits when it overflows and reports them as "external", like changes from another instance.</p>
 */
@Component(service = Observer.class, immediate = true)
@Designate(ocd = DeletedPagesObserver.Config.class)
public class DeletedPagesObserver implements Observer {

    @ObjectClassDefinition(name = "Sensible Studio Sites: pages deleted outside the console",
            description = "Keeps pages deleted outside the Sites console (CRX tools, WebDAV, packages) so Restore Version can bring them back.")
    public @interface Config {
        @AttributeDefinition(name = "Keep for days", description = "Kept copies older than this are removed; 0 keeps them for good.")
        int keepDays() default 90;

        @AttributeDefinition(name = "Largest deletion kept whole", description = "A deletion with more nodes than this is kept page by page.")
        int maxNodes() default KeptPages.MAX_NODES;

        @AttributeDefinition(name = "Most kept of one deletion", description = "Page by page, at most this many nodes of one deletion are kept.")
        int maxTotalNodes() default KeptPages.MAX_TOTAL_NODES;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DeletedPagesObserver.class);
    private static final int QUEUE = 1000;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile NodeStore nodeStore;

    private ScheduledExecutorService executor;
    private volatile BackgroundObserver background;
    private volatile int keepDays;
    private volatile int maxNodes;
    private volatile int maxTotalNodes;

    @Activate
    void activate(Config config) {
        keepDays = config.keepDays();
        maxNodes = config.maxNodes() > 0 ? config.maxNodes() : KeptPages.MAX_NODES;
        maxTotalNodes = config.maxTotalNodes() > 0 ? config.maxTotalNodes() : KeptPages.MAX_TOTAL_NODES;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "studio-siterail kept pages");
            t.setDaemon(true);
            return t;
        });
        background = new BackgroundObserver(new Keeper(), executor, QUEUE);
        executor.scheduleWithFixedDelay(this::purge, 1, 6 * 60, TimeUnit.MINUTES);
    }

    /** Whether the repository is a cluster (a document node store), where only this instance's commits are kept. */
    boolean clustered() {
        NodeStore ns = nodeStore;
        return ns != null && ns.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("document");
    }

    private void purge() {
        if (keepDays <= 0) {
            return;
        }
        try (ResourceResolver r = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SiteRailServlet.SUBSERVICE))) {
            Calendar before = Calendar.getInstance();
            before.add(Calendar.DAY_OF_MONTH, -keepDays);
            int removed = KeptPages.purge(r.adaptTo(Session.class), before);
            if (removed > 0) {
                LOG.info("Removed {} kept deletion(s) older than {} days", removed, keepDays);
            }
        } catch (Exception e) {
            LOG.warn("Could not remove old kept pages: {}", e.getMessage());
        }
    }

    @Deactivate
    void deactivate() {
        BackgroundObserver b = background;
        background = null;
        if (b != null) {
            b.close();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public void contentChanged(NodeState root, CommitInfo info) {
        BackgroundObserver b = background;
        if (b != null) {
            b.contentChanged(root, info);
        }
    }

    /** Compares each state with the one before. */
    private final class Keeper implements Observer {

        private NodeState previous;
        private boolean seen;

        @Override
        public void contentChanged(NodeState root, CommitInfo info) {
            NodeState before = previous;
            previous = root;
            if (!seen) {
                seen = true;
                LOG.info("Keeping pages deleted outside the Sites console in {}", KeptPages.ROOT);
            }
            if (before == null || info != null && info.isExternal() && clustered()) {
                return; // another instance's commit: kept there
            }
            try {
                List<DeletedPages.Deleted> found = DeletedPages.between(before, root);
                if (!found.isEmpty()) {
                    keep(found, info == null ? null : info.getUserId());
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not look for deleted pages", e);
            }
        }
    }

    private void keep(List<DeletedPages.Deleted> found, String user) {
        try (ResourceResolver r = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SiteRailServlet.SUBSERVICE))) {
            Session s = r.adaptTo(Session.class);
            VersionRestore records = new VersionRestore(r, r);
            for (DeletedPages.Deleted d : found) {
                // the versions first: Restore Version lists them with who deleted the page and when
                for (DeletedPages.Versioned v : DeletedPages.versioned(d)) {
                    try {
                        records.recordFromState(v.path, v.history, v.title, user);
                    } catch (Exception e) {
                        r.revert();
                        LOG.warn("Could not record the versions of the deleted page {}: {}", v.path, e.getMessage());
                    }
                }
                try {
                    List<String> skipped = new java.util.ArrayList<>();
                    List<String> entries = KeptPages.keepAll(s, d.path, d.state, user, Calendar.getInstance(), maxNodes, maxTotalNodes, skipped);
                    if (entries.size() == 1 && skipped.isEmpty()) {
                        LOG.info("Kept {} as it was when {} deleted it outside the Sites console ({})", d.path, user, entries.get(0));
                    } else {
                        LOG.info("Kept {} page(s) of {} one by one (deleted outside the Sites console by {})", entries.size(), d.path, user);
                    }
                    if (!skipped.isEmpty()) {
                        LOG.warn("Not kept, too large: {} page(s) of {}, e.g. {}", skipped.size(), d.path, skipped.subList(0, Math.min(5, skipped.size())));
                    }
                } catch (Exception e) {
                    LOG.warn("Could not keep the deleted page {}: {}", d.path, e.getMessage());
                    try {
                        s.refresh(false);
                    } catch (Exception ignored) {
                        // the next page starts from a fresh session state anyway
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not keep the deleted pages {}", found, e);
        }
    }
}
