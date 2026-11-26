/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** {@code cq:trigger} values as AEM 6.5 stores them in its rollout configurations. */
class TriggerTest {

    @Test
    void readsAndWritesAemTriggerValues() {
        assertEquals(Trigger.ROLLOUT, Trigger.fromName("rollout"));
        assertEquals(Trigger.MODIFICATION, Trigger.fromName("modification"));
        assertEquals(Trigger.PUBLICATION, Trigger.fromName("publish"));
        assertEquals(Trigger.DEACTIVATION, Trigger.fromName("deactivate"));
        assertEquals(Trigger.PUBLICATION, Trigger.fromName(" PUBLICATION "));
        assertNull(Trigger.fromName("never"));
        assertNull(Trigger.fromName(null));
        assertEquals("publish", Trigger.PUBLICATION.getName());
        assertEquals("deactivate", Trigger.DEACTIVATION.getName());
        assertEquals("rollout", Trigger.ROLLOUT.getName());
    }
}
