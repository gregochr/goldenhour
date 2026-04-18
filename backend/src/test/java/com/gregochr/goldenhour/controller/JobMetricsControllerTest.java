package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link JobMetricsController}.
 */
class JobMetricsControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/metrics/job-runs requires ADMIN role")
    @WithMockUser(roles = "LITE_USER")
    void getJobRuns_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/metrics/job-runs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/metrics/job-runs returns job runs for authenticated ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getJobRuns_returnsJobRunsForAdmin() throws Exception {
        JobRunEntity run = JobRunEntity.builder()
                .id(1L)
                .runType(RunType.SHORT_TERM)
                .startedAt(LocalDateTime.now())
                .succeeded(10)
                .failed(0)
                .build();
        when(jobRunService.getRecentRunsAllTypes(20)).thenReturn(List.of(run));

        mockMvc.perform(get("/api/metrics/job-runs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].runType").value("SHORT_TERM"))
                .andExpect(jsonPath("$.content[0].succeeded").value(10));
    }

    @Test
    @DisplayName("GET /api/metrics/job-runs filters by run type")
    @WithMockUser(roles = "ADMIN")
    void getJobRuns_filtersByRunType() throws Exception {
        JobRunEntity run = JobRunEntity.builder()
                .id(1L)
                .runType(RunType.WEATHER)
                .build();
        when(jobRunService.getRecentRuns(eq(RunType.WEATHER), eq(20))).thenReturn(List.of(run));

        mockMvc.perform(get("/api/metrics/job-runs?runType=WEATHER")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].runType").value("WEATHER"));
    }

    @Test
    @DisplayName("GET /api/metrics/job-runs rejects invalid run type")
    @WithMockUser(roles = "ADMIN")
    void getJobRuns_rejectsInvalidRunType() throws Exception {
        mockMvc.perform(get("/api/metrics/job-runs?runType=INVALID")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/metrics/api-calls returns calls for job run")
    @WithMockUser(roles = "ADMIN")
    void getApiCalls_returnsCallsForJobRun() throws Exception {
        com.gregochr.goldenhour.entity.ApiCallLogEntity call =
                com.gregochr.goldenhour.entity.ApiCallLogEntity.builder()
                        .id(1L)
                        .jobRunId(1L)
                        .service(com.gregochr.goldenhour.entity.ServiceName.ANTHROPIC)
                        .durationMs(250L)
                        .succeeded(true)
                        .build();
        when(jobRunService.getApiCallsForRun(1L)).thenReturn(List.of(call));

        mockMvc.perform(get("/api/metrics/api-calls?jobRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].service").value("ANTHROPIC"))
                .andExpect(jsonPath("$[0].durationMs").value(250));
    }

    @Test
    @DisplayName("GET /api/metrics/api-calls requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void getApiCalls_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/metrics/api-calls?jobRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/metrics/batch-summary returns batch summary for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getBatchSummary_returnsData() throws Exception {
        ForecastBatchEntity batch = new ForecastBatchEntity(
                "msgbatch_test", ForecastBatchEntity.BatchType.FORECAST, 10,
                Instant.now().plusSeconds(86400));
        batch.setTotalInputTokens(50000L);
        batch.setTotalOutputTokens(5000L);
        batch.setTotalCacheReadTokens(40000L);
        batch.setTotalCacheCreationTokens(10000L);
        batch.setEstimatedCostUsd(new BigDecimal("0.025000"));
        batch.setSucceededCount(8);
        batch.setErroredCount(2);
        batch.setStatus(ForecastBatchEntity.BatchStatus.COMPLETED);
        when(jobRunService.getBatchForJobRun(1L)).thenReturn(Optional.of(batch));

        mockMvc.perform(get("/api/metrics/batch-summary?jobRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInputTokens").value(50000))
                .andExpect(jsonPath("$.totalOutputTokens").value(5000))
                .andExpect(jsonPath("$.estimatedCostMicroDollars").value(25000))
                .andExpect(jsonPath("$.succeededCount").value(8))
                .andExpect(jsonPath("$.erroredCount").value(2))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /api/metrics/batch-summary returns 404 when no batch found")
    @WithMockUser(roles = "ADMIN")
    void getBatchSummary_noBatch_returns404() throws Exception {
        when(jobRunService.getBatchForJobRun(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/metrics/batch-summary?jobRunId=999")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/metrics/batch-summary returns zero cost when estimatedCostUsd is null")
    @WithMockUser(roles = "ADMIN")
    void getBatchSummary_nullCost_returnsZeroCostMicroDollars() throws Exception {
        ForecastBatchEntity batch = new ForecastBatchEntity(
                "msgbatch_nocost", ForecastBatchEntity.BatchType.FORECAST, 5,
                Instant.now().plusSeconds(86400));
        batch.setStatus(ForecastBatchEntity.BatchStatus.FAILED);
        // estimatedCostUsd is null (batch failed before token processing)
        when(jobRunService.getBatchForJobRun(7L)).thenReturn(Optional.of(batch));

        mockMvc.perform(get("/api/metrics/batch-summary?jobRunId=7")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedCostMicroDollars").value(0))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.totalInputTokens").isEmpty())
                .andExpect(jsonPath("$.requestCount").value(5));
    }

    @Test
    @DisplayName("GET /api/metrics/batch-summary requires ADMIN role")
    @WithMockUser(roles = "LITE_USER")
    void getBatchSummary_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/metrics/batch-summary?jobRunId=1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
