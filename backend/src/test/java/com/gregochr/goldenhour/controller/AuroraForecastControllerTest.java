package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AuroraForecastPreview;
import com.gregochr.goldenhour.model.AuroraForecastResultDto;
import com.gregochr.goldenhour.model.AuroraForecastRunResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AuroraForecastController} role-based access and endpoint behaviour.
 */
class AuroraForecastControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Role-based access control
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /preview returns 401 for unauthenticated request")
    void preview_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/aurora/forecast/preview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /preview returns 403 for LITE_USER")
    @WithMockUser(roles = "LITE_USER")
    void preview_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/aurora/forecast/preview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /run returns 403 for LITE_USER")
    @WithMockUser(roles = "LITE_USER")
    void run_liteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/aurora/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nights\":[\"2026-03-21\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /results/available-dates returns 403 for LITE_USER")
    @WithMockUser(roles = "LITE_USER")
    void availableDates_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/aurora/forecast/results/available-dates"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // GET /preview
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /preview returns 3 nights for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void preview_admin_returnsThreeNights() throws Exception {
        LocalDate today = LocalDate.of(2026, 3, 21);
        AuroraForecastPreview preview = new AuroraForecastPreview(List.of(
                new AuroraForecastPreview.NightPreview(today, "Tonight — Sat 21 Mar",
                        6.0, "G2", true, "Kp 6 expected 21:00–00:00", 34),
                new AuroraForecastPreview.NightPreview(today.plusDays(1), "Tomorrow — Sun 22 Mar",
                        5.0, "G1", true, "Kp 5 expected 22:00–02:00", 34),
                new AuroraForecastPreview.NightPreview(today.plusDays(2), "Mon 23 Mar",
                        2.0, null, false, "Quiet — Kp 2", 34)), false);
        when(forecastRunService.getPreview()).thenReturn(preview);

        mockMvc.perform(get("/api/aurora/forecast/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights").isArray())
                .andExpect(jsonPath("$.nights.length()").value(3))
                .andExpect(jsonPath("$.nights[0].maxKp").value(6.0))
                .andExpect(jsonPath("$.nights[0].recommended").value(true))
                .andExpect(jsonPath("$.nights[0].gScale").value("G2"))
                .andExpect(jsonPath("$.nights[2].recommended").value(false))
                .andExpect(jsonPath("$.nights[2].gScale").doesNotExist());
    }

    @Test
    @DisplayName("GET /preview returns 200 for PRO_USER")
    @WithMockUser(roles = "PRO_USER")
    void preview_proUser_returns200() throws Exception {
        when(forecastRunService.getPreview()).thenReturn(
                new AuroraForecastPreview(List.of(), false));

        mockMvc.perform(get("/api/aurora/forecast/preview"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // POST /run
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /run returns per-night results for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void run_admin_returnsNightResults() throws Exception {
        LocalDate tonight = LocalDate.of(2026, 3, 21);
        AuroraForecastRunResponse response = new AuroraForecastRunResponse(
                List.of(new AuroraForecastRunResponse.NightResult(
                        tonight, "scored", 12, 5, 6.0, "Embleton Bay ★★★★★")),
                1, "~$0.01");
        when(forecastRunService.runForecast(any())).thenReturn(response);

        mockMvc.perform(post("/api/aurora/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nights\":[\"2026-03-21\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClaudeCalls").value(1))
                .andExpect(jsonPath("$.estimatedCost").value("~$0.01"))
                .andExpect(jsonPath("$.nights[0].status").value("scored"))
                .andExpect(jsonPath("$.nights[0].locationsScored").value(12))
                .andExpect(jsonPath("$.nights[0].locationsTriaged").value(5));
    }

    @Test
    @DisplayName("POST /run returns 200 for PRO_USER")
    @WithMockUser(roles = "PRO_USER")
    void run_proUser_returns200() throws Exception {
        when(forecastRunService.runForecast(any())).thenReturn(
                new AuroraForecastRunResponse(List.of(), 0, "~$0.00"));

        mockMvc.perform(post("/api/aurora/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nights\":[]}"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // GET /results
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /results returns scored locations for a given date")
    @WithMockUser(roles = "ADMIN")
    void results_admin_returnsLocationsForDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 21);
        List<AuroraForecastResultDto> dtos = List.of(
                new AuroraForecastResultDto(1L, "Embleton Bay", 55.5, -1.6, 2,
                        4, "Great conditions", "✓ Geomagnetic: MODERATE", false, null,
                        "MODERATE", 5.5));
        when(forecastRunService.getResultsForDate(date)).thenReturn(dtos);

        mockMvc.perform(get("/api/aurora/forecast/results")
                        .param("date", "2026-03-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Embleton Bay"))
                .andExpect(jsonPath("$[0].stars").value(4))
                .andExpect(jsonPath("$[0].triaged").value(false));
    }

    // -------------------------------------------------------------------------
    // GET /results/available-dates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /results/available-dates returns ISO date strings")
    @WithMockUser(roles = "ADMIN")
    void availableDates_admin_returnsDateList() throws Exception {
        when(forecastRunService.getAvailableDates())
                .thenReturn(List.of("2026-03-21", "2026-03-22"));

        mockMvc.perform(get("/api/aurora/forecast/results/available-dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2026-03-21"))
                .andExpect(jsonPath("$[1]").value("2026-03-22"));
    }
}
