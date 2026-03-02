package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link JobMetricsController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRunService jobRunService;

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
}
