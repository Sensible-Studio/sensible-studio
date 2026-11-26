/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationException;

/**
 * Reverse replication, the author side: every half minute (and on <i>Fetch now</i>) each enabled
 * <b>reverse</b> agent ({@code serializationType=reverse}, transport URI = a publish instance's
 * {@code /bin/outbox}) lists that outbox, fetches each entry's pack, installs it here with the
 * replication service user (or removes the path for a Delete), and acknowledges it. What visitors
 * left on the publish — form submissions, user-generated content — thus arrives on the author
 * without the publish knowing the author. Users and groups that changed on a publish are not
 * installed here: they are passed on to the other publish instances ({@link AuthorizableOutbox}).
 */
@Component(service = {Runnable.class, ReverseReplication.class},
        property = {"scheduler.period:Long=30", "scheduler.concurrent:Boolean=false", "scheduler.runOn=SINGLE"})
public class ReverseReplication implements Runnable {

    static final String REVERSE = "reverse";
    private static final Logger LOG = LoggerFactory.getLogger(ReverseReplication.class);

    @Reference
    private AgentManagerImpl agents;

    @Override
    public void run() {
        try (ResourceResolver rr = agents.serviceResolver()) {
            for (Agent agent : agents.agents(rr).values()) {
                if (agent.isEnabled() && agent.isValid() && REVERSE.equals(agent.getConfiguration().getSerializationType())) {
                    fetch(rr, agent.getConfiguration());
                }
            }
        } catch (LoginException e) {
            LOG.error("No replication service user: {}", e.getMessage());
        }
    }

    /** Fetches one agent's outbox now; the number of entries installed. */
    public Map<String, Object> fetchNow(String agentId) throws ReplicationException {
        try (ResourceResolver rr = agents.serviceResolver()) {
            Agent agent = agents.agents(rr).get(agentId);
            if (agent == null) {
                throw new ReplicationException("No agent " + agentId);
            }
            if (!REVERSE.equals(agent.getConfiguration().getSerializationType())) {
                throw new ReplicationException("Agent " + agentId + " is not a reverse agent");
            }
            return fetch(rr, agent.getConfiguration());
        } catch (LoginException e) {
            throw new ReplicationException("No replication service user", e);
        }
    }

