/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;

import javax.servlet.GenericServlet;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.WCMMode;

/**
 * What a request that fails is answered with, in place of Sling's own error page (which prints the request
 * progress and, for an exception, its stack trace: fine for a developer, not for a visitor of the live site).
 *
 * <p>A page that does not exist is answered with <b>the site's own 404 page</b>: the nearest
 * {@code errors/404} page above the address asked for ({@code /content/wknd/us/en/errors/404} for anything
 * below {@code /content/wknd/us/en}), rendered as a visitor sees it, with status 404. Without such a page, and
 * for every other failure, the answer is a short page (or a JSON object for a {@code .json} address) with the
 * status and the message. Details stay in the log.</p>
 *
 * <p>Registered where Sling looks for error handlers: {@code sling/servlet/errorhandler/404} and
 * {@code …/default} (every other status, and exceptions).</p>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/libs/sling/servlet/errorhandler/404",
                "sling.servlet.paths=/libs/sling/servlet/errorhandler/default",
                // Sling's own handler sits at the same "default" address: without a ranking above it, ours answered
                // 404 only and every other failure still showed the request progress
                "service.ranking:Integer=1000"
        })
public class ErrorPageServlet extends GenericServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ErrorPageServlet.class);

    static final String ERROR_PAGES = "errors";
    private static final String HANDLING = ErrorPageServlet.class.getName() + ".handling";

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        HttpServletResponse response = (HttpServletResponse) res;
        if (response.isCommitted()) {
            return;
        }
        Object code = req.getAttribute("javax.servlet.error.status_code");
        int status = code instanceof Integer ? (Integer) code : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        Object m = req.getAttribute("javax.servlet.error.message");
        String message = m == null ? "" : String.valueOf(m);
        Object thrown = req.getAttribute("javax.servlet.error.exception");
        if (thrown instanceof Throwable) {
            // the stack trace belongs in the log, never in the answer
            LOG.error("Request failed: {}", req.getAttribute("javax.servlet.error.request_uri"), (Throwable) thrown);
            message = "The request could not be completed.";
        }
        SlingHttpServletRequest request = req instanceof SlingHttpServletRequest ? (SlingHttpServletRequest) req : null;
        String extension = request == null ? null : request.getRequestPathInfo().getExtension();
        String path = request == null || request.getResource().getPath() == null ? "" : request.getResource().getPath();

        response.reset();
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        boolean head = request != null && "HEAD".equals(request.getMethod());

        if (status == HttpServletResponse.SC_NOT_FOUND && request != null && req.getAttribute(HANDLING) == null
                && wantsPage(path, extension)) {
            String page = errorPage(request.getResourceResolver(), path, String.valueOf(status));
            if (page != null) {
                req.setAttribute(HANDLING, Boolean.TRUE);
                WCMMode before = WCMMode.DISABLED.toRequest(req);
                try {
                    response.setContentType("text/html;charset=utf-8");
                    RequestDispatcher dispatcher = request.getRequestDispatcher(page + ".html");
                    if (dispatcher != null) {
                        dispatcher.include(request, response);
                        return;
                    }
                } catch (ServletException | IOException | RuntimeException e) {
                    LOG.warn("The error page {} could not be shown: {}", page, e.toString());
                    if (response.isCommitted()) {
                        return;
                    }
                    response.reset();
                    response.setStatus(status);
                } finally {
                    if (before != null) {
                        before.toRequest(req);
                    }
                }
            }
        }
        if ("json".equals(extension)) {
            response.setContentType("application/json;charset=utf-8");
            if (!head) {
                response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + json(message) + "\"}");
            }
            return;
        }
        response.setContentType("text/html;charset=utf-8");
        if (!head) {
            String title = status + " " + reason(status);
            response.getWriter().write("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + title
                    + "</title><meta name=\"robots\" content=\"noindex\"></head><body><h1>" + title + "</h1><p>"
                    + html(message) + "</p></body></html>\n");
        }
    }

    /** A page is what was asked for: {@code .html} or no extension, not a file ({@code .png}, {@code .json}…). */
    static boolean wantsPage(String path, String extension) {
        if (extension != null) {
            return "html".equals(extension) || "htm".equals(extension);
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return !name.contains(".") || name.endsWith(".html");
    }

    /**
     * The nearest {@code errors/<status>} page above the path, or null. A short address ({@code /wknd/us/en/x},
     * as the live site writes its links) is looked up under {@code /content}.
     */
    static String errorPage(ResourceResolver resolver, String path, String status) {
        String p = path;
        int dot = p.indexOf('.', p.lastIndexOf('/') + 1);
        if (dot >= 0) {
            p = p.substring(0, dot);
        }
        if (!p.startsWith("/content/")) {
            p = "/content" + (p.startsWith("/") ? p : "/" + p);
        }
        // not above /content/<site>: one site's error page never answers for another
        for (int cut = p.lastIndexOf('/'); cut > "/content".length(); cut = p.lastIndexOf('/')) {
            p = p.substring(0, cut);
            Resource page = resolver.getResource(p + "/" + ERROR_PAGES + "/" + status);
            if (page != null && page.getChild("jcr:content") != null) {
                return page.getPath();
            }
        }
        return null;
    }

    static String reason(int status) {
        switch (status) {
            case 400: return "Bad request";
            case 401: return "Sign in";
            case 403: return "Not allowed";
            case 404: return "Not found";
            case 405: return "Method not allowed";
            case 409: return "Conflict";
            case 500: return "Something went wrong";
            case 503: return "Not available right now";
            default: return status >= 500 ? "Something went wrong" : "The request was refused";
        }
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
