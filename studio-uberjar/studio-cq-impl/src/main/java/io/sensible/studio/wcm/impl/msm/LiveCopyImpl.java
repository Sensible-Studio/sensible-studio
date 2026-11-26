/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.day.cq.wcm.msm.api.LiveCopy;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** A live copy as read from its {@code cq:LiveSyncConfig} node. */
final class LiveCopyImpl implements LiveCopy {

    private final String path;
    private final String blueprintPath;
    private final boolean deep;
    private final boolean root;
    private final Set<String> exclusions;
    private final Supplier<List<RolloutConfig>> configs;
    private List<RolloutConfig> resolved;

    LiveCopyImpl(String path, String blueprintPath, boolean deep, boolean root, String[] exclusions,
            Supplier<List<RolloutConfig>> configs) {
        this.path = path;
        this.blueprintPath = blueprintPath;
        this.deep = deep;
        this.root = root;
        Set<String> ex = new LinkedHashSet<>();
        for (String e : exclusions) {
            String n = normalize(e);
            if (!n.isEmpty()) {
                ex.add(n);
            }
        }
        this.exclusions = Collections.unmodifiableSet(ex);
        this.configs = configs;
    }

    /** @return {@code a/b} for {@code /a/b/}, {@code a/b} or {@code /a/b} */
    static String normalize(String relative) {
        String s = relative == null ? "" : relative.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getBlueprintPath() {
        return blueprintPath;
    }

    @Override
    public boolean isDeep() {
        return deep;
    }

    @Override
    public boolean isRoot() {
        return root;
    }

    @Override
    public Set<String> getExclusions() {
        return exclusions;
    }

    @Override
    public boolean isExcluded(String relativePath) {
        String rel = normalize(relativePath);
        for (String e : exclusions) {
            if (rel.equals(e) || rel.startsWith(e + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized List<RolloutConfig> getRolloutConfigs() {
        if (resolved == null) {
            resolved = Collections.unmodifiableList(configs.get());
        }
        return resolved;
    }

    @Override
    public boolean isTrigger(Trigger trigger) {
        for (RolloutConfig c : getRolloutConfigs()) {
            if (c.getTrigger() == trigger) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if {@code targetPath} belongs to this live copy: the root page and its content, and child
     *         pages unless the live copy is shallow or the page is excluded
     */
    boolean covers(String targetPath) {
        if (!Msm.isSameOrDescendant(targetPath, path)) {
            return false;
        }
        String relPage = Msm.pagePathOf(targetPath).substring(path.length());
        if (relPage.isEmpty()) {
            return true;
        }
        return deep && !isExcluded(relPage);
    }

    /** @return the blueprint path that {@code targetPath} is synchronised from */
    String sourceOf(String targetPath) {
        return blueprintPath + targetPath.substring(path.length());
    }

    /** @return the target path in this live copy of {@code sourcePath} */
    String targetOf(String sourcePath) {
        return path + sourcePath.substring(blueprintPath.length());
    }

    LiveCopyImpl asRoot(boolean isRoot) {
        return isRoot == root ? this : new LiveCopyImpl(path, blueprintPath, deep, isRoot, exclusions.toArray(new String[0]), configs);
    }

    @Override
    public String toString() {
        return "LiveCopy " + path + " <- " + blueprintPath + (deep ? "" : " (shallow)");
    }
}
