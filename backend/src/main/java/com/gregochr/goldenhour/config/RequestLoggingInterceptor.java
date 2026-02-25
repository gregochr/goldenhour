package com.gregochr.goldenhour.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

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

        // Wrap response to capture body
        ResponseWrapper wrappedResponse = new ResponseWrapper(response);

        try {
            chain.doFilter(request, wrappedResponse);
        } finally {
            wrappedResponse.flushBuffer();
            long duration = System.currentTimeMillis() - startTime;
            int status = wrappedResponse.getStatus();

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

            String body = wrappedResponse.getResponseBody();
            LOG.info("← {} {} ({} ms)", statusLabel, request.getRequestURI(), duration);
            if (status >= 400 && !body.isEmpty()) {
                LOG.info("    error: {}", body.length() > 200 ? body.substring(0, 200) + "..." : body);
            }
        }
    }

    /**
     * Wrapper to capture response body without consuming it.
     */
    private static class ResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        private ServletOutputStream output;
        private PrintWriter writer;

        ResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                output = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        capture.write(b);
                        getResponse().getOutputStream().write(b);
                    }

                    @Override
                    public void flush() throws IOException {
                        getResponse().getOutputStream().flush();
                    }

                    @Override
                    public void close() throws IOException {
                        getResponse().getOutputStream().close();
                    }

                    @Override
                    public boolean isReady() {
                        try {
                            return getResponse().getOutputStream().isReady();
                        } catch (IOException e) {
                            return false;
                        }
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        try {
                            getResponse().getOutputStream().setWriteListener(listener);
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                };
            }
            return output;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(getOutputStream(), true);
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (output != null) {
                output.flush();
            }
            super.flushBuffer();
        }

        String getResponseBody() {
            return capture.toString();
        }
    }
}
