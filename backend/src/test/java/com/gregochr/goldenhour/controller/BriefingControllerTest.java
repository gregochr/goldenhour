package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BriefingController}.
 */
class BriefingControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing returns 200 with cached briefing")
    void getBriefing_returnsCachedResult() throws Exception {
        when(briefingService.getCachedBriefing()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Today sunset looks promising in Lake District"))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value("2026-03-25"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].targetType").value("SUNSET"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].regionName")
                        .value("Lake District"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].verdict").value("GO"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing returns 204 when cache is empty")
    void getBriefing_noContent() throws Exception {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/briefing returns 401 without authentication")
    void getBriefing_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/briefing accessible to LITE_USER")
    void getBriefing_liteUser() throws Exception {
        when(briefingService.getCachedBriefing()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("JSON includes slot-level detail")
    void getBriefing_slotDetail() throws Exception {
        when(briefingService.getCachedBriefing()).thenReturn(buildSampleBriefing());

        mockMvc.perform(get("/api/briefing"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].locationName")
                        .value("Keswick"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].verdict")
                        .value("GO"))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].lowCloudPercent")
                        .value(15))
                .andExpect(jsonPath("$.days[0].eventSummaries[0].regions[0].slots[0].flags[0]")
                        .value("Tide aligned"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/briefing/run triggers refresh and returns 200 for ADMIN")
    void runBriefing_adminTriggersRefresh() throws Exception {
        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Briefing refresh complete."));

        verify(briefingService).refreshBriefing();
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/briefing/run returns 403 for non-admin")
    void runBriefing_nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/briefing/run returns 401 without authentication")
    void runBriefing_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/briefing/run"))
                .andExpect(status().isUnauthorized());
    }

    // ── Compare models endpoints ──

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/briefing/compare-models returns 200 for ADMIN")
    void compareModels_adminSuccess() throws Exception {
        BriefingModelTestRunEntity run = BriefingModelTestRunEntity.builder()
                .id(1L).succeeded(3).failed(0).build();
        when(briefingModelTestService.runComparison()).thenReturn(run);

        mockMvc.perform(post("/api/briefing/compare-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.succeeded").value(3));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/briefing/compare-models returns 403 for non-admin")
    void compareModels_nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/briefing/compare-models"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing/compare-models/runs returns recent runs")
    void getComparisonRuns_adminSuccess() throws Exception {
        when(briefingModelTestService.getRecentRuns()).thenReturn(List.of(
                BriefingModelTestRunEntity.builder().id(1L).succeeded(3).failed(0).build()));

        mockMvc.perform(get("/api/briefing/compare-models/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/briefing/compare-models/results returns results for run")
    void getComparisonResults_adminSuccess() throws Exception {
        when(briefingModelTestService.getResults(1L)).thenReturn(List.of(
                BriefingModelTestResultEntity.builder()
                        .id(1L).testRunId(1L).evaluationModel(EvaluationModel.HAIKU)
                        .succeeded(true).createdAt(LocalDateTime.now()).build()));

        mockMvc.perform(get("/api/briefing/compare-models/results").param("runId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].evaluationModel").value("HAIKU"));
    }

    private static DailyBriefingResponse buildSampleBriefing() {
        BriefingSlot slot = new BriefingSlot("Keswick",
                LocalDateTime.of(2026, 3, 25, 18, 30), Verdict.GO,
                new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                        10.5, 8.0, 1, new BigDecimal("3.2")),
                new BriefingSlot.TideInfo("HIGH", true,
                        LocalDateTime.of(2026, 3, 25, 19, 0), new BigDecimal("1.5"),
                        false, false, null, null, null),
                List.of("Tide aligned"));

        BriefingRegion region = new BriefingRegion("Lake District",
                Verdict.GO, "Clear at 1 of 1 location",
                List.of(), List.of(slot), 10.5, 8.0, 3.2, 1);

        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNSET, List.of(region), List.of());

        BriefingDay day = new BriefingDay(
                LocalDate.of(2026, 3, 25), List.of(eventSummary));

        return new DailyBriefingResponse(
                LocalDateTime.of(2026, 3, 25, 14, 0),
                "Today sunset looks promising in Lake District",
                List.of(day), List.of(), null, null, false, false, 0, "Opus");
    }
}
