/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.ArrayList;
import java.util.List;

import com.day.cq.wcm.msm.api.LiveCopy;
import com.day.cq.wcm.msm.api.LiveRelationship;
import com.day.cq.wcm.msm.api.LiveStatus;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** A source / target pair of a live copy. The status is computed on first use. */
final class LiveRelationshipImpl implements LiveRelationship {

    private final LiveCopyImpl liveCopy;
    private final String sourcePath;
    private final String targetPath;
    private final Relationships relationships;
    private LiveStatusImpl status;

    LiveRelationshipImpl(LiveCopyImpl liveCopy, String sourcePath, String targetPath, Relationships relationships) {
        this.liveCopy = liveCopy;
        this.sourcePath = sourcePath;
        this.targetPath = targetPath;
        this.relationships = relationships;
    }

    @Override
    public LiveCopy getLiveCopy() {
        return liveCopy;
    }

    LiveCopyImpl liveCopy() {
        return liveCopy;
    }

    @Override
    public String getSourcePath() {
        return sourcePath;
    }

    @Override
    public String getTargetPath() {
        return targetPath;
    }

    @Override
    public synchronized LiveStatus getStatus() {
        if (status == null) {
            status = relationships.status(liveCopy, sourcePath, targetPath);
        }
        return status;
    }

    LiveStatusImpl status() {
        return (LiveStatusImpl) getStatus();
    }

    /** Forgets the computed status (after the markers changed). */
    synchronized void refresh() {
        status = null;
    }

    @Override
    public List<RolloutConfig> getRolloutConfigs() {
        return liveCopy.getRolloutConfigs();
    }

    @Override
    public List<RolloutConfig> getRolloutConfigs(Trigger trigger) {
        List<RolloutConfig> out = new ArrayList<>();
        for (RolloutConfig c : liveCopy.getRolloutConfigs()) {
            if (trigger == null || c.getTrigger() == trigger) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return sourcePath + " -> " + targetPath;
    }
}
