/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class MaintenanceDueTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(UTC).toInstant();
    }

    @Test
    void runsInItsHourOnceADay() {
        LocalDateTime now = LocalDateTime.parse("2026-11-27T02:15:00");
        assertTrue(Maintenance.due(now, 2, at("2026-11-26T02:19:00"), UTC, false));
        assertTrue(Maintenance.due(now, 2, null, UTC, false));
        assertFalse(Maintenance.due(now, 2, at("2026-11-27T02:01:00"), UTC, false), "already ran tonight");
    }

    @Test
    void aMissedNightIsMadeUpWhenTheMachineIsBack() {
        LocalDateTime morning = LocalDateTime.parse("2026-11-27T09:30:00");
        Instant threeDaysAgo = at("2026-11-24T02:19:00");
        assertFalse(Maintenance.due(morning, 2, threeDaysAgo, UTC, false), "not while the instance is still starting");
        assertTrue(Maintenance.due(morning, 2, threeDaysAgo, UTC, true));
        assertFalse(Maintenance.due(morning, 2, at("2026-11-27T02:05:00"), UTC, true), "it ran last night");
        // before tonight's hour the run that counts is last night's
        LocalDateTime evening = LocalDateTime.parse("2026-11-27T01:30:00");
        assertFalse(Maintenance.due(evening, 2, at("2026-11-26T02:10:00"), UTC, true));
        assertTrue(Maintenance.due(evening, 2, at("2026-11-25T02:10:00"), UTC, true));
    }
}
