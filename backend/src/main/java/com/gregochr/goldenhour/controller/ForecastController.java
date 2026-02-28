package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastRunRequest;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.ForecastCommand;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.LocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * REST controller for forecast evaluation data.
 *
 * <p>Exposes endpoints to retrieve stored forecasts, query historical data,
 * and trigger on-demand forecast runs.
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastController.class);

    private final ForecastEvaluationRepository repository;
    private final LocationService locationService;
    private final ForecastCommandFactory commandFactory;
    private final ForecastCommandExecutor commandExecutor;

    /**
     * Constructs a {@code ForecastController}.
     *
     * @param repository       the forecast evaluation repository
     * @param locationService  the service for persisted locations
     * @param commandFactory   builds forecast commands from run types
     * @param commandExecutor  executes forecast commands
     */
    public ForecastController(ForecastEvaluationRepository repository,
            LocationService locationService, ForecastCommandFactory commandFactory,
            ForecastCommandExecutor commandExecutor) {
        this.repository = repository;
        this.locationService = locationService;
        this.commandFactory = commandFactory;
        this.commandExecutor = commandExecutor;
    }

    /**
     * Returns stored forecast evaluations for all configured locations from today
     * through T+{@value ForecastCommandFactory#FORECAST_HORIZON_DAYS}.
     *
     * <p>All evaluations are returned with all fields populated (rating + dual scores).
     * The frontend uses user role to decide what UI cards to display.
     *
     * @param auth the current authentication context (injected by Spring Security)
     * @return evaluations ordered by target date and target type
     */
    @GetMapping
    public List<ForecastEvaluationEntity> getForecasts(Authentication auth) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate horizon = today.plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);
        return locationService.findAll().stream()
                .flatMap(loc -> repository.findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        loc.getName(), today, horizon).stream())
                .toList();
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
     * @return evaluations ordered by target date and target type
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    @GetMapping("/history")
    public List<ForecastEvaluationEntity> getHistory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String location) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (location != null) {
            return repository
                    .findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            location, from, to);
        }
        return locationService.findAll().stream()
                .flatMap(loc -> repository
                        .findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                                loc.getName(), from, to)
                        .stream())
                .toList();
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
     * @return all saved evaluation entities produced by the run (Sonnet + Haiku)
     * @throws IllegalArgumentException if the specified location name is not configured,
     *                                  or if any date string is not a valid ISO date
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ForecastEvaluationEntity> runForecast(
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
            allLocations = locationService.findAll().stream()
                    .filter(l -> l.getName().equals(request.location()))
                    .toList();
            if (allLocations.isEmpty()) {
                throw new IllegalArgumentException(
                        "No configured location named '" + request.location() + "'");
            }
        } else {
            allLocations = locationService.findAll();
        }
        final List<LocationEntity> locations = (maxLocations != null && maxLocations > 0)
                ? allLocations.stream().limit(maxLocations).toList()
                : allLocations;

        LOG.info("POST /api/forecast/run — dates={}, location={}, maxDays={}, maxLocations={}",
                dates.size(), request != null ? request.location() : "all",
                maxDays, maxLocations);

        ForecastCommand cmd = commandFactory.create(RunType.SHORT_TERM, true, locations, dates);
        return commandExecutor.execute(cmd);
    }

    /**
     * Triggers an on-demand run of very-short-term forecasts (today, T+1). Restricted to ADMIN only.
     *
     * <p>Uses the model configured under {@code VERY_SHORT_TERM}.
     *
     * @return all saved evaluation entities produced by the run
     */
    @PostMapping("/run/very-short-term")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ForecastEvaluationEntity> runVeryShortTermForecast() {
        LOG.info("POST /api/forecast/run/very-short-term triggered by admin");
        ForecastCommand cmd = commandFactory.create(RunType.VERY_SHORT_TERM, true);
        return commandExecutor.execute(cmd);
    }

    /**
     * Triggers an on-demand run of short-term forecasts (today, T+1, T+2). Restricted to ADMIN only.
     *
     * <p>Uses the model configured under {@code SHORT_TERM}.
     *
     * @return all saved evaluation entities produced by the run
     */
    @PostMapping("/run/short-term")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ForecastEvaluationEntity> runShortTermForecast() {
        LOG.info("POST /api/forecast/run/short-term triggered by admin");
        ForecastCommand cmd = commandFactory.create(RunType.SHORT_TERM, true);
        return commandExecutor.execute(cmd);
    }

    /**
     * Triggers an on-demand run of long-term forecasts (T+3 through T+7). Restricted to ADMIN only.
     *
     * <p>Uses the model configured under {@code LONG_TERM}.
     *
     * @return all saved evaluation entities produced by the run
     */
    @PostMapping("/run/long-term")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ForecastEvaluationEntity> runLongTermForecast() {
        LOG.info("POST /api/forecast/run/long-term triggered by admin");
        ForecastCommand cmd = commandFactory.create(RunType.LONG_TERM, true);
        return commandExecutor.execute(cmd);
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
     * @return evaluations ordered by forecast_run_at ascending
     */
    @GetMapping("/compare")
    public List<ForecastEvaluationEntity> getCompare(
            @RequestParam String location,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam TargetType targetType) {
        return repository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                location, date, targetType);
    }

}
