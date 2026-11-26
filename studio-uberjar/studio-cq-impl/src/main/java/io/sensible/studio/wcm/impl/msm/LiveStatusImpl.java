/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.Calendar;
import java.util.Collections;
import java.util.Set;

import com.day.cq.wcm.msm.api.LiveStatus;

/** Status of one live relationship, computed from the markers on the target and its ancestors. */
final class LiveStatusImpl implements LiveStatus {

    final boolean cancelled;
    final boolean cancelledForChildren;
    /** the cancellation comes from an ancestor (a suspended page or a cancelled container) */
    final boolean cancelledByAncestor;
    final boolean sourceExisting;
    final boolean targetExisting;
    final boolean page;
    final Set<String> canceledProperties;
    final Calendar lastRolledOut;
    final String lastRolledOutBy;
    /** the target carries the relationship mixin (it was rolled out, it is not a local node) */
    final boolean related;

    LiveStatusImpl(boolean cancelled, boolean cancelledForChildren, boolean cancelledByAncestor, boolean sourceExisting,
            boolean targetExisting, boolean page, Set<String> canceledProperties, Calendar lastRolledOut,
            String lastRolledOutBy, boolean related) {
        this.cancelled = cancelled;
        this.cancelledForChildren = cancelledForChildren;
        this.cancelledByAncestor = cancelledByAncestor;
        this.sourceExisting = sourceExisting;
        this.targetExisting = targetExisting;
        this.page = page;
        this.canceledProperties = Collections.unmodifiableSet(canceledProperties);
        this.lastRolledOut = lastRolledOut;
        this.lastRolledOutBy = lastRolledOutBy;
        this.related = related;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isCancelledForChildren() {
        return cancelledForChildren;
    }

    @Override
    public boolean isSourceExisting() {
        return sourceExisting;
    }

    @Override
    public boolean isTargetExisting() {
        return targetExisting;
    }

    @Override
    public boolean isPage() {
        return page;
    }

    @Override
    public Set<String> getCanceledProperties() {
        return canceledProperties;
    }

    @Override
    public boolean isPropertyCancelled(String name) {
        return canceledProperties.contains(name);
    }

    @Override
    public Calendar getLastRolledOut() {
        return lastRolledOut;
    }

    @Override
    public String getLastRolledOutBy() {
        return lastRolledOutBy;
    }

    /** @return a short name for the UI: {@code synced}, {@code cancelled}, {@code suspended}, {@code sourceMissing}, {@code local} */
    String summary() {
        if (!related && targetExisting) {
            return "local";
        }
        if (!sourceExisting) {
            return "sourceMissing";
        }
        if (cancelled) {
            return page || cancelledByAncestor ? "suspended" : "cancelled";
        }
        if (!targetExisting) {
            return "missing";
        }
        return "synced";
    }
}
