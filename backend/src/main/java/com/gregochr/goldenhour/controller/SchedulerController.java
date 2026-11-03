package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.controller.GlobalExceptionHandler.ErrorResponse;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SchedulePreviewService;
import com.gregochr.goldenhour.service.SchedulerDayService;
import com.gregochr.goldenhour.service.SchedulerJobCatalogue;
import com.gregochr.goldenhour.service.SchedulerJobDetailService;
import com.gregochr.goldenhour.service.SchedulerJobOverviewService;
import com.gregochr.goldenhour.service.SolarSchedulePreviewService;
import com.gregochr.goldenhour.service.runs.SchedulerRunsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only REST endpoints for managing dynamically scheduled jobs.
 */
@RestController
@RequestMapping("/api/admin/scheduler")
public class SchedulerController {

    private final DynamicSchedulerService schedulerService;
    private final SolarSchedulePreviewService solarPreviewService;
    private final SchedulerRunsService runsService;
    private final SchedulerJobOverviewService overviewService;
    private final SchedulerJobDetailService detailService;
    private final SchedulerDayService dayService;
    private final SchedulePreviewService previewService;

    /**
     * Constructs the scheduler controller.
     *
     * @param schedulerService    the dynamic scheduler service
     * @param solarPreviewService builds the solar schedule preview
     * @param runsService         the runs a scheduled job can be proved to have written
     * @param overviewService     each job's newest fire, next fires, and the page summary
     * @param detailService       one job's typical duration and Claude spend
     * @param dayService          what every job did, or is expected to do, on one UK day
     * @param previewService      the dry run of a proposed schedule
     */
    public SchedulerController(DynamicSchedulerService schedulerService,
            SolarSchedulePreviewService solarPreviewService, SchedulerRunsService runsService,
            SchedulerJobOverviewService overviewService, SchedulerJobDetailService detailService,
            SchedulerDayService dayService, SchedulePreviewService previewService) {
        this.schedulerService = schedulerService;
        this.solarPreviewService = solarPreviewService;
        this.runsService = runsService;
        this.overviewService = overviewService;
        this.detailService = detailService;
        this.dayService = dayService;
        this.previewService = previewService;
    }

