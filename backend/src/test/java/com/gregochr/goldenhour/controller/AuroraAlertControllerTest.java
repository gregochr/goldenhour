package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.model.CurrentNight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access and wire format for {@link AuroraAlertController}. The alert service is a real bean
 * over the base class's mocked {@code forecastRunService} and {@code stateCache}.
 */
class AuroraAlertControllerTest extends AbstractControllerTest {

    private static final String PATH = "/api/aurora/alert";
    private static final int FIELD_COUNT = 6;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(forecastRunService.currentNight()).thenReturn(
                new CurrentNight(LocalDate.of(2026, 10, 25), Instant.parse("2026-10-26T05:41:00Z")));
        when(stateCache.getSimulatedData()).thenReturn(null);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.isActive()).thenReturn(true);
        when(stateCache.getLastTriggerType()).thenReturn(TriggerType.FORECAST_LOOKAHEAD);
        when(stateCache.getDarkSkyLocationCount()).thenReturn(4);
        when(stateCache.getClearLocationCount()).thenReturn(2);
    }

    @Test
    @DisplayName("GET /api/aurora/alert returns 200 for LITE_USER with exactly the role-free fields")
    @WithMockUser(roles = {"LITE_USER"})
    void getAlert_liteUser_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("MODERATE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.triggerType").value("forecast"))
                .andExpect(jsonPath("$.allOvercast").value(false))
                .andExpect(jsonPath("$.currentNightDate").value("2026-10-25"))
                .andExpect(jsonPath("$.currentNightEndsAt").value("2026-10-26T05:41:00Z"))
                .andExpect(jsonPath("$.length()").value(FIELD_COUNT));
    }

    @Test
    @DisplayName("GET /api/aurora/alert returns 200 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void getAlert_proUser_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("MODERATE"));
    }

    @Test
    @DisplayName("GET /api/aurora/alert returns 200 for ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void getAlert_admin_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("MODERATE"));
    }

    @Test
    @DisplayName("GET /api/aurora/alert returns 401 for an unauthenticated request")
    void getAlert_unauthenticated_returns401() throws Exception {
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
