package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.controller.GlobalExceptionHandler.ErrorResponse;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunRange;
import com.gregochr.goldenhour.service.runs.AdminRunsService;
import com.gregochr.goldenhour.service.runs.RunCursor;
import com.gregochr.goldenhour.service.runs.RunsCalendarService;
import com.gregochr.goldenhour.service.runs.RunsCallsService;
import com.gregochr.goldenhour.service.runs.RunsDayService;
import com.gregochr.goldenhour.service.runs.RunsRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * The merged Runs list for the admin Operations tab: pipeline runs and top-level job runs in one
 * newest-first list with a served group, name, status and note, plus a single job run by id.
 *
 * <p>ADMIN-only. All logic lives in {@link AdminRunsService}; this class only reads and validates the
 * request. An unknown {@code range}, {@code kind}, {@code before} or a non-positive {@code size} is a 400.
 */
@RestController
@RequestMapping("/api/admin/runs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRunsController {

    private final AdminRunsService runsService;
    private final RunsDayService dayService;
    private final RunsCalendarService calendarService;
    private final RunsCallsService callsService;

    /**
     * Constructs the controller.
     *
     * @param runsService     builds the merged list and the job-run detail
     * @param dayService      builds the Day view
     * @param calendarService builds the Day view's calendar month
     * @param callsService    builds a run's external calls
     */
    public AdminRunsController(AdminRunsService runsService, RunsDayService dayService,
            RunsCalendarService calendarService, RunsCallsService callsService) {
        this.runsService = runsService;
        this.dayService = dayService;
        this.calendarService = calendarService;
        this.callsService = callsService;
    }

    /**
     * One page of the merged runs list with its range-scoped aggregates.
     *
     * @param range     TODAY, WEEK (default) or MONTH, aligned to UK days
     * @param kind      ALL (default), CYCLES, BATCHES, TIDE, BRIEFING, AURORA or OTHER
     * @param attention true to keep only DEGRADED, PARTIAL and FAILED rows
     * @param before    the opaque cursor from the previous page's {@code nextCursor}
     * @param size      the page size (default 20, clamped to 100, at least 1)
     * @return the page, or 400 for an unknown value
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "WEEK") String range,
            @RequestParam(defaultValue = "ALL") String kind,
            @RequestParam(defaultValue = "false") boolean attention,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "" + AdminRunsService.DEFAULT_SIZE) int size) {
        if (size < 1) {
            return ResponseEntity.badRequest().body(new ErrorResponse("size must be >= 1"));
        }
        RunRange parsedRange;
        RunGroup parsedKind;
        RunCursor cursor;
        try {
            parsedRange = RunRange.parse(range);
            parsedKind = RunGroup.parseKind(kind);
            cursor = before == null || before.isBlank() ? null : RunCursor.decode(before);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid range, kind or cursor"));
        }
        return ResponseEntity.ok(runsService.list(parsedRange, parsedKind, attention, cursor,
                Math.min(size, AdminRunsService.MAX_SIZE)));
    }

    /**
     * One job run in full, including a cycle's batch run, which never appears at the top level.
     *
     * @param id the job run id
     * @return the run with its served status, group, name, note and batch, or 404
     */
    @GetMapping("/job/{id}")
    public ResponseEntity<?> job(@PathVariable long id) {
        return runsService.jobDetail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * One UK civil day of the Runs screen: its rows in time order, and for today what is still to run.
     *
     * @param date the day as {@code YYYY-MM-DD}; today (UK) when absent
     * @return the day, or 400 for a date that is malformed, in the future, or before the earliest day
     */
    @GetMapping("/day")
    public ResponseEntity<?> day(@RequestParam(required = false) String date) {
        LocalDate parsed;
        try {
            parsed = date == null || date.isBlank() ? null : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date must be YYYY-MM-DD"));
        }
        return ResponseEntity.ok(dayService.day(parsed));
    }

    /**
     * One month of the Day view's calendar.
     *
     * @param month the month as {@code YYYY-MM}
     * @return the month's days, or 400 for a month that is malformed, in the future, or before the earliest day
     */
    @GetMapping("/calendar")
    public ResponseEntity<?> calendar(@RequestParam(required = false) String month) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month == null ? "" : month.trim());
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("month must be YYYY-MM"));
        }
        return ResponseEntity.ok(calendarService.month(parsed));
    }

    /**
     * The external calls of one run, aggregated per service.
     *
     * @param runId {@code cycle-N}, {@code job-N} or {@code eval-N}
     * @return the calls, 400 for an id of another shape, or 404 for an unknown run
     */
    @GetMapping("/calls")
    public ResponseEntity<?> calls(@RequestParam(required = false) String runId) {
        return callsService.calls(runId == null ? null : runId.trim())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Answers a Runs request the service refused (a day or month out of range, a malformed run id) with 400.
     *
     * @param e the refusal
     * @return a 400 with the shared error body
     */
    @ExceptionHandler(RunsRequestException.class)
    public ResponseEntity<ErrorResponse> badRunsRequest(RunsRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    /**
     * Answers a request parameter or path variable of the wrong type (such as {@code size=abc},
     * {@code attention=yes} or {@code /job/abc}) with 400 instead of the global handler's 500. Scoped to
     * this controller only.
     *
     * @param e the binding failure
     * @return a 400 with the shared error body
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badParameter(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Invalid value for " + e.getName()));
    }
}
