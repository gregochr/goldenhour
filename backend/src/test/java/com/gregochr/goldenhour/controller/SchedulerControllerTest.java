package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link SchedulerController}.
 */
class SchedulerControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Happy paths — ADMIN role
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/scheduler/jobs returns 200 for ADMIN")
    void listJobs_returns200ForAdmin() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.getAll()).thenReturn(List.of(config));
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobKey").value("tide_refresh"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/scheduler/jobs/{jobKey}/pause returns 200 for ADMIN")
    void pauseJob_returns200ForAdmin() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.PAUSED);
        when(schedulerService.pause("tide_refresh")).thenReturn(config);
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/scheduler/jobs/{jobKey}/resume returns 200 for ADMIN")
    void resumeJob_returns200ForAdmin() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.resume("tide_refresh")).thenReturn(config);
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/scheduler/jobs/{jobKey}/trigger returns 200 for ADMIN")
    void triggerJob_returns200ForAdmin() throws Exception {
        doNothing().when(schedulerService).triggerNow("tide_refresh");

        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"));

        verify(schedulerService).triggerNow("tide_refresh");
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/scheduler/jobs/{jobKey}/schedule returns 200 for ADMIN")
    void updateSchedule_returns200ForAdmin() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("daily_briefing", ScheduleType.CRON,
                "0 0 6,18 * * *", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.updateSchedule(eq("daily_briefing"),
                eq("0 0 6,18 * * *"), any())).thenReturn(config);
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(put("/api/admin/scheduler/jobs/daily_briefing/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 6,18 * * *\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cronExpression").value("0 0 6,18 * * *"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/scheduler/jobs/{jobKey}/schedule returns 400 for invalid cron")
    void updateSchedule_returns400ForInvalidCron() throws Exception {
        when(schedulerService.updateSchedule(eq("tide_refresh"), eq("bad"), any()))
                .thenThrow(new IllegalArgumentException("Invalid cron expression: bad"));

        mockMvc.perform(put("/api/admin/scheduler/jobs/tide_refresh/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST trigger returns 500 when job has no registered target")
    void triggerJob_returns500WhenNoTarget() throws Exception {
        doThrow(new IllegalStateException("No registered target for job: orphan"))
                .when(schedulerService).triggerNow("orphan");

        mockMvc.perform(post("/api/admin/scheduler/jobs/orphan/trigger"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST pause returns 404 when job key does not exist")
    void pauseJob_returns404WhenNotFound() throws Exception {
        when(schedulerService.pause("nonexistent"))
                .thenThrow(new NoSuchElementException());

        mockMvc.perform(post("/api/admin/scheduler/jobs/nonexistent/pause"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST resume returns 404 when job key does not exist")
    void resumeJob_returns404WhenNotFound() throws Exception {
        when(schedulerService.resume("nonexistent"))
                .thenThrow(new NoSuchElementException());

        mockMvc.perform(post("/api/admin/scheduler/jobs/nonexistent/resume"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST trigger returns 404 when job key does not exist")
    void triggerJob_returns404WhenNotFound() throws Exception {
        doThrow(new NoSuchElementException())
                .when(schedulerService).triggerNow("nonexistent");

        mockMvc.perform(post("/api/admin/scheduler/jobs/nonexistent/trigger"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST pause returns 500 when job is DISABLED_BY_CONFIG")
    void pauseJob_returns500WhenDisabledByConfig() throws Exception {
        when(schedulerService.pause("aurora_polling"))
                .thenThrow(new IllegalStateException("disabled by config"));

        mockMvc.perform(post("/api/admin/scheduler/jobs/aurora_polling/pause"))
                .andExpect(status().isInternalServerError());
    }

    // -------------------------------------------------------------------------
    // Auth enforcement
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("All endpoints return 403 for non-admin")
    void allEndpoints_return403ForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/pause"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/resume"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/trigger"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/scheduler/jobs/tide_refresh/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 2 * * MON\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("All endpoints return 401 when unauthenticated")
    void allEndpoints_return401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/pause"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SchedulerJobConfigEntity buildConfig(String jobKey, ScheduleType type,
            String cron, Long fixedDelayMs, SchedulerJobStatus status) {
        return SchedulerJobConfigEntity.builder()
                .id(1L)
                .jobKey(jobKey)
                .displayName(jobKey)
                .description("Test job")
                .scheduleType(type)
                .cronExpression(cron)
                .fixedDelayMs(fixedDelayMs)
                .status(status)
                .build();
    }
}