    /** The outbox listing of an agent — also its connection test. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(AgentConfig agent) throws ReplicationException {
        Object body = Json.parse(get(agent, "?op=list", true));
        if (!(body instanceof Map)) {
            throw new ReplicationException("The outbox at " + agent.getTransportURI() + " did not answer with its entries");
        }
        Object entries = ((Map<String, Object>) body).get("entries");
        return entries instanceof List ? (List<Map<String, Object>>) entries : new ArrayList<>();
    }

    private Map<String, Object> fetch(ResourceResolver rr, AgentConfig agent) {
        Map<String, Object> result = new LinkedHashMap<>();
        int installed = 0;
        int failed = 0;
        String lastPath = null;
        try {
            List<Map<String, Object>> entries = list(agent);
            Session session = rr.adaptTo(Session.class);
            for (Map<String, Object> e : entries) {
                String id = String.valueOf(e.get("id"));
                String path = String.valueOf(e.get("path"));
                String action = String.valueOf(e.get("action"));
                String created = e.get("created") == null ? "" : String.valueOf(e.get("created"));
                lastPath = path;
                try {
                    if (AuthorizablePack.isAuthorizablePath(path)) {
                        // a user or a group: for the other publish instances, never for this author
                        byte[] zip = Outbox.ACTION_DELETE.equals(action) ? null
                                : getBytes(agent, "?op=fetch&id=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
                        if (zip != null && !AuthorizablePack.is(zip)) {
                            throw new ReplicationException("What " + agent.getTransportURI() + " offers for " + path + " is not a user or a group");
                        }
                        forward(rr, agent, action, path, zip);
                    } else if (Outbox.ACTION_DELETE.equals(action)) {
                        if (path.split("/").length >= 3 && ContentPack.remove(session, path)) {
                            session.save();
                        }
                        if (Outbox.isSharedWithPublishInstances(path)) {
                            forward(rr, agent, action, path, null);
                        }
                    } else {
                        byte[] zip = getBytes(agent, "?op=fetch&id=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
                        ContentPack.Unpacked pack = ContentPack.unpack(new java.io.ByteArrayInputStream(zip));
                        ContentPack.install(session, pack);
                        session.save();
                        // a comment, a rating: on the author for moderation, and on every other publish instance for visitors
                        if (Outbox.isSharedWithPublishInstances(path)) {
                            forward(rr, agent, action, path, zip);
                        }
                    }
                    post(agent, "?op=ack&id=" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&created=" + URLEncoder.encode(created, StandardCharsets.UTF_8));
                    installed++;
                    LOG.info("Reverse {}: {} {} from {}", agent.getId(), action, path, agent.getTransportURI());
                } catch (IOException | RepositoryException | ReplicationException ex) {
                    failed++;
                    LOG.warn("Reverse {}: {} {} failed: {}", agent.getId(), action, path, ex.getMessage());
                    try {
                        session.refresh(false);
                    } catch (RepositoryException ignored) {
                        // keep going with the next entry
                    }
                    record(rr, agent.getId(), action, path, ex.getMessage());
                }
            }
            waiting(rr, agent.getId(), entries.size() - installed);
            if (installed > 0 || (failed == 0 && lastPath == null)) {
                record(rr, agent.getId(), "Fetch", lastPath == null ? agent.getTransportURI() : lastPath, null);
            }
        } catch (ReplicationException e) {
            failed++;
            LOG.warn("Reverse {}: cannot read the outbox: {}", agent.getId(), e.getMessage());
            record(rr, agent.getId(), "Fetch", agent.getTransportURI(), e.getMessage());
        }
        result.put("installed", installed);
        result.put("failed", failed);
        return result;
    }

    /**
     * Passes what one publish instance offered on to the others: a user or a group (nothing of it is
     * written here), or user-generated content (installed here first). To every enabled agent that
     * delivers content, except the one for the instance it came from.
     * Fails if an instance could not be reached, so that the entry stays in the outbox and all of them
     * get it at the next fetch (taking it twice changes nothing). An instance that refuses it for good
     * (it holds something else under that name) is noted and not asked again.
     */
    private void forward(ResourceResolver rr, AgentConfig from, String action, String path, byte[] zip) throws ReplicationException {
        String source = origin(from.getTransportURI());
        String unreachable = null;
        for (Agent other : agents.agents(rr).values()) {
            AgentConfig target = other.getConfiguration();
            String type = target.getSerializationType();
            if (!other.isEnabled() || !other.isValid() || REVERSE.equals(type) || HttpTransport.sendsNoContent(type)
                    || (source != null && source.equals(origin(target.getTransportURI())))) {
                continue;
            }
            try {
                HttpTransport.deliver(target, action, path, zip);
                record(rr, other.getId(), action, path, null);
            } catch (ReplicationException ex) {
                record(rr, other.getId(), action, path, ex.getMessage());
                if (ex.getMessage() != null && ex.getMessage().startsWith("HTTP 409 ")) {
                    LOG.warn("Reverse {}: {} refuses {}: {}", from.getId(), other.getId(), path, ex.getMessage());
                } else {
                    unreachable = other.getId() + ": " + ex.getMessage();
                }
            }
        }
        if (unreachable != null) {
            throw new ReplicationException("not delivered to " + unreachable);
        }
    }

