package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.JobRunDto;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunCalendarResponse;
import com.gregochr.goldenhour.model.runs.RunCalendarResponse.CalendarDay;
import com.gregochr.goldenhour.model.runs.RunCallsResponse;
import com.gregochr.goldenhour.model.runs.RunCallsResponse.CallService;
import com.gregochr.goldenhour.model.runs.RunCallsResponse.CallStatus;
import com.gregochr.goldenhour.model.runs.RunDayResponse;
import com.gregochr.goldenhour.model.runs.RunDaySummary;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunJobDetail;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListResponse;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.model.runs.RunListSummary;
import com.gregochr.goldenhour.model.runs.RunRange;
import com.gregochr.goldenhour.service.runs.RunCursor;
import com.gregochr.goldenhour.service.runs.RunsRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

    // ── Day, calendar and calls ───────────────────────────────────────────────

    private static RunDayResponse emptyDay(LocalDate date) {
        return new RunDayResponse(date, false, LocalDate.parse("2026-10-10"), "07:12", "16:49",
                Instant.parse("2026-11-01T07:12:00Z"), Instant.parse("2026-11-01T16:49:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"), null, List.of(),
                new RunDaySummary(null, null, null, 0, 0, 0, 0, 0, 0, null, new BigDecimal("0.00"), true, null,
                        null, null, null, null),
                Map.of("ALL", 0));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("the day, calendar and calls endpoints are 403 for a non-admin")
    void deepEndpointsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/runs/day")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/runs/calendar").param("month", "2026-11")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/runs/calls").param("runId", "job-1")).andExpect(status().isForbidden());

        verifyNoInteractions(runsDayService, runsCalendarService, runsCallsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/runs/day serves the day, or today when no date is given")
    void day_served() throws Exception {
        when(runsDayService.day(LocalDate.parse("2026-11-01"))).thenReturn(emptyDay(LocalDate.parse("2026-11-01")));
        when(runsDayService.day(null)).thenReturn(emptyDay(LocalDate.parse("2026-11-02")));

        mockMvc.perform(get("/api/admin/runs/day").param("date", "2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-01"))
                .andExpect(jsonPath("$.isToday").value(false))
                .andExpect(jsonPath("$.earliestDay").value("2026-10-10"))
                .andExpect(jsonPath("$.sunrise").value("07:12"))
                .andExpect(jsonPath("$.dayStartUtc").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.summary.spendGbp").value(0.0))
                .andExpect(jsonPath("$.filterCounts.ALL").value(0));
        mockMvc.perform(get("/api/admin/runs/day")).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-02"));
        mockMvc.perform(get("/api/admin/runs/day").param("date", " ")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a malformed, future or too-early day is 400 with the reason")
    void day_badRequests() throws Exception {
        when(runsDayService.day(LocalDate.parse("2026-12-25")))
                .thenThrow(new RunsRequestException("date is in the future"));

        mockMvc.perform(get("/api/admin/runs/day").param("date", "yesterday")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("date must be YYYY-MM-DD"));
        mockMvc.perform(get("/api/admin/runs/day").param("date", "2026-13-40")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/runs/day").param("date", "2026-12-25")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("date is in the future"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/runs/calendar serves the month; a bad, missing or out-of-range month is 400")
    void calendar() throws Exception {
        when(runsCalendarService.month(YearMonth.of(2026, 11))).thenReturn(new RunCalendarResponse("2026-11",
                List.of(new CalendarDay(LocalDate.parse("2026-11-01"), 3, new BigDecimal("1.25"), true, 1)),
                new BigDecimal("1.25"), LocalDate.parse("2026-10-10")));
        when(runsCalendarService.month(YearMonth.of(2027, 1)))
                .thenThrow(new RunsRequestException("month is in the future"));

        mockMvc.perform(get("/api/admin/runs/calendar").param("month", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.days[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.days[0].runs").value(3))
                .andExpect(jsonPath("$.days[0].attention").value(1))
                .andExpect(jsonPath("$.maxSpendGbp").value(1.25));
        mockMvc.perform(get("/api/admin/runs/calendar").param("month", "November")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("month must be YYYY-MM"));
        mockMvc.perform(get("/api/admin/runs/calendar")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/runs/calendar").param("month", "2027-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("month is in the future"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/runs/calls serves the services; 404 for an unknown run, 400 for a bad id")
    void calls() throws Exception {
        when(runsCallsService.calls("job-5")).thenReturn(Optional.of(new RunCallsResponse("job-5", true,
                List.of(new CallService("ANTHROPIC", "Anthropic Messages", "Messages · ready answers", 8L, 6500L,
                        null, null, 1_000_000L, new BigDecimal("0.80"), new BigDecimal("1.00"), 3L,
                        CallStatus.ERRORS, "529 overloaded after retries")))));
        when(runsCallsService.calls("job-404")).thenReturn(Optional.empty());
        when(runsCallsService.calls("nonsense"))
                .thenThrow(new RunsRequestException("runId must be cycle-N, job-N or eval-N"));
        when(runsCallsService.calls(null))
                .thenThrow(new RunsRequestException("runId must be cycle-N, job-N or eval-N"));

        mockMvc.perform(get("/api/admin/runs/calls").param("runId", " job-5 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("job-5"))
                .andExpect(jsonPath("$.logged").value(true))
                .andExpect(jsonPath("$.services[0].label").value("Anthropic Messages"))
                .andExpect(jsonPath("$.services[0].status").value("ERRORS"))
                .andExpect(jsonPath("$.services[0].costGbp").value(0.8))
                .andExpect(jsonPath("$.services[0].errorSummary").value("529 overloaded after retries"));
        mockMvc.perform(get("/api/admin/runs/calls").param("runId", "job-404")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/runs/calls").param("runId", "nonsense")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/runs/calls")).andExpect(status().isBadRequest());
    }
}
