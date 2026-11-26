/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CONFIG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.WCMException;

/**
 * Small helpers shared by the MSM implementation: paths, mixins and the page / content node distinction.
 *
 * <p>Pages carry their MSM markers on their content node: the relationship of {@code /content/site/en/page} is
 * recorded on {@code /content/site/en/page/jcr:content}.
 */
public final class Msm {

    public static final String JCR_CONTENT = "jcr:content";
    public static final String CONTENT_SEGMENT = "/" + JCR_CONTENT;
    public static final String MIXINS = "jcr:mixinTypes";
    public static final String PRIMARY = "jcr:primaryType";
    public static final String NT_PAGE = "cq:Page";

    private Msm() {
    }

    // ------------------------------------------------------------------ paths

    /** @return the path of the page containing {@code path} (the path itself if it is not inside a content node) */
    public static String pagePathOf(String path) {
        int i = path.indexOf(CONTENT_SEGMENT + "/");
        if (i >= 0) {
            return path.substring(0, i);
        }
        return path.endsWith(CONTENT_SEGMENT) ? path.substring(0, path.length() - CONTENT_SEGMENT.length()) : path;
    }

    /** @return {@code true} if {@code path} is a page content node or lies below one */
    public static boolean isInPageContent(String path) {
        return path.endsWith(CONTENT_SEGMENT) || path.contains(CONTENT_SEGMENT + "/");
    }

    /** @return {@code true} if {@code path} is the content node of a page (the first {@code jcr:content} of the path) */
    public static boolean isPageContentNode(String path) {
        return path.endsWith(CONTENT_SEGMENT) && path.indexOf(CONTENT_SEGMENT) == path.length() - CONTENT_SEGMENT.length();
    }

    public static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    public static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** @return {@code true} if {@code path} is {@code root} or lies below it */
    public static boolean isSameOrDescendant(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    /** @return {@code true} if one path is the other or lies below it */
    public static boolean overlaps(String a, String b) {
        return isSameOrDescendant(a, b) || isSameOrDescendant(b, a);
    }

    /**
     * @return the path if it is a clean absolute content path ({@code /content/...}, no {@code .}/{@code ..} segments,
     *         no empty segments, no trailing slash), else {@code null}
     */
    public static String contentPath(String path) {
        if (path == null || !(path.equals("/content") || path.startsWith("/content/")) || path.endsWith("/")) {
            return null;
        }
        for (String seg : path.substring(1).split("/", -1)) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                return null;
            }
        }
        return path;
    }

    /** @return {@code true} for a usable node name: no path separators, not {@code .}/{@code ..}, no JCR special characters */
    public static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && !".".equals(name) && !"..".equals(name)
                && name.chars().noneMatch(c -> c == '/' || c == ':' || c == '[' || c == ']' || c == '|' || c == '*'
                        || Character.isISOControl(c));
    }

    /** @return {@code true} for a component instance (a node with {@code sling:resourceType}) */
    public static boolean isComponent(org.apache.sling.api.resource.Resource r) {
        return r != null && r.getValueMap().get("sling:resourceType", String.class) != null;
    }

    /** @return the configuration node path of a live copy root page */
    public static String configPath(String pagePath) {
        return pagePath + CONTENT_SEGMENT + "/" + NT_LIVE_SYNC_CONFIG;
    }

    // ------------------------------------------------------------------ nodes

    public static boolean isPage(Resource r) {
        return r != null && NT_PAGE.equals(r.getValueMap().get(PRIMARY, String.class));
    }

    /** @return the node holding the MSM markers of {@code r}: its content node for a page, else itself (may be {@code null}) */
    public static Resource markerNode(Resource r) {
        if (r == null) {
            return null;
        }
        return isPage(r) ? r.getChild(JCR_CONTENT) : r;
    }

    public static boolean exists(Resource r) {
        return r != null && !ResourceUtil.isNonExistingResource(r);
    }

    public static Set<String> mixins(Resource r) {
        if (r == null) {
            return new LinkedHashSet<>();
        }
        String[] m = r.getValueMap().get(MIXINS, String[].class);
        return m == null ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(m));
    }

    public static boolean hasMixin(Resource r, String mixin) {
        return mixins(r).contains(mixin);
    }

    public static void addMixin(Resource r, String mixin) throws WCMException {
        Set<String> m = mixins(r);
        if (m.add(mixin)) {
            writable(r).put(MIXINS, m.toArray(new String[0]));
        }
    }

    public static void removeMixin(Resource r, String mixin) throws WCMException {
        Set<String> m = mixins(r);
        if (m.remove(mixin)) {
            javax.jcr.Node node = r.adaptTo(javax.jcr.Node.class);
            if (node != null) {
                // JCR: jcr:mixinTypes is protected and cannot be removed as a property (the last mixin)
                try {
                    node.removeMixin(mixin);
                    return;
                } catch (javax.jcr.RepositoryException e) {
                    throw new WCMException("Could not remove " + mixin + " from " + r.getPath(), e);
                }
            }
            ModifiableValueMap mvm = writable(r);
            if (m.isEmpty()) {
                mvm.remove(MIXINS);
            } else {
                mvm.put(MIXINS, m.toArray(new String[0]));
            }
        }
    }

    public static ModifiableValueMap writable(Resource r) throws WCMException {
        ModifiableValueMap mvm = r == null ? null : r.adaptTo(ModifiableValueMap.class);
        if (mvm == null) {
            throw new WCMException("Cannot modify " + (r == null ? "null" : r.getPath()));
        }
        return mvm;
    }

    public static String[] strings(ValueMap vm, String name) {
        String[] v = vm.get(name, String[].class);
        return v == null ? new String[0] : v;
    }

    /** Stamps {@code cq:lastRolledout} and {@code cq:lastRolledoutBy} on a target node. */
    public static void stamp(Resource target, Calendar when, String user) throws WCMException {
        ModifiableValueMap mvm = writable(target);
        mvm.put("cq:lastRolledout", when);
        mvm.put("cq:lastRolledoutBy", user == null ? "anonymous" : user);
    }

    /** @return the names of the child resources, in order */
    public static List<String> childNames(Resource r) {
        List<String> names = new ArrayList<>();
        if (exists(r)) {
            for (Resource c : r.getChildren()) {
                names.add(c.getName());
            }
        }
        return names;
    }

    public static void commit(ResourceResolver resolver) throws WCMException {
        try {
            resolver.commit();
        } catch (PersistenceException e) {
            resolver.revert();
            throw new WCMException("Could not save: " + e.getMessage(), e);
        }
    }

    /** Creates {@code parent/name}, a page when {@code page} is set. */
    public static Resource createNode(ResourceResolver resolver, Resource parent, String name, java.util.Map<String, Object> props)
            throws WCMException {
        try {
            return resolver.create(parent, name, props);
        } catch (PersistenceException e) {
            throw new WCMException("Could not create " + parent.getPath() + "/" + name + ": " + e.getMessage(), e);
        }
    }
}
