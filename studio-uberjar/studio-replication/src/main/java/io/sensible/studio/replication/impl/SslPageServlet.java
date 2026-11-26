/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import javax.servlet.Servlet;

import org.osgi.service.component.annotations.Component;

/**
 * The SSL Configuration wizard's page: {@code /security/ssl.html}, and AEM's address for it,
 * {@code /libs/granite/security/content/sslConfig.html}. The author application, which routes on the URL.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/security/ssl",
                "sling.servlet.paths=/libs/granite/security/content/sslConfig",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class SslPageServlet extends ReplicationPageServlet {

    private static final long serialVersionUID = 1L;
}
