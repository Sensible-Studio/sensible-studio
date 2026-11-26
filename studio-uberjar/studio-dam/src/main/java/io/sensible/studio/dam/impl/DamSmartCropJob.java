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

/** Smart-crops one asset for its folder's image profile (after the profile was assigned or changed). */
@Component(service = JobConsumer.class, property = JobConsumer.PROPERTY_TOPICS + "=" + DamSmartCropJob.TOPIC)
public class DamSmartCropJob implements JobConsumer {

    public static final String TOPIC = "studio/dam/smartcrop";

    private static final Logger LOG = LoggerFactory.getLogger(DamSmartCropJob.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private DamClient client;

    public DamSmartCropJob() {
    }

    DamSmartCropJob(ResourceResolverFactory resolverFactory, DamClient client) {
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
            if (DamSmartCrop.compute(resolver, client, asset, false)) {
                resolver.commit();
            }
            return JobResult.OK;
        } catch (DamClient.DamProcessingException e) {
            LOG.info("No smart crops for {}: {}", path, e.getMessage());
            return JobResult.CANCEL;
        } catch (IOException e) {
            LOG.warn("Smart crop of {} failed, will retry: {}", path, e.getMessage());
            return JobResult.FAILED;
        } catch (LoginException | RuntimeException e) {
            LOG.warn("Smart crop of {} failed: {}", path, e.getMessage());
            return JobResult.CANCEL;
        }
    }
}
