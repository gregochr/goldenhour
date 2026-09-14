package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.exception.RegistrationClosedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientResponseException;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link GlobalExceptionHandler}.
 *
 * <p>Verifies that well-known exception types are translated to the correct HTTP status
 * codes and carry a structured {@code error} field in the response body.
 */
class GlobalExceptionHandlerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(turnstileService.verify(any())).thenReturn(true);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("RestClientResponseException is mapped to 502 Bad Gateway")
    void handleUpstreamError_returns502() throws Exception {
        when(commandFactory.create(any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new RestClientResponseException(
                        "Service Unavailable", 503, "Service Unavailable",
                        null, null, null));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("NoSuchElementException is mapped to 404 Not Found")
    void handleNotFound_returns404() throws Exception {
        when(commandFactory.create(any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("Location not found"));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Location not found"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("IllegalArgumentException is mapped to 400 Bad Request")
    void handleBadRequest_returns400() throws Exception {
        when(commandFactory.create(any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid location coordinates"));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid location coordinates"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Missing required request parameter is mapped to 400 Bad Request")
    void handleMissingParam_returns400() throws Exception {
        // GET /api/forecast/history requires 'from' and 'to' params. ADMIN because the endpoint
        // gained @PreAuthorize ADMIN (2026-08-28): this test is about the missing-param mapping,
        // not the gate, so it must not depend on whether binding or authorization runs first.
        mockMvc.perform(get("/api/forecast/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * An unreadable body is the caller's error, not the server's. It used to fall through to the
     * catch-all and return 500. The body carries a distinctive token so the test can also pin that
     * the response does not echo caller-supplied content back.
     */
    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Unreadable request body is mapped to 400 with a fixed message that echoes nothing")
    void handleUnreadableBody_returns400() throws Exception {
        mockMvc.perform(put("/api/models/optimisation")
                        .contentType("application/json")
                        .content("{\"runType\":\"SHORT_TERM\",\"strategyType\":\"ZZ_ECHO_PROBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body could not be read"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ZZ_ECHO_PROBE"))));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Unexpected RuntimeException is mapped to 500 Internal Server Error")
    void handleUnexpected_returns500() throws Exception {
        when(commandFactory.create(any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new RuntimeException("Unexpected failure"));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("RegistrationClosedException is mapped to 403 Forbidden")
    void handleRegistrationClosed_returns403() throws Exception {
        when(registrationService.register(anyString(), anyString(), anyBoolean()))
                .thenThrow(new RegistrationClosedException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Early access is currently full"));
    }
}
