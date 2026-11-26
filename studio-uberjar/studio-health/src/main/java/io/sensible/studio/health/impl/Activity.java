/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The activity log: who created, changed, removed, published or unpublished which page, block, form,
 * record or media file, and when — what AEM calls the audit log. Listens to the changes below
 * {@code /content} made on this instance by people (service users and the system are left out),
 * reduces each to its <i>unit</i> (the page or the media file the changed node belongs to) and keeps one
 * line per unit, user and kind of change at {@code /var/studio/health/activity/<day>/<entry>}: repeated
 * saves of the same page by the same person within five minutes are one line. Lines older than
 * {@code keepDays} go once a day.
 */
@Component(
        service = {ResourceChangeListener.class, Activity.class, Runnable.class},
        property = {
                ResourceChangeListener.PATHS + "=/content",
                ResourceChangeListener.CHANGES + "=ADDED",
                ResourceChangeListener.CHANGES + "=CHANGED",
                ResourceChangeListener.CHANGES + "=REMOVED",
                "scheduler.period:Long=86400",
                "scheduler.concurrent:Boolean=false",
                "scheduler.runOn=SINGLE"
        })
@Designate(ocd = Activity.Config.class)
public class Activity implements ResourceChangeListener, Runnable {

    @ObjectClassDefinition(name = "Sensible Studio: activity log", description = "Who changed and published what")
    public @interface Config {
        @AttributeDefinition(name = "Enabled")
        boolean enabled() default true;

        @AttributeDefinition(name = "Keep days", description = "Lines older than this are removed")
        int keepDays() default 90;
    }

