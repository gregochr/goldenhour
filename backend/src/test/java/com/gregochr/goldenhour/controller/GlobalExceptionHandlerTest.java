package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.ForecastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link GlobalExceptionHandler}.
 *
 * <p>Verifies that well-known exception types are translated to the correct HTTP status
 * codes and carry a structured {@code error} field in the response body.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ForecastService forecastService;

    @Test
    @WithMockUser
    @DisplayName("WebClientResponseException is mapped to 502 Bad Gateway")
    void handleUpstreamError_returns502() throws Exception {
        when(forecastService.runForecasts(anyString(), anyDouble(), anyDouble(),
                any(), any(), any()))
                .thenThrow(WebClientResponseException.create(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Service Unavailable",
                        null, null, null));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("NoSuchElementException is mapped to 404 Not Found")
    void handleNotFound_returns404() throws Exception {
        when(forecastService.runForecasts(anyString(), anyDouble(), anyDouble(),
                any(), any(), any()))
                .thenThrow(new NoSuchElementException("Location not found"));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Location not found"));
    }

    @Test
    @WithMockUser
    @DisplayName("IllegalArgumentException is mapped to 400 Bad Request")
    void handleBadRequest_returns400() throws Exception {
        when(forecastService.runForecasts(anyString(), anyDouble(), anyDouble(),
                any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid location coordinates"));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid location coordinates"));
    }
}
