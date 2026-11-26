/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowException;

/**
 * Housekeeping of the workflow engine, run every minute by the Sling scheduler:
 * <ul>
 * <li>overdue participant steps whose timeout handler is the auto advancer are completed along their default
 * route;</li>
 * <li>ended instances of transient models are removed a minute after they end (not at once: launchers look at
 * recently ended instances to tell a workflow's own writes from new changes);</li>
 * <li>ended instances older than {@code purge.days} are removed.</li>
 * </ul>
 */
@Component(service = {Runnable.class, WorkflowMaintenance.class}, property = {"scheduler.period:Long=60", "scheduler.concurrent:Boolean=false",
        "scheduler.runOn=SINGLE"})
@Designate(ocd = WorkflowMaintenance.Config.class)
public class WorkflowMaintenance implements Runnable {

    @ObjectClassDefinition(name = "Sensible Studio Workflow Maintenance", description = "Timeouts of participant steps and removal of ended workflows.")
    public @interface Config {
        @AttributeDefinition(name = "Purge after (days)", description = "Completed and terminated workflows older than this are removed. 0 keeps them.")
        int purge_days() default 30;
    }

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowMaintenance.class);
    static final long TRANSIENT_GRACE_MS = 60_000;

    @Reference
    private WorkflowService service;

    static final String PID = "io.sensible.studio.workflow.impl.WorkflowMaintenance";

    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL,
            policyOption = org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY)
    private volatile org.osgi.service.cm.ConfigurationAdmin configAdmin;

    private volatile int purgeDays = 30;
    private volatile long lastPurge;
    private volatile int lastRemoved;

    @Activate
    @Modified
    void configure(Config config) {
        purgeDays = Math.max(0, config.purge_days());
    }

    int purgeDays() {
        return purgeDays;
    }

    long lastPurge() {
        return lastPurge;
    }

    int lastRemoved() {
        return lastRemoved;
    }

    /** Stores the purge age in the component's OSGi configuration (the component is then reconfigured). */
    void setPurgeDays(int days) throws java.io.IOException {
        if (days < 0 || days > 36_500) {
            throw new IllegalArgumentException("The purge age must be between 0 and 36500 days");
        }
        org.osgi.service.cm.ConfigurationAdmin ca = configAdmin;
        if (ca == null) {
            throw new IllegalStateException("No configuration admin service");
        }
        org.osgi.service.cm.Configuration c = ca.getConfiguration(PID, "?");
        java.util.Dictionary<String, Object> props = c.getProperties();
        if (props == null) {
            props = new java.util.Hashtable<>();
        }
        props.put("purge.days", days);
        c.update(props);
        purgeDays = days; // at once, the reconfiguration follows asynchronously
    }

    /** Runs the housekeeping now with {@code days} as the purge age; returns how many instances were removed. */
    int purgeNow(int days) throws LoginException, PersistenceException {
        try (ResourceResolver resolver = service.engineResolver()) {
            Result r = sweep(resolver, System.currentTimeMillis(), days);
            lastPurge = System.currentTimeMillis();
            lastRemoved = r.removed;
            return r.removed;
        }
    }

    @Override
    public void run() {
        try (ResourceResolver resolver = service.engineResolver()) {
            Result r = sweep(resolver, System.currentTimeMillis(), purgeDays);
            lastPurge = System.currentTimeMillis();
            if (r.removed > 0) {
                lastRemoved = r.removed;
            }
            for (String[] overdue : r.overdue) {
                try {
                    service.timeout(overdue[0], overdue[1]);
                } catch (WorkflowException | RuntimeException e) {
                    LOG.warn("Could not advance the overdue step {}", overdue[0] + "/workItems/" + overdue[1], e);
                }
            }
            if (r.removed > 0) {
                LOG.info("Removed {} ended workflow instance(s)", r.removed);
            }
        } catch (LoginException | PersistenceException | RuntimeException e) {
            LOG.warn("Workflow maintenance failed", e);
        }
    }

    static final class Result {
        final List<String[]> overdue = new ArrayList<>();
        int removed;
    }

    /** Removes what is due for removal and returns the overdue steps ({instance path, item name}) to advance. */
    static Result sweep(ResourceResolver resolver, long now, int purgeDays) throws PersistenceException {
        Result r = new Result();
        long purgeBefore = purgeDays <= 0 ? Long.MIN_VALUE : now - purgeDays * 86_400_000L;
        for (Resource instance : Instances.all(resolver)) {
            ValueMap vm = instance.getValueMap();
            String status = vm.get("status", "");
            if (Engine.STATUS_RUNNING.equals(status)) {
                for (Resource item : Engine.items(instance)) {
                    ValueMap iv = item.getValueMap();
                    Calendar due = iv.get("dueTime", Calendar.class);
                    if (due != null && due.getTimeInMillis() <= now && Engine.ITEM_ACTIVE.equals(iv.get("status", ""))
                            && Engine.AUTO_ADVANCER.equals(iv.get("timeoutHandler", "")) && !iv.get("timeoutFired", false)) {
                        r.overdue.add(new String[] {instance.getPath(), item.getName()});
                    }
                }
                continue;
            }
            if (!Engine.STATUS_COMPLETED.equals(status) && !Engine.STATUS_ABORTED.equals(status)) {
                continue;
            }
            Calendar end = vm.get("endTime", Calendar.class);
            long ended = end == null ? 0 : end.getTimeInMillis();
            boolean transientDone = vm.get("transient", false) && now - ended > TRANSIENT_GRACE_MS;
            if (transientDone || ended < purgeBefore) {
                Resource day = instance.getParent();
                resolver.delete(instance);
                r.removed++;
                if (day != null && !day.listChildren().hasNext()) {
                    resolver.delete(day);
                }
            }
        }
        if (r.removed > 0) {
            resolver.commit();
        }
        return r;
    }
}
