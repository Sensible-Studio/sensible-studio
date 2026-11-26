/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;

import org.apache.jackrabbit.api.observation.JackrabbitEvent;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Users and groups across the publish instances of a farm, the sending side: a visitor who registers,
 * changes the profile or the password, joins a group does so on one instance; that instance puts the
 * user or group, whole ({@link AuthorizablePack}), into its outbox, from where the author's reverse
 * agent takes it to the other publish instances ({@link ReverseReplication}). The author passes it on
 * and keeps nothing.
 *
 * <p>What arrives that way from another instance is written with a mark and is not offered again, or
 * two instances would send each other the same user for ever. Only on a publish instance.
 */
@Component(service = AuthorizableOutbox.class, immediate = true)
@Designate(ocd = AuthorizableOutbox.Config.class)
public class AuthorizableOutbox implements EventListener {

    @ObjectClassDefinition(name = "Sensible Studio Publishing: users and groups for the other publish instances",
            description = "A publish instance offers the users and groups that change on it to the other publish instances, through the author's reverse agent")
    public @interface Config {
        @AttributeDefinition(name = "Enabled", description = "Offer changed users and groups")
        boolean enabled() default true;
    }

    private static final Logger LOG = LoggerFactory.getLogger(AuthorizableOutbox.class);
    private static final int EVENTS = Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED | Event.PROPERTY_REMOVED;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private SlingSettingsService settings;

    @Reference
    private Outbox outbox;

    private ResourceResolver listening;
    private final java.util.List<EventListener> listeners = new java.util.ArrayList<>();

    @Activate
    void activate(Config config) {
        if (!config.enabled() || !settings.getRunModes().contains("publish")) {
            return;
        }
        try {
            listening = service();
            Session session = listening.adaptTo(Session.class);
            // a listener of its own for each place: registering one twice moves it, it does not add a place
            for (String home : AuthorizablePack.HOMES) {
                EventListener listener = this::onEvent;
                session.getWorkspace().getObservationManager().addEventListener(listener, EVENTS, home, true, null, null, false);
                listeners.add(listener);
            }
            LOG.info("Changed users and groups are offered to the other publish instances");
        } catch (LoginException | RepositoryException | RuntimeException e) {
            LOG.error("Users and groups that change here are not offered to the other publish instances: {}", e.toString());
            close();
        }
    }

    @Deactivate
    void deactivate() {
        close();
    }

    private void close() {
        ResourceResolver rr = listening;
        listening = null;
        if (rr != null) {
            try {
                for (EventListener listener : listeners) {
                    rr.adaptTo(Session.class).getWorkspace().getObservationManager().removeEventListener(listener);
                }
            } catch (RepositoryException | RuntimeException e) {
                LOG.debug("Could not stop listening: {}", e.toString());
            }
            rr.close();
        }
        listeners.clear();
    }

    private ResourceResolver service() throws LoginException {
        return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AgentManagerImpl.SUBSERVICE));
    }

    @Override
    public void onEvent(EventIterator events) {
        Set<String> touched = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        while (events.hasNext()) {
            Event event = events.nextEvent();
            try {
                if (AuthorizablePack.MARK.equals(userData(event))) {
                    continue;
                }
                String path = event.getPath();
                if (event.getType() == Event.NODE_REMOVED) {
                    removed.add(path);
                } else if (event.getType() == Event.NODE_ADDED) {
                    touched.add(path);
                } else {
                    touched.add(path.substring(0, Math.max(1, path.lastIndexOf('/'))));
                }
            } catch (RepositoryException e) {
                LOG.debug("An event without a path: {}", e.toString());
            }
        }
        LOG.debug("Changed below the users and groups: {}, removed: {}", touched, removed);
        if (touched.isEmpty() && removed.isEmpty()) {
            return;
        }
        try (ResourceResolver rr = service()) {
            synchronized (outbox.lock()) {
                int entries = record(rr, rr.adaptTo(Session.class), outbox, touched, removed);
                LOG.debug("{} users and groups offered to the other publish instances", entries);
                if (entries > 0) {
                    outbox.trimTo(rr);
                    rr.commit();
                }
            }
        } catch (LoginException | RepositoryException | IOException | RuntimeException e) {
            LOG.warn("Could not offer the changed users and groups {} to the other publish instances: {}", touched, e.toString());
        }
    }

    private static String userData(Event event) {
        try {
            if (event instanceof JackrabbitEvent && ((JackrabbitEvent) event).isExternal()) {
                return null; // a change another cluster node made: no mark to read
            }
            return event.getUserData();
        } catch (RepositoryException | RuntimeException e) {
            return null;
        }
    }

    /** One entry for every user or group the changes belong to, and one for each that is gone. Returns how many. */
    static int record(ResourceResolver rr, Session session, Outbox outbox, Set<String> touched, Set<String> removed)
            throws RepositoryException, IOException {
        Map<String, Node> units = new LinkedHashMap<>();
        for (String path : touched) {
            if (!AuthorizablePack.isAuthorizablePath(path) || AuthorizablePack.notCarried(path)) {
                continue;
            }
            Node unit = AuthorizablePack.unitOf(session, path);
            if (unit != null) {
                units.put(unit.getPath(), unit);
            }
        }
        Set<String> gone = new LinkedHashSet<>();
        for (String path : removed) {
            if (!AuthorizablePack.isAuthorizablePath(path) || AuthorizablePack.notCarried(path) || AuthorizablePack.systemPath(path)
                    || session.nodeExists(path)) {
                continue;
            }
            // a part of a user that is still there: the user changed
            Node unit = AuthorizablePack.unitOf(session, path);
            if (unit != null) {
                units.put(unit.getPath(), unit);
            } else if (!below(removed, path)) {
                gone.add(path);
            }
        }
        int entries = 0;
        for (Map.Entry<String, Node> unit : units.entrySet()) {
            try {
                outbox.putPack(rr, unit.getKey(), Outbox.ACTION_ACTIVATE, AuthorizablePack.pack(session, unit.getValue()));
                entries++;
            } catch (RepositoryException | IOException e) {
                LOG.warn("Could not offer {} to the other publish instances: {}", unit.getKey(), e.toString());
            }
        }
        for (String path : gone) {
            outbox.putPack(rr, path, Outbox.ACTION_DELETE, null);
            entries++;
        }
        return entries;
    }

    /** Removed together with something above it: the removal above says it all. */
    private static boolean below(Set<String> removed, String path) {
        for (String other : removed) {
            if (path.startsWith(other + "/")) {
                return true;
            }
        }
        return false;
    }
}
