/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthoringEndpointsFilterTest {

    private static final String[] OPEN = {"/bin/studio/forms/submit", "/bin/studio/dam/deliver"};

    @Test
    void whoIsNotSignedInGetsNoAuthoringEndpoint() {
        for (String user : new String[] {null, "anonymous"}) {
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/replication.json", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/graphql/list.json", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/graphql/list.json/wknd-shared", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/graphql/persist.json/wknd-shared/q", user, OPEN));
            assertFalse(AuthoringEndpointsFilter.refused("/graphql/execute.json/wknd-shared/q", user, OPEN));
            assertFalse(AuthoringEndpointsFilter.refused("/graphql/listing.json", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/replication.css", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/sites", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio.json", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/dam/metadata.json", user, OPEN));
            // a look-alike of an open path is not the open path
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/forms/submissions.json", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/forms/submitted", user, OPEN));
            assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/dam/delivery.json", user, OPEN));
        }
    }

    @Test
    void whatVisitorsUseStaysOpen() {
        assertFalse(AuthoringEndpointsFilter.refused("/bin/studio/forms/submit", "anonymous", OPEN));
        assertFalse(AuthoringEndpointsFilter.refused("/bin/studio/forms/submit.json", null, OPEN));
        assertFalse(AuthoringEndpointsFilter.refused("/bin/studio/dam/deliver/ab12/sig/photo.jpg", "anonymous", OPEN));
    }

    @Test
    void aSignedInUserAndOtherPathsAreNotItsBusiness() {
        assertFalse(AuthoringEndpointsFilter.refused("/bin/studio/replication.json", "admin", OPEN));
        assertFalse(AuthoringEndpointsFilter.refused("/bin/studio/sites.json", "an-author", OPEN));
        assertFalse(AuthoringEndpointsFilter.refused("/content/wknd/us/en.html", "anonymous", OPEN));
        assertFalse(AuthoringEndpointsFilter.refused("/bin/studios/x.json", "anonymous", OPEN));
        assertFalse(AuthoringEndpointsFilter.refused("/bin/receive", "anonymous", OPEN));
        assertFalse(AuthoringEndpointsFilter.refused(null, "anonymous", OPEN));
    }

    @Test
    void aCarelessListOpensNothing() {
        String[] careless = {"/", "/bin", "/bin/studio", "", null};
        assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/replication.json", "anonymous", careless));
        assertTrue(AuthoringEndpointsFilter.refused("/bin/studio/security.json", null, careless));
    }
}
