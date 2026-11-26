/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Push on modify for Media's live copies: a change below the source of a live copy whose rollout
 * configurations include {@link DamLiveCopy#PUSH_ON_MODIFY} is synchronized there a few seconds
 * later (changes are gathered, so an upload and its processing make one or two pushes, not dozens).
 *
 * <p>Runs as the service user {@value #SUBSERVICE}, but only for a live copy whose person who
 * turned pushing on ({@link DamLiveCopy#PN_PUSH_BY}) can still read the source and change the
 * live copy; otherwise nothing is pushed there.</p>
 */
@Component(service = {ResourceChangeListener.class, DamLiveCopyFollower.class}, property = {
        ResourceChangeListener.PATHS + "=/content/dam",
        ResourceChangeListener.CHANGES + "=ADDED",
        ResourceChangeListener.CHANGES + "=CHANGED",
        ResourceChangeListener.CHANGES + "=REMOVED"})
public class DamLiveCopyFollower implements ResourceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(DamLiveCopyFollower.class);

    static final String SUBSERVICE = "livecopy";
    /** Quiet time before a push. */
    static final long DELAY_MS = 3000;
    /** Known push-on-modify live copies are looked up again at most this often. */
    private static final long ROOTS_TTL_MS = 30_000;

    @Reference
    private ResourceResolverFactory factory;

    private ScheduledExecutorService executor;
    private final Set<String> pending = new TreeSet<>();
    private ScheduledFuture<?> scheduled;
    private volatile List<String[]> roots = null; // {root, master}
    private volatile long rootsAt;

    @Activate
    void activate() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "studio-dam-livecopy");
            t.setDaemon(true);
            return t;
        });
    }

    @Deactivate
    void deactivate() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        boolean config = false;
        synchronized (pending) {
            for (ResourceChange c : changes) {
                String p = c.getPath();
                if (p.contains("/" + DamLiveCopy.CONFIG)) {
                    config = true;
                    continue;
                }
                if (p.contains("/jcr:content/cq:annotations")) {
                    continue; // comments stay where they are
                }
                pending.add(p);
            }
            if (config) {
                roots = null;
            }
            if (pending.isEmpty() || executor == null) {
                return;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            scheduled = executor.schedule(this::pushPending, DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void pushPending() {
        Set<String> paths;
        synchronized (pending) {
            paths = new TreeSet<>(pending);
            pending.clear();
        }
        try (ResourceResolver service = factory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            push(service, paths);
        } catch (LoginException e) {
            LOG.warn("Live copies not updated (service user missing?): {}", e.getMessage());
        } catch (Exception e) {
            LOG.warn("Live copies not updated: {}", e.toString());
        }
    }

    void push(ResourceResolver service, Set<String> paths) throws Exception {
        List<String[]> known = pushRoots(service);
        if (known.isEmpty()) {
            return;
        }
        for (String[] rm : known) {
            String rootPath = rm[0];
            String master = rm[1];
            Set<String> targets = new TreeSet<>();
            for (String p : paths) {
                if (!(p.equals(master) || p.startsWith(master + "/")) || p.startsWith(rootPath + "/") || p.equals(rootPath)) {
                    continue;
                }
                targets.add(itemOf(rootPath + p.substring(master.length())));
            }
            if (targets.isEmpty()) {
                continue;
            }
            Resource root = service.getResource(rootPath);
            if (root == null || !DamLiveCopy.isPushOnModify(root)) {
                continue;
            }
            String by = root.getChild("jcr:content/" + DamLiveCopy.CONFIG).getValueMap().get(DamLiveCopy.PN_PUSH_BY, String.class);
            if (!permitted(service, by, master, rootPath)) {
                LOG.info("Live copy {}: not pushed, {} may no longer read {} or change the live copy", rootPath, by, master);
                continue;
            }
            DamLiveCopy.ACTOR.set(by);
            try {
                for (String t : outermost(targets)) {
                    Resource item = nearestInherited(service, t, rootPath);
                    if (item == null) {
                        continue;
                    }
                    try {
                        DamLiveCopy.synchronize(service, item, false, false);
                        service.commit();
                    } catch (IllegalArgumentException e) {
                        service.revert();
                        LOG.debug("Live copy {}: {}", t, e.getMessage());
                    }
                }
            } finally {
                DamLiveCopy.ACTOR.remove();
            }
        }
    }

    /** The asset or folder a changed path belongs to (renditions, metadata... are part of their asset). */
    static String itemOf(String path) {
        int i = path.indexOf("/jcr:content");
        return i < 0 ? path : path.substring(0, i);
    }

    /** Only the outermost paths: a folder's synchronization covers what is in it. */
    static List<String> outermost(Set<String> sorted) {
        List<String> out = new ArrayList<>();
        for (String p : sorted) {
            if (out.isEmpty() || !(p.equals(out.get(out.size() - 1)) || p.startsWith(out.get(out.size() - 1) + "/"))) {
                out.add(p);
            }
        }
        return out;
    }

    /** The target, or (new or removed in the source) its nearest existing folder in the live copy. */
    private static Resource nearestInherited(ResourceResolver service, String path, String rootPath) {
        String p = path;
        while (p.length() >= rootPath.length()) {
            Resource r = service.getResource(p);
            if (r != null) {
                return DamLiveCopy.isInherited(r) ? r : null;
            }
            p = p.substring(0, p.lastIndexOf('/'));
        }
        return null;
    }

    private List<String[]> pushRoots(ResourceResolver service) {
        List<String[]> r = roots;
        if (r != null && System.currentTimeMillis() - rootsAt < ROOTS_TTL_MS) {
            return r;
        }
        List<String[]> out = new ArrayList<>();
        for (Resource root : DamLiveCopy.roots(service)) {
            if (DamLiveCopy.isPushOnModify(root)) {
                out.add(new String[] {root.getPath(), DamLiveCopy.masterOf(root)});
            }
        }
        roots = out;
        rootsAt = System.currentTimeMillis();
        return out;
    }

    /** Overridden in tests, which have no JCR access control. */
    boolean permitted(ResourceResolver service, String userId, String master, String root) {
        return allowed(service, userId, master, root);
    }

    /** {@code userId} may read the source and change the live copy (administrators may everything). */
    static boolean allowed(ResourceResolver service, String userId, String master, String root) {
        Session session = service.adaptTo(Session.class);
        if (userId == null || !(session instanceof JackrabbitSession)) {
            return false;
        }
        try {
            JackrabbitSession js = (JackrabbitSession) session;
            Authorizable a = js.getUserManager().getAuthorizable(userId);
            if (a == null || a.isGroup()) {
                return false;
            }
            Set<Principal> principals = new LinkedHashSet<>();
            principals.add(a.getPrincipal());
            boolean admin = a instanceof User && ((User) a).isAdmin();
            for (Iterator<Group> it = a.memberOf(); it.hasNext();) {
                Group g = it.next();
                principals.add(g.getPrincipal());
                admin |= "administrators".equals(g.getID());
            }
            if (admin) {
                return true;
            }
            principals.add(js.getPrincipalManager().getEveryone());
            AccessControlManager acm = session.getAccessControlManager();
            if (!(acm instanceof JackrabbitAccessControlManager)) {
                return false;
            }
            JackrabbitAccessControlManager jacm = (JackrabbitAccessControlManager) acm;
            return session.nodeExists(master) && session.nodeExists(root)
                    && jacm.hasPrivileges(master, principals, new Privilege[] {jacm.privilegeFromName(Privilege.JCR_READ)})
                    && jacm.hasPrivileges(root, principals, new Privilege[] {
                        jacm.privilegeFromName(Privilege.JCR_MODIFY_PROPERTIES),
                        jacm.privilegeFromName(Privilege.JCR_ADD_CHILD_NODES),
                        jacm.privilegeFromName(Privilege.JCR_REMOVE_CHILD_NODES)});
        } catch (RepositoryException e) {
            return false;
        }
    }
}
