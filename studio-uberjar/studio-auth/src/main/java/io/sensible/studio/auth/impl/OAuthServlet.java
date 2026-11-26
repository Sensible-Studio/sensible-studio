package io.sensible.studio.auth.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The OAuth endpoints ({@link OAuth}):
 * <ul>
 * <li>{@code GET /oauth/authorize?response_type=code&client_id&redirect_uri&scope&state&code_challenge&code_challenge_method=S256}:
 * a user who is not signed in is sent to the login page and back; a signed-in user sees what the
 * client asks for and allows it or not ({@code POST /oauth/authorize}, from this page only); the
 * browser goes back to the client with {@code code} and {@code state}, or {@code error=access_denied}.</li>
 * <li>{@code POST /oauth/token} ({@code client_id} and {@code client_secret} in the body):
 * {@code grant_type=authorization_code} ({@code code}, {@code redirect_uri}, {@code code_verifier}),
 * {@code refresh_token}, {@code client_credentials}.</li>
 * <li>{@code POST /oauth/revoke} ({@code token}, the client's id and secret): RFC 7009.</li>
 * </ul>
 * A request made with a token cannot ask for or give tokens.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/oauth/authorize",
        "sling.servlet.paths=/oauth/token",
        "sling.servlet.paths=/oauth/revoke",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST" })
public class OAuthServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String LOGIN = "/libs/granite/core/content/login.html";

    @Reference
    private transient OAuthService service;

    /** For the tests. */
    void service(OAuthService s) {
        service = s;
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!request.getRequestURI().endsWith("/oauth/authorize")) {
            json(response, 405, error("invalid_request", "Use POST"));
            return;
        }
        authorize(request, response, false);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (request.getAttribute(BearerHandler.SCOPES) != null) {
            json(response, 403, error("invalid_request", "Tokens are not asked for with a token"));
            return;
        }
        String uri = request.getRequestURI();
        if (uri.endsWith("/oauth/authorize")) {
            authorize(request, response, true);
        } else if (uri.endsWith("/oauth/token")) {
            token(request, response);
        } else {
            revoke(request, response);
        }
    }

    // ---------- authorize

    private void authorize(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean decided) throws IOException {
        if (request.getAttribute(BearerHandler.SCOPES) != null) {
            json(response, 403, error("invalid_request", "Tokens are not asked for with a token"));
            return;
        }
        OAuth oauth = service.oauth();
        String clientId = request.getParameter("client_id");
        String redirectUri = request.getParameter("redirect_uri");
        String state = request.getParameter("state");
        Set<String> scopes;
        try {
            if (!"code".equals(request.getParameter("response_type"))) {
                throw new OAuth.Failure("unsupported_response_type", "Only response_type=code");
            }
            scopes = oauth.checkAuthorization(clientId, redirectUri, request.getParameter("scope"),
                    request.getParameter("code_challenge"), request.getParameter("code_challenge_method"));
        } catch (OAuth.Failure f) {
            // the redirect address is not known to be the client's: say it here, never send the browser there
            page(response, 400, "This application cannot be allowed", "<p>" + esc(f.getMessage()) + "</p>");
            return;
        }
        String user = request.getRemoteUser();
        if (user == null || "anonymous".equals(user)) {
            String back = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect(LOGIN + "?resource=" + URLEncoder.encode(back, StandardCharsets.UTF_8));
            return;
        }
        if (!decided) {
            consent(request, response, oauth.client(clientId), user, scopes);
            return;
        }
        if (!sameOrigin(request)) {
            page(response, 403, "Not allowed", "<p>The answer has to come from this page.</p>");
            return;
        }
        String back;
        if ("allow".equals(request.getParameter("decision"))) {
            String code = oauth.issueCode(clientId, user, redirectUri, scopes, request.getParameter("code_challenge"));
            back = with(redirectUri, "code", code, state);
        } else {
            back = with(redirectUri, "error", "access_denied", state);
        }
        response.setStatus(303);
        response.setHeader("Location", back);
        response.setHeader("Cache-Control", "no-store");
    }

    /** Is this POST the consent page's own (not another site's form)? */
    static boolean sameOrigin(SlingHttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return "same-origin".equals(site);
        }
        String own = request.getScheme() + "://" + request.getHeader("Host");
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return origin.equals(own);
        }
        String referer = request.getHeader("Referer");
        return referer != null && referer.startsWith(own + "/");
    }

    static String with(String redirectUri, String key, String value, String state) {
        StringBuilder sb = new StringBuilder(redirectUri).append(redirectUri.contains("?") ? '&' : '?');
        sb.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        if (state != null) {
            sb.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void consent(SlingHttpServletRequest request, SlingHttpServletResponse response, Map<String, Object> client, String user,
            Set<String> scopes) throws IOException {
        StringBuilder asks = new StringBuilder("<ul>");
        if (scopes.contains(OAuth.WRITE)) {
            asks.append("<li>read and <strong>change</strong> what you may read and change here</li>");
        } else {
            asks.append("<li>read what you may read here</li>");
        }
        if (scopes.contains(OAuth.OFFLINE)) {
            asks.append("<li>go on doing so when you are not signed in, until you revoke it (up to 30 days at a time)</li>");
        } else {
            asks.append("<li>for an hour</li>");
        }
        asks.append("</ul>");
        StringBuilder hidden = new StringBuilder();
        for (String p : new String[] { "response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method" }) {
            String v = request.getParameter(p);
            if (v != null) {
                hidden.append("<input type=\"hidden\" name=\"").append(p).append("\" value=\"").append(esc(v)).append("\">");
            }
        }
        String host = hostOf(request.getParameter("redirect_uri"));
        // the answer is posted here and the browser then goes on to the client: form-action covers
        // that redirect too, so it names the redirect address's origin (one the client registered)
        page(response, 200, originOf(request.getParameter("redirect_uri")), "Allow " + esc(String.valueOf(client.get("name"))) + "?",
                "<p><strong>" + esc(String.valueOf(client.get("name"))) + "</strong> asks to act as you, <strong>" + esc(user)
                        + "</strong>, on this instance:</p>" + asks
                        + "<p class=\"muted\">It never learns your password. You can revoke it in Users › OAuth. Afterwards you go back to "
                        + esc(host) + ".</p>"
                        + "<form method=\"post\" action=\"/oauth/authorize\">" + hidden
                        + "<button type=\"submit\" name=\"decision\" value=\"deny\">Deny</button> "
                        + "<button type=\"submit\" name=\"decision\" value=\"allow\" class=\"primary\">Allow</button></form>");
    }

    private static String hostOf(String uri) {
        try {
            return new java.net.URI(uri).getHost();
        } catch (Exception e) {
            return uri;
        }
    }

    /** {@code scheme://host[:port]} of an address, for a CSP source; null if it has none. */
    static String originOf(String uri) {
        try {
            java.net.URI u = new java.net.URI(uri);
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            return u.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + u.getHost().toLowerCase(java.util.Locale.ROOT)
                    + (u.getPort() < 0 ? "" : ":" + u.getPort());
        } catch (Exception e) {
            return null;
        }
    }

    private static void page(SlingHttpServletResponse response, int status, String title, String body) throws IOException {
        page(response, status, null, title, body);
    }

    private static void page(SlingHttpServletResponse response, int status, String formTarget, String title, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Frame-Options", "DENY");
        String formAction = "'self'" + (formTarget == null || formTarget.matches(".*[\\s;,'].*") ? "" : " " + formTarget);
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action " + formAction + "; frame-ancestors 'none'");
        PrintWriter w = response.getWriter();
        w.write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + " · Sensible Studio</title><style>"
                + "body{font:15px/1.5 system-ui,-apple-system,sans-serif;background:#f4f4f5;color:#18181b;margin:0;display:grid;place-items:center;min-height:100vh}"
                + "main{background:#fff;border:1px solid #e4e4e7;border-radius:10px;padding:28px 32px;max-width:460px}"
                + "h1{font-size:20px;margin:0 0 12px}.muted{color:#71717a;font-size:13px}form{margin-top:20px;text-align:right}"
                + "button{font:inherit;padding:7px 16px;border-radius:6px;border:1px solid #d4d4d8;background:#fff;cursor:pointer}"
                + "button.primary{background:#18181b;color:#fff;border-color:#18181b;margin-left:6px}"
                + "</style></head><body><main><h1>" + title + "</h1>" + body + "</main></body></html>");
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ---------- token, revoke

    private void token(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        OAuth oauth = service.oauth();
        String clientId = request.getParameter("client_id");
        try {
            Map<String, Object> client = oauth.authenticate(clientId, request.getParameter("client_secret"));
            String grant = request.getParameter("grant_type");
            Map<String, Object> out;
            if ("authorization_code".equals(grant)) {
                out = oauth.exchangeCode(client, clientId, request.getParameter("code"), request.getParameter("redirect_uri"),
                        request.getParameter("code_verifier"));
            } else if ("refresh_token".equals(grant)) {
                out = oauth.refresh(clientId, request.getParameter("refresh_token"), request.getParameter("scope"));
            } else if ("client_credentials".equals(grant)) {
                out = oauth.clientCredentials(client, clientId, request.getParameter("scope"));
            } else {
                throw new OAuth.Failure("unsupported_grant_type", "authorization_code, refresh_token or client_credentials");
            }
            json(response, 200, out);
        } catch (OAuth.Failure f) {
            if (f.status == 401) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"oauth\"");
            }
            json(response, f.status, error(f.error, f.getMessage()));
        }
    }

    private void revoke(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        OAuth oauth = service.oauth();
        String clientId = request.getParameter("client_id");
        try {
            oauth.authenticate(clientId, request.getParameter("client_secret"));
            oauth.revoke(clientId, request.getParameter("token"));
            json(response, 200, new LinkedHashMap<>());
        } catch (OAuth.Failure f) {
            json(response, f.status, error(f.error, f.getMessage()));
        }
    }

    private static Map<String, Object> error(String error, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("error_description", description);
        return m;
    }

    private static void json(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.getWriter().write(OAuthJson.write(body));
    }
}
