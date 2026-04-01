package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastRunRequest;
import com.gregochr.goldenhour.model.RunProgress;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.ForecastCommand;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * REST controller for forecast evaluation data.
 *
 * <p>Exposes endpoints to retrieve stored forecasts, query historical data,
 * and trigger on-demand forecast runs. GET endpoints return {@link ForecastEvaluationDto}
 * with role-based score selection (LITE users receive basic scores, PRO/ADMIN receive
 * enhanced directional scores).
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastController.class);

    private final ForecastEvaluationRepository repository;
    private final LocationService locationService;
    private final ForecastCommandFactory commandFactory;
    private final ForecastCommandExecutor commandExecutor;
    private final ScheduledForecastService scheduledForecastService;
    private final ForecastDtoMapper dtoMapper;
    private final JobRunService jobRunService;
    private final RunProgressTracker progressTracker;
    private final Executor forecastExecutor;

    /**
     * Constructs a {@code ForecastController}.
     *
     * @param repository                the forecast evaluation repository
     * @param locationService           the service for persisted locations
     * @param commandFactory            builds forecast commands from run types
     * @param commandExecutor           executes forecast commands
     * @param scheduledForecastService  the scheduled forecast service (for tide refresh)
     * @param dtoMapper                 maps entities to role-aware DTOs
     * @param jobRunService             the service for creating job run entities
     * @param progressTracker           tracks live run progress for SSE broadcasting
     * @param forecastExecutor          the executor used for async forecast runs
     */
    public ForecastController(ForecastEvaluationRepository repository,
            LocationService locationService, ForecastCommandFactory commandFactory,
            ForecastCommandExecutor commandExecutor,
            ScheduledForecastService scheduledForecastService,
            ForecastDtoMapper dtoMapper, JobRunService jobRunService,
            RunProgressTracker progressTracker, Executor forecastExecutor) {
        this.repository = repository;
        this.locationService = locationService;
        this.commandFactory = commandFactory;
        this.commandExecutor = commandExecutor;
        this.scheduledForecastService = scheduledForecastService;
        this.dtoMapper = dtoMapper;
        this.jobRunService = jobRunService;
        this.progressTracker = progressTracker;
        this.forecastExecutor = forecastExecutor;
    }

    /**
     * Returns stored forecast evaluations for all configured locations from today
     * through T+{@value ForecastCommandFactory#FORECAST_HORIZON_DAYS}.
     *
     * <p>Scores are role-aware: LITE users receive basic (observer-point) scores,
     * PRO/ADMIN users receive enhanced (directional) scores.
     *
     * @param auth the current authentication context (injected by Spring Security)
     * @return evaluations ordered by target date and target type
     */
    @GetMapping
    public List<ForecastEvaluationDto> getForecasts(Authentication auth) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(7);
        LocalDate horizon = today.plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);
        var entities = locationService.findAllEnabled().stream()
                .flatMap(loc -> repository.findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        loc.getId(), from, horizon).stream())
                .toList();
        return dtoMapper.toDtoList(entities, isLiteUser(auth));
    }

    /**
     * Returns stored forecast evaluations within a date range.
     *
     * <p>If {@code location} is supplied only that location's evaluations are returned;
     * otherwise evaluations for all configured locations are returned.
     *
     * @param from     start of the date range (inclusive), ISO format {@code yyyy-MM-dd}
     * @param to       end of the date range (inclusive), ISO format {@code yyyy-MM-dd}
     * @param location optional location name filter
     * @param auth     the current authentication context
     * @return evaluations ordered by target date and target type
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    @GetMapping("/history")
    public List<ForecastEvaluationDto> getHistory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String location,
            Authentication auth) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        List<com.gregochr.goldenhour.entity.ForecastEvaluationEntity> entities;
        if (location != null) {
            LocationEntity loc = locationService.findByName(location);
            entities = repository
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            loc.getId(), from, to);
        } else {
            entities = locationService.findAllEnabled().stream()
                    .flatMap(loc -> repository
                            .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                                    loc.getId(), from, to)
                            .stream())
                    .toList();
        }
        return dtoMapper.toDtoList(entities, isLiteUser(auth));
    }

    /**
     * Triggers an on-demand forecast run using the currently active model. Restricted to ADMIN only.
     *
     * <p>Runs the active evaluation model (HAIKU or SONNET) for each location/date combination.
     * This is a recovery mechanism for when scheduled jobs fail.
     *
     * <p>If the request body is absent or its fields are {@code null}, defaults apply:
     * {@code dates} defaults to today only; {@code location} defaults to all configured locations.
     *
     * @param request optional run parameters (dates and/or location)
     * @param maxDays maximum number of days to forecast (optional; if null, uses all dates)
     * @param maxLocations maximum number of locations to process (optional; if null, uses all)
     * @return 202 Accepted with status message
     * @throws IllegalArgumentException if the specified location name is not configured,
     *                                  or if any date string is not a valid ISO date
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runForecast(
            @RequestBody(required = false) ForecastRunRequest request,
            @RequestParam(required = false) Integer maxDays,
            @RequestParam(required = false) Integer maxLocations) {
        // Build dates list with optional limit
        List<LocalDate> allDates = (request != null
                && request.dates() != null
                && !request.dates().isEmpty())
                ? request.dates().stream().map(LocalDate::parse).toList()
                : List.of(LocalDate.now(ZoneOffset.UTC));
        final List<LocalDate> dates = (maxDays != null && maxDays > 0)
                ? allDates.stream().limit(maxDays).toList()
                : allDates;

        // Build locations list with optional limit
        List<LocationEntity> allLocations;
        if (request != null && request.location() != null) {
            allLocations = locationService.findAllEnabled().stream()
                    .filter(l -> l.getName().equals(request.location()))
                    .toList();
            if (allLocations.isEmpty()) {
                throw new IllegalArgumentException(
                        "No configured location named '" + request.location() + "'");
            }
        } else {
            allLocations = locationService.findAllEnabled();
        }
        final List<LocationEntity> locations = (maxLocations != null && maxLocations > 0)
                ? allLocations.stream().limit(maxLocations).toList()
                : allLocations;

        // Build excluded slots from targetType (only evaluate the requested event type)
        Set<String> excludedSlots = Set.of();
        if (request != null && request.targetType() != null) {
            TargetType opposite = request.targetType() == TargetType.SUNRISE
                    ? TargetType.SUNSET : TargetType.SUNRISE;
            excludedSlots = dates.stream()
                    .map(d -> d + "|" + opposite.name())
                    .collect(Collectors.toUnmodifiableSet());
        }

        LOG.info("POST /api/forecast/run — dates={}, location={}, targetType={}, maxDays={}, maxLocations={}",
                dates.size(), request != null ? request.location() : "all",
                request != null ? request.targetType() : "all",
                maxDays, maxLocations);

        ForecastCommand cmd = commandFactory.create(
                RunType.SHORT_TERM, true, locations, dates, excludedSlots);
        JobRunEntity jobRun = jobRunService.startRun(RunType.SHORT_TERM, true, null, null);
        CompletableFuture.runAsync(() -> commandExecutor.execute(cmd, jobRun), forecastExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(buildRunResponse("Forecast run started", "SHORT_TERM", jobRun.getId()));
    }

    /**
     * Triggers an on-demand run of very-short-term forecasts (today, T+1). Restricted to ADMIN only.
     *
     * <p>Uses the model configured under {@code VERY_SHORT_TERM}.
     * An optional body may carry {@code excludedSlots} and/or {@code excludedLocations}.
     *
     * @param request optional slot and location exclusions
     * @return 202 Accepted with status message
     */
    @PostMapping("/run/very-short-term")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runVeryShortTermForecast(
            @RequestBody(required = false) ForecastRunRequest request) {
        LOG.info("POST /api/forecast/run/very-short-term triggered by admin");
        Set<String> excludedSlots = toExcludedSlotKeys(request);
        Set<String> excludedLocations = toExcludedLocationNames(request);
        ForecastCommand cmd = commandFactory.create(RunType.VERY_SHORT_TERM, true, null, null,
                excludedSlots, excludedLocations);
        JobRunEntity jobRun = jobRunService.startRun(RunType.VERY_SHORT_TERM, true, null, null);
        CompletableFuture.runAsync(() -> commandExecutor.execute(cmd, jobRun), forecastExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(buildRunResponse("Forecast run started", "VERY_SHORT_TERM", jobRun.getId()));
    }

    /**
     * Triggers an on-demand run of short-term forecasts (today, T+1, T+2). Restricted to ADMIN only.
     *
     * <p>Uses the model configured under {@code SHORT_TERM}.
     * An optional body may carry {@code excludedSlots} and/or {@code excludedLocations}.
     *
     * @param request optional slot and location exclusions
     * @return 202 Accepted with status message
     */
    @PostMapping("/run/short-term")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runShortTermForecast(
            @RequestBody(required = false) ForecastRunRequest request) {
        LOG.info("POST /api/forecast/run/short-term triggered by admin");
        Set<String> excludedSlots = toExcludedSlotKeys(request);
        Set<String> excludedLocations = toExcludedLocationNames(request);
        ForecastCommand cmd = commandFactory.create(RunType.SHORT_TERM, true, null, null,
                excludedSlots, excludedLocations);
        JobRunEntity jobRun = jobRunService.startRun(RunType.SHORT_TERM, true, null, null);
        CompletableFuture.runAsync(() -> commandExecutor.execute(cmd, jobRun), forecastExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(buildRunResponse("Forecast run started", "SHORT_TERM", jobRun.getId()));
    }

    /**
     * Triggers an on-demand run of long-term forecasts (T+3 through T+5). Restricted to ADMIN only.
     *
     * <p>Uses the model configured under {@code LONG_TERM}.
     *
     * @return 202 Accepted with status message
     */
    @PostMapping("/run/long-term")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> runLongTermForecast() {
        LOG.info("POST /api/forecast/run/long-term triggered by admin");
        ForecastCommand cmd = commandFactory.create(RunType.LONG_TERM, true);
        JobRunEntity jobRun = jobRunService.startRun(RunType.LONG_TERM, true, null, null);
        CompletableFuture.runAsync(() -> commandExecutor.execute(cmd, jobRun), forecastExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(buildRunResponse("Forecast run started", "LONG_TERM", jobRun.getId()));
    }

    /**
     * Triggers a manual refresh of tide extreme data for all coastal locations.
     * Restricted to ADMIN only.
     *
     * <p>Delegates to {@link ScheduledForecastService#refreshTideExtremes()}, which
     * fetches 14 days of high/low extremes from WorldTides and stores them in the
     * {@code tide_extreme} table. The run is tracked as a TIDE {@code JobRunEntity}.
     *
     * @return 202 Accepted with status message
     */
    @PostMapping("/run/tide")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> refreshTideData() {
        LOG.info("POST /api/forecast/run/tide triggered by admin");
        CompletableFuture.runAsync(() -> scheduledForecastService.refreshTideExtremes());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "Tide refresh started", "runType", "TIDE"));
    }

    /**
     * Triggers a backfill of 12 months of historical tide data for all SEASCAPE locations.
     * Restricted to ADMIN only.
     *
     * <p>Fetches in 7-day chunks, skipping ranges where data already exists to avoid
     * duplicate WorldTides API charges. The run is tracked as a TIDE {@code JobRunEntity}.
     *
     * @return 202 Accepted with status message
     */
    @PostMapping("/run/tide/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> backfillTideData() {
        LOG.info("POST /api/forecast/run/tide/backfill triggered by admin");
        CompletableFuture.runAsync(() -> scheduledForecastService.backfillTideExtremes());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "Tide backfill started (12 months, SEASCAPE locations)",
                        "runType", "TIDE"));
    }

    /**
     * Returns all evaluation runs for a specific location, date, and target type.
     *
     * <p>Designed for backtesting — compare how the forecast rating changed across
     * multiple evaluation runs as the target date approached.
     *
     * @param location   the configured location name
     * @param date       the target date, ISO format {@code yyyy-MM-dd}
     * @param targetType SUNRISE or SUNSET
     * @param auth       the current authentication context
     * @return evaluations ordered by forecast_run_at ascending
     */
    @GetMapping("/compare")
    public List<ForecastEvaluationDto> getCompare(
            @RequestParam String location,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam TargetType targetType,
            Authentication auth) {
        LocationEntity loc = locationService.findByName(location);
        var entities = repository.findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                loc.getId(), date, targetType);
        return dtoMapper.toDtoList(entities, isLiteUser(auth));
    }

    /**
     * SSE endpoint streaming live progress for a specific forecast run. ADMIN only.
     *
     * @param runId the job run ID
     * @return an SSE emitter streaming task-update, run-summary, and run-complete events
     */
    @GetMapping(value = "/run/{runId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public SseEmitter getRunProgress(@PathVariable long runId) {
        return progressTracker.subscribe(runId);
    }

    /**
     * SSE endpoint for run-complete notifications. Any authenticated user can subscribe.
     * Fires a single event per completed run (lightweight, no per-location detail).
     *
     * @return an SSE emitter streaming run-complete events
     */
    @GetMapping(value = "/run/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getRunNotifications() {
        return progressTracker.subscribeNotifications();
    }

    /**
     * Retries failed tasks from a previous run. ADMIN only.
     *
     * @param runId the job run ID whose failed tasks to retry
     * @return 202 Accepted with new job run ID, or 404 if the run is not found or has no failures
     */
    @PostMapping("/run/{runId}/retry-failed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> retryFailed(@PathVariable long runId) {
        RunProgress progress = progressTracker.getProgress(runId);
        if (progress == null || progress.getFailedTasks().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var failedTasks = progress.getFailedTasks();
        List<String> locationNames = failedTasks.stream()
                .map(t -> t.locationName())
                .distinct()
                .toList();

        List<LocationEntity> locations = locationService.findAllEnabled().stream()
                .filter(loc -> locationNames.contains(loc.getName()))
                .toList();

        List<LocalDate> dates = failedTasks.stream()
                .map(t -> LocalDate.parse(t.targetDate()))
                .distinct()
                .toList();

        ForecastCommand cmd = commandFactory.create(RunType.SHORT_TERM, true, locations, dates);
        JobRunEntity jobRun = jobRunService.startRun(RunType.SHORT_TERM, true, null, null);
        CompletableFuture.runAsync(() -> commandExecutor.execute(cmd, jobRun), forecastExecutor);

        LOG.info("POST /api/forecast/run/{}/retry-failed — retrying {} failed tasks",
                runId, failedTasks.size());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(buildRunResponse("Retry run started", "SHORT_TERM", jobRun.getId()));
    }

    /**
     * Converts a request's {@code excludedSlots} list into a set of "date|TARGETTYPE" keys
     * for use in {@link ForecastCommand}. Returns an empty set when the request or its
     * excluded slots are null.
     */
    private Set<String> toExcludedSlotKeys(ForecastRunRequest request) {
        if (request == null || request.excludedSlots() == null) {
            return Set.of();
        }
        return request.excludedSlots().stream()
                .map(s -> s.date() + "|" + s.targetType())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Converts a request's {@code excludedLocations} list into an immutable set of location names
     * for use in {@link ForecastCommand}. Returns an empty set when the request or its
     * excluded locations are null.
     */
    private Set<String> toExcludedLocationNames(ForecastRunRequest request) {
        if (request == null || request.excludedLocations() == null) {
            return Set.of();
        }
        return Set.copyOf(request.excludedLocations());
    }

    private Map<String, Object> buildRunResponse(String status, String runType, Long jobRunId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("runType", runType);
        response.put("jobRunId", jobRunId);
        return response;
    }

    private boolean isLiteUser(Authentication auth) {
        if (auth == null) {
            return true;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_LITE_USER"::equals);
    }
}