    /**
     * Lists all scheduled jobs with their current status, computed next fire time and, for the five jobs whose
     * runs can be attributed, their last run ({@code lastRun}, a Runs list item; null for every other job).
     *
     * <p>Polled every 30 seconds, so it adds only cheap fields: {@code catalogue} (the plain-English entry, null for
     * a job key the catalogue does not know), {@code lastFire} (the newest {@code scheduler_fire_log} row, read for
     * all jobs by one query; null when the job has never fired) and {@code nextFires} (up to three upcoming fire
     * instants; empty for a paused or off-in-config job).
     *
     * @return list of job detail maps
     */
    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listJobs() {
        List<SchedulerJobConfigEntity> configs = schedulerService.getAll();
        Map<String, RunListItem> lastRuns = runsService.lastRuns(
                configs.stream().map(SchedulerJobConfigEntity::getJobKey).toList());
        Map<String, SchedulerJobOverviewService.LastFire> lastFires = overviewService.lastFires();
        List<Map<String, Object>> result = configs.stream()
                .map(config -> {
                    Map<String, Object> dto = toDto(config);
                    dto.put("lastRun", lastRuns.get(config.getJobKey()));
                    dto.put("catalogue", SchedulerJobCatalogue.find(config.getJobKey()).orElse(null));
                    dto.put("lastFire", lastFires.get(config.getJobKey()));
                    dto.put("nextFires", overviewService.nextFires(config));
                    return dto;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * The page-level counts for the Scheduler header and filters: totals by status and schedule type, how many jobs
     * use Claude, how many last failed, and the soonest non-repeating fire.
     *
     * @return the summary
     */
    @GetMapping("/jobs/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SchedulerJobOverviewService.Summary> jobsSummary() {
        return ResponseEntity.ok(overviewService.summary());
    }

    /**
     * One job's panel-only detail: its typical duration and its Claude spend over the last 30 days. Not polled.
     *
     * @param jobKey the job key
     * @return the detail; 404 for an unknown job
     */
    @GetMapping("/jobs/{jobKey}/detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SchedulerJobDetailService.Detail> jobDetail(@PathVariable String jobKey) {
        return detailService.detail(jobKey).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * What every job did, or is expected to do, on one UK civil day: the Scheduler "Day's jobs" picture.
     *
     * @param date the UK civil date ({@code YYYY-MM-DD}); today when omitted
     * @return the day; 400 for an unparsable date or one more than 7 days from today
     */
    @GetMapping("/day")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SchedulerDayService.Day> day(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(dayService.day(date));
    }

    /**
     * A dry run of a proposed schedule: validates it as saving would, and returns the next fires, the year range for
     * a solar rule and the neighbouring jobs. Never saves.
     *
     * <p>Body: {@code {scheduleType, solarEvent?, solarOffsetMinutes?, times?, daysOfWeek?, timeZone?,
     * fixedDelayMs?}}.
     *
     * @param jobKey the job key
     * @param body   the proposed schedule
     * @return the preview ({@code valid: false} with the message for a proposal saving would refuse); 404 for an
     *         unknown job, 400 for no type or a type the job may not use
     */
    @PostMapping("/jobs/{jobKey}/schedule-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SchedulePreviewService.Preview> schedulePreview(@PathVariable String jobKey,
            @RequestBody SchedulePreviewService.Proposal body) {
        return ResponseEntity.ok(previewService.preview(jobKey, body));
    }

    /**
     * The newest runs of one job, by the Runs list's rules ({@code RunListItem}), for the Scheduler panel's
     * "Recent runs". Only the three forecast cycle jobs, {@code tide_refresh} and
     * {@code aurora_batch_evaluation} have attributable runs; every other known job answers an empty list.
     *
     * @param jobKey the job key
     * @param limit  how many runs (default 3, clamped to 10, at least 1)
     * @return the runs, newest first; 400 for a limit below 1, 404 for an unknown job
     */
    @GetMapping("/jobs/{jobKey}/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> jobRuns(@PathVariable String jobKey,
            @RequestParam(defaultValue = "" + SchedulerRunsService.DEFAULT_LIMIT) int limit) {
        if (limit < 1) {
            return ResponseEntity.badRequest().body(new ErrorResponse("limit must be >= 1"));
        }
        return runsService.recentRunsForJob(jobKey, Math.min(limit, SchedulerRunsService.MAX_LIMIT))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Answers a request parameter of the wrong type (such as {@code limit=abc}) with 400 instead of the global
     * handler's 500. Scoped to this controller only.
     *
     * @param e the binding failure
     * @return a 400 with the shared error body
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badParameter(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Invalid value for " + e.getName()));
    }

    /**
     * Updates the schedule expression for a job.
     *
     * @param jobKey the job key
     * @param body   request body with optional cronExpression, fixedDelayMs, and timeZone ({@code Europe/London} or
     *               null/{@code UTC}; omit it to keep the job's current zone)
     * @return the updated job detail
     */
    @PutMapping("/jobs/{jobKey}/schedule")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateSchedule(
            @PathVariable String jobKey,
            @RequestBody Map<String, Object> body) {
        String cronExpression = (String) body.get("cronExpression");
        Long fixedDelayMs = body.get("fixedDelayMs") != null
                ? ((Number) body.get("fixedDelayMs")).longValue() : null;

        DynamicSchedulerService.ZoneChange zoneChange = null;
        if (body.containsKey("timeZone")) {
            Object rawZone = body.get("timeZone");
            if (rawZone != null && !(rawZone instanceof String)) {
                throw new IllegalArgumentException("timeZone must be a string");
            }
            zoneChange = new DynamicSchedulerService.ZoneChange((String) rawZone);
        }
        SchedulerJobConfigEntity updated = schedulerService.updateSchedule(
                jobKey, cronExpression, fixedDelayMs, zoneChange);
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Switches a forecast cycle job between {@code CRON} and {@code SOLAR_OFFSET}.
     *
     * <p>Body: {@code {scheduleType, solarEvent?, solarOffsetMinutes?}}. A 400 (nothing changed)
     * for an unknown or unsupported type, a job outside the allow-list, missing or out-of-range
     * solar fields, or a stored cron that is not valid.
     *
     * @param jobKey the job key
     * @param body   the request body
     * @return the updated job detail
     */
    @PutMapping("/jobs/{jobKey}/schedule-type")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> switchScheduleType(
            @PathVariable String jobKey,
            @RequestBody Map<String, Object> body) {
        ScheduleType type = parseEnum(ScheduleType.class, body.get("scheduleType"), "scheduleType");
        SolarEvent event = parseEnum(SolarEvent.class, body.get("solarEvent"), "solarEvent");
        Integer offset = null;
        Object rawOffset = body.get("solarOffsetMinutes");
        if (rawOffset != null) {
            if (!(rawOffset instanceof Number number) || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("solarOffsetMinutes must be a whole number");
            }
            offset = number.intValue();
        }
        return ResponseEntity.ok(toDto(
                schedulerService.switchScheduleType(jobKey, type, event, offset)));
    }

    /**
     * The read-only preview of the two forecast cycles' solar rules: today's and tomorrow's event
     * and fire instants, the year's earliest and latest fire, the reference point and the median
     * cycle duration. Computed by the scheduler's own trigger arithmetic.
     *
     * @return the preview
     */
    @GetMapping("/solar-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SolarSchedulePreviewService.Preview> solarPreview() {
        return ResponseEntity.ok(solarPreviewService.preview());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            try {
                return Enum.valueOf(type, text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(field + " is not a recognised value", e);
            }
        }
        throw new IllegalArgumentException(field + " must be a string");
    }

    /**
     * Pauses a scheduled job.
     *
     * @param jobKey the job key
     * @return the updated job detail
     */
    @PostMapping("/jobs/{jobKey}/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> pauseJob(@PathVariable String jobKey) {
        SchedulerJobConfigEntity updated = schedulerService.pause(jobKey);
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Resumes a paused job.
     *
     * @param jobKey the job key
     * @return the updated job detail
     */
    @PostMapping("/jobs/{jobKey}/resume")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> resumeJob(@PathVariable String jobKey) {
        SchedulerJobConfigEntity updated = schedulerService.resume(jobKey);
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Triggers a job to run immediately.
     *
     * @param jobKey the job key
     * @return confirmation message
     */
    @PostMapping("/jobs/{jobKey}/trigger")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> triggerJob(@PathVariable String jobKey) {
        schedulerService.triggerNow(jobKey);
        return ResponseEntity.ok(Map.of("status", "triggered", "jobKey", jobKey));
    }

    /**
     * One job as the API serves it. Mutable, because {@code runGroup} (the Runs group of the job's attributable
     * runs, null when it has none) and the list's {@code lastRun} can be null, which an immutable map refuses.
     */
    private Map<String, Object> toDto(SchedulerJobConfigEntity config) {
        Map<String, Object> dto = new LinkedHashMap<>(baseDto(config));
        dto.put("runGroup", SchedulerRunsService.groupOf(config.getJobKey()).map(Enum::name).orElse(null));
        return dto;
    }

    private Map<String, Object> baseDto(SchedulerJobConfigEntity config) {
        Instant nextFireTime = schedulerService.calculateNextFireTime(config);
        return Map.ofEntries(
                Map.entry("id", config.getId()),
                Map.entry("jobKey", config.getJobKey()),
                Map.entry("displayName", config.getDisplayName()),
                Map.entry("description",
                        config.getDescription() != null ? config.getDescription() : ""),
                Map.entry("scheduleType",
                        config.getScheduleTypeRaw() != null ? config.getScheduleTypeRaw() : ""),
                Map.entry("timeZone",
                        config.getTimeZone() != null && !config.getTimeZone().isBlank()
                                ? config.getTimeZone() : "UTC"),
                Map.entry("cronExpression",
                        config.getCronExpression() != null ? config.getCronExpression() : ""),
                Map.entry("solarEvent",
                        config.getSolarEventRaw() != null ? config.getSolarEventRaw() : ""),
                Map.entry("solarOffsetMinutes",
                        config.getSolarOffsetMinutes() != null
                                ? config.getSolarOffsetMinutes() : 0),
                Map.entry("fixedDelayMs",
                        config.getFixedDelayMs() != null ? config.getFixedDelayMs() : 0),
                Map.entry("initialDelayMs",
                        config.getInitialDelayMs() != null ? config.getInitialDelayMs() : 0),
                Map.entry("status", config.getStatus().name()),
                Map.entry("lastFireTime",
                        config.getLastFireTime() != null
                                ? config.getLastFireTime().toString() : ""),
                Map.entry("lastCompletionTime",
                        config.getLastCompletionTime() != null
                                ? config.getLastCompletionTime().toString() : ""),
                Map.entry("nextFireTime",
                        nextFireTime != null ? nextFireTime.toString() : ""),
                Map.entry("configSource",
                        config.getConfigSource() != null ? config.getConfigSource() : ""),
                Map.entry("updatedAt", config.getUpdatedAt().toString())
        );
    }
}
