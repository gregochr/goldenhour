package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Logs all API endpoint invocations with timing and response status.
 */
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingInterceptor.class);
    private static final String START_TIME = "startTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        request.setAttribute(START_TIME, System.currentTimeMillis());
        LOG.info("→ {} {}", request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            ModelAndView modelAndView) throws Exception {
        // Not used
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) throws Exception {
        long startTime = (long) request.getAttribute(START_TIME);
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

        if (ex != null) {
            LOG.error("Exception in {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        }
    }
}
