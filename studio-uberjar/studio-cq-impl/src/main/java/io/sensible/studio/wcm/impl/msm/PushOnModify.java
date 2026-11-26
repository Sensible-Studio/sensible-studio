/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.LiveRelationshipManager;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/**
 * Rollouts triggered by changes ({@code cq:trigger="modification"}, e.g. the {@code pushonmodify} configuration).
 *
 * <p>Changes to a blueprint page are collected per page and, once the page has been quiet for a moment, a job
 * ({@value #TOPIC}) rolls the page out to the live copies that have a modification configuration. The job runs with
 * the {@value #SUBSERVICE} service user, but only to the live copies whose target the author of the change may write
 * (and only if the author may read the blueprint page); changes without an author are not pushed. Changes made by
 * rollouts land in live copies, so they only cascade where a live copy is itself the blueprint of another one with such
 * a configuration.
 */
@Component(
        service = {ResourceChangeListener.class, JobConsumer.class},
        property = {
                ResourceChangeListener.PATHS + "=/content",
                ResourceChangeListener.CHANGES + "=ADDED",
                ResourceChangeListener.CHANGES + "=CHANGED",
                ResourceChangeListener.CHANGES + "=REMOVED",
                JobConsumer.PROPERTY_TOPICS + "=" + PushOnModify.TOPIC
        })
@Designate(ocd = PushOnModify.Config.class)
public class PushOnModify implements ResourceChangeListener, JobConsumer {

    @ObjectClassDefinition(name = "Sensible Studio MSM Push on Modify",
            description = "Rolls blueprint pages out to live copies with a modification trigger when they change.")
    public @interface Config {
        @AttributeDefinition(name = "Quiet period (ms)",
                description = "How long a page must stay unchanged before it is rolled out.")
        long delay() default 2000;
    }

    public static final String TOPIC = "studio/msm/rollout";
    public static final String SUBSERVICE = "msm";
    static final String PROP_PATH = "path";
    static final String PROP_USER = "user";

    private static final Logger LOG = LoggerFactory.getLogger(PushOnModify.class);

    @Reference
    private JobManager jobManager;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private LiveRelationshipManager relationshipManager;

    @Reference
    private RolloutManagerImpl rolloutManager;

    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    /** who last changed each pending page */
    private final Map<String, String> authors = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;
    private long delay;

    @Activate
    void activate(Config config) {
        delay = Math.max(0, config.delay());
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Sensible Studio MSM push on modify");
            t.setDaemon(true);
            return t;
        });
    }

    @Deactivate
    void deactivate() {
        executor.shutdownNow();
        pending.clear();
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        Set<String> pages = new LinkedHashSet<>();
        for (ResourceChange c : changes) {
            String p = c.getPath();
            if (p.startsWith("/content/dam/") || p.contains("cq:LiveSyncConfig") || !p.startsWith("/content/")) {
                continue;
            }
            String page = Msm.pagePathOf(p);
            pages.add(page);
            if (c.getUserId() != null) {
                authors.put(page, c.getUserId());
            }
        }
        for (String page : pages) {
            pending.compute(page, (k, old) -> {
                if (old != null) {
                    old.cancel(false);
                }
                return executor.schedule(() -> enqueue(k), delay, TimeUnit.MILLISECONDS);
            });
        }
    }

    private void enqueue(String page) {
        pending.remove(page);
        String author = authors.remove(page);
        if (author == null) {
            LOG.debug("Push on modify skips {}: the change has no author", page);
            return;
        }
        try (ResourceResolver resolver = serviceResolver()) {
            org.apache.sling.api.resource.Resource r = resolver.getResource(page);
            if (r == null) {
                r = new org.apache.sling.api.resource.NonExistingResource(resolver, page);
            }
            if (relationshipManager.getLiveRelationships(r, null, Trigger.MODIFICATION).hasNext()) {
                Map<String, Object> props = new java.util.HashMap<>();
                props.put(PROP_PATH, page);
                props.put(PROP_USER, author);
                jobManager.addJob(TOPIC, props);
                LOG.debug("Queued push on modify for {}", page);
            }
        } catch (LoginException e) {
            LOG.warn("MSM push on modify needs the service user for subservice {}: {}", SUBSERVICE, e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("MSM push on modify could not check {}", page, e);
        }
    }

    @Override
    public JobResult process(Job job) {
        String page = job.getProperty(PROP_PATH, String.class);
        if (page == null) {
            return JobResult.CANCEL;
        }
        String author = job.getProperty(PROP_USER, String.class);
        try (ResourceResolver resolver = serviceResolver()) {
            RolloutManagerImpl.Options o = RolloutManagerImpl.Options.of(false, Trigger.MODIFICATION);
            // the service user may write everywhere: roll out only where the author of the change could
            o.targets = allowedTargets(resolver, page, author);
            if (o.targets.isEmpty()) {
                return JobResult.OK;
            }
            RolloutResult result = rolloutManager.rollout(resolver, page, o);
            Msm.commit(resolver);
            LOG.info("Pushed {} to live copies: {}", page, result);
            return JobResult.OK;
        } catch (LoginException e) {
            LOG.warn("MSM push on modify needs the service user for subservice {}", SUBSERVICE);
            return JobResult.FAILED;
        } catch (WCMException e) {
            LOG.warn("Push on modify of {} failed: {}", page, e.getMessage());
            return JobResult.FAILED;
        }
    }

    private Set<String> allowedTargets(ResourceResolver resolver, String page, String author) {
        Set<String> out = new LinkedHashSet<>();
        AuthorPermissions perms = AuthorPermissions.of(resolver, author);
        if (perms == null || !(relationshipManager instanceof LiveRelationshipManagerImpl)) {
            LOG.info("Push on modify of {} skipped: the permissions of {} cannot be checked", page, author);
            return out;
        }
        if (!perms.canRead(page)) {
            return out;
        }
        for (LiveRelationshipImpl rel : ((LiveRelationshipManagerImpl) relationshipManager).relationshipsOf(resolver, page)) {
            if (perms.canWrite(rel.getTargetPath())) {
                out.add(rel.getTargetPath());
            } else {
                LOG.info("Push on modify: {} may not change {}, left out", author, rel.getTargetPath());
            }
        }
        return out;
    }

    private ResourceResolver serviceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }
}
