/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import java.util.Date;

/** One entry of what happened in a workflow: a step that ended, failed or still waits, oldest first. */
public interface HistoryItem {

    /** What became of the step: {@code ACTIVE}, {@code COMPLETED}, {@code FAILED} or {@code TERMINATED}. */
    String getAction();

    /** What was written when the step was completed, or why it failed. */
    String getComment();

    /** When the step ended, or when it began while it waits. */
    Date getDate();

    /** Who completed the step; who has it while it waits. */
    String getUserId();

    WorkItem getWorkItem();

    HistoryItem getNextHistoryItem();

    HistoryItem getPreviousHistoryItem();
}
