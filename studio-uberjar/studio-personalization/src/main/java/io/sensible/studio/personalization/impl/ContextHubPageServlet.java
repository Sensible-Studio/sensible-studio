/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import javax.servlet.Servlet;

import org.osgi.service.component.annotations.Component;

/**
 * The ContextHub console at AEM's address, {@code /libs/granite/contexthub/content/admin/contexthub.html[/conf/<site>]}
 * (Tools › Sites › ContextHub): the author application's {@code index.html}, as for the Audiences console.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/libs/granite/contexthub/content/admin/contexthub",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class ContextHubPageServlet extends AudiencesPageServlet {

    private static final long serialVersionUID = 1L;
}
