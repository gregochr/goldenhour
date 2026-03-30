package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BriefingEvaluationController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BriefingEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BriefingEvaluationService evaluationService;

    @Test
    @DisplayName("SSE endpoint returns text/event-stream content type")
    @WithMockUser(roles = "ADMIN")
    void evaluate_returnsEventStream() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
    }

    @Test
    @DisplayName("LITE_USER gets 403 on SSE endpoint")
    @WithMockUser(roles = "LITE_USER")
    void evaluate_liteUserDenied() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PRO_USER can access SSE endpoint")
    @WithMockUser(roles = "PRO_USER")
    void evaluate_proUserAllowed() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cache endpoint returns cached results")
    @WithMockUser(roles = "ADMIN")
    void cache_returnsCachedScores() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 30);
        BriefingEvaluationResult result = new BriefingEvaluationResult(
                "Bamburgh", 4, 72, 65, "Good conditions");
        when(evaluationService.getCachedScores("Northumberland", date, TargetType.SUNSET))
                .thenReturn(Map.of("Bamburgh", result));

        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Bamburgh.rating").value(4))
                .andExpect(jsonPath("$.Bamburgh.fierySkyPotential").value(72));
    }

    @Test
    @DisplayName("LITE_USER gets 403 on cache endpoint")
    @WithMockUser(roles = "LITE_USER")
    void cache_liteUserDenied() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request gets 401 on SSE endpoint")
    void evaluate_unauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Unauthenticated request gets 401 on cache endpoint")
    void cache_unauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Missing regionName param returns 400")
    @WithMockUser(roles = "ADMIN")
    void evaluate_missingRegionName() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing date param returns 400")
    @WithMockUser(roles = "ADMIN")
    void evaluate_missingDate() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("regionName", "Northumberland")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing targetType param returns 400")
    @WithMockUser(roles = "ADMIN")
    void evaluate_missingTargetType() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Cache returns empty object when no scores cached")
    @WithMockUser(roles = "ADMIN")
    void cache_emptyWhenNothingCached() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 30);
        when(evaluationService.getCachedScores("Northumberland", date, TargetType.SUNSET))
                .thenReturn(Map.of());

        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    @Test
    @DisplayName("PRO_USER can access cache endpoint")
    @WithMockUser(roles = "PRO_USER")
    void cache_proUserAllowed() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 30);
        when(evaluationService.getCachedScores("Northumberland", date, TargetType.SUNSET))
                .thenReturn(Map.of());

        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cache endpoint returns all fields in JSON")
    @WithMockUser(roles = "ADMIN")
    void cache_returnsAllFields() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 30);
        BriefingEvaluationResult result = new BriefingEvaluationResult(
                "Bamburgh", 5, 90, 85, "Spectacular sunset expected");
        when(evaluationService.getCachedScores("Northumberland", date, TargetType.SUNSET))
                .thenReturn(Map.of("Bamburgh", result));

        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Bamburgh.locationName").value("Bamburgh"))
                .andExpect(jsonPath("$.Bamburgh.rating").value(5))
                .andExpect(jsonPath("$.Bamburgh.fierySkyPotential").value(90))
                .andExpect(jsonPath("$.Bamburgh.goldenHourPotential").value(85))
                .andExpect(jsonPath("$.Bamburgh.summary").value("Spectacular sunset expected"));
    }

    @Test
    @DisplayName("Cache endpoint with multiple locations")
    @WithMockUser(roles = "ADMIN")
    void cache_multipleLocations() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 30);
        BriefingEvaluationResult r1 = new BriefingEvaluationResult(
                "Bamburgh", 4, 72, 65, "Good conditions");
        BriefingEvaluationResult r2 = new BriefingEvaluationResult(
                "Dunstanburgh", 3, 50, 45, "Average conditions");
        when(evaluationService.getCachedScores("Northumberland", date, TargetType.SUNSET))
                .thenReturn(Map.of("Bamburgh", r1, "Dunstanburgh", r2));

        mockMvc.perform(get("/api/briefing/evaluate/cache")
                        .param("regionName", "Northumberland")
                        .param("date", "2026-03-30")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Bamburgh.rating").value(4))
                .andExpect(jsonPath("$.Dunstanburgh.rating").value(3));
    }
}
