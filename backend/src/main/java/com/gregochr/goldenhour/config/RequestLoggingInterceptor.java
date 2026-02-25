package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Logs all /api/** endpoint invocations with timing, response status, and error details.
 * Runs at Servlet filter level (before Spring Security) to capture all requests.
 */
@Component
public class RequestLoggingInterceptor implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // Only log /api/** requests
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();
        LOG.info("→ {} {}", request.getMethod(), request.getRequestURI());

        try {
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            int status = response.getStatus();

            String statusLabel;
            if (status >= 200 && status < 300) {
                statusLabel = "✓ " + status;
            } else if (status >= 300 && status < 400) {
                statusLabel = "→ " + status;
            } else if (status >= 400 && status < 500) {
                statusLabel = "⚠ " + status;
            } else {
                statusLabel = "✗ " + status;
            }

            LOG.info("← {} {} ({} ms)", statusLabel, request.getRequestURI(), duration);
        }
    }
}