    public static final String ROOT = "/var/studio/health/activity";
    static final String CREATED = "created";
    static final String MODIFIED = "modified";
    static final String DELETED = "deleted";
    static final String PUBLISHED = "published";
    static final String UNPUBLISHED = "unpublished";
    /** the same person saving the same page again within this is the same line */
    static final long SAME_EDIT_MILLIS = 5 * 60 * 1000L;
    private static final Logger LOG = LoggerFactory.getLogger(Activity.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    private volatile boolean enabled = true;
    private volatile int keepDays = 90;
    /** unit|user|type → when it was last written, to fold repeated saves */
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    /** unit|user|modified → the line last written for it, so a publication can take back the save that came with it */
    private final Map<String, String> lines = new ConcurrentHashMap<>();

    @Activate
    @Modified
    void activate(Config config) {
        enabled = config.enabled();
        keepDays = Math.max(1, config.keepDays());
    }

    /** One thing someone did. */
    static final class Event {
        final String path;
        final String type;
        final String user;

        Event(String path, String type, String user) {
            this.path = path;
            this.type = type;
            this.user = user;
        }
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        if (!enabled) {
            return;
        }
        List<ResourceChange> mine = new ArrayList<>();
        for (ResourceChange c : changes) {
            if (!c.isExternal() && isPerson(c.getUserId()) && !isNoise(c.getPath())) {
                mine.add(c);
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        try (ResourceResolver rr = serviceResolver()) {
            // the units created in this batch: their own jcr:content changes are part of the creation
            Map<String, Event> events = new LinkedHashMap<>();
            for (ResourceChange c : mine) {
                Event e = classify(rr, c.getPath(), c.getType(), c.getUserId(), System.currentTimeMillis());
                if (e == null) {
                    continue;
                }
                String key = e.path + "|" + e.user;
                Event had = events.get(key);
                if (had == null || rank(e.type) > rank(had.type)) {
                    events.put(key, e);
                }
            }
            long now = System.currentTimeMillis();
            boolean wrote = false;
            for (Event e : events.values()) {
                String key = e.path + "|" + e.user + "|" + e.type;
                Long last = recent.get(key);
                // repeated saves are one line; one publication reaches us as several changes of the same stamp
                if (last != null && now - last < (MODIFIED.equals(e.type) ? SAME_EDIT_MILLIS : 10000)) {
                    continue;
                }
                // a page just created (or published) by this person: the saves that come with it are not news
                Long made = recent.get(e.path + "|" + e.user + "|" + CREATED);
                if (MODIFIED.equals(e.type) && made != null && now - made < 10000) {
                    continue;
                }
                // publishing saves a version first, which reaches us as a change of the page a moment before
                // the publication itself: that line is not something the person did
                if (PUBLISHED.equals(e.type) || UNPUBLISHED.equals(e.type)) {
                    String modifiedKey = e.path + "|" + e.user + "|" + MODIFIED;
                    Long modified = recent.get(modifiedKey);
                    String line = lines.remove(modifiedKey);
                    if (modified != null && now - modified < 10000 && line != null) {
                        Resource stale = rr.getResource(line);
                        if (stale != null) {
                            rr.delete(stale);
                        }
                        recent.remove(modifiedKey);
                    }
                }
                recent.put(key, now);
                String written = write(rr, e, Calendar.getInstance());
                if (MODIFIED.equals(e.type)) {
                    lines.put(key, written);
                }
                wrote = true;
            }
            if (wrote) {
                rr.commit();
            }
            if (recent.size() > 5000) {
                lines.clear();
                recent.clear();
            }
        } catch (LoginException | PersistenceException e) {
            LOG.debug("Activity: cannot record: {}", e.getMessage());
        }
    }

    /** created and deleted say more than published, which says more than modified. */
    private static int rank(String type) {
        return CREATED.equals(type) || DELETED.equals(type) ? 3 : PUBLISHED.equals(type) || UNPUBLISHED.equals(type) ? 2 : 1;
    }

    static boolean isPerson(String user) {
        return user != null && !user.isEmpty() && !user.endsWith("-service") && !user.startsWith("sling-") && !"anonymous".equals(user);
    }

    /** Changes that are bookkeeping, not something a person did to content. */
    static boolean isNoise(String path) {
        return path.contains("/rep:policy") || path.contains("/rep:cugPolicy") || path.contains("/jcr:content/renditions")
                || path.contains("/cq:annotations") || path.startsWith("/content/usergenerated") || path.startsWith("/content/projects") || path.endsWith("/jcr:content/cq:LiveSyncConfig");
    }

    /**
     * What a change means: the unit (the node above {@code jcr:content}, or the changed node itself) and what
     * happened to it. A change inside {@code jcr:content} is a modification of the unit — or its publication,
     * when the content's replication stamp is from this moment; a node added or removed that is itself a page,
     * a media file or a folder is created or deleted. Anything else is nothing.
     */
    static Event classify(ResourceResolver rr, String path, ResourceChange.ChangeType type, String user, long now) {
        int at = path.indexOf("/jcr:content");
        if (at > 0) {
            String unit = path.substring(0, at);
            Resource content = rr.getResource(unit + "/jcr:content");
            if (content == null) {
                return null; // the unit went with it: its own removal is the event
            }
            ValueMap v = content.getValueMap();
            Calendar replicated = v.get("cq:lastReplicated", Calendar.class);
            if (replicated != null && Math.abs(now - replicated.getTimeInMillis()) < 10000 && user.equals(v.get("cq:lastReplicatedBy", user))) {
                return new Event(unit, "Activate".equalsIgnoreCase(v.get("cq:lastReplicationAction", "Activate")) ? PUBLISHED : UNPUBLISHED, user);
            }
            return new Event(unit, MODIFIED, user);
        }
        if (type == ResourceChange.ChangeType.REMOVED) {
            return rr.getResource(path) == null ? new Event(path, DELETED, user) : null;
        }
        if (type == ResourceChange.ChangeType.ADDED) {
            Resource r = rr.getResource(path);
            String nt = r == null ? null : r.getValueMap().get("jcr:primaryType", String.class);
            if (nt != null && (nt.equals("cq:Page") || nt.equals("dam:Asset") || nt.endsWith("Folder"))) {
                return new Event(path, CREATED, user);
            }
        }
        return null;
    }

    static String kindOf(String path) {
        return path.startsWith("/content/dam") ? "media" : path.startsWith("/content/experience-fragments") ? "block" : path.startsWith("/content/forms") ? "form" : "page";
    }

    /** Writes one line (no commit); its path. */
    static String write(ResourceResolver rr, Event e, Calendar when) throws PersistenceException {
        Resource day = ResourceUtil.getOrCreateResource(rr, ROOT + "/" + day(when), "sling:Folder", "sling:Folder", false);
        String name = Long.toString(when.getTimeInMillis(), 36);
        for (int i = 0; day.getChild(name) != null; i++) {
            name = Long.toString(when.getTimeInMillis(), 36) + "-" + i;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("at", when);
        props.put("user", e.user);
        props.put("type", e.type);
        props.put("path", e.path);
        props.put("kind", kindOf(e.path));
        Resource content = rr.getResource(e.path + "/jcr:content");
        String title = content == null ? null : content.getValueMap().get("jcr:title", String.class);
        if (title == null && content != null) {
            Resource metadata = content.getChild("metadata");
            title = metadata == null ? null : metadata.getValueMap().get("dc:title", String.class);
        }
        props.put("title", title != null ? title : e.path.substring(e.path.lastIndexOf('/') + 1));
        return rr.create(day, name, props).getPath();
    }

    static String day(Calendar when) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(when.getTime());
    }

    /**
     * The lines, newest first: at most {@code limit}, of this user, of this type, at or below this path (each
     * filter optional), older than {@code before} (epoch millis; 0 for no bound).
     */
    static List<Map<String, Object>> query(ResourceResolver rr, String user, String type, String path, long before, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            return out;
        }
        List<Resource> days = new ArrayList<>();
        root.getChildren().forEach(days::add);
        days.sort((a, b) -> b.getName().compareTo(a.getName()));
        for (Resource day : days) {
            List<Resource> lines = new ArrayList<>();
            day.getChildren().forEach(lines::add);
            lines.sort((a, b) -> Long.compare(millis(b), millis(a)));
            for (Resource line : lines) {
                ValueMap v = line.getValueMap();
                long at = millis(line);
                String p = v.get("path", "");
                if ((before > 0 && at >= before) || (user != null && !user.isEmpty() && !user.equalsIgnoreCase(v.get("user", "")))
                        || (type != null && !type.isEmpty() && !type.equals(v.get("type", "")))
                        || (path != null && !path.isEmpty() && !(p.equals(path) || p.startsWith(path.endsWith("/") ? path : path + "/")))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", day.getName() + "/" + line.getName());
                m.put("at", at);
                m.put("user", v.get("user", String.class));
                m.put("type", v.get("type", String.class));
                m.put("path", p);
                m.put("kind", v.get("kind", String.class));
                m.put("title", v.get("title", String.class));
                m.put("exists", rr.getResource(p) != null);
                out.add(m);
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        return out;
    }

    private static long millis(Resource line) {
        Calendar at = line.getValueMap().get("at", Calendar.class);
        return at == null ? 0 : at.getTimeInMillis();
    }

    /** Removes the days older than {@code keepDays}; the number of days removed. */
    static int purge(ResourceResolver rr, int keepDays, Calendar now) throws PersistenceException {
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            return 0;
        }
        Calendar limit = (Calendar) now.clone();
        limit.add(Calendar.DAY_OF_MONTH, -keepDays);
        String oldest = day(limit);
        List<Resource> gone = new ArrayList<>();
        for (Resource day : root.getChildren()) {
            if (day.getName().compareTo(oldest) < 0) {
                gone.add(day);
            }
        }
        for (Resource day : gone) {
            rr.delete(day);
        }
        if (!gone.isEmpty()) {
            rr.commit();
        }
        return gone.size();
    }

    /** Once a day: the old lines go. */
    @Override
    public void run() {
        try (ResourceResolver rr = serviceResolver()) {
            int removed = purge(rr, keepDays, Calendar.getInstance());
            if (removed > 0) {
                LOG.info("Activity log: removed {} day(s) older than {} days", removed, keepDays);
            }
        } catch (LoginException | PersistenceException e) {
            LOG.debug("Activity: cannot purge: {}", e.getMessage());
        }
    }

    ResourceResolver serviceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, Maintenance.SUBSERVICE));
    }

    int keepDays() {
        return keepDays;
    }
}
