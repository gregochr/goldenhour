package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link StabilityController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StabilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ForecastCommandExecutor forecastCommandExecutor;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/stability/summary returns 200 with summary when snapshot exists")
    void getSummary_returnsSnapshot() throws Exception {
        StabilitySummaryResponse summary = buildSummary();
        when(forecastCommandExecutor.getLatestStabilitySummary()).thenReturn(summary);

        mockMvc.perform(get("/api/admin/stability/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGridCells").value(2))
                .andExpect(jsonPath("$.cells.length()").value(2))
                .andExpect(jsonPath("$.cells[0].gridCellKey").value("54.2500,-1.5000"))
                .andExpect(jsonPath("$.cells[0].stability").value("SETTLED"))
                .andExpect(jsonPath("$.cells[0].evaluationWindowDays").value(3))
                .andExpect(jsonPath("$.cells[0].locationNames[0]").value("Roseberry Topping"))
                .andExpect(jsonPath("$.cells[1].stability").value("UNSETTLED"))
                .andExpect(jsonPath("$.cells[1].evaluationWindowDays").value(0));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/stability/summary returns 204 when no run has completed")
    void getSummary_returns204WhenNoSnapshot() throws Exception {
        when(forecastCommandExecutor.getLatestStabilitySummary()).thenReturn(null);

        mockMvc.perform(get("/api/admin/stability/summary"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/stability/summary returns 403 for non-ADMIN")
    void getSummary_returns403ForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/stability/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/stability/summary returns 401 for unauthenticated")
    void getSummary_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/stability/summary"))
                .andExpect(status().isUnauthorized());
    }

    private StabilitySummaryResponse buildSummary() {
        var cell1 = new StabilitySummaryResponse.GridCellDetail(
                "54.2500,-1.5000", 54.25, -1.50,
                ForecastStability.SETTLED, "Pressure rising — stabilising; Low precip probability",
                3, List.of("Roseberry Topping"));
        var cell2 = new StabilitySummaryResponse.GridCellDetail(
                "54.7500,-1.6250", 54.75, -1.625,
                ForecastStability.UNSETTLED, "Pressure falling 8.2 hPa/24h; Deep low (985 hPa)",
                0, List.of("Durham UK", "Seaham"));
        return new StabilitySummaryResponse(
                Instant.parse("2026-04-06T06:00:00Z"),
                2,
                Map.of(ForecastStability.SETTLED, 1L, ForecastStability.UNSETTLED, 1L),
                List.of(cell1, cell2));
    }
}
