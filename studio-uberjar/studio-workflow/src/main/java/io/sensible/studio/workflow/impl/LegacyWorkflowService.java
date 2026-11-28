package io.sensible.studio.workflow.impl;

import java.util.Collections;

import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code com.day.cq.workflow.WorkflowService}: how code written for the older API comes to a workflow
 * session, with the JCR session it has. The session it gets is the Granite one under the older names.
 */
@Component(service = com.day.cq.workflow.WorkflowService.class)
public class LegacyWorkflowService implements com.day.cq.workflow.WorkflowService {

    @Reference
    private WorkflowService service;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public com.day.cq.workflow.WorkflowSession getWorkflowSession(Session session) {
        try {
            // a resolver over the caller's own session: it is the caller's to end
            ResourceResolver resolver = resolverFactory.getResourceResolver(Collections.singletonMap("user.jcr.session", session));
            return session(new Objects.SessionImpl(resolver, service), this);
        } catch (LoginException e) {
            throw new IllegalStateException("No workflow session for " + session.getUserID(), e);
        }
    }

    /** The user or group of that name, as far as the session may see it. */
    private static org.apache.jackrabbit.api.security.user.Authorizable authorizable(com.adobe.granite.workflow.WorkflowSession granite, String id) {
        Session session = granite.adaptTo(Session.class);
        try {
            return session instanceof org.apache.jackrabbit.api.JackrabbitSession
                    ? ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager().getAuthorizable(id) : null;
        } catch (javax.jcr.RepositoryException e) {
            return null;
        }
    }

    /** A Granite session under the older names, with the two things only the older one has. */
    static com.day.cq.workflow.WorkflowSession session(com.adobe.granite.workflow.WorkflowSession granite,
            com.day.cq.workflow.WorkflowService from) {
        com.day.cq.workflow.WorkflowSession old = Legacy.old(granite, com.day.cq.workflow.WorkflowSession.class);
        return (com.day.cq.workflow.WorkflowSession) java.lang.reflect.Proxy.newProxyInstance(com.day.cq.workflow.WorkflowSession.class.getClassLoader(),
                new Class<?>[] {com.day.cq.workflow.WorkflowSession.class}, (proxy, method, args) -> {
                    // what only the older session has, or has in another shape
                    Class<?>[] types = method.getParameterTypes();
                    switch (method.getName()) {
                        case "getSession":
                            return granite.adaptTo(Session.class);
                        case "getWorkflowService":
                            return from;
                        case "getUser":
                            return authorizable(granite, granite.adaptTo(Session.class).getUserID());
                        case "getRoutes":
                            if (types.length == 1) {
                                return old.getRoutes((com.day.cq.workflow.exec.WorkItem) args[0], false);
                            }
                            break;
                        case "getBackRoutes":
                            if (types.length == 1) {
                                return old.getBackRoutes((com.day.cq.workflow.exec.WorkItem) args[0], false);
                            }
                            break;
                        case "delegateWorkItem": {
                            org.apache.jackrabbit.api.security.user.Authorizable to = (org.apache.jackrabbit.api.security.user.Authorizable) args[1];
                            try {
                                granite.delegateWorkItem(Legacy.granite(args[0], com.adobe.granite.workflow.exec.WorkItem.class),
                                        new Objects.ParticipantImpl(to.getID(), to.isGroup()));
                            } catch (com.adobe.granite.workflow.WorkflowException e) {
                                throw new com.day.cq.workflow.WorkflowException(e.getMessage(), e);
                            }
                            return null;
                        }
                        case "getDelegatees": {
                            java.util.List<org.apache.jackrabbit.api.security.user.Authorizable> out = new java.util.ArrayList<>();
                            java.util.Iterator<com.adobe.granite.workflow.exec.Participant> all = granite
                                    .getDelegates(Legacy.granite(args[0], com.adobe.granite.workflow.exec.WorkItem.class));
                            while (all.hasNext()) {
                                org.apache.jackrabbit.api.security.user.Authorizable a = authorizable(granite, all.next().getID());
                                if (a != null) {
                                    out.add(a);
                                }
                            }
                            return out;
                        }
                        case "evaluate": {
                            java.util.Map<String, Object> vars = new java.util.HashMap<>();
                            vars.put("workflowData", Legacy.granite(args[0], com.adobe.granite.workflow.exec.WorkflowData.class));
                            vars.put("workflowSession", granite);
                            vars.put("jcrSession", granite.adaptTo(Session.class));
                            return Engine.holds((String) args[1], () -> vars);
                        }
                        case "startWorkflow":
                            if (types.length == 3 && types[2] == java.util.Dictionary.class) {
                                java.util.Map<String, Object> meta = new java.util.HashMap<>();
                                java.util.Dictionary<?, ?> given = (java.util.Dictionary<?, ?>) args[2];
                                if (given != null) {
                                    for (java.util.Enumeration<?> keys = given.keys(); keys.hasMoreElements();) {
                                        Object k = keys.nextElement();
                                        meta.put(String.valueOf(k), given.get(k));
                                    }
                                }
                                return old.startWorkflow((com.day.cq.workflow.model.WorkflowModel) args[0],
                                        (com.day.cq.workflow.exec.WorkflowData) args[1], meta);
                            }
                            break;
                        default:
                            break;
                    }
                    try {
                        return method.invoke(old, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
