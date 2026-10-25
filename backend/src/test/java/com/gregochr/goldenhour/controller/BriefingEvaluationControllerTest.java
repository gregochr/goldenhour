package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the surviving endpoints on {@link BriefingEvaluationController}.
 *
 * <p>The SSE evaluation endpoint, GET {@code /cache} and GET {@code /cache/timestamp}
 * were removed in Pass 3.3.3. The merged-view endpoint ({@code /scores}) is covered here for its
 * role shaping (pro-features-gate-plan.md §6): LITE and an unrecognised role lose the potentials
 * and the written why, PRO and ADMIN keep them.
 */
class BriefingEvaluationControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("DELETE /cache?confirm=true clears cache and returns count for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void clearCache_adminWithConfirmReturnsCount() throws Exception {
        when(evaluationService.clearCache()).thenReturn(5);

        mockMvc.perform(delete("/api/briefing/evaluate/cache").param("confirm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(5));

        verify(evaluationService).clearCache();
    }

    @Test
    @DisplayName("DELETE /cache without confirm is rejected and does not clear")
    @WithMockUser(roles = "ADMIN")
    void clearCache_withoutConfirmRejected() throws Exception {
        mockMvc.perform(delete("/api/briefing/evaluate/cache"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Confirmation required"));

        verify(evaluationService, never()).clearCache();
    }

    @Test
    @DisplayName("DELETE /cache denied for PRO_USER")
    @WithMockUser(roles = "PRO_USER")
    void clearCache_proUserDenied() throws Exception {
        mockMvc.perform(delete("/api/briefing/evaluate/cache"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /cache denied for unauthenticated request")
    void clearCache_unauthenticatedDenied() throws Exception {
        mockMvc.perform(delete("/api/briefing/evaluate/cache"))
                .andExpect(status().isUnauthorized());
    }

    private static LocationEvaluationView scored() {
        return new LocationEvaluationView(7L, "Bamburgh", 3L, "North East", LocalDate.of(2026, 10, 25),
                TargetType.SUNSET, LocationEvaluationView.Source.CACHED_EVALUATION, 4,
                "Clear sky over the castle.", 81, 66, null, "msg", "SONNET", null,
                DisplayVerdict.WORTH_IT, null, null, null, null);
    }

    private void stubScores() {
        when(evaluationViewService.forDateRange(any(LocalDate.class), any(LocalDate.class),
                eq(Set.of(TargetType.SUNRISE, TargetType.SUNSET)))).thenReturn(List.of(scored()));
    }

    @Test
    @DisplayName("GET /scores gives LITE the star and the verdict but no potentials and no summary")
    @WithMockUser(roles = "LITE_USER")
    void scores_liteLosesPotentialsAndSummary() throws Exception {
        stubScores();

        mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rating").value(4))
                .andExpect(jsonPath("$[0].displayVerdict").value("WORTH_IT"))
                .andExpect(jsonPath("$[0].triageMessage").value("msg"))
                .andExpect(jsonPath("$[0].locationName").value("Bamburgh"))
                .andExpect(jsonPath("$[0].fierySkyPotential").doesNotExist())
                .andExpect(jsonPath("$[0].goldenHourPotential").doesNotExist())
                .andExpect(jsonPath("$[0].summary").doesNotExist());
    }

    @Test
    @DisplayName("GET /scores gives PRO_USER the potentials and the summary")
    @WithMockUser(roles = "PRO_USER")
    void scores_proKeepsEverything() throws Exception {
        stubScores();

        mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fierySkyPotential").value(81))
                .andExpect(jsonPath("$[0].goldenHourPotential").value(66))
                .andExpect(jsonPath("$[0].summary").value("Clear sky over the castle."));
    }

    @Test
    @DisplayName("GET /scores gives ADMIN the potentials and the summary")
    @WithMockUser(roles = "ADMIN")
    void scores_adminKeepsEverything() throws Exception {
        stubScores();

        mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fierySkyPotential").value(81))
                .andExpect(jsonPath("$[0].summary").value("Clear sky over the castle."));
    }

    @Test
    @DisplayName("GET /scores fails closed: an authenticated caller with no recognised role is served as LITE")
    @WithMockUser(roles = "USER")
    void scores_unrecognisedRoleFailsClosed() throws Exception {
        stubScores();

        mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rating").value(4))
                .andExpect(jsonPath("$[0].fierySkyPotential").doesNotExist())
                .andExpect(jsonPath("$[0].goldenHourPotential").doesNotExist())
                .andExpect(jsonPath("$[0].summary").doesNotExist());
    }

    @Test
    @DisplayName("GET /scores requires authentication")
    void scores_unauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isUnauthorized());
    }
}
