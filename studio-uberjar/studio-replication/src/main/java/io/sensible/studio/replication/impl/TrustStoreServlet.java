/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code /bin/studio/truststore.json}, the Trust Store console's endpoint.
 *
 * <ul>
 * <li>GET {@code op=list}: the certificates ({@code alias} and what {@link TrustStore#describe}
 * says), and whether this user may change them ({@code canEdit});</li>
 * <li>GET {@code op=check&url=https://…}: is that server trusted, and the certificates it shows;</li>
 * <li>POST {@code op=add&alias=}, with {@code certificate} (PEM text) or {@code file} (PEM or
 * DER): adds them (one alias each: {@code alias}, {@code alias-2}, …);</li>
 * <li>POST {@code op=remove&alias=}.</li>
 * </ul>
 * Changes are made with the user's own session: only who may write {@code /etc/truststore}.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/truststore",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST" })
public class TrustStoreServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient TrustStore store;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        if ("check".equals(op)) {
            String url = request.getParameter("url");
            if (url == null || url.isEmpty()) {
                error(response, HttpServletResponse.SC_BAD_REQUEST, "url is required");
                return;
            }
            send(response, TrustStore.check(url.trim()));
            return;
        }
        if (op != null && !"list".equals(op)) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("certificates", list(request.getResourceResolver()));
        out.put("canEdit", canEdit(request.getResourceResolver()));
        send(response, out);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver rr = request.getResourceResolver();
        if (!canEdit(rr)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Only administrators may change the trust store");
            return;
        }
        String op = request.getParameter("op");
        try {
            if ("add".equals(op)) {
                byte[] data = null;
                RequestParameter file = request.getRequestParameter("file");
                if (file != null && !file.isFormField() && file.getSize() > 0) {
                    data = file.get();
                } else if (request.getParameter("certificate") != null) {
                    data = request.getParameter("certificate").getBytes(StandardCharsets.UTF_8);
                }
                if (data == null || data.length == 0) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, "A certificate is required (PEM text or a .pem, .crt, .cer or .der file)");
                    return;
                }
                List<X509Certificate> certs;
                try {
                    certs = TrustStore.parse(data);
                } catch (CertificateException e) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, "Not a certificate: " + e.getMessage());
                    return;
                }
                Resource root = ResourceUtil.getOrCreateResource(rr, TrustStore.PATH, "sling:Folder", "sling:Folder", false);
                String base = alias(request.getParameter("alias"), certs.get(0));
                List<String> added = new ArrayList<>();
                for (int i = 0; i < certs.size(); i++) {
                    String name = i == 0 ? base : base + "-" + (i + 1);
                    if (root.getChild(name) != null) {
                        rr.revert();
                        error(response, HttpServletResponse.SC_CONFLICT, "There is a certificate called " + name + " already");
                        return;
                    }
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("jcr:primaryType", "nt:unstructured");
                    props.put(TrustStore.CERTIFICATE, TrustStore.pem(certs.get(i)));
                    rr.create(root, name, props);
                    added.add(name);
                }
                rr.commit();
                store.reload();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("added", added);
                out.put("certificates", list(rr));
                out.put("canEdit", true);
                send(response, out);
            } else if ("remove".equals(op)) {
                String alias = request.getParameter("alias");
                Resource r = alias == null ? null : rr.getResource(TrustStore.PATH + "/" + alias);
                if (r == null || alias.contains("/")) {
                    error(response, HttpServletResponse.SC_NOT_FOUND, "No such certificate");
                    return;
                }
                rr.delete(r);
                rr.commit();
                store.reload();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("certificates", list(rr));
                out.put("canEdit", true);
                send(response, out);
            } else {
                error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op");
            }
        } catch (PersistenceException | CertificateException e) {
            rr.revert();
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    static List<Map<String, Object>> list(ResourceResolver rr) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = rr.getResource(TrustStore.PATH);
        if (root == null) {
            return out;
        }
        for (Resource r : root.getChildren()) {
            String pem = r.getValueMap().get(TrustStore.CERTIFICATE, String.class);
            if (pem == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alias", r.getName());
            try {
                m.putAll(TrustStore.describe(TrustStore.parse(pem.getBytes(StandardCharsets.UTF_8)).get(0)));
            } catch (CertificateException e) {
                m.put("error", "Not a certificate: " + e.getMessage());
            }
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("alias")).compareTo(String.valueOf(b.get("alias"))));
        return out;
    }

    /** Who may write the trust store's folder (administrators). */
    static boolean canEdit(ResourceResolver rr) {
        Session s = rr.adaptTo(Session.class);
        if (s == null) {
            return false;
        }
        try {
            String path = s.nodeExists(TrustStore.PATH) ? TrustStore.PATH : "/etc";
            return s.hasPermission(path + "/studio-permission-check", "add_node");
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** The alias asked for, or the certificate's common name; letters, digits, '.', '-', '_'. */
    static String alias(String asked, X509Certificate c) {
        String a = asked == null ? "" : asked.trim();
        if (a.isEmpty()) {
            String dn = c.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                if (part.trim().startsWith("CN=")) {
                    a = part.trim().substring(3);
                    break;
                }
            }
            if (a.isEmpty()) {
                try {
                    a = TrustStore.fingerprint(c).replace(":", "").substring(0, 16);
                } catch (GeneralSecurityException e) {
                    a = "certificate";
                }
            }
        }
        String clean = a.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|-+$", "");
        return clean.isEmpty() ? "certificate" : clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static void send(SlingHttpServletResponse response, Object body) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }

    private static void error(SlingHttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        send(response, Collections.singletonMap("error", message == null ? "" : message));
    }
}
