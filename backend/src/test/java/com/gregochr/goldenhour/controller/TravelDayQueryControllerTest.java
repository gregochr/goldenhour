package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.TravelDayResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link TravelDayQueryController} — the read-only,
 * any-authenticated overlay endpoint.
 */
class TravelDayQueryControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/travel-days returns 200 for a non-admin authenticated user")
    void list_returns200ForAnyAuthenticatedUser() throws Exception {
        when(travelDayService.list()).thenReturn(List.of(new TravelDayResponse(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), "London")));

        mockMvc.perform(get("/api/travel-days"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].startDate").value("2026-07-01"));
    }

    @Test
    @DisplayName("GET /api/travel-days returns 401 when unauthenticated")
    void list_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/travel-days"))
                .andExpect(status().isUnauthorized());
    }
}
