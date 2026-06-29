package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.TravelDayResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link TravelDayController}.
 */
class TravelDayControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/travel-days returns 200 with ranges for ADMIN")
    void list_returns200ForAdmin() throws Exception {
        when(travelDayService.list()).thenReturn(List.of(new TravelDayResponse(
                1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), "London")));

        mockMvc.perform(get("/api/admin/travel-days"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].note").value("London"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/travel-days returns 201 for ADMIN")
    void add_returns201ForAdmin() throws Exception {
        when(travelDayService.add(any())).thenReturn(new TravelDayResponse(
                5L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), "London"));

        mockMvc.perform(post("/api/admin/travel-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-03\","
                                + "\"note\":\"London\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/travel-days returns 400 for an inverted range")
    void add_returns400ForInvalidRange() throws Exception {
        when(travelDayService.add(any()))
                .thenThrow(new IllegalArgumentException("endDate must not be before startDate"));

        mockMvc.perform(post("/api/admin/travel-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-07-03\",\"endDate\":\"2026-07-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("DELETE /api/admin/travel-days/{id} returns 204 for ADMIN")
    void delete_returns204ForAdmin() throws Exception {
        doNothing().when(travelDayService).delete(5L);

        mockMvc.perform(delete("/api/admin/travel-days/5"))
                .andExpect(status().isNoContent());

        verify(travelDayService).delete(5L);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("DELETE /api/admin/travel-days/{id} returns 404 when not found")
    void delete_returns404WhenMissing() throws Exception {
        doThrow(new NoSuchElementException()).when(travelDayService).delete(99L);

        mockMvc.perform(delete("/api/admin/travel-days/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("All endpoints return 403 for non-admin")
    void allEndpoints_return403ForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/travel-days"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/travel-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-03\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/travel-days/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Endpoints return 401 when unauthenticated")
    void endpoints_return401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/travel-days"))
                .andExpect(status().isUnauthorized());
    }
}
