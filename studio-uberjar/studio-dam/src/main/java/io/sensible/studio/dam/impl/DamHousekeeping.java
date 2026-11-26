/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Once a day, tells studio-dam which blobs are still in use, so it can remove the others: renditions
 * already copied into the repository, images sent for Smart Tags, originals that were replaced or
 * whose asset was deleted. In use are the {@code studio:blobId} of every asset under
 * {@code /content/dam}, plus originals uploaded on demand for delivery since Sensible Studio started
 * ({@link #inUse}). studio-dam keeps anything younger than three days whatever the list says, so an
 * upload that is not on the list yet is safe; an asset restored from an old version whose
 * original was removed is simply processed again.
 *
 * <p>Run by the Sling scheduler (whiteboard: a {@link Runnable} with {@code scheduler.period});
 * an admin can also run it, or only see what it would remove, with {@code op=blobGc}.</p>
 */
@Component(service = Runnable.class, property = {
        "scheduler.period:Long=86400",
        "scheduler.concurrent:Boolean=false",
        "scheduler.runOn=SINGLE",
        "scheduler.name=studio-dam-blob-gc" })
public class DamHousekeeping implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DamHousekeeping.class);

    /** Blobs only kept until the next run (removed after 3 days if nobody refers to them). */
    static final long GRACE_SECONDS = 3L * 86_400;

    /** Upper bound for {@link #inUse}: older entries fall out, the next GET uploads again. */
    static final int MAX_ON_DEMAND = 20_000;

    private static final Set<String> ON_DEMAND = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_ON_DEMAND;
                }
            }));

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private DamClient client;

    /** A blob in use that no asset refers to (an imported asset's original, uploaded to deliver it). */
    static void inUse(String blobId) {
        if (blobId != null) {
            ON_DEMAND.add(blobId);
        }
    }

    /** Forgets every {@link #inUse} blob (tests: the list is static, so it outlives the test that filled it). */
    static void forgetInUse() {
        ON_DEMAND.clear();
    }

    @Override
    public void run() {
        if (!client.isAvailable()) {
            return;
        }
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AssetProcessingJob.SUBSERVICE))) {
            Map<String, Object> out = gc(resolver, client, false);
            LOG.info("Blob GC: {}", out);
        } catch (LoginException | IOException | RuntimeException e) {
            LOG.warn("Blob GC did not run: {}", e.getMessage());
        }
    }

    /**
     * Sends the blobs in use to studio-dam, which removes the rest (or, with {@code dryRun}, counts
     * them). Nothing is sent when no asset refers to a blob: that is more likely a resolver that
     * cannot read the DAM than a DAM without images.
     */
    static Map<String, Object> gc(ResourceResolver resolver, DamClient client, boolean dryRun) throws IOException {
        Set<String> keep = blobIds(resolver);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("referenced", (long) keep.size());
        if (keep.isEmpty()) {
            out.put("skipped", "no asset refers to a blob");
            return out;
        }
        synchronized (ON_DEMAND) {
            keep.addAll(ON_DEMAND);
        }
        out.putAll(client.gcBlobs(keep, GRACE_SECONDS, dryRun));
        return out;
    }

    /** The {@code studio:blobId} of every asset under {@code /content/dam}. */
    static Set<String> blobIds(ResourceResolver resolver) {
        Set<String> ids = new HashSet<>();
        Resource root = resolver.getResource(DamItems.ROOT);
        if (root != null) {
            collect(root, ids);
        }
        return ids;
    }

    private static void collect(Resource r, Set<String> ids) {
        for (Resource child : r.getChildren()) {
            if ("jcr:content".equals(child.getName())) {
                String id = child.getValueMap().get(AssetProcessor.PN_BLOB_ID, String.class);
                if (id != null && id.matches("[0-9a-f]{64}")) {
                    ids.add(id);
                }
                continue; // renditions and metadata hold no assets
            }
            collect(child, ids);
        }
    }
}
