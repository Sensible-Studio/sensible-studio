/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import java.util.Calendar;
import java.util.Set;

/**
 * Synchronisation status of a live relationship.
 */
public interface LiveStatus {

    /** @return {@code true} if inheritance was cancelled for the target ({@code cq:LiveSyncCancelled}) */
    boolean isCancelled();

    /** @return {@code true} if the cancellation also applies to the nodes below ({@code cq:isCancelledForChildren}) */
    boolean isCancelledForChildren();

    /** @return {@code true} if the source exists */
    boolean isSourceExisting();

    /** @return {@code true} if the target exists */
    boolean isTargetExisting();

    /** @return {@code true} if the relationship is between pages */
    boolean isPage();

    /** @return the properties whose inheritance is cancelled ({@code cq:propertyInheritanceCancelled}) */
    Set<String> getCanceledProperties();

    /** @return {@code true} if the inheritance of that property is cancelled */
    boolean isPropertyCancelled(String name);

    /** @return when the target was last rolled out ({@code cq:lastRolledout}), or {@code null} */
    Calendar getLastRolledOut();

    /** @return who last rolled the target out ({@code cq:lastRolledoutBy}), or {@code null} */
    String getLastRolledOutBy();
}
