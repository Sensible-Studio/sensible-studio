/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * What else keeps DAM paths, brought up to date after a move or rename: the relations of the
 * moved assets (theirs and their partners') and the members of collections. Pages are handled
 * by {@link DamReferences}, for the pages the author chose.
 */
final class DamMoveFollow {

    /** Assets looked at below moved folders, at most. */
    static final int MAX_ASSETS = 20_000;

    private DamMoveFollow() {
    }

    /** {@code p}, or where it is now if it was (or was inside) a moved path. */
    static String map(Map<String, String> moves, String p) {
        if (p == null) {
            return null;
        }
        for (Map.Entry<String, String> m : moves.entrySet()) {
            if (p.equals(m.getKey())) {
                return m.getValue();
            }
            if (p.startsWith(m.getKey() + "/")) {
                return m.getValue() + p.substring(m.getKey().length());
            }
        }
        return p;
    }

    /** Rewrites relations and collection members; returns how many nodes changed. */
    static int follow(ResourceResolver resolver, Map<String, String> moves) {
        if (moves.isEmpty()) {
            return 0;
        }
        int changed = 0;
        // the moved assets, at their new place
        List<Resource> moved = new ArrayList<>();
        Deque<Resource> queue = new ArrayDeque<>();
        for (String to : moves.values()) {
            Resource r = resolver.getResource(to);
            if (r != null) {
                queue.add(r);
            }
        }
        while (!queue.isEmpty() && moved.size() < MAX_ASSETS) {
            Resource r = queue.poll();
            if (DamItems.isAsset(r)) {
                moved.add(r);
            } else if (DamItems.isFolder(r)) {
                r.getChildren().forEach(queue::add);
            }
        }
        for (Resource a : moved) {
            List<String> others = DamRelations.others(a);
            if (others.isEmpty()) {
                continue;
            }
            changed += rewrite(a.getChild("jcr:content/" + DamRelations.NODE), DamRelations.PN_OTHERS, moves);
            for (String p : others) {
                Resource partner = resolver.getResource(map(moves, p));
                if (partner != null && !moves.containsValue(partner.getPath())) {
                    changed += rewrite(partner.getChild("jcr:content/" + DamRelations.NODE), DamRelations.PN_OTHERS, moves);
                }
            }
        }
        Resource collections = resolver.getResource(DamCollections.ROOT);
        if (collections != null) {
            Deque<Resource> cq = new ArrayDeque<>();
            cq.add(collections);
            int seen = 0;
            while (!cq.isEmpty() && seen++ < MAX_ASSETS) {
                for (Resource c : cq.poll().getChildren()) {
                    if (DamCollections.isCollection(c)) {
                        changed += rewrite(c, "studio:members", moves);
                    } else if (DamItems.isFolder(c)) {
                        cq.add(c);
                    }
                }
            }
        }
        return changed;
    }

    /** Maps each path of a multi-value property; leaves the node alone when nothing changes or it is read-only. */
    private static int rewrite(Resource node, String property, Map<String, String> moves) {
        if (node == null) {
            return 0;
        }
        String[] before = node.getValueMap().get(property, new String[0]);
        String[] after = Arrays.stream(before).map(p -> map(moves, p)).toArray(String[]::new);
        if (Arrays.equals(before, after)) {
            return 0;
        }
        ModifiableValueMap m = node.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            return 0; // someone else's: it shows the old path until its owner fixes it
        }
        m.put(property, after);
        return 1;
    }
}
