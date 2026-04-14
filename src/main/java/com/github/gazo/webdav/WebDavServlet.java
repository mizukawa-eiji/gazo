package com.github.gazo.webdav;

import io.milton.http.HttpManager;
import io.milton.http.Request;
import io.milton.http.Response;
import io.milton.servlet.ServletRequest;
import io.milton.servlet.ServletResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Thin servlet that delegates all requests to Milton's HttpManager.
 * Allows us to inject a pre-built HttpManager instead of relying on
 * MiltonServlet's init-param-based configuration.
 */
public class WebDavServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(WebDavServlet.class);

    private final HttpManager httpManager;

    public WebDavServlet(HttpManager httpManager) {
        this.httpManager = httpManager;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            Request request = new ServletRequest(req, getServletContext());
            Response response = new ServletResponse(resp);
            httpManager.process(request, response);
        } finally {
            resp.getOutputStream().flush();
            resp.flushBuffer();
        }
    }

    @Override
    public void destroy() {
        log.info("Shutting down WebDAV servlet");
    }
}
