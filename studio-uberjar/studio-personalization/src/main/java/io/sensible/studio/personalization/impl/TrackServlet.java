/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /bin/studio/targeting/track}: the page's ContextHub script reports what targeted components
 * showed and which goals were met. Body: {@code {"page":"/content/…","events":[{"type":"impression"|"conversion",
 * "target":"/content/…/jcr:content/…","experience":"summer"}]}} (at most 50 events). Anonymous on publish;
 * nothing about the visitor is stored, only counters. Answers 204.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/targeting/track",
                "sling.servlet.methods=POST"
        })
public class TrackServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(TrackServlet.class);
    static final String SUBSERVICE = "personalization";
    private static final int MAX_EVENTS = 50;
    private static final int MAX_BODY = 64 * 1024;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        byte[] body = readBody(request);
        Object parsed;
        try {
            parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            response.setStatus(400);
            return;
        }
        if (!(parsed instanceof Map)) {
            response.setStatus(400);
            return;
        }
        Map<String, Object> msg = (Map<String, Object>) parsed;
        Object events = msg.get("events");
        String page = msg.get("page") instanceof String ? (String) msg.get("page") : null;
        if (!(events instanceof List)) {
            response.setStatus(400);
            return;
        }
        try (ResourceResolver rr = resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            Session session = rr.adaptTo(Session.class);
            int n = 0;
            for (Object o : (List<Object>) events) {
                if (!(o instanceof Map) || n++ >= MAX_EVENTS) {
                    continue;
                }
                Map<String, Object> e = (Map<String, Object>) o;
                String type = String.valueOf(e.get("type"));
                Object target = e.get("target");
                Object experience = e.get("experience");
                if (target instanceof String && experience instanceof String && ("impression".equals(type) || "conversion".equals(type))) {
                    Stats.record(session, (String) target, (String) experience, "conversion".equals(type), page);
                }
            }
            if (session.hasPendingChanges()) {
                session.save();
            }
            response.setStatus(204);
        } catch (LoginException e) {
            LOG.error("No personalization service user: {}", e.getMessage());
            response.setStatus(500);
        } catch (RepositoryException e) {
            LOG.warn("Tracking failed: {}", e.getMessage());
            response.setStatus(500);
        }
    }

    private static byte[] readBody(SlingHttpServletRequest request) throws IOException {
        java.io.InputStream in = request.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        for (int r; (r = in.read(buf)) > 0;) {
            total += r;
            if (total > MAX_BODY) {
                break;
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
