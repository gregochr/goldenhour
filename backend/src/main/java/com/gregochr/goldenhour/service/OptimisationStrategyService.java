package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.OptimisationStrategyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for managing configurable cost optimisation strategies per run type.
 *
 * <p>Handles CRUD, mutual exclusivity validation, and serialisation for audit logging.
 */
@Service
public class OptimisationStrategyService {

    private static final Logger LOG = LoggerFactory.getLogger(OptimisationStrategyService.class);

    /** Run types that support optimisation strategies (excludes WEATHER and TIDE). */
    private static final Set<RunType> FORECAST_RUN_TYPES = Set.of(
            RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM);

    private final OptimisationStrategyRepository repository;

    /**
     * Constructs an {@code OptimisationStrategyService}.
     *
     * @param repository the strategy repository
     */
    public OptimisationStrategyService(OptimisationStrategyRepository repository) {
        this.repository = repository;
    }

    /**
     * Seeds default strategy rows when the table is empty (local dev with Flyway disabled).
     */
    @PostConstruct
    void seedDefaults() {
        if (repository.count() > 0) {
            return;
        }
        LOG.info("Seeding default optimisation strategies");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // UI-visible strategy types (excludes BATCH_API)
        OptimisationStrategyType[] uiTypes = {
                OptimisationStrategyType.SKIP_LOW_RATED,
                OptimisationStrategyType.SKIP_EXISTING,
                OptimisationStrategyType.FORCE_IMMINENT,
                OptimisationStrategyType.FORCE_STALE,
                OptimisationStrategyType.EVALUATE_ALL,
                OptimisationStrategyType.NEXT_EVENT_ONLY
        };
        for (RunType rt : FORECAST_RUN_TYPES) {
            for (OptimisationStrategyType st : uiTypes) {
                boolean enabled = isDefaultEnabled(rt, st);
                Integer param = (st == OptimisationStrategyType.SKIP_LOW_RATED) ? 3 : null;
                repository.save(OptimisationStrategyEntity.builder()
                        .runType(rt).strategyType(st)
                        .enabled(enabled).paramValue(param)
                        .updatedAt(now).build());
            }
        }
    }

    private static boolean isDefaultEnabled(RunType rt, OptimisationStrategyType st) {
        if (rt == RunType.VERY_SHORT_TERM) {
            return st == OptimisationStrategyType.SKIP_LOW_RATED;
        }
        if (rt == RunType.LONG_TERM) {
            return st == OptimisationStrategyType.SKIP_EXISTING;
        }
        return false;
    }

    /**
     * Returns enabled strategies for a given run type.
     *
     * @param runType the run type to query
     * @return list of enabled strategy entities
     */
    public List<OptimisationStrategyEntity> getEnabledStrategies(RunType runType) {
        return repository.findByRunTypeAndEnabledTrue(runType);
    }

    /**
     * Returns all strategy configurations grouped by forecast run type.
     *
     * @return map of run type to list of all strategies (enabled and disabled)
     */
    public Map<RunType, List<OptimisationStrategyEntity>> getAllConfigs() {
        Map<RunType, List<OptimisationStrategyEntity>> result = new EnumMap<>(RunType.class);
        for (RunType rt : FORECAST_RUN_TYPES) {
            result.put(rt, repository.findByRunType(rt));
        }
        return result;
    }

