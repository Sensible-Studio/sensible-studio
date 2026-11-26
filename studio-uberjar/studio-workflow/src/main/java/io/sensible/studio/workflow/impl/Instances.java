/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/** Reading workflow instances for the consoles and the session. */
final class Instances {

    static final String ADMINISTRATORS = "administrators";

    private Instances() {
    }

    /** All instances, newest day first. */
    static List<Resource> all(ResourceResolver resolver) {
        List<Resource> out = new ArrayList<>();
        Resource root = resolver.getResource(Engine.INSTANCES);
        if (root == null) {
            return out;
        }
        List<Resource> days = new ArrayList<>();
        root.getChildren().forEach(days::add);
        days.sort((a, b) -> b.getName().compareTo(a.getName()));
        for (Resource day : days) {
            for (Resource i : day.getChildren()) {
                if (i.getValueMap().get("modelId") != null) {
                    out.add(i);
                }
            }
        }
        out.sort((a, b) -> Long.compare(time(b, "startTime"), time(a, "startTime")));
        return out;
    }

    private static long time(Resource r, String name) {
        Calendar c = r.getValueMap().get(name, Calendar.class);
        return c == null ? 0 : c.getTimeInMillis();
    }

    static Map<String, Object> summary(Resource instance) {
        ValueMap vm = instance.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", instance.getPath());
        m.put("name", instance.getName());
        m.put("modelId", vm.get("modelId", String.class));
        m.put("modelTitle", vm.get("modelTitle", String.class));
        m.put("modelVersion", vm.get("modelVersion", String.class));
        m.put("status", vm.get("status", Engine.STATUS_RUNNING));
        m.put("initiator", vm.get("initiator", String.class));
        m.put("startTime", ModelStore.iso(vm.get("startTime", Calendar.class)));
        m.put("endTime", ModelStore.iso(vm.get("endTime", Calendar.class)));
        Resource data = instance.getChild("data");
        m.put("payload", data == null ? null : data.getValueMap().get("payload", String.class));
        Resource meta = instance.getChild("metaData");
        if (meta != null) {
            m.put("title", meta.getValueMap().get("workflowTitle", String.class));
            m.put("comment", meta.getValueMap().get("startComment", String.class));
        }
        List<Map<String, Object>> current = new ArrayList<>();
        List<Map<String, Object>> history = new ArrayList<>();
        for (Resource item : Engine.items(instance)) {
            Map<String, Object> im = item(item);
            history.add(im);
            String s = String.valueOf(im.get("status"));
            if (Engine.ITEM_ACTIVE.equals(s) || Engine.ITEM_FAILED.equals(s)) {
                current.add(im);
            }
        }
        m.put("current", current);
        String[] stages = stages(instance);
        m.put("stages", java.util.Arrays.asList(stages));
        if (stages.length > 0) {
            String stage = null;
            List<Map<String, Object>> from = current.isEmpty() ? history : current;
            for (int k = from.size() - 1; k >= 0 && stage == null; k--) {
                stage = (String) from.get(k).get("stage");
            }
            m.put("stage", stage);
        }
        m.put("failed", current.stream().anyMatch(i -> Engine.ITEM_FAILED.equals(i.get("status"))));
        m.put("history", history);
        return m;
    }

    /** The stages of the model version the instance runs on (the runtime model's {@code workflowStages}). */
    static String[] stages(Resource instance) {
        String modelId = instance.getValueMap().get("modelId", String.class);
        Resource meta = modelId == null ? null : instance.getResourceResolver().getResource(modelId + "/metaData");
        String[] s = meta == null ? null : meta.getValueMap().get("workflowStages", String[].class);
        return s == null ? new String[0] : s;
    }

    static Map<String, Object> item(Resource item) {
        ValueMap iv = item.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getPath());
        m.put("name", item.getName());
        m.put("nodeId", iv.get("nodeId", String.class));
        m.put("title", iv.get("nodeTitle", String.class));
        m.put("type", iv.get("type", String.class));
        m.put("status", iv.get("status", String.class));
        m.put("assignee", iv.get("assignee", String.class));
        m.put("startTime", ModelStore.iso(iv.get("startTime", Calendar.class)));
        m.put("endTime", ModelStore.iso(iv.get("endTime", Calendar.class)));
        m.put("completedBy", iv.get("completedBy", String.class));
        m.put("comment", iv.get("comment", String.class));
        m.put("failure", iv.get("failure", String.class));
        m.put("delegatedFrom", iv.get("delegatedFrom", String.class));
        m.put("dueTime", ModelStore.iso(iv.get("dueTime", Calendar.class)));
        m.put("stage", iv.get("workflowStage", String.class));
        return m;
    }

    /** The user ID and the IDs of all groups the resolver's user belongs to (with {@code everyone}). */
    static List<String> principals(ResourceResolver resolver) {
        List<String> out = new ArrayList<>();
        String user = resolver.getUserID();
        if (user != null) {
            out.add(user);
        }
        out.add("everyone");
        if ("admin".equals(user)) {
            out.add(ADMINISTRATORS); // the repository's admin acts for the administrators group even when not a member
        }
        Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession) || user == null) {
            return out;
        }
        try {
            UserManager um = ((JackrabbitSession) session).getUserManager();
            Authorizable a = um.getAuthorizable(user);
            if (a instanceof User && ((User) a).isAdmin() && !out.contains(ADMINISTRATORS)) {
                out.add(ADMINISTRATORS);
            }
            if (a != null) {
                for (Iterator<Group> it = a.memberOf(); it.hasNext();) {
                    String id = it.next().getID();
                    if (!out.contains(id)) {
                        out.add(id);
                    }
                }
            }
        } catch (RepositoryException e) {
            // only the user itself
        }
        return out;
    }

    /** Whether the resolver's user is an administrator (admin or member of administrators). */
    static boolean isAdmin(ResourceResolver resolver) {
        if ("admin".equals(resolver.getUserID())) {
            return true;
        }
        Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            return false;
        }
        try {
            Authorizable a = ((JackrabbitSession) session).getUserManager().getAuthorizable(resolver.getUserID());
            return a instanceof User && ((User) a).isAdmin() || principals(resolver).contains(ADMINISTRATORS);
        } catch (RepositoryException e) {
            return false;
        }
    }

    static List<String> list(Object v) {
        return v == null ? Collections.emptyList() : Collections.singletonList(String.valueOf(v));
    }
}
