/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.apache.sling.caconfig.annotation.Configuration;
import org.apache.sling.caconfig.annotation.Property;

/**
 * A site's analytics: the scripts of the service that measures it (Adobe Tags, Google Analytics, Matomo,
 * Plausible…), put into the head of every page of the site on the live site. Set per site in Site
 * configurations; no change to the site's page component is needed. See {@link AnalyticsFilter}.
 */
@Configuration(label = "Analytics",
        description = "The scripts of the service that measures this site (Adobe Tags, Google Analytics, Matomo…). "
                + "They are added to the head of every page of the site on the live site. Publish the configuration "
                + "for it to take effect there.")
public @interface AnalyticsConfig {

    @Property(label = "Script addresses", order = 1,
            description = "One address per script, as the service gives it: https://…, //… or a path of this site.")
    String[] scripts() default {};

    @Property(label = "Load without holding the page back (async)", order = 2,
            description = "On, as the services recommend. Off only when their instructions ask for it.")
    boolean async() default true;

    @Property(label = "Also on the author's View as Published", order = 3,
            description = "Off: what authors look at is not measured.")
    boolean onAuthor() default false;
}
