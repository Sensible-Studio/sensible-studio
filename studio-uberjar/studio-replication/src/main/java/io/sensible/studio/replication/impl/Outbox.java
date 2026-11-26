/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reverse replication, the publish side: what visitors create on a publish instance (form submissions,
 * user-generated content) waits in the <b>outbox</b>, {@code /var/studio/replication/outbox/<entry>},
 * packed the way forward replication packs content, until an author's reverse agent fetches it
 * ({@link OutboxServlet}). The publish never needs the author's address or credentials.
 * <p>
 * Watches the configured roots; a change below one of them makes an entry for its <i>unit</i>: the
 * nearest node at or above the change (and below the root) that carries a {@code sling:resourceType}
 * — a form submission, a comment — or the changed node itself. One entry per unit: a later change
 * replaces the pack. A removal makes a {@code Delete} entry. Does nothing on an author instance.
 */
@Component(
        service = {ResourceChangeListener.class, Outbox.class},
        property = {
                ResourceChangeListener.PATHS + "=/var/studio/forms/submissions",
                ResourceChangeListener.PATHS + "=/content/usergenerated",
                ResourceChangeListener.CHANGES + "=ADDED",
                ResourceChangeListener.CHANGES + "=CHANGED",
                ResourceChangeListener.CHANGES + "=REMOVED"
        })
@Designate(ocd = Outbox.Config.class)
public class Outbox implements ResourceChangeListener {

    @ObjectClassDefinition(name = "Sensible Studio Publishing: outbox", description = "What a publish instance keeps for the author's reverse agents")
    public @interface Config {
        @AttributeDefinition(name = "Enabled", description = "Keep an outbox on publish instances")
        boolean enabled() default true;

        @AttributeDefinition(name = "Keep at most", description = "Entries kept; the oldest go when a reverse agent is not fetching")
        int maxEntries() default 10000;

        @AttributeDefinition(name = "Not from these users",
                description = "Changes these users make are not offered: what replication itself brings here (the transport user, "
                        + "the replication service user). Otherwise content passed on from another publish instance would go back "
                        + "and forth for ever.")
        String[] ignoredUsers() default {"studio-transport", "studio-replication-service"};
    }

