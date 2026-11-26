/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import java.util.Locale;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.WCMException;

/**
 * Multi Site Manager rollouts: synchronise live copies from their blueprint.
 */
public interface RolloutManager {

    /** Rolls out {@code params.master} to its live copies (or the given targets). */
    void rollout(RolloutParams params) throws WCMException;

    /** @return {@code true} if a page property is never synchronised (e.g. {@code jcr:created}, {@code cq:lastModified}) */
    boolean isExcludedProperty(String name);

    /** Receives a line per synchronised page. */
    interface RolloutProgressListener {
        void reportProgress(String message);
    }

    /** What to roll out. */
    class RolloutParams {
        /** the blueprint page */
        public Page master;
        /** also the pages below it */
        public boolean isDeep;
        /** live copy target paths to roll out to; {@code null} for all live copies of the master */
        public String[] targets;
        /** only these paragraphs (resource paths below the master); {@code null} for the whole page */
        public String[] paragraphs;
        public Trigger trigger = Trigger.ROLLOUT;
        /** re-enable cancelled inheritance and overwrite local changes */
        public boolean reset;
        public RolloutProgressListener listener;
    }

    /** Events that can trigger a rollout. */
    enum Trigger {
        ROLLOUT, MODIFICATION, PUBLICATION, DEACTIVATION;

        /**
         * @return the trigger for a {@code cq:trigger} value as AEM stores it ({@code rollout}, {@code modification},
         *         {@code publish}, {@code deactivate}; the enum names are accepted too), or {@code null}
         */
        public static Trigger fromName(String name) {
            if (name == null) {
                return null;
            }
            String n = name.trim().toLowerCase(Locale.ROOT);
            for (Trigger t : values()) {
                if (t.getName().equals(n) || t.name().toLowerCase(Locale.ROOT).equals(n)) {
                    return t;
                }
            }
            return null;
        }

        /** @return the {@code cq:trigger} value */
        public String getName() {
            switch (this) {
                case PUBLICATION:
                    return "publish";
                case DEACTIVATION:
                    return "deactivate";
                default:
                    return name().toLowerCase(Locale.ROOT);
            }
        }
    }
}
