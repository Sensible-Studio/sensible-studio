/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The Mail page's API, {@code /bin/studio/forms/mail.json}, for administrators: {@code GET} the mail server
 * settings (never the password), {@code POST op=save} with {@code host, port, starttls, user, password, from}
 * (an empty password keeps the stored one), {@code POST op=test&to=…} sends a test message. Writes need
 * {@code X-Studio-CSRF: 1}. The settings live in the {@code io.sensible.studio.forms.impl.Mail} configuration.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/forms/mail",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class MailServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String PID = "io.sensible.studio.forms.impl.Mail";

    @Reference
    private transient ConfigurationAdmin configAdmin;

    @Reference
    private transient Mail mail;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!admin(request, response)) {
            return;
        }
        SubmissionsServlet.json(response, settings());
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!admin(request, response)) {
            return;
        }
        if (!"1".equals(request.getHeader(SubmissionsServlet.CSRF_HEADER))) {
            SubmitServlet.send(response, 403, SubmitServlet.error("missing " + SubmissionsServlet.CSRF_HEADER + " header"));
            return;
        }
        String op = SubmissionsServlet.param(request, "op", "save");
        try {
            if ("test".equals(op)) {
                String to = SubmissionsServlet.param(request, "to", "").trim();
                if (to.isEmpty()) {
                    SubmitServlet.send(response, 400, SubmitServlet.error("Say whom to send the test to"));
                    return;
                }
                mail.send(Collections.singletonList(to), "Sensible Studio: test message",
                        "This is a test message from Sensible Studio Forms. The mail server is set up.\n");
                SubmissionsServlet.json(response, Collections.singletonMap("ok", Boolean.TRUE));
                return;
            }
            Configuration c = configAdmin.getConfiguration(PID, null);
            Dictionary<String, Object> old = c.getProperties();
            Dictionary<String, Object> props = new Hashtable<>();
            put(props, "host", SubmissionsServlet.param(request, "host", ""));
            put(props, "port", String.valueOf(SubmissionsServlet.intParam(request, "port", 587)));
            put(props, "starttls", String.valueOf(!"false".equals(SubmissionsServlet.param(request, "starttls", "true"))));
            put(props, "user", SubmissionsServlet.param(request, "user", ""));
            put(props, "from", SubmissionsServlet.param(request, "from", ""));
            String password = request.getParameter("password");
            if (password != null && !password.isEmpty()) {
                props.put("password", password);
            } else if (old != null && old.get("password") != null) {
                props.put("password", old.get("password"));
            }
            c.update(props);
            SubmissionsServlet.json(response, settings());
        } catch (Exception e) {
            SubmitServlet.send(response, 500, SubmitServlet.error(e.getMessage()));
        }
    }

    private Map<String, Object> settings() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Configuration c = configAdmin.getConfiguration(PID, null);
        Dictionary<String, Object> p = c.getProperties();
        out.put("host", p == null ? "" : str(p.get("host")));
        out.put("port", p == null || p.get("port") == null ? 587 : Integer.parseInt(str(p.get("port"))));
        out.put("starttls", p == null || p.get("starttls") == null || Boolean.parseBoolean(str(p.get("starttls"))));
        out.put("user", p == null ? "" : str(p.get("user")));
        out.put("from", p == null ? "" : str(p.get("from")));
        out.put("hasPassword", p != null && p.get("password") != null && !str(p.get("password")).isEmpty());
        out.put("configured", p != null && !str(p.get("host")).trim().isEmpty());
        return out;
    }

    private static void put(Dictionary<String, Object> d, String k, String v) {
        d.put(k, v == null ? "" : v.trim());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Only administrators (admin, or a member of administrators) see or change the mail server. */
    static boolean admin(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!SubmissionsServlet.signedIn(request, response)) {
            return false;
        }
        boolean ok = false;
        Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session instanceof JackrabbitSession) {
            try {
                Authorizable a = ((JackrabbitSession) session).getUserManager().getAuthorizable(session.getUserID());
                ok = a instanceof User && ((User) a).isAdmin();
                if (a != null && !ok) {
                    for (Iterator<Group> it = a.memberOf(); it.hasNext() && !ok; ) {
                        ok = "administrators".equals(it.next().getID());
                    }
                }
            } catch (RepositoryException e) {
                ok = false;
            }
        }
        if (!ok) {
            SubmitServlet.send(response, 403, SubmitServlet.error("Only administrators can change the mail server"));
        }
        return ok;
    }
}
