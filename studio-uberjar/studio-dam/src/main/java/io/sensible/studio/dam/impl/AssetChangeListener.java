/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queues a processing job whenever an asset's original is added or replaced, however that
 * happened: {@code AssetManager}, the upload servlet, WebDAV, a content package.
 *
 * <p>Processing only writes renditions and metadata, never the original, so it cannot
 * re-trigger itself.
 *
 * <p>{@code enabled=false} (configuration {@code io.sensible.studio.dam.impl.AssetChangeListener})
 * switches it off: a publish instance receives its files processed, renditions and metadata
 * included, and must not make them again (it would also overwrite a rendition somebody replaced
 * by hand on the author).
 */
@Component(service = ResourceChangeListener.class, property = {
        ResourceChangeListener.PATHS + "=/content/dam",
        ResourceChangeListener.CHANGES + "=ADDED",
        ResourceChangeListener.CHANGES + "=CHANGED"})
public class AssetChangeListener implements ResourceChangeListener {

    private static final Pattern ORIGINAL =
            Pattern.compile("^(/content/dam/.+?)/jcr:content/renditions/original(?:/jcr:content)?$");

    private static final Logger LOG = LoggerFactory.getLogger(AssetChangeListener.class);

    @Reference
    private JobManager jobManager;

    @Reference
    private ResourceResolverFactory resolverFactory;

    private volatile boolean enabled = true;

    public AssetChangeListener() {
    }

    @Activate
    @Modified
    protected void activate(Map<String, Object> config) {
        Object e = config == null ? null : config.get("enabled");
        enabled = e == null || !"false".equalsIgnoreCase(String.valueOf(e).trim());
        if (!enabled) {
            LOG.info("Files that arrive are not processed here (enabled=false)");
        }
    }

    AssetChangeListener(JobManager jobManager, ResourceResolverFactory resolverFactory) {
        this.jobManager = jobManager;
        this.resolverFactory = resolverFactory;
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        if (!enabled) {
            return;
        }
        Set<String> assets = new LinkedHashSet<>();
        Set<String> addedRoots = new LinkedHashSet<>();
        for (ResourceChange change : changes) {
            String path = change.getPath();
            Matcher m = ORIGINAL.matcher(path);
            if (m.matches()) {
                assets.add(m.group(1));
            } else if (change.getType() == ResourceChange.ChangeType.ADDED && !path.contains("/jcr:content")) {
                // Some stores report only the root of an added subtree.
                addedRoots.add(path);
            }
        }
        if (!addedRoots.isEmpty()) {
            assets.addAll(assetsAmong(addedRoots));
        }
        for (String asset : assets) {
            jobManager.addJob(AssetProcessingJob.TOPIC, Collections.singletonMap(AssetProcessingJob.PROP_PATH, asset));
            LOG.debug("Queued processing for {}", asset);
        }
    }

    private Set<String> assetsAmong(Set<String> paths) {
        Set<String> out = new LinkedHashSet<>();
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AssetProcessingJob.SUBSERVICE))) {
            for (String p : paths) {
                Resource r = resolver.getResource(p);
                if (r != null && "dam:Asset".equals(r.getValueMap().get("jcr:primaryType", String.class))
                        && r.getChild("jcr:content/renditions/original") != null) {
                    out.add(p);
                }
            }
        } catch (LoginException e) {
            LOG.warn("Cannot check added DAM nodes: no service user for subservice {}", AssetProcessingJob.SUBSERVICE);
        }
        return out;
    }
}
