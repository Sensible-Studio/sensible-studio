/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.AgentManager;

/**
 * The agents of this instance: {@code /etc/replication/agents.author/<id>/jcr:content}, as AEM lays
 * them out ({@code cq:Page} with a {@code cq:PageContent} holding the properties). Read through the
 * replication service user, so every author sees the same agents.
 */
@Component(service = {AgentManager.class, AgentManagerImpl.class})
public class AgentManagerImpl implements AgentManager {

    /** The author's agents; a publish instance keeps its own (flush agents) under {@code agents.publish}. */
    public static final String AGENTS_ROOT = "/etc/replication/agents.author";
    public static final String PUBLISH_AGENTS_ROOT = "/etc/replication/agents.publish";
    /** Mapped to the replication service user. */
    public static final String SUBSERVICE = "replication";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private JobManager jobManager;

    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL)
    private volatile org.apache.sling.settings.SlingSettingsService settings;

    public AgentManagerImpl() {
    }

    AgentManagerImpl(ResourceResolverFactory resolverFactory, JobManager jobManager) {
        this.resolverFactory = resolverFactory;
        this.jobManager = jobManager;
    }

    /** Where this instance's agents live: by its run mode, as AEM does. */
    public String agentsRoot() {
        org.apache.sling.settings.SlingSettingsService s = settings;
        return s != null && s.getRunModes().contains("publish") ? PUBLISH_AGENTS_ROOT : AGENTS_ROOT;
    }

    ResourceResolver serviceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    @Override
    public Map<String, Agent> getAgents() {
        try (ResourceResolver rr = serviceResolver()) {
            return agents(rr);
        } catch (LoginException e) {
            return Collections.emptyMap();
        }
    }

    /** The agents as a resolver sees them. */
    Map<String, Agent> agents(ResourceResolver rr) {
        Map<String, Agent> out = new LinkedHashMap<>();
        Resource root = rr.getResource(agentsRoot());
        if (root == null) {
            return out;
        }
        for (Resource r : root.getChildren()) {
            Resource content = r.getChild("jcr:content");
            if (content == null || r.getName().startsWith("jcr:") || r.getName().startsWith("rep:")) {
                continue;
            }
            // a copy: the resolver that read it may be closed by the time the agent is used
            ValueMap copy = new org.apache.sling.api.wrappers.ValueMapDecorator(new java.util.HashMap<>(content.getValueMap()));
            out.put(r.getName(), new AgentImpl(new ConfigImpl(r.getName(), content.getPath(), copy), this));
        }
        return out;
    }

    @Override
    public Agent getAgent(String id) {
        return id == null ? null : getAgents().get(id);
    }

    /** Queued requests of an agent (Sling jobs of the replication topic for that agent). */
    int queueSize(String agentId) {
        return jobManager == null ? 0 : ReplicationJob.find(jobManager, agentId, null, -1).size();
    }

    boolean blocked(String agentId) {
        if (jobManager == null) {
            return false;
        }
        for (Job j : ReplicationJob.find(jobManager, agentId, null, 1)) {
            return j.getRetryCount() > 0;
        }
        return false;
    }

    static final class ConfigImpl implements AgentConfig {
        private final String id;
        private final String path;
        private final ValueMap props;

        ConfigImpl(String id, String path, ValueMap props) {
            this.id = id;
            this.path = path;
            this.props = props;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return props.get(AGENT_NAME, id);
        }

        @Override
        public String getDescription() {
            return props.get(AGENT_DESCRIPTION, String.class);
        }

        @Override
        public String getConfigPath() {
            return path;
        }

        @Override
        public boolean isEnabled() {
            return props.get(AGENT_ENABLED, false);
        }

        @Override
        public String getTransportURI() {
            return props.get(TRANSPORT_URI, String.class);
        }

        @Override
        public String getTransportUser() {
            return props.get(TRANSPORT_USER, String.class);
        }

        @Override
        public String getTransportPassword() {
            return props.get(TRANSPORT_PASSWORD, String.class);
        }

        @Override
        public long getRetryDelay() {
            return props.get(RETRY_DELAY, 60000L);
        }

        @Override
        public String getSerializationType() {
            return props.get(SERIALIZATION_TYPE, "studio");
        }

        @Override
        public ValueMap getProperties() {
            return props;
        }
    }

    static final class AgentImpl implements Agent {
        private final ConfigImpl config;
        private final AgentManagerImpl manager;

        AgentImpl(ConfigImpl config, AgentManagerImpl manager) {
            this.config = config;
            this.manager = manager;
        }

        @Override
        public String getId() {
            return config.getId();
        }

        @Override
        public AgentConfig getConfiguration() {
            return config;
        }

        @Override
        public boolean isEnabled() {
            return config.isEnabled();
        }

        @Override
        public boolean isValid() {
            String uri = config.getTransportURI();
            return uri != null && (uri.startsWith("http://") || uri.startsWith("https://"));
        }

        @Override
        public int getQueueSize() {
            return manager.queueSize(getId());
        }

        @Override
        public boolean isBlocked() {
            return manager.blocked(getId());
        }
    }
}
