package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.CurrentNight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access and wire format for {@link AuroraNightsController}. The nights service is a real
 * bean over the base class's mocked {@code forecastRunService} and {@code stateCache}.
 */
class AuroraNightsControllerTest extends AbstractControllerTest {

    private static final String PATH = "/api/aurora/nights";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(forecastRunService.currentNight()).thenReturn(
                new CurrentNight(LocalDate.of(2026, 10, 25), Instant.parse("2026-10-26T05:41:00Z")));
        when(forecastRunService.getAvailableDates()).thenReturn(List.of("2026-10-26"));
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.isActive()).thenReturn(true);
        when(stateCache.getCachedScores()).thenReturn(List.of());
    }

    @Test
    @DisplayName("GET /api/aurora/nights returns 200 for LITE_USER with the nights and the night's end")
    @WithMockUser(roles = {"LITE_USER"})
    void getNights_liteUser_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights.length()").value(2))
                .andExpect(jsonPath("$.nights[0]").value("2026-10-25"))
                .andExpect(jsonPath("$.nights[1]").value("2026-10-26"))
                .andExpect(jsonPath("$.currentNightEndsAt").value("2026-10-26T05:41:00Z"));
    }

    @Test
    @DisplayName("GET /api/aurora/nights returns 200 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void getNights_proUser_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/aurora/nights returns 200 for ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void getNights_admin_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/aurora/nights returns 401 for an unauthenticated request")
    void getNights_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/aurora/status still returns 403 for LITE_USER: the new endpoint opens nothing else")
    @WithMockUser(roles = {"LITE_USER"})
    void getStatus_liteUser_stillReturns403() throws Exception {
        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isForbidden());
    }
}
