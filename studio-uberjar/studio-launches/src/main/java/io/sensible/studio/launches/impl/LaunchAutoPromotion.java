/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.launches.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;

/**
 * A launch's own publishing rule, AEM's "Production ready": every minute, a launch that is production
 * ready and whose live date has come is promoted (all its pages) and the promoted pages are published.
 * A launch that fails keeps the reason ({@link Launches#PROP_AUTO_ERROR}) and is left alone until
 * someone marks it production ready again.
 */
@Component(service = Runnable.class,
        property = {"scheduler.period:Long=60", "scheduler.concurrent:Boolean=false", "scheduler.runOn=SINGLE"})
public class LaunchAutoPromotion implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchAutoPromotion.class);
    static final String SUBSERVICE = "launches";

    @Reference
    private ResourceResolverFactory resolvers;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile Replicator replicator;

    @Override
    public void run() {
        try (ResourceResolver rr = resolvers.getServiceResourceResolver(
                Collections.<String, Object>singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            Session session = rr.adaptTo(Session.class);
            Calendar now = Calendar.getInstance();
            for (Launches.Launch l : Launches.list(session)) {
                if (l.due(now)) {
                    goLive(session, replicator, l);
                }
            }
        } catch (LoginException e) {
            LOG.debug("No launches service user: {}", e.getMessage());
        } catch (RepositoryException | RuntimeException e) {
            LOG.warn("Launches going live: {}", e.getMessage(), e);
        }
    }

    /** Promotes a due launch and publishes what was promoted; a failure is written on the launch. Saves. */
    static void goLive(Session session, Replicator replicator, Launches.Launch l) throws RepositoryException {
        try {
            Launches.Promotion p = Launches.promote(session, l, "all", null, false);
            session.save();
            List<String> published = publish(replicator, session, p);
            Node jc = session.getNode(l.path).getNode("jcr:content");
            jc.setProperty(Launches.PROP_AUTO_PUBLISHED, published.toArray(new String[0]));
            session.save();
            LOG.info("Release {} went live: {} pages promoted, {} published", l.path, p.promoted.size() + p.created.size(), published.size());
        } catch (RepositoryException | ReplicationException | RuntimeException e) {
            session.refresh(false);
            session.getNode(l.path).getNode("jcr:content").setProperty(Launches.PROP_AUTO_ERROR, String.valueOf(e.getMessage()));
            session.save();
            LOG.warn("Release {} did not go live: {}", l.path, e.getMessage());
        }
    }

    /** Publishes the pages a promotion wrote (changed and new), parents first; returns them. */
    static List<String> publish(Replicator replicator, Session session, Launches.Promotion p) throws ReplicationException {
        List<String> paths = new ArrayList<>(p.promoted);
        paths.addAll(p.created);
        Collections.sort(paths);
        if (paths.isEmpty()) {
            return paths;
        }
        if (replicator == null) {
            throw new ReplicationException("Publishing is not available on this instance");
        }
        replicator.replicate(session, ReplicationActionType.ACTIVATE, paths.toArray(new String[0]), new ReplicationOptions());
        return paths;
    }
}
