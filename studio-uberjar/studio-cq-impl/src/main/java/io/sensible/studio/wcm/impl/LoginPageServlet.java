/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Sensible Studio's login page, at AEM's URL {@code /libs/granite/core/content/login.html?resource=<where to go next>}. The form
 * posts to {@code .../login.html/j_security_check} (Sling's form authentication: {@code j_username},
 * {@code j_password}, {@code resource}); with JavaScript it validates first ({@code j_validate}) and shows a wrong
 * password in place, without it Sling redirects to the resource or back here with {@code j_reason}.
 *
 * <p>Sling's form authentication handler sends here (its {@code form.login.form}, set in {@code studio-compat-config}),
 * and {@link LoginRedirectFilter} sends Sling's own form ({@code /system/sling/form/login}) here too. Signed-in
 * visitors go straight on to the resource.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=" + LoginPageServlet.PATH,
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD",
                "sling.auth.requirements=-" + LoginPageServlet.PATH
        })
public class LoginPageServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    public static final String PATH = "/libs/granite/core/content/login";
    public static final String URL = PATH + ".html";
    static final String ACTION = URL + "/j_security_check";

    /** Run modes: on a publish instance the page is for a visitor of the site. Absent in tests. */
    @org.osgi.service.component.annotations.Reference(
            cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL,
            policy = org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC,
            policyOption = org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY)
    private transient volatile org.apache.sling.settings.SlingSettingsService settings;

    private boolean live() {
        org.apache.sling.settings.SlingSettingsService s = settings;
        return s != null && s.getRunModes().contains("publish");
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        String resource = safeResource(request.getParameter("resource"));
        // the code says what happened; the text beside it is Sling's own wording
        String reason = request.getParameter("j_reason_code");
        if (reason == null || reason.isEmpty()) {
            reason = request.getParameter("j_reason");
        }
        String user = request.getResourceResolver().getUserID();
        if (user != null && !"anonymous".equals(user) && reason == null) {
            response.sendRedirect(request.getContextPath() + resource);
            return;
        }
        String html;
        try (InputStream in = LoginPageServlet.class.getResourceAsStream("login.html")) {
            if (in == null) {
                throw new ServletException("login.html is missing from the bundle");
            }
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String message = message(reason);
        if (live()) {
            html = forVisitors(html);
        }
        html = html.replace("{{action}}", escape(request.getContextPath() + ACTION))
                .replace("{{resource}}", escape(resource))
                .replace("{{errorClass}}", message.isEmpty() ? "" : " is-visible")
                .replace("{{error}}", escape(message));
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Frame-Options", "DENY");
        response.getWriter().write(html);
    }

    /** @return the page to open after signing in: a path on this server, {@code /} otherwise */
    static String safeResource(String resource) {
        if (resource == null || resource.isEmpty() || !resource.startsWith("/") || resource.startsWith("//")
                || resource.contains("\\") || resource.startsWith(PATH)) {
            return "/";
        }
        for (int i = 0; i < resource.length(); i++) {
            if (Character.isISOControl(resource.charAt(i))) {
                return "/";
            }
        }
        return resource;
    }

    /**
     * The page as a visitor of the live site meets it (a wrong password in the site's own sign-in form, a
     * members' page): without the product's welcome, which speaks to authors, and without "author" under the form.
     */
    static String forVisitors(String html) {
        int from = html.indexOf("<section class=\"welcome\">");
        int to = from < 0 ? -1 : html.indexOf("</section>", from);
        if (to > 0) {
            html = html.substring(0, from) + html.substring(to + "</section>".length());
        }
        return html.replace("<footer>Sensible Studio author</footer>", "")
                .replace("<main class=\"page\">", "<main class=\"page page--solo\">")
                .replace("<title>Sensible Studio | Sign In</title>", "<title>Sign in</title>");
    }

    /** Sling's {@code FormReason} (and its reason codes) in words. */
    static String message(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        if ("invalid_login".equalsIgnoreCase(reason) || reason.startsWith("User name and password do not match")) {
            return "User name and password do not match.";
        }
        if (reason.startsWith("Too many attempts")) {
            return "Too many attempts. Try again later.";
        }
        switch (reason) {
            case "TIMEOUT":
                return "Your session has expired. Sign in again.";
            case "INVALID_CREDENTIALS":
            case "UNKNOWN_USER":
                return "User name and password do not match.";
            case "ACCOUNT_LOCKED":
                return "This account is locked.";
            case "ACCOUNT_NOT_FOUND":
                return "User name and password do not match.";
            case "PASSWORD_EXPIRED":
            case "PASSWORD_EXPIRED_AND_NEW_PASSWORD_IN_HISTORY":
                return "Your password has expired. Ask an administrator to reset it.";
            default:
                return "Sign in failed.";
        }
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
