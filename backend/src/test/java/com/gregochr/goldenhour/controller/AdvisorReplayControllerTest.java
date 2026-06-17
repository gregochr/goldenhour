package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.Confidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AdvisorReplayController} — wiring and admin gating of the advisor
 * replay harness. The model is mocked via {@code bestBetAdvisor}; these assert the controller
 * resolves the rollup and prompts correctly and is ADMIN-gated, not live model output.
 */
class AdvisorReplayControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String CURRENT_PROMPT = "CURRENT SYSTEM PROMPT";
    private static final String ROLLUP = "{\"validEvents\":[],\"validRegions\":[],\"events\":[]}";

    private BestBet pick(String region) {
        return new BestBet(1, "Headline", "Detail", "2026-06-18_sunset", region,
                Confidence.HIGH, null, null, null, null);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Supplied rollup + candidate prompt returns both pick-sets side by side")
    void suppliedRollupWithCandidate_returnsTwoPickSets() throws Exception {
        when(bestBetAdvisor.currentSystemPrompt()).thenReturn(CURRENT_PROMPT);
        when(bestBetAdvisor.replayWithPrompt(ROLLUP, CURRENT_PROMPT, EvaluationModel.HAIKU))
                .thenReturn(BestBetResult.withPicks(List.of(pick("Northumberland"))));
        when(bestBetAdvisor.replayWithPrompt(ROLLUP, "CANDIDATE", EvaluationModel.HAIKU))
                .thenReturn(BestBetResult.withPicks(List.of(pick("The Lake District"))));

        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollupJson\":" + asJsonString(ROLLUP)
                                + ",\"candidatePrompt\":\"CANDIDATE\",\"model\":\"HAIKU\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("HAIKU"))
                .andExpect(jsonPath("$.current.status").value("SUCCESS_WITH_PICKS"))
                .andExpect(jsonPath("$.current.picks[0].region").value("Northumberland"))
                .andExpect(jsonPath("$.candidate.picks[0].region").value("The Lake District"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Supplied rollup without candidate prompt runs the current prompt only")
    void suppliedRollupNoCandidate_returnsCurrentOnly() throws Exception {
        when(bestBetAdvisor.currentSystemPrompt()).thenReturn(CURRENT_PROMPT);
        when(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                .thenReturn(EvaluationModel.OPUS);
        when(bestBetAdvisor.replayWithPrompt(ROLLUP, CURRENT_PROMPT, EvaluationModel.OPUS))
                .thenReturn(BestBetResult.withPicks(List.of(pick("Northumberland"))));

        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollupJson\":" + asJsonString(ROLLUP) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("OPUS"))
                .andExpect(jsonPath("$.current.picks[0].region").value("Northumberland"))
                .andExpect(jsonPath("$.candidate").isEmpty());

        // Only the current prompt was run — no candidate call.
        verify(bestBetAdvisor, never()).replayWithPrompt(eq(ROLLUP), eq("CANDIDATE"), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Captured rollup is resolved from api_call_log.request_body and replayed")
    void capturedRollupById_resolvedFromRequestBody() throws Exception {
        ApiCallLogEntity row = ApiCallLogEntity.builder().requestBody(ROLLUP).build();
        when(apiCallLogRepository.findById(99L)).thenReturn(Optional.of(row));
        when(bestBetAdvisor.currentSystemPrompt()).thenReturn(CURRENT_PROMPT);
        when(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                .thenReturn(EvaluationModel.OPUS);
        when(bestBetAdvisor.replayWithPrompt(ROLLUP, CURRENT_PROMPT, EvaluationModel.OPUS))
                .thenReturn(BestBetResult.withPicks(List.of(pick("Northumberland"))));

        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiCallLogId\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.picks[0].region").value("Northumberland"));

        // The captured request_body was the rollup replayed.
        verify(bestBetAdvisor).replayWithPrompt(ROLLUP, CURRENT_PROMPT, EvaluationModel.OPUS);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Pre-capture row (null request_body) returns 400, not a null pointer")
    void preCaptureRowNullBody_returns400() throws Exception {
        ApiCallLogEntity row = ApiCallLogEntity.builder().requestBody(null).build();
        when(apiCallLogRepository.findById(5L)).thenReturn(Optional.of(row));

        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiCallLogId\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Unknown api_call_log id returns 404")
    void unknownApiCallLogId_returns404() throws Exception {
        when(apiCallLogRepository.findById(7L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiCallLogId\":7}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Neither rollupJson nor apiCallLogId returns 400")
    void neitherSource_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"HAIKU\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("Non-admin is forbidden")
    void nonAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/admin/advisor-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollupJson\":" + asJsonString(ROLLUP) + "}"))
                .andExpect(status().isForbidden());
    }

    /** Quotes and escapes a string as a JSON string literal for embedding in a request body. */
    private static String asJsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
