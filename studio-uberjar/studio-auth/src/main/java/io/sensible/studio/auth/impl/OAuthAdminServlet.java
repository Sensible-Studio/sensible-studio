package io.sensible.studio.auth.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code /bin/studio/oauth.json}, the console's endpoint. Administrators (admin and the members of
 * {@code administrators}) see and manage every client and token; anybody else signed in sees and
 * revokes the tokens that act as them. Never with an OAuth token ({@link OAuthScopeFilter}).
 * <ul>
 * <li>GET {@code op=clients}, {@code op=tokens}; both say {@code admin};</li>
 * <li>POST {@code op=createClient} ({@code name}, {@code redirectUris} one a line, {@code scope}
 * repeated, {@code runAs}): the answer has {@code secret}, shown this once;</li>
 * <li>POST {@code op=updateClient&id=}, {@code op=secret&id=} (a new secret, once),
 * {@code op=deleteClient&id=} (and its tokens), {@code op=revoke&id=} (a token and its grant).</li>
 * </ul>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/oauth",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST" })
public class OAuthAdminServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient OAuthService service;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getRemoteUser();
        if (user == null || "anonymous".equals(user)) {
            json(response, 401, Collections.singletonMap("error", "sign in"));
            return;
        }
        boolean admin = isAdmin(request);
        OAuth oauth = service.oauth();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("admin", admin);
        out.put("user", user);
        if ("clients".equals(request.getParameter("op"))) {
            out.put("clients", admin ? oauth.clients() : Collections.emptyList());
        } else {
            out.put("tokens", oauth.tokens(admin ? null : user));
        }
        json(response, 200, out);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getRemoteUser();
        if (user == null || "anonymous".equals(user)) {
            json(response, 401, Collections.singletonMap("error", "sign in"));
            return;
        }
        boolean admin = isAdmin(request);
        OAuth oauth = service.oauth();
        String op = request.getParameter("op");
        String id = request.getParameter("id");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            if ("revoke".equals(op)) {
                if (!oauth.revokeById(id, admin ? null : user)) {
                    json(response, 404, Collections.singletonMap("error", "No such token"));
                    return;
                }
                out.put("tokens", oauth.tokens(admin ? null : user));
                json(response, 200, out);
                return;
            }
            if (!admin) {
                json(response, 403, Collections.singletonMap("error", "Only administrators manage OAuth clients"));
                return;
            }
            List<String> uris = lines(request.getParameter("redirectUris"));
            String[] scopes = request.getParameterValues("scope");
            List<String> scopeList = scopes == null ? Collections.emptyList() : Arrays.asList(scopes);
            switch (op == null ? "" : op) {
                case "createClient":
                    out.put("client", oauth.createClient(request.getParameter("name"), uris, scopeList, request.getParameter("runAs"), user));
                    break;
                case "updateClient":
                    out.put("client", oauth.updateClient(id, request.getParameter("name"), uris, scopeList, request.getParameter("runAs")));
                    break;
                case "secret":
                    out.put("secret", oauth.newSecret(id));
                    break;
                case "deleteClient":
                    oauth.deleteClient(id);
                    break;
                default:
                    json(response, 400, Collections.singletonMap("error", "Unknown op"));
                    return;
            }
            out.put("clients", oauth.clients());
            json(response, 200, out);
        } catch (OAuth.Failure f) {
            json(response, f.status == 401 ? 400 : f.status, Collections.singletonMap("error", f.getMessage()));
        }
    }

    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String l : text.split("[\\r\\n,]+")) {
                if (!l.trim().isEmpty()) {
                    out.add(l.trim());
                }
            }
        }
        return out;
    }

    /** admin, or a member (also through other groups) of administrators. */
    static boolean isAdmin(SlingHttpServletRequest request) {
        Session session = request.getResourceResolver().adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            return false;
        }
        try {
            String id = session.getUserID();
            if ("admin".equals(id)) {
                return true;
            }
            Authorizable me = ((JackrabbitSession) session).getUserManager().getAuthorizable(id);
            if (me == null) {
                return false;
            }
            for (Iterator<Group> it = me.memberOf(); it.hasNext();) {
                if ("administrators".equals(it.next().getID())) {
                    return true;
                }
            }
            return false;
        } catch (RepositoryException e) {
            return false;
        }
    }

    private static void json(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(OAuthJson.write(body));
    }
}
