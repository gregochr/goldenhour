package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastRunRequest;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ScheduledForecastService;
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
import java.util.stream.Stream;

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
    private final ForecastService forecastService;
    private final LocationService locationService;

    /**
     * Constructs a {@code ForecastController}.
     *
     * @param repository      the forecast evaluation repository
     * @param forecastService the service for running forecasts
     * @param locationService the service for persisted locations
     */
    public ForecastController(ForecastEvaluationRepository repository,
            ForecastService forecastService, LocationService locationService) {
        this.repository = repository;
        this.forecastService = forecastService;
        this.locationService = locationService;
    }

    /**
     * Returns stored forecast evaluations for all configured locations from today
     * through T+{@value ScheduledForecastService#FORECAST_HORIZON_DAYS}.
     *
     * <p>LITE_USER receives HAIKU rows (1–5 rating); PRO_USER and ADMIN receive
     * SONNET rows (dual 0–100 scores).
     *
     * @param auth the current authentication context (injected by Spring Security)
     * @return evaluations ordered by target date and target type
     */
    @GetMapping
    public List<ForecastEvaluationEntity> getForecasts(Authentication auth) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate horizon = today.plusDays(ScheduledForecastService.FORECAST_HORIZON_DAYS);
        EvaluationModel model = modelForAuth(auth);
        return locationService.findAll().stream()
                .flatMap(loc -> repository
                        .findByLocationAndDateRangeAndModel(loc.getName(), today, horizon, model)
                        .stream())
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
     * Triggers an on-demand forecast run for both Haiku and Sonnet models. Restricted to ADMIN only.
     *
     * <p>Runs Sonnet (dual 0–100 scores) and Haiku (1–5 rating) for each location/date combination.
     * This is a recovery mechanism for when scheduled jobs fail.
     *
     * <p>If the request body is absent or its fields are {@code null}, defaults apply:
     * {@code dates} defaults to today only; {@code location} defaults to all configured locations.
     *
     * @param request optional run parameters (dates and/or location)
     * @return all saved evaluation entities produced by the run (Sonnet + Haiku)
     * @throws IllegalArgumentException if the specified location name is not configured,
     *                                  or if any date string is not a valid ISO date
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ForecastEvaluationEntity> runForecast(
            @RequestBody(required = false) ForecastRunRequest request) {
        List<LocalDate> dates = (request != null
                && request.dates() != null
                && !request.dates().isEmpty())
                ? request.dates().stream().map(LocalDate::parse).toList()
                : List.of(LocalDate.now(ZoneOffset.UTC));

        List<LocationEntity> locations;
        if (request != null && request.location() != null) {
            locations = locationService.findAll().stream()
                    .filter(l -> l.getName().equals(request.location()))
                    .toList();
            if (locations.isEmpty()) {
                throw new IllegalArgumentException(
                        "No configured location named '" + request.location() + "'");
            }
        } else {
            locations = locationService.findAll();
        }

        TargetType targetType = (request != null) ? request.targetType() : null;

        LOG.info("POST /api/forecast/run — dates={}, location={}, targetType={}",
                dates,
                request != null ? request.location() : null,
                targetType);

        return dates.stream()
                .flatMap(date -> locations.stream()
                        .flatMap(loc -> {
                            List<ForecastEvaluationEntity> sonnetResults = forecastService
                                    .runForecasts(loc.getName(), loc.getLat(), loc.getLon(),
                                            loc.getId(), date, targetType, loc.getTideType(),
                                            EvaluationModel.SONNET);
                            List<ForecastEvaluationEntity> haikuResults = forecastService
                                    .runForecasts(loc.getName(), loc.getLat(), loc.getLon(),
                                            loc.getId(), date, targetType, loc.getTideType(),
                                            EvaluationModel.HAIKU);
                            return Stream.concat(
                                    sonnetResults.stream(), haikuResults.stream());
                        }))
                .toList();
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

    /**
     * Resolves which evaluation model to return for the authenticated user.
     *
     * <p>LITE_USER → HAIKU; PRO_USER and ADMIN → SONNET.
     *
     * @param auth the current authentication context
     * @return the appropriate {@link EvaluationModel}
     */
    private EvaluationModel modelForAuth(Authentication auth) {
        boolean isLite = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_LITE_USER"));
        return isLite ? EvaluationModel.HAIKU : EvaluationModel.SONNET;
    }
}