    /**
     * Updates a strategy toggle with mutual exclusivity validation.
     *
     * <p>Mutual exclusions enforced:
     * <ul>
     *   <li>EVALUATE_ALL enabled → disables SKIP_LOW_RATED, SKIP_EXISTING</li>
     *   <li>SKIP_EXISTING ↔ SKIP_LOW_RATED (cannot both be enabled)</li>
     * </ul>
     *
     * @param runType      the run type
     * @param strategyType the strategy to toggle
     * @param enabled      whether to enable or disable
     * @param paramValue   optional integer parameter (e.g. min rating)
     * @return the updated entity
     * @throws IllegalArgumentException if mutual exclusivity is violated or strategy not found
     */
    public OptimisationStrategyEntity updateStrategy(RunType runType,
            OptimisationStrategyType strategyType, boolean enabled, Integer paramValue) {
        OptimisationStrategyEntity entity = repository
                .findByRunTypeAndStrategyType(runType, strategyType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Strategy not found: " + runType + "/" + strategyType));

        if (enabled) {
            validateMutualExclusions(runType, strategyType);
        }

        entity.setEnabled(enabled);
        if (paramValue != null) {
            entity.setParamValue(paramValue);
        }
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        LOG.info("Updated optimisation strategy: {} {} enabled={} param={}",
                runType, strategyType, enabled, paramValue);

        return repository.save(entity);
    }

    /**
     * Serialises enabled strategies for a run type into a compact audit string.
     *
     * <p>Format example: {@code "SKIP_LOW_RATED(3),FORCE_IMMINENT"}
     *
     * @param runType the run type
     * @return comma-separated string of enabled strategy names with params
     */
    public String serialiseEnabledStrategies(RunType runType) {
        List<OptimisationStrategyEntity> enabled = getEnabledStrategies(runType);
        if (enabled.isEmpty()) {
            return "";
        }
        return enabled.stream()
                .map(e -> {
                    String name = e.getStrategyType().name();
                    return e.getParamValue() != null
                            ? name + "(" + e.getParamValue() + ")"
                            : name;
                })
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /**
     * Validates that enabling a strategy does not conflict with already-enabled strategies.
     */
    private void validateMutualExclusions(RunType runType, OptimisationStrategyType strategyType) {
        List<OptimisationStrategyEntity> currentlyEnabled = getEnabledStrategies(runType);
        Set<OptimisationStrategyType> activeTypes = new java.util.HashSet<>();
        for (OptimisationStrategyEntity e : currentlyEnabled) {
            activeTypes.add(e.getStrategyType());
        }

        switch (strategyType) {
            case EVALUATE_ALL -> {
                if (activeTypes.contains(OptimisationStrategyType.SKIP_LOW_RATED)
                        || activeTypes.contains(OptimisationStrategyType.SKIP_EXISTING)
                        || activeTypes.contains(OptimisationStrategyType.NEXT_EVENT_ONLY)) {
                    throw new IllegalArgumentException(
                            "EVALUATE_ALL conflicts with skip strategies. "
                            + "Disable SKIP_LOW_RATED, SKIP_EXISTING, and NEXT_EVENT_ONLY first.");
                }
            }
            case NEXT_EVENT_ONLY -> {
                if (activeTypes.contains(OptimisationStrategyType.EVALUATE_ALL)) {
                    throw new IllegalArgumentException(
                            "NEXT_EVENT_ONLY conflicts with EVALUATE_ALL. Disable EVALUATE_ALL first.");
                }
            }
            case SKIP_LOW_RATED -> {
                if (activeTypes.contains(OptimisationStrategyType.SKIP_EXISTING)) {
                    throw new IllegalArgumentException(
                            "SKIP_LOW_RATED conflicts with SKIP_EXISTING. Disable SKIP_EXISTING first.");
                }
                if (activeTypes.contains(OptimisationStrategyType.EVALUATE_ALL)) {
                    throw new IllegalArgumentException(
                            "SKIP_LOW_RATED conflicts with EVALUATE_ALL. Disable EVALUATE_ALL first.");
                }
            }
            case SKIP_EXISTING -> {
                if (activeTypes.contains(OptimisationStrategyType.SKIP_LOW_RATED)) {
                    throw new IllegalArgumentException(
                            "SKIP_EXISTING conflicts with SKIP_LOW_RATED. Disable SKIP_LOW_RATED first.");
                }
                if (activeTypes.contains(OptimisationStrategyType.EVALUATE_ALL)) {
                    throw new IllegalArgumentException(
                            "SKIP_EXISTING conflicts with EVALUATE_ALL. Disable EVALUATE_ALL first.");
                }
            }
            default -> { /* FORCE_IMMINENT, FORCE_STALE are always compatible */ }
        }
    }
}
