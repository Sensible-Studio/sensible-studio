package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.sling.api.resource.observation.ResourceChange;
import org.junit.jupiter.api.Test;

/** What the outbox offers again, and what the author passes on to the other publish instances. */
class OutboxSharingTest {

    private static ResourceChange by(String user) {
        return new ResourceChange(ResourceChange.ChangeType.ADDED, "/content/usergenerated/site/c1", false) {
            @Override
            public String getUserId() {
                return user;
            }
        };
    }

    @Test
    void whatReplicationBroughtIsNotOfferedAgain() {
        Set<String> ignored = new HashSet<>(Arrays.asList("studio-transport", "studio-replication-service"));
        assertTrue(Outbox.fromReplication(by("studio-transport"), ignored));
        assertTrue(Outbox.fromReplication(by("studio-replication-service"), ignored));
        assertFalse(Outbox.fromReplication(by("ana"), ignored));
        assertFalse(Outbox.fromReplication(by("anonymous"), ignored));
        assertFalse(Outbox.fromReplication(by(null), ignored), "a change without a user is offered");
    }

    @Test
    void userGeneratedContentGoesToEveryPublishInstanceSubmissionsDoNot() {
        assertTrue(Outbox.isSharedWithPublishInstances("/content/usergenerated/site/comments/c1"));
        assertFalse(Outbox.isSharedWithPublishInstances("/content/usergenerated"));
        assertFalse(Outbox.isSharedWithPublishInstances("/content/usergeneratedx/a"));
        assertFalse(Outbox.isSharedWithPublishInstances("/var/studio/forms/submissions/site/contact/2026/11/x"));
        assertFalse(Outbox.isSharedWithPublishInstances(null));
    }
}
