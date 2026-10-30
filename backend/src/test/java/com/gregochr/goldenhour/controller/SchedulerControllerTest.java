package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.service.SolarSchedulePreviewService;
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
    @DisplayName("GET /api/admin/scheduler/jobs reports timeZone: UTC when none is stored, else the stored zone")
    void listJobs_reportsTimeZone() throws Exception {
        SchedulerJobConfigEntity utc = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity london = buildConfig("evening_sunrise_refresh", ScheduleType.CRON,
                "0 0 21 * * *", null, SchedulerJobStatus.ACTIVE);
        london.setTimeZone("Europe/London");
        when(schedulerService.getAll()).thenReturn(List.of(utc, london));

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].timeZone").value("UTC"))
                .andExpect(jsonPath("$[1].timeZone").value("Europe/London"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/scheduler/jobs lists a row with an unrecognised schedule type")
    void listJobs_serialisesUnrecognisedScheduleType() throws Exception {
        SchedulerJobConfigEntity unknown = SchedulerJobConfigEntity.builder()
                .id(7L).jobKey("future_job").displayName("Future").scheduleTypeRaw("LUNAR_PHASE")
                .status(SchedulerJobStatus.ACTIVE).build();
        SchedulerJobConfigEntity known = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.getAll()).thenReturn(List.of(known, unknown));
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].scheduleType").value("CRON"))
                .andExpect(jsonPath("$[1].scheduleType").value("LUNAR_PHASE"))
                .andExpect(jsonPath("$[1].jobKey").value("future_job"));
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

    // -------------------------------------------------------------------------
    // Solar-offset schedule type (solar-relative-refresh-plan P2a)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule-type passes the parsed body to the service and returns the DTO "
            + "with the solar fields")
    void switchScheduleType_returnsSolarDto() throws Exception {
        SchedulerJobConfigEntity solar = SchedulerJobConfigEntity.builder().id(3L)
                .jobKey("near_term_batch_evaluation").displayName("n")
                .scheduleType(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNRISE)
                .solarOffsetMinutes(255).cronExpression("0 0 1 * * *")
                .status(SchedulerJobStatus.ACTIVE).build();
        when(schedulerService.switchScheduleType("near_term_batch_evaluation",
                ScheduleType.SOLAR_OFFSET, SolarEvent.SUNRISE, 255)).thenReturn(solar);
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(put("/api/admin/scheduler/jobs/near_term_batch_evaluation/schedule-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\":\"SOLAR_OFFSET\",\"solarEvent\":\"SUNRISE\","
                                + "\"solarOffsetMinutes\":255}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleType").value("SOLAR_OFFSET"))
                .andExpect(jsonPath("$.solarEvent").value("SUNRISE"))
                .andExpect(jsonPath("$.solarOffsetMinutes").value(255))
                .andExpect(jsonPath("$.cronExpression").value("0 0 1 * * *"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule-type to CRON passes null solar fields")
    void switchScheduleType_toCron() throws Exception {
        SchedulerJobConfigEntity cron = buildConfig("intraday_forecast_refresh", ScheduleType.CRON,
                "0 0 14 * * *", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.switchScheduleType("intraday_forecast_refresh", ScheduleType.CRON,
                null, null)).thenReturn(cron);
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(put("/api/admin/scheduler/jobs/intraday_forecast_refresh/schedule-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\":\"CRON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solarEvent").value(""))
                .andExpect(jsonPath("$.solarOffsetMinutes").value(0));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule-type is 400 for an unknown enum value, a non-string type, and a "
            + "fractional or non-numeric offset, without calling the service")
    void switchScheduleType_returns400ForMalformedBody() throws Exception {
        String url = "/api/admin/scheduler/jobs/near_term_batch_evaluation/schedule-type";
        List<String> bodies = List.of(
                "{\"scheduleType\":\"WEEKLY\"}",
                "{\"scheduleType\":7}",
                "{\"scheduleType\":\"SOLAR_OFFSET\",\"solarEvent\":\"NOON\",\"solarOffsetMinutes\":10}",
                "{\"scheduleType\":\"SOLAR_OFFSET\",\"solarEvent\":\"SUNRISE\","
                        + "\"solarOffsetMinutes\":10.5}",
                "{\"scheduleType\":\"SOLAR_OFFSET\",\"solarEvent\":\"SUNRISE\","
                        + "\"solarOffsetMinutes\":\"10\"}");
        for (String body : bodies) {
            mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(schedulerService, org.mockito.Mockito.never()).switchScheduleType(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule-type maps the service's refusal to 400 and an unknown job to 404")
    void switchScheduleType_mapsServiceErrors() throws Exception {
        when(schedulerService.switchScheduleType(eq("tide_refresh"), eq(ScheduleType.SOLAR_OFFSET),
                any(), any())).thenThrow(new IllegalArgumentException("may not be switched"));
        when(schedulerService.switchScheduleType(eq("ghost"), eq(ScheduleType.CRON), any(), any()))
                .thenThrow(new NoSuchElementException());

        mockMvc.perform(put("/api/admin/scheduler/jobs/tide_refresh/schedule-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\":\"SOLAR_OFFSET\",\"solarEvent\":\"SUNRISE\","
                                + "\"solarOffsetMinutes\":255}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/scheduler/jobs/ghost/schedule-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\":\"CRON\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET solar-preview returns the preview for ADMIN")
    void solarPreview_returnsPreviewForAdmin() throws Exception {
        when(solarSchedulePreviewService.preview()).thenReturn(
                new SolarSchedulePreviewService.Preview(
                        new SolarSchedulePreviewService.ReferencePoint(54.776, -1.575),
                        java.time.Instant.parse("2026-10-13T10:00:00Z"), List.of()));

        mockMvc.perform(get("/api/admin/scheduler/solar-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referencePoint.lat").value(54.776))
                .andExpect(jsonPath("$.referencePoint.lon").value(-1.575))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-13T10:00:00Z"))
                .andExpect(jsonPath("$.jobs.length()").value(0));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("schedule-type and solar-preview are ADMIN only")
    void solarEndpoints_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(put("/api/admin/scheduler/jobs/near_term_batch_evaluation/schedule-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\":\"CRON\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/scheduler/solar-preview")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs carries solarEvent and solarOffsetMinutes: real values for a solar row, "
            + "'' and 0 (never null) for the rest")
    void listJobs_solarFieldsDefaultForNonSolarRows() throws Exception {
        SchedulerJobConfigEntity solar = SchedulerJobConfigEntity.builder().id(3L)
                .jobKey("near_term_batch_evaluation").displayName("n")
                .scheduleType(ScheduleType.SOLAR_OFFSET).solarEvent(SolarEvent.SUNSET)
                .solarOffsetMinutes(240).cronExpression("0 0 1 * * *")
                .status(SchedulerJobStatus.ACTIVE).build();
        SchedulerJobConfigEntity cron = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.getAll()).thenReturn(List.of(solar, cron));
        when(schedulerService.calculateNextFireTime(any())).thenReturn(null);

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].solarEvent").value("SUNSET"))
                .andExpect(jsonPath("$[0].solarOffsetMinutes").value(240))
                .andExpect(jsonPath("$[1].solarEvent").value(""))
                .andExpect(jsonPath("$[1].solarOffsetMinutes").value(0));
    }

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
