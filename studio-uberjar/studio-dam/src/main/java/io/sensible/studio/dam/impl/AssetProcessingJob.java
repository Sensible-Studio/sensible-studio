/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.Collections;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link AssetProcessor} for jobs on {@link #TOPIC}. Transient problems (studio-dam down,
 * network) return {@code FAILED} so the job queue retries; permanent ones are written to the
 * asset by the processor and the job ends.
 */
@Component(service = JobConsumer.class, property = JobConsumer.PROPERTY_TOPICS + "=" + AssetProcessingJob.TOPIC)
public class AssetProcessingJob implements JobConsumer {

    public static final String TOPIC = "studio/dam/process";
    public static final String PROP_PATH = "path";
    /** Mapped to a service user in ServiceUserMapperImpl.amended~studio-dam.cfg.json. */
    public static final String SUBSERVICE = "dam-processor";

    private static final Logger LOG = LoggerFactory.getLogger(AssetProcessingJob.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private DamClient client;

    public AssetProcessingJob() {
    }

    AssetProcessingJob(ResourceResolverFactory resolverFactory, DamClient client) {
        this.resolverFactory = resolverFactory;
        this.client = client;
    }

    /** Out of retries: say so on the asset instead of leaving it in "processing" forever. */
    private void giveUp(String path, String message) {
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            AssetProcessor.markFailed(resolver, path, message);
        } catch (LoginException | IOException | RuntimeException e) {
            LOG.error("Cannot mark {} as failed: {}", path, e.getMessage());
        }
    }

    @Override
    public JobResult process(Job job) {
        String path = job.getProperty(PROP_PATH, String.class);
        if (path == null || !path.startsWith("/content/dam/")) {
            return JobResult.CANCEL;
        }
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            AssetProcessor.Outcome outcome = new AssetProcessor(client).process(resolver, path);
            LOG.debug("{}: {}", path, outcome);
            return JobResult.OK;
        } catch (LoginException e) {
            LOG.error("No service user for {} (subservice {}); check the studio-dam repoinit and mapping configs",
                    "io.sensible.studio.dam", SUBSERVICE);
            return JobResult.FAILED;
        } catch (IOException e) {
            if (job.getRetryCount() >= job.getNumberOfRetries()) {
                giveUp(path, "studio-dam unavailable after " + job.getRetryCount() + " retries: " + e.getMessage());
                return JobResult.CANCEL;
            }
            LOG.info("Will retry {}: {}", path, e.getMessage());
            return JobResult.FAILED;
        } catch (RuntimeException e) {
            LOG.error("Unexpected error processing {}", path, e);
            return JobResult.FAILED;
        }
    }
}
