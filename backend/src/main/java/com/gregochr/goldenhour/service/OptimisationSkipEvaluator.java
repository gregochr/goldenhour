package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates whether a forecast slot should be skipped based on active optimisation strategies.
 *
 * <p>Evaluation order:
 * <ol>
 *   <li><b>EVALUATE_ALL</b> — if enabled, never skip (return false immediately)</li>
 *   <li><b>FORCE_IMMINENT</b> — if today's event hasn't occurred yet, never skip</li>
 *   <li><b>FORCE_STALE</b> — if latest eval was generated before today, never skip</li>
 *   <li><b>SKIP_EXISTING</b> — if any forecast exists for this slot, skip</li>
 *   <li><b>SKIP_LOW_RATED</b> — if no prior evaluation exists or prior rating &lt; threshold, skip</li>
 *   <li><b>NEXT_EVENT_ONLY</b> — skip if a closer solar event exists for this location</li>
 * </ol>
 *
 * <p>The DB query for the latest evaluation is shared between SKIP_LOW_RATED
 * and FORCE_STALE to avoid duplicate lookups.
 */
@Service
public class OptimisationSkipEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(OptimisationSkipEvaluator.class);

    private final ForecastEvaluationRepository forecastRepository;
    private final SolarService solarService;

    /**
     * Constructs an {@code OptimisationSkipEvaluator}.
     *
     * @param forecastRepository repository for querying existing evaluations
     * @param solarService       service for calculating solar event times
     */
    public OptimisationSkipEvaluator(ForecastEvaluationRepository forecastRepository,
            SolarService solarService) {
        this.forecastRepository = forecastRepository;
        this.solarService = solarService;
    }

    /**
     * Determines whether a forecast slot should be skipped based on active optimisation strategies.
     *
     * @param enabledStrategies the active strategies for the current run type
     * @param location          the location entity (needed for solar time calculations)
     * @param targetDate        the target date
     * @param targetType        SUNRISE or SUNSET
     * @return {@code true} if the slot should be skipped
     */
    public boolean shouldSkip(List<OptimisationStrategyEntity> enabledStrategies,
            LocationEntity location, LocalDate targetDate, TargetType targetType) {

        if (enabledStrategies.isEmpty()) {
            return false;
        }

        Long locationId = location.getId();

        Map<OptimisationStrategyType, OptimisationStrategyEntity> strategyMap =
                enabledStrategies.stream()
                        .collect(Collectors.toMap(
                                OptimisationStrategyEntity::getStrategyType, e -> e));

        Set<OptimisationStrategyType> activeTypes = strategyMap.keySet();

        // 1. EVALUATE_ALL — override everything
        if (activeTypes.contains(OptimisationStrategyType.EVALUATE_ALL)) {
            LOG.debug("EVALUATE_ALL active — evaluating location {} {} on {}",
                    locationId, targetType, targetDate);
            return false;
        }

        // Shared lookup for strategies that need the latest evaluation
        boolean needsLatest = activeTypes.contains(OptimisationStrategyType.SKIP_LOW_RATED)
                || activeTypes.contains(OptimisationStrategyType.FORCE_STALE);

        Optional<ForecastEvaluationEntity> latest = Optional.empty();
        if (needsLatest) {
            latest = forecastRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            locationId, targetDate, targetType);
        }

        // 2. FORCE_IMMINENT — if target date is today, never skip
        if (activeTypes.contains(OptimisationStrategyType.FORCE_IMMINENT)
                && targetDate.equals(LocalDate.now(ZoneOffset.UTC))) {
            LOG.debug("FORCE_IMMINENT active — evaluating location {} {} on {} (today)",
                    locationId, targetType, targetDate);
            return false;
        }

        // 3. FORCE_STALE — if latest eval was from before today, never skip
        if (activeTypes.contains(OptimisationStrategyType.FORCE_STALE) && latest.isPresent()) {
            LocalDate evalDate = latest.get().getForecastRunAt().toLocalDate();
            if (evalDate.isBefore(LocalDate.now(ZoneOffset.UTC))) {
                LOG.debug("FORCE_STALE active — re-evaluating location {} {} on {} (last eval from {})",
                        locationId, targetType, targetDate, evalDate);
                return false;
            }
        }

        // 4. SKIP_EXISTING — skip if any forecast exists
        if (activeTypes.contains(OptimisationStrategyType.SKIP_EXISTING)) {
            List<ForecastEvaluationEntity> existing = forecastRepository
                    .findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                            locationId, targetDate, targetType);
            if (!existing.isEmpty()) {
                LOG.info("SKIP_EXISTING — skipping location {} {} on {} (exists from {})",
                        locationId, targetType, targetDate,
                        existing.getFirst().getForecastRunAt());
                return true;
            }
        }

        // 5. SKIP_LOW_RATED — skip if no prior exists OR prior rating below threshold
        if (activeTypes.contains(OptimisationStrategyType.SKIP_LOW_RATED)) {
            if (latest.isEmpty()) {
                LOG.info("SKIP_LOW_RATED — skipping location {} {} on {} (no prior evaluation)",
                        locationId, targetType, targetDate);
                return true;
            }
            int minRating = strategyMap.get(OptimisationStrategyType.SKIP_LOW_RATED)
                    .getParamValue() != null
                    ? strategyMap.get(OptimisationStrategyType.SKIP_LOW_RATED).getParamValue()
                    : 3;
            Integer rating = latest.get().getRating();
            if (rating != null && rating < minRating) {
                LOG.info("SKIP_LOW_RATED — skipping location {} {} on {} (prior rating {} < {})",
                        locationId, targetType, targetDate, rating, minRating);
                return true;
            }
        }

        // 6. NEXT_EVENT_ONLY — skip if a closer solar event exists for this location
        if (activeTypes.contains(OptimisationStrategyType.NEXT_EVENT_ONLY)) {
            if (shouldSkipForNextEventOnly(location, targetDate, targetType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns {@code true} if this slot is not the single nearest upcoming solar event
     * for the given location. Compares this event's time against today's and tomorrow's
     * sunrise and sunset to find the closest future event.
     */
    private boolean shouldSkipForNextEventOnly(LocationEntity location,
            LocalDate targetDate, TargetType targetType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        double lat = location.getLat();
        double lon = location.getLon();

        // Calculate this slot's event time
        LocalDateTime thisEventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(lat, lon, targetDate)
                : solarService.sunsetUtc(lat, lon, targetDate);

        // Find the nearest upcoming event across today and tomorrow
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate tomorrow = today.plusDays(1);
        LocalDateTime nextEventTime = null;

        for (LocalDate day : List.of(today, tomorrow)) {
            for (LocalDateTime candidate : List.of(
                    solarService.sunriseUtc(lat, lon, day),
                    solarService.sunsetUtc(lat, lon, day))) {
                if (candidate.isAfter(now)
                        && (nextEventTime == null || candidate.isBefore(nextEventTime))) {
                    nextEventTime = candidate;
                }
            }
        }

        if (nextEventTime == null) {
            return false;
        }

        // Skip if this slot's event time is not the nearest
        if (!thisEventTime.equals(nextEventTime)) {
            LOG.info("NEXT_EVENT_ONLY — skipping {} {} on {} for {} (next event at {})",
                    location.getName(), targetType, targetDate, location.getName(), nextEventTime);
            return true;
        }

        return false;
    }
}
