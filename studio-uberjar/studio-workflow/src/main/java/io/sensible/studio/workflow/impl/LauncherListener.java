/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts workflows for the enabled launchers. Listens to {@code /content}, {@code /conf} and {@code /etc} (where
 * launchers in practice point) and to the launcher configurations themselves, which it caches until they change.
 * A launcher does not start its model on a payload that already has a running or suspended instance of it, so a
 * workflow that writes to its own payload does not start itself again.
 */
@Component(service = ResourceChangeListener.class, property = {
        ResourceChangeListener.PATHS + "=/content",
        ResourceChangeListener.PATHS + "=/conf",
        ResourceChangeListener.PATHS + "=/etc",
        ResourceChangeListener.PATHS + "=" + Launchers.DEFAULT,
        ResourceChangeListener.CHANGES + "=ADDED",
        ResourceChangeListener.CHANGES + "=CHANGED",
        ResourceChangeListener.CHANGES + "=REMOVED"})
public class LauncherListener implements ResourceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(LauncherListener.class);
    private static final String CONFIG_ROOT = "/conf/global/settings/workflow/launcher";

    @Reference
    private WorkflowService service;

    private volatile List<Launchers.Launcher> cache;

    @Override
    public void onChange(List<ResourceChange> changes) {
        List<ResourceChange> content = new ArrayList<>();
        for (ResourceChange c : changes) {
            String p = c.getPath();
            if (p.startsWith(CONFIG_ROOT) || p.startsWith(Launchers.DEFAULT) || p.startsWith(AutoAssign.STORE) || p.equals("/conf") || p.equals("/conf/global")) {
                cache = null;
            } else if (!p.startsWith("/conf/global/settings/workflow/")) {
                content.add(c);
            }
        }
        if (content.isEmpty()) {
            return;
        }
        try (ResourceResolver resolver = service.engineResolver()) {
            List<Launchers.Launcher> launchers = launchers(resolver);
            if (launchers.isEmpty()) {
                return;
            }
            Collection<String> workflowUsers = Arrays.asList(service.serviceUserId(), "workflow-process-service");
            for (ResourceChange c : content) {
                Launchers.Change change = new Launchers.Change(type(c), c.getPath(), properties(c), c.getUserId());
                if (change.eventType == 0) {
                    continue;
                }
                Resource resource = change.eventType == Launchers.REMOVED ? null : resolver.getResource(c.getPath());
                for (Launchers.Launcher l : launchers) {
                    if (Launchers.matches(l, change, resource, workflowUsers)) {
                        service.launch(l, c.getPath(), c.getUserId());
                    }
                }
            }
        } catch (LoginException e) {
            LOG.warn("Workflow launchers cannot read the repository", e);
        } catch (RuntimeException e) {
            LOG.warn("Workflow launchers failed on {}", changes, e);
        }
    }

    private List<Launchers.Launcher> launchers(ResourceResolver resolver) {
        List<Launchers.Launcher> l = cache;
        if (l == null) {
            List<Launchers.Launcher> active = new ArrayList<>();
            for (Launchers.Launcher x : Launchers.list(resolver)) {
                if (x.enabled && x.onAuthor() && x.pattern != null) {
                    active.add(x);
                }
            }
            // Auto-Assign rules: a page created with a template starts a flow
            active.addAll(AutoAssign.launchers(resolver));
            cache = l = active;
        }
        return l;
    }

    private static int type(ResourceChange c) {
        switch (c.getType()) {
            case ADDED:
                return Launchers.CREATED;
            case CHANGED:
                return Launchers.MODIFIED;
            case REMOVED:
                return Launchers.REMOVED;
            default:
                return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static Collection<String> properties(ResourceChange c) {
        Set<String> out = new LinkedHashSet<>();
        if (c.getChangedPropertyNames() != null) {
            out.addAll(c.getChangedPropertyNames());
        }
        if (c.getAddedPropertyNames() != null) {
            out.addAll(c.getAddedPropertyNames());
        }
        if (c.getRemovedPropertyNames() != null) {
            out.addAll(c.getRemovedPropertyNames());
        }
        return out;
    }
}
