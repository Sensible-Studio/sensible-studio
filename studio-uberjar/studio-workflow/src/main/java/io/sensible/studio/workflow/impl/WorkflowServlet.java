/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowException;

/**
 * JSON API of the workflow consoles ({@code /bin/studio/workflow.json?op=...}):
 * <pre>
 * GET  models   model&amp;path=&lt;design path&gt;   runtime&amp;id=   processes
 *      instances[&amp;status=&amp;model=&amp;failed=true]   instance&amp;id=   inbox   launchers
 * POST createModel (title, name?, description?)   copyModel (path of a default model)   deleteModel (path)   sync (path)
 *      addStep (container, resourceType, title?, before?)   moveStep (path, container, before?)
 *      deleteStep (path)   branches (path)   touch (path)
 *      start (model, payload, payloadType?, title?, comment?)   complete (item, route?, comment?)
 *      terminate (id)   suspend (id)   resume (id)   retry (item)   delegate (item, assignee)   deleteInstance (id)
 *      saveLauncher (path?, glob, eventType, nodetype, conditions*, excludeList*, workflow, description, enabled, runModes)
 *      enableLauncher (path, enabled)   deleteLauncher (path)   setPurge (days)   purgeNow (days)
 * GET  maintenance (purge settings and what there is to purge)
 * </pre>
 * Model editing runs with the requesting user's resolver (so the repository's permissions apply); the engine works
 * with its own service user.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/workflow",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class WorkflowServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowServlet.class);
    private static final int MAX_INSTANCES = 500;

    @Reference
    private transient WorkflowService service;

    @Reference
    private transient WorkflowMaintenance maintenance;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, false);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, true);
    }

    private void handle(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean post) throws IOException {
        String op = request.getParameter("op");
        ResourceResolver resolver = request.getResourceResolver();
        try {
            Object result = post ? write(op, request, resolver) : read(op, request, resolver);
            if (result == null) {
                send(response, HttpServletResponse.SC_NOT_FOUND, error("Unknown op " + op));
            } else {
                send(response, HttpServletResponse.SC_OK, result);
            }
        } catch (IllegalArgumentException e) {
            resolver.revert();
            send(response, HttpServletResponse.SC_BAD_REQUEST, error(e.getMessage()));
        } catch (SecurityException e) {
            resolver.revert();
            send(response, HttpServletResponse.SC_FORBIDDEN, error(e.getMessage()));
        } catch (WorkflowException | PersistenceException | RepositoryException | RuntimeException e) {
            resolver.revert();
            LOG.warn("Workflow op {} failed", op, e);
            send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error(e.getMessage()));
        }
    }

    private Object read(String op, SlingHttpServletRequest r, ResourceResolver resolver) {
        switch (String.valueOf(op)) {
            case "models":
                service.syncDefaults();
                return new ModelStore(resolver).list();
            case "model":
                return new ModelStore(resolver).design(r.getParameter("path"));
            case "runtime": {
                Resource m = resolver.getResource(String.valueOf(r.getParameter("id")));
                if (m == null || m.getChild("nodes") == null) {
                    throw new IllegalArgumentException("No runtime model " + r.getParameter("id"));
                }
                return RuntimeModel.read(m).toJson();
            }
            case "processes": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("processes", service.processLabels());
                out.put("choosers", service.chooserLabels());
                return out;
            }
            case "instances": {
                String status = r.getParameter("status");
                String model = r.getParameter("model");
                boolean failedOnly = "true".equals(r.getParameter("failed"));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Resource i : Instances.all(resolver)) {
                    ValueMap vm = i.getValueMap();
                    if ((status == null || status.isEmpty() || java.util.Arrays.asList(status.split(",")).contains(vm.get("status", "")))
                            && (model == null || model.isEmpty() || model.equals(vm.get("modelId", "")))) {
                        Map<String, Object> summary = Instances.summary(i);
                        if (failedOnly && !Boolean.TRUE.equals(summary.get("failed"))) {
                            continue;
                        }
                        out.add(summary);
                        if (out.size() >= MAX_INSTANCES) {
                            break;
                        }
                    }
                }
                return out;
            }
            case "instance": {
                Resource i = resolver.getResource(String.valueOf(r.getParameter("id")));
                if (i == null || i.getValueMap().get("modelId") == null) {
                    throw new IllegalArgumentException("No workflow instance " + r.getParameter("id"));
                }
                return Instances.summary(i);
            }
            case "inbox": {
                List<String> me = Instances.principals(resolver);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Resource i : Instances.all(resolver)) {
                    if (!Engine.STATUS_RUNNING.equals(i.getValueMap().get("status", ""))) {
                        continue;
                    }
                    Map<String, Object> summary = null;
                    for (Resource item : Engine.items(i)) {
                        ValueMap iv = item.getValueMap();
                        if (Engine.ITEM_ACTIVE.equals(iv.get("status", "")) && me.contains(iv.get("assignee", ""))) {
                            if (summary == null) {
                                summary = Instances.summary(i);
                            }
                            Map<String, Object> entry = new LinkedHashMap<>(Instances.item(item));
                            entry.put("workflow", summary);
                            entry.put("routes", routes(resolver, i, item));
                            out.add(entry);
                        }
                    }
                }
                return out;
            }
            case "maintenance": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("purgeDays", maintenance.purgeDays());
                out.put("lastRun", maintenance.lastPurge() == 0 ? null : java.time.Instant.ofEpochMilli(maintenance.lastPurge()).toString());
                out.put("lastRemoved", maintenance.lastRemoved());
                int ended = 0;
                String oldest = null;
                for (Resource i : Instances.all(resolver)) {
                    ValueMap vm = i.getValueMap();
                    String st = vm.get("status", "");
                    if (Engine.STATUS_COMPLETED.equals(st) || Engine.STATUS_ABORTED.equals(st)) {
                        ended++;
                        String end = ModelStore.iso(vm.get("endTime", java.util.Calendar.class));
                        if (end != null && (oldest == null || end.compareTo(oldest) < 0)) {
                            oldest = end;
                        }
                    }
                }
                out.put("ended", ended);
                out.put("oldestEnded", oldest);
                out.put("admin", Instances.isAdmin(resolver));
                return out;
            }
            case "launchers": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Launchers.Launcher l : Launchers.list(resolver)) {
                    out.add(l.toJson());
                }
                return out;
            }
            case "autoAssign": {
                Map<String, Object> out = new LinkedHashMap<>();
                List<Map<String, Object>> rules = new ArrayList<>();
                for (AutoAssign.Rule rule : AutoAssign.list(resolver)) {
                    rules.add(rule.toJson());
                }
                out.put("rules", rules);
                out.put("templates", AutoAssign.templates(resolver));
                return out;
            }
            default:
                return null;
        }
    }

    private static List<Map<String, Object>> routes(ResourceResolver resolver, Resource instance, Resource item) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource m = resolver.getResource(instance.getValueMap().get("modelId", ""));
        if (m == null) {
            return out;
        }
        RuntimeModel model = RuntimeModel.read(m);
        for (com.adobe.granite.workflow.exec.Route route : Objects.routes(model.nodes.get(item.getValueMap().get("nodeId", "")), true)) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", route.getId());
            rm.put("name", route.getName());
            rm.put("default", route.hasDefault());
            out.add(rm);
        }
        return out;
    }

    private Object write(String op, SlingHttpServletRequest r, ResourceResolver resolver)
            throws PersistenceException, RepositoryException, WorkflowException {
        ModelStore models = new ModelStore(resolver);
        switch (String.valueOf(op)) {
            case "createModel":
                return models.create(r.getParameter("title"), r.getParameter("name"), r.getParameter("description"));
            case "copyModel":
                return models.design(models.copyToCustom(required(r, "path")));
            case "deleteModel":
                models.delete(r.getParameter("path"));
                return Collections.singletonMap("deleted", r.getParameter("path"));
            case "sync": {
                RuntimeModel m = models.sync(r.getParameter("path"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", m.id);
                out.put("version", m.version);
                return out;
            }
            case "addStep":
                return models.addStep(r.getParameter("container"), required(r, "resourceType"), r.getParameter("title"), r.getParameter("before"));
            case "moveStep":
                models.moveStep(r.getParameter("path"), r.getParameter("container"), r.getParameter("before"));
                return Collections.singletonMap("moved", r.getParameter("path"));
            case "deleteStep":
                models.deleteStep(r.getParameter("path"));
                return Collections.singletonMap("deleted", r.getParameter("path"));
            case "branches":
                models.normalizeBranches(r.getParameter("path"));
                return Collections.singletonMap("path", r.getParameter("path"));
            case "touch":
                models.touchAndCommit(required(r, "path"));
                return Collections.singletonMap("path", r.getParameter("path"));
            case "start": {
                Map<String, Object> meta = new LinkedHashMap<>();
                if (r.getParameter("title") != null && !r.getParameter("title").isEmpty()) {
                    meta.put("workflowTitle", r.getParameter("title"));
                }
                if (r.getParameter("comment") != null && !r.getParameter("comment").isEmpty()) {
                    meta.put("startComment", r.getParameter("comment"));
                }
                String path = service.start(resolver, required(r, "model"), r.getParameter("payloadType"), required(r, "payload"), meta);
                return Collections.singletonMap("id", path);
            }
            case "complete":
                service.complete(resolver, required(r, "item"), empty(r.getParameter("route")), r.getParameter("comment"));
                return Collections.singletonMap("completed", r.getParameter("item"));
            case "terminate":
                service.terminate(resolver, required(r, "id"));
                return Collections.singletonMap("terminated", r.getParameter("id"));
            case "suspend":
            case "resume":
                service.suspend(resolver, required(r, "id"), "suspend".equals(op));
                return Collections.singletonMap(op, r.getParameter("id"));
            case "retry":
                service.retry(resolver, required(r, "item"));
                return Collections.singletonMap("retried", r.getParameter("item"));
            case "delegate":
                service.delegate(resolver, required(r, "item"), r.getParameter("assignee"));
                return Collections.singletonMap("delegated", r.getParameter("item"));
            case "deleteInstance":
                service.deleteInstance(resolver, required(r, "id"));
                return Collections.singletonMap("deleted", r.getParameter("id"));
            case "setPurge":
                if (!Instances.isAdmin(resolver)) {
                    throw new SecurityException("Only administrators may change the purge settings");
                }
                try {
                    maintenance.setPurgeDays(Integer.parseInt(required(r, "days")));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a number of days: " + r.getParameter("days"));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Could not save the purge settings: " + e.getMessage(), e);
                }
                return Collections.singletonMap("purgeDays", maintenance.purgeDays());
            case "purgeNow": {
                if (!Instances.isAdmin(resolver)) {
                    throw new SecurityException("Only administrators may purge workflows");
                }
                int days;
                try {
                    days = Integer.parseInt(required(r, "days"));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a number of days: " + r.getParameter("days"));
                }
                if (days < 1) {
                    throw new IllegalArgumentException("Purge workflows that ended at least one day ago");
                }
                try {
                    return Collections.singletonMap("removed", maintenance.purgeNow(days));
                } catch (org.apache.sling.api.resource.LoginException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            case "saveLauncher":
                return Launchers.save(resolver, empty(r.getParameter("path")), Launchers.values(r.getParameterMap())).toJson();
            case "enableLauncher":
                Launchers.setEnabled(resolver, required(r, "path"), Boolean.parseBoolean(r.getParameter("enabled")));
                return Collections.singletonMap("path", r.getParameter("path"));
            case "saveAutoAssign":
                return AutoAssign.save(resolver, empty(r.getParameter("path")), r.getParameter("template"), r.getParameter("workflow"),
                        r.getParameter("rootPath")).toJson();
            case "deleteAutoAssign":
                AutoAssign.delete(resolver, required(r, "path"));
                return Collections.singletonMap("deleted", r.getParameter("path"));
            case "deleteLauncher":
                Launchers.delete(resolver, required(r, "path"));
                return Collections.singletonMap("deleted", r.getParameter("path"));
            default:
                return null;
        }
    }

    private static String required(SlingHttpServletRequest r, String name) {
        String v = r.getParameter(name);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return v.trim();
    }

    private static String empty(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static Map<String, String> error(String message) {
        return Collections.singletonMap("error", message == null ? "" : message);
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }
}
