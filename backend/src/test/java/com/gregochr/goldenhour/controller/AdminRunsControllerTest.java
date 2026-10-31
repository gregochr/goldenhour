package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.JobRunDto;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunJobDetail;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListResponse;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.model.runs.RunListSummary;
import com.gregochr.goldenhour.model.runs.RunRange;
import com.gregochr.goldenhour.service.runs.RunCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the admin Runs endpoints: the role gate, request validation and 404.
 */
class AdminRunsControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static RunListResponse emptyResponse() {
        return new RunListResponse(List.of(), null, 0,
                new RunListSummary(0, 0, 0, 0, 0, 0, 0, 0L, null, null, 0),
                Map.of("ALL", 0), 0, 0, null, null, false);
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("GET /api/admin/runs is 403 for a non-admin")
    void list_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/runs")).andExpect(status().isForbidden());

        verifyNoInteractions(adminRunsService);
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("GET /api/admin/runs/job/{id} is 403 for a non-admin")
    void job_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/runs/job/1")).andExpect(status().isForbidden());

        verifyNoInteractions(adminRunsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("defaults: WEEK, no kind filter, no attention, first page of 20")
    void list_defaults() throws Exception {
        when(adminRunsService.list(RunRange.WEEK, null, false, null, 20)).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/admin/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("params reach the service parsed; the cursor is decoded; an oversized page is clamped to 100")
    void list_paramsParsed() throws Exception {
        RunCursor cursor = new RunCursor(Instant.parse("2026-10-31T01:00:00Z"),
                RunKind.JOB, 9L);
        ArgumentCaptor<RunCursor> captor = ArgumentCaptor.forClass(RunCursor.class);
        when(adminRunsService.list(eq(RunRange.MONTH), eq(RunGroup.AURORA), eq(true), captor.capture(), eq(100)))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/admin/runs").param("range", "month").param("kind", "aurora")
                        .param("attention", "true").param("before", cursor.encode()).param("size", "500"))
                .andExpect(status().isOk());

        assertThat(captor.getValue()).isEqualTo(cursor);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a bad range, kind, cursor or size is 400 and never reaches the service")
    void list_badParams() throws Exception {
        mockMvc.perform(get("/api/admin/runs").param("range", "YEAR")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid range, kind or cursor"));
        mockMvc.perform(get("/api/admin/runs").param("kind", "NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/runs").param("before", "not-a-cursor")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/runs").param("size", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("size must be >= 1"));
        mockMvc.perform(get("/api/admin/runs").param("size", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get("/api/admin/runs").param("attention", "maybe")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/runs/job/abc")).andExpect(status().isBadRequest());

        verifyNoInteractions(adminRunsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/runs/job/{id} is 404 for a missing job run")
    void job_missing() throws Exception {
        when(adminRunsService.jobDetail(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/runs/job/404")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/runs/job/{id} serves the run with its status, group, name and note")
    void job_found() throws Exception {
        JobRunDto run = new JobRunDto(7L, RunType.TIDE, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        when(adminRunsService.jobDetail(7L)).thenReturn(Optional.of(new RunJobDetail(run, RunListStatus.PARTIAL,
                RunGroup.TIDE, "Tide data", "2 requests errored", NoteTone.WARN, true, null)));

        mockMvc.perform(get("/api/admin/runs/job/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(7))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.group").value("TIDE"))
                .andExpect(jsonPath("$.name").value("Tide data"))
                .andExpect(jsonPath("$.note").value("2 requests errored"))
                .andExpect(jsonPath("$.noteTone").value("WARN"))
                .andExpect(jsonPath("$.triggeredManually").value(true))
                .andExpect(jsonPath("$.batch").isEmpty());
    }
}
