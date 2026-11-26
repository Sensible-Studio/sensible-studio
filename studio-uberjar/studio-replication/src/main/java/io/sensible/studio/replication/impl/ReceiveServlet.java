/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.Agent;
import com.day.cq.replication.ReplicationActionType;

/**
 * The receiving end of an agent ({@code /bin/receive}, AEM's path): installs the content pack of an
 * activation, removes the content of a deactivation, answers a test. Runs with the authenticated
 * user's session: the transport user of the agent must be allowed to write there.
 *
 * <p>On a publish instance, once the content is saved, the instance's own agents that deliver no
 * content (a cache flush, a webhook: {@code /etc/replication/agents.publish}) are sent the same
 * action and path (AEM's "On Receive" trigger). The cache in front of the publish is so told only
 * after the publish has what was published; a flush sent by the author could arrive before it.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/receive",
                "sling.servlet.methods=POST",
                "sling.servlet.methods=GET"
        })
public class ReceiveServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ReceiveServlet.class);

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private transient volatile AgentManagerImpl agents;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private transient volatile JobManager jobManager;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        response.getWriter().write("Sensible Studio replication receiver. POST content packs here.");
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        Session session = request.getResourceResolver().adaptTo(Session.class);
        String user = request.getResourceResolver().getUserID();
        if (session == null || user == null || "anonymous".equals(user)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Replication needs an authenticated transport user");
            return;
        }
        String action = request.getHeader(HttpTransport.HEADER_ACTION);
        String path = request.getHeader(HttpTransport.HEADER_PATH);
        ReplicationActionType type = ReplicationActionType.fromName(action);
        if (type == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown action " + action);
            return;
        }
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        try {
            switch (type) {
                case TEST:
                    response.getWriter().write("OK " + user);
                    return;
                case ACTIVATE: {
                    byte[] body = ContentPack.readAll(request.getInputStream());
                    if (AuthorizablePack.is(body)) {
                        authorizable(session, path, body, response);
                        return;
                    }
                    ContentPack.Unpacked pack = ContentPack.unpack(new java.io.ByteArrayInputStream(body));
                    if (path != null && !path.equals(pack.path())) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Pack is for " + pack.path() + ", not " + path);
                        return;
                    }
                    ContentPack.install(session, pack);
                    session.save();
                    LOG.info("Activated {} ({} binaries)", pack.path(), pack.binaries.size());
                    onReceive(type, pack.path(), user);
                    response.getWriter().write("Activated " + pack.path());
                    return;
                }
                case DEACTIVATE:
                case DELETE: {
                    if (AuthorizablePack.isAuthorizablePath(path)) {
                        authorizable(session, path, null, response);
                        return;
                    }
                    if (path == null || !path.startsWith("/") || "/".equals(path) || path.split("/").length < 3) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Refusing to remove " + path);
                        return;
                    }
                    boolean removed = ContentPack.remove(session, path);
                    session.save();
                    LOG.info("Deactivated {} ({})", path, removed ? "removed" : "was not there");
                    onReceive(type, path, user);
                    response.getWriter().write((removed ? "Deactivated " : "Nothing at ") + path);
                    return;
                }
                default:
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported action " + action);
            }
        } catch (RepositoryException | RuntimeException e) {
            LOG.error("Replication of {} failed: {}", path, e.getMessage(), e);
            try {
                session.refresh(false);
            } catch (RepositoryException ignored) {
                // nothing more to undo
            }
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * A user or a group another publish instance (or the author) sends: taken by publish instances only,
     * from whoever may write the publishing log there (the transport user), and written with the
     * publishing service user, which alone may manage users for this. What the pack may not be is
     * {@link AuthorizablePack}'s to refuse (409: asking again will not help).
     */
    private void authorizable(Session caller, String path, byte[] zip, SlingHttpServletResponse response) throws IOException, RepositoryException {
        AgentManagerImpl am = agents;
        if (am == null || !AgentManagerImpl.PUBLISH_AGENTS_ROOT.equals(am.agentsRoot())) {
            response.sendError(HttpServletResponse.SC_CONFLICT, "Users and groups are taken by publish instances only");
            return;
        }
        if (!caller.hasPermission(ReplicationJob.LOG_ROOT, Session.ACTION_SET_PROPERTY)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, caller.getUserID() + " may not send users and groups");
            return;
        }
        try (org.apache.sling.api.resource.ResourceResolver rr = am.serviceResolver()) {
            Session service = rr.adaptTo(Session.class);
            try {
                if (zip == null) {
                    boolean removed = AuthorizablePack.remove(service, path);
                    service.save();
                    LOG.info("User or group {} {}", path, removed ? "removed" : "was not there");
                    response.getWriter().write((removed ? "Deactivated " : "Nothing at ") + path);
                    return;
                }
                AuthorizablePack.Unpacked pack = AuthorizablePack.unpack(zip);
                if (path != null && !path.equals(pack.path)) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Pack is for " + pack.path + ", not " + path);
                    return;
                }
                AuthorizablePack.install(service, pack);
                service.save();
                LOG.info("{} {} taken from another instance", pack.group ? "Group" : "User", pack.id);
                response.getWriter().write("Activated " + pack.path);
            } catch (RepositoryException e) {
                service.refresh(false);
                LOG.warn("User or group {} not taken: {}", path, e.getMessage());
                response.sendError(e.getMessage() != null && e.getMessage().startsWith("Refused") ? HttpServletResponse.SC_CONFLICT
                        : HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        } catch (org.apache.sling.api.resource.LoginException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No publishing service user");
        }
    }

    /**
     * The content is saved: queues the request for this publish instance's flush and webhook agents.
     * Never fails the delivery: the content has arrived, and a cache that could not be told is told
     * when the queue retries.
     */
    private void onReceive(ReplicationActionType type, String path, String user) {
        AgentManagerImpl am = agents;
        JobManager jm = jobManager;
        if (am == null || jm == null || !AgentManagerImpl.PUBLISH_AGENTS_ROOT.equals(am.agentsRoot())) {
            return;
        }
        try {
            for (Map<String, Object> job : jobsOnReceive(am.getAgents().values(), type.getName(), path, user)) {
                if (jm.addJob(ReplicationJob.topicOf((String) job.get(ReplicationJob.PROP_AGENT)), job) == null) {
                    LOG.warn("The replication queue refused the {} of {} for agent {}", type.getName(), path, job.get(ReplicationJob.PROP_AGENT));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not tell this instance's agents about {}: {}", path, e.getMessage());
        }
    }

    /** One queued request for every enabled, valid agent that delivers no content (a flush, a webhook). */
    static List<Map<String, Object>> jobsOnReceive(Collection<Agent> agents, String action, String path, String user) {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Agent a : agents) {
            if (!a.isEnabled() || !a.isValid() || !HttpTransport.sendsNoContent(a.getConfiguration().getSerializationType())) {
                continue;
            }
            Map<String, Object> props = new HashMap<>();
            props.put(ReplicationJob.PROP_AGENT, a.getId());
            props.put(ReplicationJob.PROP_ACTION, action);
            props.put(ReplicationJob.PROP_PATH, path);
            props.put(ReplicationJob.PROP_DEEP, false);
            props.put(ReplicationJob.PROP_USER, user);
            props.put(ReplicationJob.PROP_REQUESTED, Calendar.getInstance());
            jobs.add(props);
        }
        return jobs;
    }
}