    public static final String ROOT = "/var/studio/replication/outbox";
    static final String ACTION_ACTIVATE = "Activate";
    static final String ACTION_DELETE = "Delete";
    private static final Logger LOG = LoggerFactory.getLogger(Outbox.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private SlingSettingsService settings;

    private volatile boolean active;
    private volatile int maxEntries = 10000;
    private volatile Set<String> ignoredUsers = Collections.emptySet();
    private final Object lock = new Object();

    @Activate
    void activate(Config config) {
        maxEntries = config.maxEntries();
        ignoredUsers = config.ignoredUsers() == null ? Collections.emptySet()
                : new java.util.HashSet<>(java.util.Arrays.asList(config.ignoredUsers()));
        active = config.enabled() && settings.getRunModes().contains("publish");
        LOG.info("Publishing outbox {}", active ? "active (publish instance)" : "inactive");
    }

    public boolean isActive() {
        return active;
    }

    /** Held while entries are written, by whoever writes them. */
    Object lock() {
        return lock;
    }

    /** After entries were written: the oldest go if there are too many. */
    void trimTo(ResourceResolver rr) {
        trim(rr);
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        if (!active) {
            return;
        }
        Set<String> removed = new LinkedHashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        for (ResourceChange c : changes) {
            if (c.getPath().startsWith(ROOT) || c.getPath().contains("/rep:policy") || fromReplication(c, ignoredUsers)) {
                continue;
            }
            if (c.getType() == ResourceChange.ChangeType.REMOVED) {
                removed.add(c.getPath());
            } else {
                touched.add(c.getPath());
            }
        }
        if (removed.isEmpty() && touched.isEmpty()) {
            return;
        }
        try (ResourceResolver rr = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AgentManagerImpl.SUBSERVICE))) {
            synchronized (lock) {
                Set<String> units = new LinkedHashSet<>();
                for (String p : touched) {
                    String unit = unitOf(rr, p);
                    if (unit != null) {
                        units.add(unit);
                    }
                }
                for (String unit : units) {
                    if (!isAncestorOfAnother(units, unit)) {
                        put(rr, unit, ACTION_ACTIVATE);
                    }
                }
                for (String p : removed) {
                    if (rr.getResource(p) == null && !coveredBy(units, p)) {
                        put(rr, p, ACTION_DELETE);
                    }
                }
                trim(rr);
                rr.commit();
            }
        } catch (LoginException | RepositoryException | IOException e) {
            LOG.warn("Outbox: cannot record {}: {}", touched, e.getMessage());
        }
    }

    /** A change replication made here (content another instance offered, passed on by the author): not offered again. */
    static boolean fromReplication(ResourceChange c, Set<String> ignoredUsers) {
        return c.getUserId() != null && ignoredUsers.contains(c.getUserId());
    }

    /** User-generated content (not form submissions, which are for the author alone): passed on to every publish instance. */
    static boolean isSharedWithPublishInstances(String path) {
        return path != null && path.startsWith(USER_GENERATED + "/");
    }

    static final String USER_GENERATED = "/content/usergenerated";

    /** A folder created on the way to a submission: the submission's pack carries it, so no entry of its own. */
    static boolean isAncestorOfAnother(Set<String> units, String unit) {
        for (String u : units) {
            if (u.startsWith(unit + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean coveredBy(Set<String> units, String path) {
        for (String u : units) {
            if (path.equals(u) || path.startsWith(u + "/")) {
                return true;
            }
        }
        return false;
    }

    /** The unit a change belongs to: the nearest node at or above it with a resource type, inside a watched root. */
    static String unitOf(ResourceResolver rr, String path) {
        Resource r = rr.getResource(path);
        if (r == null) {
            return null;
        }
        String root = rootOf(path);
        if (root == null) {
            return null;
        }
        Resource unit = null;
        for (Resource x = r; x != null && x.getPath().length() > root.length(); x = x.getParent()) {
            if (x.getValueMap().get("sling:resourceType", String.class) != null) {
                unit = x;
            }
        }
        if (unit != null) {
            return unit.getPath();
        }
        return path.length() > root.length() ? path : null;
    }

    private static final String[] ROOTS = {"/var/studio/forms/submissions", "/content/usergenerated"};

    static String rootOf(String path) {
        for (String root : ROOTS) {
            if (path.startsWith(root + "/")) {
                return root;
            }
        }
        return null;
    }

    /** One entry per unit: the pack of what the unit is now, or the fact that it is gone. */
    void put(ResourceResolver rr, String path, String action) throws PersistenceException, RepositoryException, IOException {
        putPack(rr, path, action, ACTION_ACTIVATE.equals(action) ? ContentPack.pack(rr.adaptTo(Session.class), path, action, true) : null);
    }

    /** As {@link #put}, with a pack made by the caller (a user or a group: {@link AuthorizableOutbox}); null for a removal. */
    void putPack(ResourceResolver rr, String path, String action, byte[] pack) throws PersistenceException {
        Resource root = ResourceUtil.getOrCreateResource(rr, ROOT, "sling:Folder", "sling:Folder", false);
        String name = entryName(path);
        Resource existing = root.getChild(name);
        if (existing != null) {
            rr.delete(existing);
        }
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("path", path);
        props.put("action", action);
        props.put("created", Calendar.getInstance());
        Resource entry = rr.create(root, name, props);
        if (pack != null) {
            Map<String, Object> file = new HashMap<>();
            file.put("jcr:primaryType", "nt:file");
            Resource f = rr.create(entry, "pack", file);
            Map<String, Object> content = new HashMap<>();
            content.put("jcr:primaryType", "nt:resource");
            content.put("jcr:mimeType", HttpTransport.CONTENT_TYPE);
            content.put("jcr:data", new ByteArrayInputStream(pack));
            content.put("jcr:lastModified", Calendar.getInstance());
            rr.create(f, "jcr:content", content);
        }
        LOG.debug("Outbox: {} {}", action, path);
    }

    /** A stable, path-derived name, so a later change of the same unit replaces its entry. */
    static String entryName(String path) {
        return Integer.toHexString(path.hashCode()) + "-" + path.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private void trim(ResourceResolver rr) {
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            return;
        }
        List<Resource> entries = new ArrayList<>();
        root.getChildren().forEach(entries::add);
        if (entries.size() <= maxEntries) {
            return;
        }
        entries.sort((a, b) -> {
            Calendar ca = a.getValueMap().get("created", Calendar.class);
            Calendar cb = b.getValueMap().get("created", Calendar.class);
            return ca == null || cb == null ? 0 : ca.compareTo(cb);
        });
        for (int i = 0; i < entries.size() - maxEntries; i++) {
            try {
                rr.delete(entries.get(i));
            } catch (PersistenceException e) {
                LOG.debug("Outbox: cannot trim {}", entries.get(i).getPath());
            }
        }
    }

    /** The entries waiting, oldest first: id, path, action, created. */
    static List<Map<String, Object>> list(ResourceResolver rr) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            return out;
        }
        for (Resource e : root.getChildren()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getName());
            m.put("path", e.getValueMap().get("path", String.class));
            m.put("action", e.getValueMap().get("action", String.class));
            Calendar created = e.getValueMap().get("created", Calendar.class);
            m.put("created", created == null ? null : created.toInstant().toString());
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("created")).compareTo(String.valueOf(b.get("created"))));
        return out;
    }

    /** Marks an entry as fetched and installed: it goes. */
    static boolean ack(ResourceResolver rr, String id) throws PersistenceException {
        return ack(rr, id, null);
    }

    /**
     * As {@link #ack(ResourceResolver, String)}, for the entry as it was listed ({@code created}): if the
     * unit changed again since, the entry holds the newer state, which was not fetched, and stays.
     */
    static boolean ack(ResourceResolver rr, String id, String created) throws PersistenceException {
        Resource e = rr.getResource(ROOT + "/" + id);
        if (e == null) {
            return false;
        }
        Calendar now = e.getValueMap().get("created", Calendar.class);
        if (created != null && !created.isEmpty() && now != null && !created.equals(now.toInstant().toString())) {
            return true;
        }
        rr.delete(e);
        rr.commit();
        return true;
    }

    static ModifiableValueMap props(Resource r) {
        return r.adaptTo(ModifiableValueMap.class);
    }
}