    /**
     * The first copy of the users to a publish instance: every user and group that travels, read from
     * the publish instance behind a reverse agent ({@code from}, its outbox's {@code op=authorizables}),
     * delivered to the instance of a content agent ({@code to}) the way the user sync does. Taking one
     * twice changes nothing, so it can be run again; one the target refuses for good (409: something
     * else of that name there) is counted, not retried. For a new publish instance before it takes
     * visitors; on one that already has users, what it changed since is replaced by the source's.
     */
    public Map<String, Object> copyAuthorizables(String fromId, String toId) throws ReplicationException {
        try (ResourceResolver rr = agents.serviceResolver()) {
            Map<String, Agent> all = agents.agents(rr);
            Agent from = all.get(fromId);
            Agent to = all.get(toId);
            if (from == null || !REVERSE.equals(from.getConfiguration().getSerializationType())) {
                throw new ReplicationException("from must be a reverse agent (the publish instance that has the users)");
            }
            if (to == null || REVERSE.equals(to.getConfiguration().getSerializationType()) || HttpTransport.sendsNoContent(to.getConfiguration().getSerializationType())) {
                throw new ReplicationException("to must be an agent that delivers content (the publish instance to copy them to)");
            }
            String source = origin(from.getConfiguration().getTransportURI());
            if (source != null && source.equals(origin(to.getConfiguration().getTransportURI()))) {
                throw new ReplicationException("from and to are the same instance");
            }
            Object body = Json.parse(get(from.getConfiguration(), "?op=authorizables", true));
            @SuppressWarnings("unchecked")
            Object entries = body instanceof Map ? ((Map<String, Object>) body).get("entries") : null;
            if (!(entries instanceof List)) {
                throw new ReplicationException(from.getConfiguration().getTransportURI() + " did not list its users");
            }
            int copied = 0;
            int refused = 0;
            int failed = 0;
            String lastError = null;
            for (Object e : (List<?>) entries) {
                String path = String.valueOf(((Map<?, ?>) e).get("path"));
                try {
                    byte[] zip = getBytes(from.getConfiguration(), "?op=authorizable&path=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
                    if (!AuthorizablePack.is(zip)) {
                        throw new ReplicationException("not a user or a group");
                    }
                    HttpTransport.deliver(to.getConfiguration(), "Activate", path, zip);
                    copied++;
                } catch (ReplicationException ex) {
                    if (ex.getMessage() != null && ex.getMessage().startsWith("HTTP 409 ")) {
                        refused++;
                    } else {
                        failed++;
                    }
                    lastError = path + ": " + ex.getMessage();
                    LOG.warn("Copying users {} → {}: {} {}", fromId, toId, path, ex.getMessage());
                }
            }
            String summary = copied + " copied, " + refused + " refused, " + failed + " failed";
            LOG.info("Copied the users of {} to {}: {}", fromId, toId, summary);
            record(rr, toId, "Copy users", from.getConfiguration().getTransportURI(), failed > 0 ? lastError : null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("listed", ((List<?>) entries).size());
            out.put("copied", copied);
            out.put("refused", refused);
            out.put("failed", failed);
            out.put("message", summary);
            if (lastError != null) {
                out.put("lastError", lastError);
            }
            return out;
        } catch (LoginException e) {
            throw new ReplicationException("No replication service user", e);
        }
    }

    /** scheme://host:port of an address, to tell which agents talk to the same instance. */
    static String origin(String uri) {
        try {
            java.net.URI u = new java.net.URI(uri);
            if (u.getHost() == null) {
                return null;
            }
            int port = u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            return (u.getScheme() + "://" + u.getHost() + ":" + port).toLowerCase(java.util.Locale.ROOT);
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** What the outbox still held after this fetch (entries that could not be installed), for the agent's card. */
    private static void waiting(ResourceResolver rr, String agentId, int count) {
        try {
            org.apache.sling.api.resource.Resource r = org.apache.sling.api.resource.ResourceUtil.getOrCreateResource(rr,
                    ReplicationJob.LOG_ROOT + "/" + agentId, "nt:unstructured", "sling:Folder", false);
            org.apache.sling.api.resource.ModifiableValueMap m = r.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
            if (m != null && !Integer.valueOf(count).equals(m.get("outboxWaiting", Integer.class))) {
                m.put("outboxWaiting", count);
                rr.commit();
            }
        } catch (PersistenceException e) {
            LOG.debug("Reverse {}: cannot record the outbox count: {}", agentId, e.getMessage());
        }
    }

    private static void record(ResourceResolver rr, String agentId, String action, String path, String error) {
        try {
            ReplicationJob.record(rr, agentId, action, path, error);
        } catch (PersistenceException e) {
            LOG.debug("Reverse {}: cannot record: {}", agentId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static String get(AgentConfig agent, String query, boolean text) throws ReplicationException {
        return new String(getBytes(agent, query), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(AgentConfig agent, String query) throws ReplicationException {
        return call(agent, "GET", query);
    }

    private static void post(AgentConfig agent, String query) throws ReplicationException {
        call(agent, "POST", query);
    }

    private static byte[] call(AgentConfig agent, String method, String query) throws ReplicationException {
        String uri = agent.getTransportURI();
        if (uri == null || uri.isEmpty()) {
            throw new ReplicationException("Agent " + agent.getId() + " has no transport URI");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(uri + query).openConnection();
            TrustStore.apply(c);
            c.setConnectTimeout(10000);
            c.setReadTimeout(120000);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            String user = agent.getTransportUser();
            if (user != null && !user.isEmpty()) {
                String pw = agent.getTransportPassword() == null ? "" : agent.getTransportPassword();
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8)));
            }
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = in == null ? new byte[0] : ContentPack.readAll(in);
            if (status < 200 || status >= 300) {
                String t = new String(body, StandardCharsets.UTF_8).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                throw new ReplicationException("HTTP " + status + " from " + uri + (t.isEmpty() ? "" : ": " + (t.length() > 200 ? t.substring(0, 200) + "…" : t)));
            }
            return body;
        } catch (IOException e) {
            throw new ReplicationException("Cannot reach " + uri + ": " + e.getMessage(), e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
