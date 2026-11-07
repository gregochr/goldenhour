package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.exception.JobDisabledByConfigException;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SchedulePreviewService;
import com.gregochr.goldenhour.service.SchedulerDayService;
import com.gregochr.goldenhour.service.SchedulerJobDetailService;
import com.gregochr.goldenhour.service.SchedulerJobOverviewService;
import com.gregochr.goldenhour.service.SolarSchedulePreviewService;
import com.gregochr.goldenhour.service.runs.SchedulerJobCostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @DisplayName("GET /api/admin/scheduler/jobs serves every optional field of a fully populated job as stored")
    void listJobs_servesEveryOptionalField() throws Exception {
        java.time.Instant fired = java.time.Instant.parse("2026-11-05T05:00:00Z");
        java.time.Instant completed = java.time.Instant.parse("2026-11-05T05:00:30Z");
        java.time.Instant next = java.time.Instant.parse("2026-11-06T05:00:00Z");
        SchedulerJobConfigEntity full = SchedulerJobConfigEntity.builder()
                .id(9L).jobKey("full_job").displayName("Full job").description("Everything set")
                .scheduleType(ScheduleType.FIXED_DELAY).fixedDelayMs(60_000L).initialDelayMs(5_000L)
                .solarOffsetMinutes(-30).solarEventRaw("SUNRISE").cronExpression("0 0 5 * * *")
                .timeZone("Europe/London").lastFireTime(fired).lastCompletionTime(completed)
                .configSource("ADMIN").status(SchedulerJobStatus.ACTIVE).build();
        when(schedulerService.getAll()).thenReturn(List.of(full));
        when(schedulerService.calculateNextFireTime(full)).thenReturn(next);

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Everything set"))
                .andExpect(jsonPath("$[0].scheduleType").value("FIXED_DELAY"))
                .andExpect(jsonPath("$[0].solarEvent").value("SUNRISE"))
                .andExpect(jsonPath("$[0].solarOffsetMinutes").value(-30))
                .andExpect(jsonPath("$[0].fixedDelayMs").value(60000))
                .andExpect(jsonPath("$[0].initialDelayMs").value(5000))
                .andExpect(jsonPath("$[0].lastFireTime").value("2026-11-05T05:00:00Z"))
                .andExpect(jsonPath("$[0].lastCompletionTime").value("2026-11-05T05:00:30Z"))
                .andExpect(jsonPath("$[0].nextFireTime").value("2026-11-06T05:00:00Z"))
                .andExpect(jsonPath("$[0].configSource").value("ADMIN"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/scheduler/jobs serves empty strings and zeros, not nulls, for a bare job")
    void listJobs_bareJobHasDefaults() throws Exception {
        SchedulerJobConfigEntity bare = SchedulerJobConfigEntity.builder()
                .id(10L).jobKey("bare_job").displayName("Bare job").status(SchedulerJobStatus.ACTIVE).build();
        when(schedulerService.getAll()).thenReturn(List.of(bare));
        when(schedulerService.calculateNextFireTime(bare)).thenReturn(null);

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value(""))
                .andExpect(jsonPath("$[0].scheduleType").value(""))
                .andExpect(jsonPath("$[0].timeZone").value("UTC"))
                .andExpect(jsonPath("$[0].cronExpression").value(""))
                .andExpect(jsonPath("$[0].solarEvent").value(""))
                .andExpect(jsonPath("$[0].solarOffsetMinutes").value(0))
                .andExpect(jsonPath("$[0].fixedDelayMs").value(0))
                .andExpect(jsonPath("$[0].initialDelayMs").value(0))
                .andExpect(jsonPath("$[0].lastFireTime").value(""))
                .andExpect(jsonPath("$[0].lastCompletionTime").value(""))
                .andExpect(jsonPath("$[0].nextFireTime").value(""))
                .andExpect(jsonPath("$[0].configSource").value(""));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/scheduler/jobs/{jobKey}/schedule passes a fixedDelayMs through as a long")
    void updateSchedule_passesFixedDelay() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("aurora_polling", ScheduleType.FIXED_DELAY,
                null, 300_000L, SchedulerJobStatus.ACTIVE);
        when(schedulerService.updateSchedule(eq("aurora_polling"), isNull(), eq(300_000L), isNull()))
                .thenReturn(config);
        when(schedulerService.calculateNextFireTime(config)).thenReturn(null);

        mockMvc.perform(put("/api/admin/scheduler/jobs/aurora_polling/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fixedDelayMs\": 300000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixedDelayMs").value(300000));
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
                eq("0 0 6,18 * * *"), isNull(), isNull())).thenReturn(config);
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
        when(schedulerService.updateSchedule(eq("tide_refresh"), eq("bad"), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("Invalid cron expression: bad"));

        mockMvc.perform(put("/api/admin/scheduler/jobs/tide_refresh/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // PUT /schedule — optional timeZone
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule passes an explicit timeZone to the service as a zone change")
    void updateSchedule_passesTimeZone() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("daily_briefing", ScheduleType.CRON,
                "0 0 6 * * *", null, SchedulerJobStatus.ACTIVE);
        config.setTimeZone("Europe/London");
        when(schedulerService.updateSchedule("daily_briefing", "0 0 6 * * *", null,
                new DynamicSchedulerService.ZoneChange("Europe/London"))).thenReturn(config);

        mockMvc.perform(put("/api/admin/scheduler/jobs/daily_briefing/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\": \"0 0 6 * * *\", \"timeZone\": \"Europe/London\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule with timeZone: null asks for UTC, which is a zone change to null")
    void updateSchedule_explicitNullTimeZoneIsAChange() throws Exception {
        SchedulerJobConfigEntity config = buildConfig("daily_briefing", ScheduleType.CRON,
                "0 0 6 * * *", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.updateSchedule("daily_briefing", null, null,
                new DynamicSchedulerService.ZoneChange(null))).thenReturn(config);

        mockMvc.perform(put("/api/admin/scheduler/jobs/daily_briefing/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("UTC"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule returns 400 for a timeZone the service refuses")
    void updateSchedule_returns400ForInvalidTimeZone() throws Exception {
        when(schedulerService.updateSchedule("daily_briefing", null, null,
                new DynamicSchedulerService.ZoneChange("Mars/Olympus")))
                .thenThrow(new IllegalArgumentException("timeZone must be Europe/London or UTC"));

        mockMvc.perform(put("/api/admin/scheduler/jobs/daily_briefing/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\": \"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("PUT schedule returns 400 for a timeZone that is not a string, and never reaches the service")
    void updateSchedule_returns400ForNonStringTimeZone() throws Exception {
        mockMvc.perform(put("/api/admin/scheduler/jobs/daily_briefing/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\": 5}"))
                .andExpect(status().isBadRequest());
        verify(schedulerService, never()).updateSchedule("daily_briefing", null, null,
                new DynamicSchedulerService.ZoneChange(null));
    }

    // -------------------------------------------------------------------------
    // GET /day and POST /schedule-preview
    // -------------------------------------------------------------------------

    private static SchedulerDayService.Day dayOf(LocalDate date, boolean today, long failedRuns) {
        Instant start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return new SchedulerDayService.Day(date, today, Instant.parse("2027-03-05T12:00:00Z"), start,
                start.plusSeconds(86_400), "GMT", "GMT", null, null, List.of(),
                new SchedulerDayService.RepeatingTally(failedRuns, 0), List.of(), List.of());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /day with no date asks the service for today and serves its day")
    void day_defaultsToToday() throws Exception {
        when(schedulerDayService.day(null)).thenReturn(dayOf(LocalDate.of(2027, 3, 5), true, 0));

        mockMvc.perform(get("/api/admin/scheduler/day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2027-03-05"))
                .andExpect(jsonPath("$.isToday").value(true))
                .andExpect(jsonPath("$.zoneLabelAtStart").value("GMT"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /day?date= parses the UK civil date")
    void day_parsesDate() throws Exception {
        when(schedulerDayService.day(LocalDate.of(2027, 3, 4))).thenReturn(dayOf(LocalDate.of(2027, 3, 4), false, 2));

        mockMvc.perform(get("/api/admin/scheduler/day").param("date", "2027-03-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isToday").value(false))
                .andExpect(jsonPath("$.repeatingTally.failedRuns").value(2));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /day returns 400 for an unparsable date, and never reaches the service")
    void day_returns400ForUnparsableDate() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/day").param("date", "yesterday"))
                .andExpect(status().isBadRequest());
        verify(schedulerDayService, never()).day(null);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /day returns 400 when the service refuses a date outside today +/- 7 days")
    void day_returns400ForOutOfRangeDate() throws Exception {
        when(schedulerDayService.day(LocalDate.of(2027, 4, 1)))
                .thenThrow(new IllegalArgumentException("date must be within 7 days of today (2027-03-05)"));

        mockMvc.perform(get("/api/admin/scheduler/day").param("date", "2027-04-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("date must be within 7 days of today (2027-03-05)"));
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("GET /day is forbidden to a non-admin")
    void day_forbiddenToNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/day")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /schedule-preview hands the body to the service and serves its preview")
    void schedulePreview_servesPreview() throws Exception {
        SchedulePreviewService.Proposal proposal = new SchedulePreviewService.Proposal(ScheduleType.CRON, null,
                null, List.of("06:15", "18:15"), List.of("MON"), "Europe/London", null);
        when(schedulePreviewService.preview("tide_refresh", proposal)).thenReturn(
                new SchedulePreviewService.Preview(true, null, "0 15 6,18 * * MON", "Europe/London",
                        List.of(Instant.parse("2027-03-08T06:15:00Z")), null, List.of()));

        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/schedule-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\": \"CRON\", \"times\": [\"06:15\", \"18:15\"], "
                                + "\"daysOfWeek\": [\"MON\"], \"timeZone\": \"Europe/London\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.cronExpression").value("0 15 6,18 * * MON"))
                .andExpect(jsonPath("$.nextFires[0]").value("2027-03-08T06:15:00Z"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /schedule-preview returns 404 for an unknown job and 400 for a refused type")
    void schedulePreview_errors() throws Exception {
        SchedulePreviewService.Proposal solar = new SchedulePreviewService.Proposal(ScheduleType.SOLAR_OFFSET,
                SolarEvent.SUNSET, 240, null, null, null, null);
        when(schedulePreviewService.preview("ghost", solar)).thenThrow(new NoSuchElementException("ghost"));
        when(schedulePreviewService.preview("tide_refresh", solar))
                .thenThrow(new IllegalArgumentException("Job 'tide_refresh' may not be switched to SOLAR_OFFSET"));
        String body = "{\"scheduleType\": \"SOLAR_OFFSET\", \"solarEvent\": \"SUNSET\", "
                + "\"solarOffsetMinutes\": 240}";

        mockMvc.perform(post("/api/admin/scheduler/jobs/ghost/schedule-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/schedule-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /schedule-preview returns 400 for an unrecognised schedule type")
    void schedulePreview_returns400ForUnknownType() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/schedule-preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scheduleType\": \"LUNAR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("POST /schedule-preview is forbidden to a non-admin")
    void schedulePreview_forbiddenToNonAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/scheduler/jobs/tide_refresh/schedule-preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scheduleType\": \"CRON\"}"))
                .andExpect(status().isForbidden());
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

    // -------------------------------------------------------------------------
    // Runs redesign R4: lastRun, runGroup, recent runs, 409 on a job switched off in config
    // -------------------------------------------------------------------------

    private static RunListItem runItem(long id) {
        return new RunListItem(RunKind.CYCLE, id, RunGroup.CYCLES, "Overnight cycle",
                Instant.parse("2026-11-01T01:12:00Z"), Instant.parse("2026-11-01T03:30:00Z"), 8280L,
                RunListStatus.DEGRADED, "Briefing skipped", NoteTone.WARN, 1_000_000L, 0.79, 3, 3, null, null, null,
                CycleType.NIGHTLY, 3, null, null, null);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs carries lastRun and runGroup: set for a mapped job, null (never absent) for the rest")
    void listJobs_carriesLastRunAndRunGroup() throws Exception {
        SchedulerJobConfigEntity nightly = buildConfig("near_term_batch_evaluation", ScheduleType.CRON,
                "0 0 1 * * *", null, SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity polling = buildConfig("aurora_polling", ScheduleType.FIXED_DELAY,
                null, 300000L, SchedulerJobStatus.ACTIVE);
        when(schedulerService.getAll()).thenReturn(List.of(nightly, polling));
        when(schedulerRunsService.lastRuns(List.of("near_term_batch_evaluation", "aurora_polling")))
                .thenReturn(Map.of("near_term_batch_evaluation", runItem(412L)));

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runGroup").value("CYCLES"))
                .andExpect(jsonPath("$[0].lastRun.id").value(412))
                .andExpect(jsonPath("$[0].lastRun.kind").value("CYCLE"))
                .andExpect(jsonPath("$[0].lastRun.status").value("DEGRADED"))
                .andExpect(jsonPath("$[1].runGroup").value(nullValue()))
                .andExpect(jsonPath("$[1].lastRun").value(nullValue()))
                .andExpect(jsonPath("$[1]", hasKey("lastRun")))
                .andExpect(jsonPath("$[1]", hasKey("runGroup")));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/runs serves the service's runs, three by default")
    void jobRuns_defaultsToThree() throws Exception {
        when(schedulerRunsService.recentRunsForJob("tide_refresh", 3))
                .thenReturn(Optional.of(List.of(runItem(7L), runItem(6L))));

        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[1].id").value(6));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/runs honours a limit up to 10 and clamps anything larger to 10")
    void jobRuns_limitIsClampedToTheMaximum() throws Exception {
        when(schedulerRunsService.recentRunsForJob("tide_refresh", 5)).thenReturn(Optional.of(List.of()));
        when(schedulerRunsService.recentRunsForJob("tide_refresh", 10)).thenReturn(Optional.of(List.of()));

        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs?limit=5")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs?limit=10")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs?limit=500")).andExpect(status().isOk());

        verify(schedulerRunsService).recentRunsForJob("tide_refresh", 5);
        verify(schedulerRunsService, times(2)).recentRunsForJob("tide_refresh", 10);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/runs refuses a limit below 1 or not a number with 400")
    void jobRuns_badLimitIs400() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs?limit=0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs?limit=-2"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs?limit=abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/runs answers 404 for a job the scheduler does not know")
    void jobRuns_unknownJobIs404() throws Exception {
        when(schedulerRunsService.recentRunsForJob("nonexistent", 3)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/scheduler/jobs/nonexistent/runs"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET jobs/{key}/runs is forbidden to a non-admin")
    void jobRuns_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/runs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST trigger answers 409, not 500, for a job switched off in config; pause and resume likewise")
    void disabledByConfigIs409() throws Exception {
        JobDisabledByConfigException off = new JobDisabledByConfigException(
                "Job 'aurora_polling' is disabled by config (photocast.aurora.enabled)");
        doThrow(off).when(schedulerService).triggerNow("aurora_polling");
        when(schedulerService.pause("aurora_polling")).thenThrow(off);
        when(schedulerService.resume("aurora_polling")).thenThrow(off);

        mockMvc.perform(post("/api/admin/scheduler/jobs/aurora_polling/trigger"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(off.getMessage()));
        mockMvc.perform(post("/api/admin/scheduler/jobs/aurora_polling/pause"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/scheduler/jobs/aurora_polling/resume"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // Scheduler redesign S3a: catalogue, lastFire, nextFires, summary, detail
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs carries the catalogue entry, the newest fire and the next fires, reading the log once")
    void listJobs_carriesCatalogueLastFireAndNextFires() throws Exception {
        SchedulerJobConfigEntity tide = buildConfig("tide_refresh", ScheduleType.CRON,
                "0 0 2 * * MON", null, SchedulerJobStatus.ACTIVE);
        SchedulerJobConfigEntity dormant = buildConfig("dormant_account_sweep", ScheduleType.CRON,
                "0 15 10 * * *", null, SchedulerJobStatus.PAUSED);
        when(schedulerService.getAll()).thenReturn(List.of(tide, dormant));
        Instant fired = Instant.parse("2027-03-05T02:00:00Z");
        when(schedulerJobOverviewService.lastFires()).thenReturn(Map.of("tide_refresh",
                new SchedulerJobOverviewService.LastFire(fired, fired.plusSeconds(35), "PARTIAL",
                        "40 of 41 seascape locations", "MANUAL")));
        when(schedulerJobOverviewService.nextFires(tide)).thenReturn(List.of(Instant.parse("2027-03-08T02:00:00Z"),
                Instant.parse("2027-03-15T02:00:00Z")));
        when(schedulerJobOverviewService.nextFires(dormant)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].catalogue.alias").value("Weekly tide tables"))
                .andExpect(jsonPath("$[0].catalogue.purpose").value("Places"))
                .andExpect(jsonPath("$[0].catalogue.services[0]").value("WorldTides"))
                .andExpect(jsonPath("$[0].catalogue.usesClaude").value(false))
                .andExpect(jsonPath("$[0].catalogue.watchedBy.length()").value(0))
                .andExpect(jsonPath("$[0].lastFire.status").value("PARTIAL"))
                .andExpect(jsonPath("$[0].lastFire.summary").value("40 of 41 seascape locations"))
                .andExpect(jsonPath("$[0].lastFire.trigger").value("MANUAL"))
                .andExpect(jsonPath("$[0].lastFire.firedAt").value("2027-03-05T02:00:00Z"))
                .andExpect(jsonPath("$[0].lastFire.completedAt").value("2027-03-05T02:00:35Z"))
                .andExpect(jsonPath("$[0].nextFires[0]").value("2027-03-08T02:00:00Z"))
                .andExpect(jsonPath("$[0].nextFires.length()").value(2))
                .andExpect(jsonPath("$[1].lastFire").value(nullValue()))
                .andExpect(jsonPath("$[1]", hasKey("lastFire")))
                .andExpect(jsonPath("$[1].nextFires.length()").value(0));
        verify(schedulerJobOverviewService, times(1)).lastFires();
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs serves catalogue null, not an error, for a job key the catalogue does not know")
    void listJobs_unknownJobHasNullCatalogue() throws Exception {
        SchedulerJobConfigEntity handAdded = buildConfig("hand_added_job", ScheduleType.CRON,
                "0 0 5 * * *", null, SchedulerJobStatus.ACTIVE);
        when(schedulerService.getAll()).thenReturn(List.of(handAdded));

        mockMvc.perform(get("/api/admin/scheduler/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobKey").value("hand_added_job"))
                .andExpect(jsonPath("$[0].catalogue").value(nullValue()))
                .andExpect(jsonPath("$[0]", hasKey("catalogue")));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/summary serves the counts and the next scheduled job")
    void jobsSummary_servesCounts() throws Exception {
        when(schedulerJobOverviewService.summary()).thenReturn(new SchedulerJobOverviewService.Summary(28,
                Map.of("ACTIVE", 27, "PAUSED", 1, "DISABLED_BY_CONFIG", 0),
                Map.of("SOLAR_OFFSET", 2, "CRON", 19, "FIXED_DELAY", 7), 5, 1,
                new SchedulerJobOverviewService.NextScheduled("astro_conditions_refresh", "Astro Conditions",
                        Instant.parse("2027-03-05T15:30:00Z"))));

        mockMvc.perform(get("/api/admin/scheduler/jobs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(28))
                .andExpect(jsonPath("$.byStatus.PAUSED").value(1))
                .andExpect(jsonPath("$.byType.SOLAR_OFFSET").value(2))
                .andExpect(jsonPath("$.usesClaude").value(5))
                .andExpect(jsonPath("$.lastFireFailed").value(1))
                .andExpect(jsonPath("$.nextScheduled.jobKey").value("astro_conditions_refresh"))
                .andExpect(jsonPath("$.nextScheduled.displayName").value("Astro Conditions"))
                .andExpect(jsonPath("$.nextScheduled.at").value("2027-03-05T15:30:00Z"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/summary serves nextScheduled null when nothing is left to run")
    void jobsSummary_nullNextScheduled() throws Exception {
        when(schedulerJobOverviewService.summary()).thenReturn(new SchedulerJobOverviewService.Summary(0,
                Map.of(), Map.of(), 0, 0, null));

        mockMvc.perform(get("/api/admin/scheduler/jobs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextScheduled").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("nextScheduled")));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/detail serves the duration and the Claude cost")
    void jobDetail_servesDurationAndCost() throws Exception {
        when(schedulerJobDetailService.detail("near_term_batch_evaluation")).thenReturn(Optional.of(
                new SchedulerJobDetailService.Detail("near_term_batch_evaluation", 1260L, "runs",
                        new SchedulerJobCostService.ClaudeCost(new BigDecimal("41.20"), true, 30,
                                List.of("batches", "briefing", "askReady"), true, new BigDecimal("1.37"), null))));

        mockMvc.perform(get("/api/admin/scheduler/jobs/near_term_batch_evaluation/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobKey").value("near_term_batch_evaluation"))
                .andExpect(jsonPath("$.typicalDurationSeconds").value(1260))
                .andExpect(jsonPath("$.typicalDurationBasis").value("runs"))
                .andExpect(jsonPath("$.claudeCost30d.gbp").value(41.2))
                .andExpect(jsonPath("$.claudeCost30d.complete").value(true))
                .andExpect(jsonPath("$.claudeCost30d.runs").value(30))
                .andExpect(jsonPath("$.claudeCost30d.includes[2]").value("askReady"))
                .andExpect(jsonPath("$.claudeCost30d.inclManual").value(true))
                .andExpect(jsonPath("$.claudeCost30d.perRun").value(1.37))
                .andExpect(jsonPath("$.claudeCost30d.reason").value(nullValue()));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/detail serves nulls for a job with no history and no Claude use")
    void jobDetail_nullsForQuietJob() throws Exception {
        when(schedulerJobDetailService.detail("tide_refresh")).thenReturn(
                Optional.of(new SchedulerJobDetailService.Detail("tide_refresh", null, null, null)));

        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typicalDurationSeconds").value(nullValue()))
                .andExpect(jsonPath("$.typicalDurationBasis").value(nullValue()))
                .andExpect(jsonPath("$.claudeCost30d").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("claudeCost30d")));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET jobs/{key}/detail is 404 for an unknown job")
    void jobDetail_unknownJobIs404() throws Exception {
        when(schedulerJobDetailService.detail("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/scheduler/jobs/nope/detail"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("the summary and detail endpoints return 403 for a non-admin")
    void newEndpoints_return403ForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs/summary"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/detail"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the summary and detail endpoints return 401 when unauthenticated")
    void newEndpoints_return401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs/summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/scheduler/jobs/tide_refresh/detail"))
                .andExpect(status().isUnauthorized());
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
