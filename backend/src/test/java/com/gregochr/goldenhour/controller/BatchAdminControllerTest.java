package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceResultEntry;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceResultResponse;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceSubmitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BatchAdminController}.
 */
class BatchAdminControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/recent returns 200 with batch list")
    void getRecentBatches_returnsOk() throws Exception {
        ForecastBatchEntity batch = buildBatch("msgbatch_abc123", BatchType.FORECAST);
        when(batchRepository.findTop20ByOrderBySubmittedAtDesc()).thenReturn(List.of(batch));

        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].anthropicBatchId").value("msgbatch_abc123"))
                .andExpect(jsonPath("$[0].batchType").value("FORECAST"))
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/recent returns empty list when no batches")
    void getRecentBatches_emptyList() throws Exception {
        when(batchRepository.findTop20ByOrderBySubmittedAtDesc()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/{id} returns 200 when batch exists")
    void getBatch_found() throws Exception {
        ForecastBatchEntity batch = buildBatch("msgbatch_xyz999", BatchType.AURORA);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));

        mockMvc.perform(get("/api/admin/batches/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anthropicBatchId").value("msgbatch_xyz999"))
                .andExpect(jsonPath("$.batchType").value("AURORA"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/{id} returns 404 when not found")
    void getBatch_notFound() throws Exception {
        when(batchRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/batches/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/batches/recent returns 403 for non-ADMIN")
    void getRecentBatches_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/batches/recent returns 401 when unauthenticated")
    void getRecentBatches_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/recent returns completed batch with correct counts")
    void getRecentBatches_completedBatchHasCounts() throws Exception {
        ForecastBatchEntity batch = buildBatch("msgbatch_done", BatchType.FORECAST);
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setSucceededCount(12);
        batch.setErroredCount(0);
        batch.setEndedAt(Instant.parse("2026-04-06T06:30:00Z"));
        when(batchRepository.findTop20ByOrderBySubmittedAtDesc()).thenReturn(List.of(batch));

        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].succeededCount").value(12))
                .andExpect(jsonPath("$[0].erroredCount").value(0));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/reset-guards returns 200 with reset flags")
    void resetBatchGuards_returnsOkWithFlags() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forecastReset").value(true))
                .andExpect(jsonPath("$.auroraReset").value(true));

        verify(scheduledBatchEvaluationService).resetBatchGuards();
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/admin/batches/reset-guards returns 403 for non-ADMIN")
    void resetBatchGuards_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/batches/reset-guards returns 401 when unauthenticated")
    void resetBatchGuards_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 200 with valid request")
    void forceSubmit_validRequest_returnsOk() throws Exception {
        ForceSubmitResult result = new ForceSubmitResult(
                "msgbatch_force001", 5, "in_progress", 8, 5, 3,
                List.of("LocA", "LocB", "LocC"));
        when(forceSubmitBatchService.forceSubmit(
                eq(7L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("msgbatch_force001"))
                .andExpect(jsonPath("$.requestCount").value(5))
                .andExpect(jsonPath("$.locationsAttempted").value(8))
                .andExpect(jsonPath("$.locationsIncluded").value(5))
                .andExpect(jsonPath("$.locationsFailedData").value(3));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 422 when all data fails")
    void forceSubmit_allFailed_returns422() throws Exception {
        ForceSubmitResult result = new ForceSubmitResult(
                null, 0, null, 3, 0, 3, List.of("A", "B", "C"));
        when(forceSubmitBatchService.forceSubmit(
                eq(7L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.batchId").isEmpty())
                .andExpect(jsonPath("$.locationsFailedData").value(3));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 422 for unknown region")
    void forceSubmit_unknownRegion_returns422() throws Exception {
        when(forceSubmitBatchService.forceSubmit(
                eq(999L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenThrow(new IllegalArgumentException("Region not found: 999"));

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":999,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 400 for invalid event type")
    void forceSubmit_invalidEvent_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/admin/batches/force-submit returns 403 for non-ADMIN")
    void forceSubmit_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/batches/force-submit returns 401 when unauthenticated")
    void forceSubmit_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/force-result/{batchId} returns status")
    void forceResult_returnsStatus() throws Exception {
        ForceResultResponse response = new ForceResultResponse(
                "msgbatch_test", "in_progress", 3, 2, 0, 0, null, 0);
        when(forceSubmitBatchService.getResult("msgbatch_test")).thenReturn(response);

        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("msgbatch_test"))
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.processing").value(3))
                .andExpect(jsonPath("$.succeeded").value(2));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/force-result/{batchId} returns results when ended")
    void forceResult_ended_returnsResults() throws Exception {
        ForceResultEntry entry = new ForceResultEntry(
                "force-test-10-2026-04-16-SUNSET", "succeeded", "{\"rating\":4}");
        ForceResultResponse response = new ForceResultResponse(
                "msgbatch_done", "ended", 0, 1, 0, 0, List.of(entry), 1);
        when(forceSubmitBatchService.getResult("msgbatch_done")).thenReturn(response);

        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ended"))
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.results[0].customId")
                        .value("force-test-10-2026-04-16-SUNSET"))
                .andExpect(jsonPath("$.results[0].responsePreview")
                        .value("{\"rating\":4}"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/batches/force-result returns 403 for non-ADMIN")
    void forceResult_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_test"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/batches/force-result returns 401 when unauthenticated")
    void forceResult_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit passes exact args to service")
    void forceSubmit_passesExactArgsToService() throws Exception {
        ForceSubmitResult result = new ForceSubmitResult(
                "msgbatch_exact", 3, "in_progress", 3, 3, 0, List.of());
        when(forceSubmitBatchService.forceSubmit(
                eq(12L), eq(LocalDate.of(2026, 5, 1)), eq(TargetType.SUNRISE)))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":12,\"date\":\"2026-05-01\",\"event\":\"SUNRISE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("msgbatch_exact"));

        verify(forceSubmitBatchService).forceSubmit(
                eq(12L), eq(LocalDate.of(2026, 5, 1)), eq(TargetType.SUNRISE));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit accepts lowercase event and converts to uppercase")
    void forceSubmit_lowercaseEvent_convertedToUppercase() throws Exception {
        ForceSubmitResult result = new ForceSubmitResult(
                "msgbatch_lc", 1, "in_progress", 1, 1, 0, List.of());
        when(forceSubmitBatchService.forceSubmit(
                eq(7L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"sunset\"}"))
                .andExpect(status().isOk());

        verify(forceSubmitBatchService).forceSubmit(
                eq(7L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns failedLocations in response body")
    void forceSubmit_returnsFailedLocationsArray() throws Exception {
        ForceSubmitResult result = new ForceSubmitResult(
                "msgbatch_fl", 2, "in_progress", 5, 2, 3,
                List.of("Broken A", "Broken B", "Broken C"));
        when(forceSubmitBatchService.forceSubmit(
                eq(7L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedLocations.length()").value(3))
                .andExpect(jsonPath("$.failedLocations[0]").value("Broken A"))
                .andExpect(jsonPath("$.failedLocations[1]").value("Broken B"))
                .andExpect(jsonPath("$.failedLocations[2]").value("Broken C"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns status field in response")
    void forceSubmit_returnsStatusField() throws Exception {
        ForceSubmitResult result = new ForceSubmitResult(
                "msgbatch_st", 1, "in_progress", 1, 1, 0, List.of());
        when(forceSubmitBatchService.forceSubmit(
                eq(7L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"));
    }

    // ── Null field validation → 400 ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 400 when regionId is null")
    void forceSubmit_nullRegionId_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":null,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 400 when date is null")
    void forceSubmit_nullDate_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":null,\"event\":\"SUNSET\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/force-submit returns 400 when event is null")
    void forceSubmit_nullEvent_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":null}"))
                .andExpect(status().isBadRequest());
    }

    // ── LITE_USER access → 403 ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("POST /api/admin/batches/force-submit returns 403 for LITE_USER")
    void forceSubmit_liteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/admin/batches/force-result returns 403 for LITE_USER")
    void forceResult_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_test"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("POST /api/admin/batches/reset-guards returns 403 for LITE_USER")
    void resetBatchGuards_liteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/admin/batches/recent returns 403 for LITE_USER")
    void getRecentBatches_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/admin/batches/{id} returns 403 for LITE_USER")
    void getBatch_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/batches/1"))
                .andExpect(status().isForbidden());
    }

    // ── force-result exact batchId verification ─────────────────────────────

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/force-result passes exact batchId to service")
    void forceResult_passesExactBatchIdToService() throws Exception {
        ForceResultResponse response = new ForceResultResponse(
                "msgbatch_exact99", "in_progress", 1, 0, 0, 0, null, 0);
        when(forceSubmitBatchService.getResult("msgbatch_exact99")).thenReturn(response);

        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_exact99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("msgbatch_exact99"));

        verify(forceSubmitBatchService).getResult("msgbatch_exact99");
    }

    // ── 422 body contains error message from IllegalArgumentException ────────

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST force-submit returns error message in failedLocations on IAE")
    void forceSubmit_illegalArg_errorMessageInBody() throws Exception {
        when(forceSubmitBatchService.forceSubmit(
                eq(999L), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET)))
                .thenThrow(new IllegalArgumentException("Region not found: 999"));

        mockMvc.perform(post("/api/admin/batches/force-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":999,\"date\":\"2026-04-16\",\"event\":\"SUNSET\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.requestCount").value(0))
                .andExpect(jsonPath("$.locationsAttempted").value(0))
                .andExpect(jsonPath("$.failedLocations[0]").value("Region not found: 999"));
    }

    // ── force-result ended response body fields ─────────────────────────────

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/force-result returns cancelled count in body")
    void forceResult_returnsCancelledCount() throws Exception {
        ForceResultResponse response = new ForceResultResponse(
                "msgbatch_canc", "ended", 0, 3, 1, 2, List.of(), 6);
        when(forceSubmitBatchService.getResult("msgbatch_canc")).thenReturn(response);

        mockMvc.perform(get("/api/admin/batches/force-result/msgbatch_canc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(2))
                .andExpect(jsonPath("$.errored").value(1))
                .andExpect(jsonPath("$.totalResults").value(6));
    }

    private ForecastBatchEntity buildBatch(String anthropicBatchId, BatchType type) {
        return new ForecastBatchEntity(anthropicBatchId, type, 5,
                Instant.parse("2026-04-07T06:00:00Z"));
    }
}
