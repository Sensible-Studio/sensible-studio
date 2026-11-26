/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mentions in comments and annotations, as AEM's: {@code @user} in a comment on a Media file tells that user, who
 * finds it with the other things waiting for them (the Inbox, the bell). Each person's notifications are kept below
 * {@value #ROOT}{@code /<hash of the user id>/<id>} ({@code user}, {@code from}, {@code path}, {@code comment},
 * {@code text}, {@code created}, {@code read}) by the service user {@code studio-dam-notifications}; nobody else reads
 * them, a person sees only their own through {@code /bin/studio/dam.json?op=notifications}.
 */
@Component(service = DamNotifications.class)
public class DamNotifications {

    private static final Logger LOG = LoggerFactory.getLogger(DamNotifications.class);

    static final String SUBSERVICE = "notifications";
    static final String ROOT = "/var/studio/dam/notifications";
    /** @ then a user id: letters, digits and . _ - + @ (an e-mail address is a user id too), not ending in a dot. */
    static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z0-9][A-Za-z0-9._+@-]{0,99}[A-Za-z0-9_])");
    /** Kept per person, newest first; older ones go. */
    static final int MAX_PER_USER = 200;
    static final int MAX_MENTIONS = 20;

    @Reference
    private ResourceResolverFactory factory;

    public DamNotifications() {
    }

    DamNotifications(ResourceResolverFactory factory) {
        this.factory = factory;
    }

    /** The user ids written after an @ in a text, in order, each once. */
    static Set<String> mentionsIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = MENTION.matcher(text);
        while (m.find() && out.size() < MAX_MENTIONS) {
            out.add(m.group(1));
        }
        return out;
    }

    private ResourceResolver service() throws LoginException {
        return factory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    /**
     * Tells each user mentioned in a comment (not its author, only users that exist). Never fails the comment: what
     * cannot be told is logged.
     *
     * @return the user ids told
     */
    public List<String> mentioned(String from, String path, String commentId, String text) {
        Set<String> ids = mentionsIn(text);
        ids.remove(from);
        List<String> told = new ArrayList<>();
        if (ids.isEmpty() || factory == null) {
            return told;
        }
        try (ResourceResolver r = service()) {
            for (String id : ids) {
                if (!isUser(r, id)) {
                    continue;
                }
                add(r, id, from, path, commentId, text);
                told.add(id);
            }
            r.commit();
        } catch (LoginException e) {
            LOG.warn("Cannot tell {} about a comment on {}: no service user for subservice {}", ids, path, SUBSERVICE);
        } catch (PersistenceException | RuntimeException e) {
            LOG.warn("Cannot tell {} about a comment on {}", ids, path, e);
        }
        return told;
    }

    static boolean isUser(ResourceResolver r, String id) {
        Session s = r.adaptTo(Session.class);
        if (!(s instanceof JackrabbitSession)) {
            return false;
        }
        try {
            UserManager um = ((JackrabbitSession) s).getUserManager();
            Authorizable a = um.getAuthorizable(id);
            return a != null && !a.isGroup() && !"anonymous".equals(id);
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** Adds one notification for a user, keeping at most {@link #MAX_PER_USER}. */
    static void add(ResourceResolver r, String user, String from, String path, String commentId, String text) throws PersistenceException {
        Resource folder = folder(r, user, true);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jcr:primaryType", "nt:unstructured");
        p.put("kind", "mention");
        p.put("user", user);
        if (from != null) {
            p.put("from", from);
        }
        p.put("path", path);
        if (commentId != null) {
            p.put("comment", commentId);
        }
        String t = text == null ? "" : text.trim();
        p.put("text", t.length() > 500 ? t.substring(0, 500) + "…" : t);
        p.put("created", Calendar.getInstance());
        p.put("read", false);
        String name = "n" + System.currentTimeMillis();
        while (folder.getChild(name) != null) {
            name = name + "_";
        }
        r.create(folder, name, p);
        List<Resource> all = new ArrayList<>();
        folder.getChildren().forEach(all::add);
        if (all.size() > MAX_PER_USER) {
            all.sort((a, b) -> b.getName().compareTo(a.getName()));
            for (Resource old : all.subList(MAX_PER_USER, all.size())) {
                r.delete(old);
            }
        }
    }

    /** The notifications of a user, newest first, and how many are unread. */
    public Map<String, Object> list(String user, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        int unread = 0;
        if (user != null && !"anonymous".equals(user) && factory != null) {
            try (ResourceResolver r = service()) {
                Resource folder = folder(r, user, false);
                if (folder != null) {
                    List<Resource> all = new ArrayList<>();
                    folder.getChildren().forEach(all::add);
                    all.sort((a, b) -> b.getName().compareTo(a.getName()));
                    for (Resource n : all) {
                        ValueMap v = n.getValueMap();
                        if (!user.equals(v.get("user", String.class))) {
                            continue; // a hash collision: not theirs
                        }
                        boolean read = v.get("read", false);
                        if (!read) {
                            unread++;
                        }
                        if (items.size() < limit) {
                            items.add(describe(n, v, read));
                        }
                    }
                }
            } catch (LoginException | PersistenceException e) {
                LOG.debug("Cannot read the notifications of {}", user, e);
            }
        }
        out.put("notifications", items);
        out.put("unread", unread);
        return out;
    }

    /** Marks notifications read (ids empty: all of them) or removes them; only the user's own. */
    public Map<String, Object> update(String user, Collection<String> ids, boolean remove) throws PersistenceException {
        if (user == null || "anonymous".equals(user)) {
            throw new SecurityException("Sign in first");
        }
        int changed = 0;
        try (ResourceResolver r = service()) {
            Resource folder = folder(r, user, false);
            if (folder != null) {
                List<Resource> all = new ArrayList<>();
                folder.getChildren().forEach(all::add);
                for (Resource n : all) {
                    if (!user.equals(n.getValueMap().get("user", String.class)) || (!ids.isEmpty() && !ids.contains(n.getName()))) {
                        continue;
                    }
                    if (remove) {
                        r.delete(n);
                    } else {
                        n.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put("read", true);
                    }
                    changed++;
                }
                r.commit();
            }
        } catch (LoginException e) {
            throw new IllegalStateException("No service user for notifications");
        }
        return list(user, 50);
    }

    private static Map<String, Object> describe(Resource n, ValueMap v, boolean read) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getName());
        m.put("kind", v.get("kind", "mention"));
        m.put("from", v.get("from", String.class));
        m.put("path", v.get("path", String.class));
        m.put("comment", v.get("comment", String.class));
        m.put("text", v.get("text", ""));
        Calendar created = v.get("created", Calendar.class);
        m.put("created", created == null ? null : created.toInstant().toString());
        m.put("read", read);
        return m;
    }

    /** {@value #ROOT}/&lt;first 2 hex&gt;/&lt;sha-256 of the user id&gt;: no user id in a path. */
    static Resource folder(ResourceResolver r, String user, boolean create) throws PersistenceException {
        String hash = sha(user);
        String path = ROOT + "/" + hash.substring(0, 2) + "/" + hash;
        Resource f = r.getResource(path);
        if (f != null || !create) {
            return f;
        }
        Resource root = r.getResource(ROOT);
        if (root == null) {
            throw new PersistenceException("No " + ROOT + " (the studio-dam repoinit makes it)");
        }
        Resource shard = root.getChild(hash.substring(0, 2));
        if (shard == null) {
            shard = r.create(root, hash.substring(0, 2), Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r.create(shard, hash, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
