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

    /** A Granite session under the older names, with the two things only the older one has. */
    static com.day.cq.workflow.WorkflowSession session(com.adobe.granite.workflow.WorkflowSession granite,
            com.day.cq.workflow.WorkflowService from) {
        com.day.cq.workflow.WorkflowSession old = Legacy.old(granite, com.day.cq.workflow.WorkflowSession.class);
        return (com.day.cq.workflow.WorkflowSession) java.lang.reflect.Proxy.newProxyInstance(com.day.cq.workflow.WorkflowSession.class.getClassLoader(),
                new Class<?>[] {com.day.cq.workflow.WorkflowSession.class}, (proxy, method, args) -> {
                    if ("getSession".equals(method.getName()) && method.getParameterCount() == 0) {
                        return granite.adaptTo(Session.class);
                    }
                    if ("getWorkflowService".equals(method.getName())) {
                        return from;
                    }
                    try {
                        return method.invoke(old, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
