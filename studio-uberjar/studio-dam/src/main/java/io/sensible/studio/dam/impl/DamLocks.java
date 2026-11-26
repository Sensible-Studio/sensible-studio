/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Deque;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Checkout / Check In of assets, as AEM's: the user who checked an asset out is the only one who
 * may change, move or delete it until it is checked in again. Stored as {@code cq:drivelock}
 * (the user ID) on the asset's {@code jcr:content}, which is what AEM uses too.
 */
final class DamLocks {

    static final String PN_LOCK = "cq:drivelock";
    static final String PN_LOCKED_AT = "studio:checkedOutAt";
    /** Assets looked at inside a folder before giving up (then the folder counts as locked). */
    static final int MAX_WALK = 20_000;

    private DamLocks() {
    }

    /** The user who has the asset checked out, or null. */
    static String owner(Resource asset) {
        Resource c = asset == null ? null : asset.getChild("jcr:content");
        return c == null ? null : c.getValueMap().get(PN_LOCK, String.class);
    }

    /**
     * Refuses when the asset, or an asset inside the folder, is checked out by someone else.
     */
    static void checkWritable(ResourceResolver resolver, Resource item) throws DamApiServlet.ApiException {
        String user = resolver.getUserID();
        if (DamItems.isAsset(item)) {
            String o = owner(item);
            if (o != null && !o.equals(user)) {
                throw new DamApiServlet.ApiException(423, item.getName() + " is checked out by " + o);
            }
            return;
        }
        Deque<Resource> queue = new ArrayDeque<>();
        queue.add(item);
        int seen = 0;
        while (!queue.isEmpty()) {
            for (Resource c : queue.poll().getChildren()) {
                if (++seen > MAX_WALK) {
                    return; // too large to check; the per-asset checks still hold for direct edits
                }
                if (DamItems.isAsset(c)) {
                    String o = owner(c);
                    if (o != null && !o.equals(user)) {
                        throw new DamApiServlet.ApiException(423, c.getPath() + " is checked out by " + o);
                    }
                } else if (DamItems.isFolder(c)) {
                    queue.add(c);
                }
            }
        }
    }

    static void checkout(ResourceResolver resolver, Resource asset) throws DamApiServlet.ApiException {
        String o = owner(asset);
        String user = resolver.getUserID();
        if (o != null && !o.equals(user)) {
            throw new DamApiServlet.ApiException(423, asset.getName() + " is already checked out by " + o);
        }
        ModifiableValueMap c = content(asset);
        c.put(PN_LOCK, user);
        c.put(PN_LOCKED_AT, Calendar.getInstance());
    }

    /** The owner may check in; so may an administrator (as in AEM, to release a forgotten lock). */
    static void checkin(ResourceResolver resolver, Resource asset) throws DamApiServlet.ApiException {
        String o = owner(asset);
        if (o == null) {
            return;
        }
        String user = resolver.getUserID();
        if (!o.equals(user) && !DamAccess.isAdmin(resolver)) {
            throw new DamApiServlet.ApiException(423, asset.getName() + " is checked out by " + o);
        }
        ModifiableValueMap c = content(asset);
        c.remove(PN_LOCK);
        c.remove(PN_LOCKED_AT);
    }

    private static ModifiableValueMap content(Resource asset) throws DamApiServlet.ApiException {
        Resource c = asset.getChild("jcr:content");
        ModifiableValueMap m = c == null ? null : c.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new DamApiServlet.ApiException(403, "cannot change " + asset.getPath());
        }
        return m;
    }
}
