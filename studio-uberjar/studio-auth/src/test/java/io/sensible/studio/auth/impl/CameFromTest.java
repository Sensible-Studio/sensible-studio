/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;

/** Where a wrong password typed into a site's own sign-in form sends the visitor back to. */
class CameFromTest {

    private static HttpServletRequest request(String referer, String accept) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", referer);
        headers.put("Accept", accept);
        return (HttpServletRequest) Proxy.newProxyInstance(CameFromTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHeader": return headers.get(String.valueOf(args[0]));
                        case "getServerName": return "www.example.com";
                        case "getServerPort": return 4503;
                        default: return null;
                    }
                });
    }

    @Test
    void a_page_of_this_site_is_where_the_visitor_goes_back_to() {
        assertEquals("/content/wknd/us/en.html", LoginHandler.cameFrom(request("http://www.example.com:4503/content/wknd/us/en.html", "text/html,*/*")));
        assertEquals("/wknd/us/en.html?a=1", LoginHandler.cameFrom(request("http://WWW.example.com:4503/wknd/us/en.html?a=1#sign-in", "text/html")));
    }

    @Test
    void another_site_a_script_or_the_check_itself_is_not() {
        assertNull(LoginHandler.cameFrom(request("http://evil.example.org:4503/content/x.html", "text/html")));
        assertNull(LoginHandler.cameFrom(request("http://www.example.com:8080/content/x.html", "text/html")));
        assertNull(LoginHandler.cameFrom(request("http://www.example.com:4503/content/x.html", "application/json")));
        assertNull(LoginHandler.cameFrom(request("http://www.example.com:4503/content/x.html", null)));
        assertNull(LoginHandler.cameFrom(request(null, "text/html")));
        assertNull(LoginHandler.cameFrom(request("http://www.example.com:4503/system/sling/login/j_security_check", "text/html")));
        assertNull(LoginHandler.cameFrom(request("not a url at all", "text/html")));
    }
}
