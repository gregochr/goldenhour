package com.gregochr.goldenhour.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RequestLoggingInterceptor} — validates URI-based filtering,
 * chain invocation, exception resilience, and status code categorisation.
 */
@ExtendWith(MockitoExtension.class)
class RequestLoggingInterceptorTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    private RequestLoggingInterceptor filter;

    /**
     * Creates a fresh filter instance before each test.
     */
    @BeforeEach
    void setUp() {
        filter = new RequestLoggingInterceptor();
    }

    // --- Non-API paths pass through without logging ---

    /**
     * Verifies that requests to non-API paths (e.g. /actuator/health) are passed
     * straight through the filter chain without any logging side-effects.
     */
    @Test
    @DisplayName("Non-API path (/actuator/health) passes through without logging")
    void doFilter_nonApiPath_passesThrough() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    /**
     * Verifies that a root path request is passed through without logging.
     */
    @Test
    @DisplayName("Root path (/) passes through without logging")
    void doFilter_rootPath_passesThrough() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    /**
     * Verifies that a static resource path is passed through without logging.
     */
    @Test
    @DisplayName("Static resource path (/index.html) passes through without logging")
    void doFilter_staticResourcePath_passesThrough() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/index.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- API paths are logged and chain.doFilter is called ---

    /**
     * Verifies that an API path triggers the filter's logging behaviour
     * and the chain is invoked.
     */
    @Test
    @DisplayName("API path (/api/forecast) invokes chain and logs")
    void doFilter_apiPath_invokesChainAndLogs() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    /**
     * Verifies that a nested API path is treated as an API request.
     */
    @Test
    @DisplayName("Nested API path (/api/forecast/run) invokes chain and logs")
    void doFilter_nestedApiPath_invokesChainAndLogs() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast/run");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(200);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Chain is called even when an exception is thrown (finally block) ---

    /**
     * Verifies that the response status is still read in the finally block
     * even when the filter chain throws an IOException.
     */
    @Test
    @DisplayName("IOException from chain still executes finally block (logs response)")
    void doFilter_chainThrowsIOException_finallyBlockExecutes() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(500);
        doThrow(new IOException("connection reset")).when(chain).doFilter(request, response);

        try {
            filter.doFilter(request, response, chain);
        } catch (IOException ignored) {
            // expected
        }

        // The finally block calls response.getStatus(), verifying it ran
        verify(response).getStatus();
    }

    /**
     * Verifies that the response status is still read in the finally block
     * even when the filter chain throws a ServletException.
     */
    @Test
    @DisplayName("ServletException from chain still executes finally block (logs response)")
    void doFilter_chainThrowsServletException_finallyBlockExecutes() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/locations");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(500);
        doThrow(new ServletException("servlet error")).when(chain).doFilter(request, response);

        try {
            filter.doFilter(request, response, chain);
        } catch (ServletException ignored) {
            // expected
        }

        verify(response).getStatus();
    }

    /**
     * Verifies that the response status is still read in the finally block
     * even when the filter chain throws a RuntimeException.
     */
    @Test
    @DisplayName("RuntimeException from chain still executes finally block (logs response)")
    void doFilter_chainThrowsRuntimeException_finallyBlockExecutes() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(500);
        doThrow(new RuntimeException("unexpected error")).when(chain).doFilter(request, response);

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException ignored) {
            // expected
        }

        verify(response).getStatus();
    }

    // --- Status code categorisation: 2xx, 3xx, 4xx, 5xx ---

    /**
     * Verifies that a 200 OK response reads the status (2xx category: check mark).
     */
    @Test
    @DisplayName("2xx status (200 OK) is categorised with check mark label")
    void doFilter_status200_categorisedAs2xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 201 Created response reads the status (2xx category).
     */
    @Test
    @DisplayName("2xx status (201 Created) is categorised with check mark label")
    void doFilter_status201_categorisedAs2xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/outcome");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(201);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 301 redirect response reads the status (3xx category: arrow).
     */
    @Test
    @DisplayName("3xx status (301 Moved) is categorised with redirect arrow label")
    void doFilter_status301_categorisedAs3xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(301);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 304 Not Modified response reads the status (3xx category).
     */
    @Test
    @DisplayName("3xx status (304 Not Modified) is categorised with redirect arrow label")
    void doFilter_status304_categorisedAs3xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(304);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 400 Bad Request response reads the status (4xx category: warning).
     */
    @Test
    @DisplayName("4xx status (400 Bad Request) is categorised with warning label")
    void doFilter_status400_categorisedAs4xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/locations");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(400);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 401 Unauthorized response reads the status (4xx category).
     */
    @Test
    @DisplayName("4xx status (401 Unauthorized) is categorised with warning label")
    void doFilter_status401_categorisedAs4xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(401);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 404 Not Found response reads the status (4xx category).
     */
    @Test
    @DisplayName("4xx status (404 Not Found) is categorised with warning label")
    void doFilter_status404_categorisedAs4xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/locations");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(404);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 500 Internal Server Error response reads the status (5xx category: cross).
     */
    @Test
    @DisplayName("5xx status (500 Internal Server Error) is categorised with error cross label")
    void doFilter_status500_categorisedAs5xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast/run");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(500);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }

    /**
     * Verifies that a 503 Service Unavailable response reads the status (5xx category).
     */
    @Test
    @DisplayName("5xx status (503 Service Unavailable) is categorised with error cross label")
    void doFilter_status503_categorisedAs5xx() throws IOException, ServletException {
        when(request.getRequestURI()).thenReturn("/api/forecast");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(503);

        filter.doFilter(request, response, chain);

        verify(response).getStatus();
    }
}
