package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
 * </ol>
 *
 * <p>The DB query for the latest evaluation is shared between SKIP_LOW_RATED
 * and FORCE_STALE to avoid duplicate lookups.
 */
@Service
public class OptimisationSkipEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(OptimisationSkipEvaluator.class);

    private final ForecastEvaluationRepository forecastRepository;

    /**
     * Constructs an {@code OptimisationSkipEvaluator}.
     *
     * @param forecastRepository repository for querying existing evaluations
     */
    public OptimisationSkipEvaluator(ForecastEvaluationRepository forecastRepository) {
        this.forecastRepository = forecastRepository;
    }

    /**
     * Determines whether a forecast slot should be skipped based on active optimisation strategies.
     *
     * @param enabledStrategies the active strategies for the current run type
     * @param locationName      the location name
     * @param targetDate        the target date
     * @param targetType        SUNRISE or SUNSET
     * @return {@code true} if the slot should be skipped
     */
    public boolean shouldSkip(List<OptimisationStrategyEntity> enabledStrategies,
            String locationName, LocalDate targetDate, TargetType targetType) {

        if (enabledStrategies.isEmpty()) {
            return false;
        }

        Map<OptimisationStrategyType, OptimisationStrategyEntity> strategyMap =
                enabledStrategies.stream()
                        .collect(Collectors.toMap(
                                OptimisationStrategyEntity::getStrategyType, e -> e));

        Set<OptimisationStrategyType> activeTypes = strategyMap.keySet();

        // 1. EVALUATE_ALL — override everything
        if (activeTypes.contains(OptimisationStrategyType.EVALUATE_ALL)) {
            LOG.debug("EVALUATE_ALL active — evaluating {} {} on {}",
                    locationName, targetType, targetDate);
            return false;
        }

        // Shared lookup for strategies that need the latest evaluation
        boolean needsLatest = activeTypes.contains(OptimisationStrategyType.SKIP_LOW_RATED)
                || activeTypes.contains(OptimisationStrategyType.FORCE_STALE);

        Optional<ForecastEvaluationEntity> latest = Optional.empty();
        if (needsLatest) {
            latest = forecastRepository
                    .findTopByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            locationName, targetDate, targetType);
        }

        // 2. FORCE_IMMINENT — if target date is today, never skip
        if (activeTypes.contains(OptimisationStrategyType.FORCE_IMMINENT)
                && targetDate.equals(LocalDate.now(ZoneOffset.UTC))) {
            LOG.debug("FORCE_IMMINENT active — evaluating {} {} on {} (today)",
                    locationName, targetType, targetDate);
            return false;
        }

        // 3. FORCE_STALE — if latest eval was from before today, never skip
        if (activeTypes.contains(OptimisationStrategyType.FORCE_STALE) && latest.isPresent()) {
            LocalDate evalDate = latest.get().getForecastRunAt().toLocalDate();
            if (evalDate.isBefore(LocalDate.now(ZoneOffset.UTC))) {
                LOG.debug("FORCE_STALE active — re-evaluating {} {} on {} (last eval from {})",
                        locationName, targetType, targetDate, evalDate);
                return false;
            }
        }

        // 4. SKIP_EXISTING — skip if any forecast exists
        if (activeTypes.contains(OptimisationStrategyType.SKIP_EXISTING)) {
            List<ForecastEvaluationEntity> existing = forecastRepository
                    .findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                            locationName, targetDate, targetType);
            if (!existing.isEmpty()) {
                LOG.info("SKIP_EXISTING — skipping {} {} on {} (exists from {})",
                        locationName, targetType, targetDate,
                        existing.getFirst().getForecastRunAt());
                return true;
            }
        }

        // 5. SKIP_LOW_RATED — skip if no prior exists OR prior rating below threshold
        if (activeTypes.contains(OptimisationStrategyType.SKIP_LOW_RATED)) {
            if (latest.isEmpty()) {
                LOG.info("SKIP_LOW_RATED — skipping {} {} on {} (no prior evaluation)",
                        locationName, targetType, targetDate);
                return true;
            }
            int minRating = strategyMap.get(OptimisationStrategyType.SKIP_LOW_RATED)
                    .getParamValue() != null
                    ? strategyMap.get(OptimisationStrategyType.SKIP_LOW_RATED).getParamValue()
                    : 3;
            Integer rating = latest.get().getRating();
            if (rating != null && rating < minRating) {
                LOG.info("SKIP_LOW_RATED — skipping {} {} on {} (prior rating {} < {})",
                        locationName, targetType, targetDate, rating, minRating);
                return true;
            }
        }

        return false;
    }
}
