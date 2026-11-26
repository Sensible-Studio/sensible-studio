/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.Collections;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smart-tags one asset ({@code op=smartTag} of {@link DamApiServlet} queues one job per asset),
 * from its existing renditions: nothing is rendered again.
 */
@Component(service = JobConsumer.class, property = JobConsumer.PROPERTY_TOPICS + "=" + DamSmartTagJob.TOPIC)
public class DamSmartTagJob implements JobConsumer {

    public static final String TOPIC = "studio/dam/smarttag";

    private static final Logger LOG = LoggerFactory.getLogger(DamSmartTagJob.class);

    /** Job property: only the embedding is wanted (Find similar's "index now"), not the tags. */
    static final String PROP_EMBED_ONLY = "embedOnly";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private DamClient client;

    public DamSmartTagJob() {
    }

    DamSmartTagJob(ResourceResolverFactory resolverFactory, DamClient client) {
        this.resolverFactory = resolverFactory;
        this.client = client;
    }

    @Override
    public JobResult process(Job job) {
        String path = job.getProperty(AssetProcessingJob.PROP_PATH, String.class);
        if (path == null || !path.startsWith(DamItems.ROOT + "/")) {
            return JobResult.CANCEL;
        }
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AssetProcessingJob.SUBSERVICE))) {
            Resource asset = resolver.getResource(path);
            if (asset == null) {
                return JobResult.CANCEL;
            }
            // "find similar": the picture's embedding, from the same model (also when no labels are configured)
            boolean embedOnly = Boolean.TRUE.equals(job.getProperty(PROP_EMBED_ONLY, Boolean.class));
            if (embedOnly && DamSimilar.stored(asset) != null && DamSimilar.available(client, DamSimilar.Space.SEARCH)
                    && DamSimilar.stored(asset, DamSimilar.Space.SEARCH) == null) {
                // only the multilingual embedding is missing
                if (DamSimilar.index(client, asset, DamSimilar.Space.SEARCH)) {
                    resolver.commit();
                }
                return JobResult.OK;
            }
            if (embedOnly || DamSimilar.stored(asset) == null) {
                if (DamSimilar.index(client, asset)) {
                    resolver.commit();
                }
                asset = resolver.getResource(path);
                if (asset != null && DamSimilar.stored(asset, DamSimilar.Space.SEARCH) == null && DamSimilar.available(client, DamSimilar.Space.SEARCH)
                        && DamSimilar.index(client, asset, DamSimilar.Space.SEARCH)) {
                    resolver.commit();
                    asset = resolver.getResource(path);
                }
            }
            if (embedOnly || asset == null) {
                return JobResult.OK;
            }
            DamSmartTags.Settings settings = DamSmartTags.settings(resolver);
            DamSmartTags.Outcome outcome = DamSmartTags.tag(resolver, client, asset, settings);
            if (outcome == DamSmartTags.Outcome.TAGGED) {
                resolver.commit();
            }
            LOG.debug("{}: {}", path, outcome);
            return JobResult.OK;
        } catch (DamClient.DamProcessingException e) {
            LOG.info("No smart tags for {}: {}", path, e.getMessage());
            return JobResult.CANCEL; // model missing or image refused: retrying will not help
        } catch (IOException e) {
            return job.getRetryCount() >= Math.min(3, job.getNumberOfRetries()) ? JobResult.CANCEL : JobResult.FAILED;
        } catch (LoginException e) {
            LOG.error("No service user for smart tags (subservice {})", AssetProcessingJob.SUBSERVICE);
            return JobResult.FAILED;
        } catch (RuntimeException e) {
            LOG.error("Unexpected error smart-tagging {}", path, e);
            return JobResult.CANCEL;
        }
    }
}
