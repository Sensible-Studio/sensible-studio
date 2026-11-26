package io.sensible.studio.auth.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OAuth server of this instance ({@link OAuth}) with its records in the repository, below
 * {@code /var/studio/oauth}, read and written with the bundle's service user {@code oauth}
 * (nobody else may read there). Clients, codes and tokens are this instance's: an author's tokens
 * do not work on a publish instance, as in AEM. Every hour the codes and tokens that are over go.
 */
@Component(service = { OAuthService.class, Runnable.class }, immediate = true, property = {
        "scheduler.period:Long=3600",
        "scheduler.concurrent:Boolean=false" })
public class OAuthService implements Runnable {

    static final String ROOT = "/var/studio/oauth";
    private static final Logger LOG = LoggerFactory.getLogger(OAuthService.class);

    @Reference
    private ResourceResolverFactory factory;

    private OAuth oauth;

    @Activate
    void activate() {
        oauth = new OAuth(new Repository(), System::currentTimeMillis, this::userMaySignIn);
    }

    OAuth oauth() {
        return oauth;
    }

    /** For the tests: the server with its records elsewhere. */
    void use(OAuth o) {
        oauth = o;
    }

    @Override
    public void run() {
        try {
            int removed = oauth.sweep();
            if (removed > 0) {
                LOG.info("OAuth: {} codes and tokens that were over removed", removed);
            }
        } catch (RuntimeException e) {
            LOG.warn("OAuth: could not remove what is over: {}", e.toString());
        }
    }

    private <T> T withResolver(Function<ResourceResolver, T> work) {
        try (ResourceResolver rr = factory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, "oauth"))) {
            return work.apply(rr);
        } catch (LoginException e) {
            throw new IllegalStateException("OAuth: no service user: " + e.getMessage(), e);
        }
    }

    /** A user a token may sign in: there, a user (not a group), enabled, not a system user, not anonymous. */
    boolean userMaySignIn(String id) {
        if (id == null || id.isEmpty() || "anonymous".equals(id)) {
            return false;
        }
        return withResolver(rr -> {
            Session s = rr.adaptTo(Session.class);
            if (!(s instanceof JackrabbitSession)) {
                return false;
            }
            try {
                Authorizable a = ((JackrabbitSession) s).getUserManager().getAuthorizable(id);
                if (a == null || a.isGroup()) {
                    return false;
                }
                User u = (User) a;
                return !u.isDisabled() && !u.isSystemUser();
            } catch (RepositoryException e) {
                return false;
            }
        });
    }

    /** The records as nodes: {@code /var/studio/oauth/<kind>/<id>}. */
    private final class Repository implements OAuthStorage {

        @Override
        public Map<String, Object> get(String kind, String id) {
            if (!safe(id)) {
                return null;
            }
            return withResolver(rr -> {
                Resource r = rr.getResource(ROOT + "/" + kind + "/" + id);
                return r == null ? null : read(r);
            });
        }

        @Override
        public void put(String kind, String id, Map<String, Object> record) {
            if (!safe(id)) {
                throw new IllegalArgumentException("not an id: " + id);
            }
            withResolver(rr -> {
                try {
                    Resource parent = rr.getResource(ROOT + "/" + kind);
                    if (parent == null) {
                        Resource root = rr.getResource(ROOT);
                        if (root == null) {
                            throw new IllegalStateException(ROOT + " is missing (repoinit studio-oauth)");
                        }
                        parent = rr.create(root, kind, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
                    }
                    Resource existing = parent.getChild(id);
                    if (existing == null) {
                        Map<String, Object> props = new HashMap<>(record);
                        props.put("jcr:primaryType", "nt:unstructured");
                        props.values().removeIf(v -> v == null);
                        rr.create(parent, id, props);
                    } else {
                        ModifiableValueMap m = existing.adaptTo(ModifiableValueMap.class);
                        for (Iterator<String> it = new ArrayList<>(m.keySet()).iterator(); it.hasNext();) {
                            String k = it.next();
                            if (!k.startsWith("jcr:") && !record.containsKey(k)) {
                                m.remove(k);
                            }
                        }
                        for (Map.Entry<String, Object> e : record.entrySet()) {
                            if (e.getValue() == null) {
                                m.remove(e.getKey());
                            } else {
                                m.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                    rr.commit();
                    return null;
                } catch (PersistenceException e) {
                    throw new IllegalStateException("OAuth: could not write " + kind + ": " + e.getMessage(), e);
                }
            });
        }

        @Override
        public void delete(String kind, String id) {
            if (!safe(id)) {
                return;
            }
            withResolver(rr -> {
                Resource r = rr.getResource(ROOT + "/" + kind + "/" + id);
                if (r != null) {
                    try {
                        rr.delete(r);
                        rr.commit();
                    } catch (PersistenceException e) {
                        throw new IllegalStateException("OAuth: could not remove: " + e.getMessage(), e);
                    }
                }
                return null;
            });
        }

        @Override
        public List<Map<String, Object>> list(String kind) {
            return withResolver(rr -> {
                List<Map<String, Object>> out = new ArrayList<>();
                Resource parent = rr.getResource(ROOT + "/" + kind);
                if (parent != null) {
                    for (Resource r : parent.getChildren()) {
                        Map<String, Object> m = read(r);
                        m.put("id", r.getName());
                        out.add(m);
                    }
                }
                return out;
            });
        }

        private Map<String, Object> read(Resource r) {
            Map<String, Object> m = new LinkedHashMap<>();
            ValueMap vm = r.getValueMap();
            for (Map.Entry<String, Object> e : vm.entrySet()) {
                if (!e.getKey().startsWith("jcr:")) {
                    m.put(e.getKey(), e.getValue());
                }
            }
            return m;
        }

        private boolean safe(String id) {
            return id != null && id.matches("[A-Za-z0-9_-]{1,96}");
        }
    }
}
