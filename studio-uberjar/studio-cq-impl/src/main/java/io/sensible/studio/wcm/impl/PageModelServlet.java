/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.NameConstants;

/**
 * The JSON model of a page, at AEM's URL: {@code /content/site/en.model.json} is the model of
 * {@code /content/site/en/jcr:content}, which the page component's Sling Model exports (Core
 * Components register their exporters for the component's resource type, not for {@code cq:Page}).
 * Without this, Sling's default JSON rendering of the page node takes {@code model} for a depth and
 * answers 400.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=cq:Page",
                "sling.servlet.resourceTypes=cq/Page",
                "sling.servlet.selectors=model",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class PageModelServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        Resource content = request.getResource().getChild(NameConstants.NN_CONTENT);
        if (content == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Page has no content");
            return;
        }
        RequestDispatcher dispatcher = request.getRequestDispatcher(content);
        if (dispatcher == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        dispatcher.include(request, response);
    }
}
