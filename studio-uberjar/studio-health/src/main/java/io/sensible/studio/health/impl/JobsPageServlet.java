/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import javax.servlet.Servlet;

import org.osgi.service.component.annotations.Component;

/**
 * The Jobs console at AEM's address, {@code /mnt/overlay/granite/async/content/asyncjobs.html}: the author
 * application's {@code index.html} as for the Health console (the application routes on the URL).
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/mnt/overlay/granite/async/content/asyncjobs",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class JobsPageServlet extends HealthPageServlet {

    private static final long serialVersionUID = 1L;
}
