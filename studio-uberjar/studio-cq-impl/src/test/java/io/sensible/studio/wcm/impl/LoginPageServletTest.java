/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class LoginPageServletTest {

    private final SlingContext context = new SlingContext();

    @Test
    void onlyLocalPathsAreFollowedAfterSigningIn() {
        assertEquals("/sites.html/content?x=1", LoginPageServlet.safeResource("/sites.html/content?x=1"));
        assertEquals("/", LoginPageServlet.safeResource(null));
        assertEquals("/", LoginPageServlet.safeResource("https://evil.example"));
        assertEquals("/", LoginPageServlet.safeResource("//evil.example/x"));
        assertEquals("/", LoginPageServlet.safeResource("/\\evil.example"));
        assertEquals("/", LoginPageServlet.safeResource(LoginPageServlet.URL));
        assertEquals("/", LoginPageServlet.safeResource("/a\r\nSet-Cookie: x"));
    }

    @Test
    void rendersTheFormWithTheResourceEscaped() throws Exception {
        context.request().setParameterMap(Map.of("resource", "/sites.html/content\"><script>x</script>",
                "j_reason", "INVALID_CREDENTIALS"));
        new LoginPageServlet().doGet(context.request(), context.response());
        String html = context.response().getOutputAsString();
        assertTrue(html.contains("action=\"/libs/granite/core/content/login.html/j_security_check\""), html);
        assertTrue(html.contains("value=\"/sites.html/content&quot;&gt;&lt;script&gt;x&lt;/script&gt;\""));
        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("class=\"error is-visible\""));
        assertTrue(html.contains("User name and password do not match."));
        assertFalse(html.contains("{{"), "every placeholder is filled");
    }

    @Test
    void noMessageWithoutAReason() throws Exception {
        context.request().setParameterMap(Map.of("resource", "/studio/start.html"));
        new LoginPageServlet().doGet(context.request(), context.response());
        String html = context.response().getOutputAsString();
        assertTrue(html.contains("class=\"error\""));
        assertTrue(html.contains("value=\"/studio/start.html\""));
    }

    @Test
    void a_wrong_password_is_said_in_words_whatever_names_it() {
        assertEquals("User name and password do not match.", LoginPageServlet.message("invalid_login"));
        assertEquals("User name and password do not match.", LoginPageServlet.message("User name and password do not match"));
        assertEquals("Too many attempts. Try again later.", LoginPageServlet.message("Too many attempts. Try again later."));
        assertEquals("Sign in failed.", LoginPageServlet.message("something else"));
    }

    @Test
    void visitors_of_the_live_site_get_the_form_without_the_authors_welcome() {
        String page = "<main class=\"page\"><section class=\"welcome\"><h1>Welcome</h1></section><section class=\"panel\"><form></form>"
                + "<footer>Sensible Studio author</footer></section></main>";
        assertEquals("<main class=\"page page--solo\"><section class=\"panel\"><form></form></section></main>", LoginPageServlet.forVisitors(page));
    }
}
