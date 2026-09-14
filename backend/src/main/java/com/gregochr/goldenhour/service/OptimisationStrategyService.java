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
 * <p>Handles CRUD and serialisation for audit logging. The two surviving strategies are
 * independent, so there is no mutual-exclusion rule left to enforce: this class used to carry five,
 * and the only one involving a surviving type ({@code SENTINEL_SAMPLING} vs {@code EVALUATE_ALL})
 * went with the retired types (V153).
 */
@Service
public class OptimisationStrategyService {

    private static final Logger LOG = LoggerFactory.getLogger(OptimisationStrategyService.class);

    /** Run types that support optimisation strategies (excludes WEATHER and TIDE). */
    private static final Set<RunType> FORECAST_RUN_TYPES = Set.of(
            RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM);

    /** Sentinel threshold a fresh row starts with — production's value (V52). */
    private static final int DEFAULT_SENTINEL_THRESHOLD = 2;

    /**
     * The strategy types V153 retired, by name — they no longer exist in the enum, so only their
     * strings remain. Must match V153's {@code DELETE} list; see {@link #seedDefaults()} for why this
     * is an explicit list rather than "anything the enum does not know".
     */
    static final List<String> RETIRED_STRATEGY_TYPES = List.of(
            "SKIP_LOW_RATED", "SKIP_EXISTING", "FORCE_IMMINENT", "FORCE_STALE",
            "EVALUATE_ALL", "NEXT_EVENT_ONLY", "BATCH_API");

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
     * Deletes rows naming a retired strategy type, then inserts any missing default row.
     *
     * <p>⚠️ Both halves exist for local dev, which runs no migrations (H2 with Flyway disabled). V153
     * deletes the retired types' rows in every migrated database, but a local database built before
     * it keeps them — and {@code strategy_type} maps through {@code @Enumerated(STRING)}, so the first
     * read of such a row throws. That takes down the Run Config screen (which loads every row) and
     * any manual run whose run type still has a retired type enabled — the old local seed enabled
     * {@code SKIP_LOW_RATED} for very-short-term and {@code SKIP_EXISTING} for long-term.
     *
     * <p>⚠️ The delete names the retired types explicitly ({@link #RETIRED_STRATEGY_TYPES}); it never
     * deletes "anything the enum does not know". It runs on every start in every profile, and
     * production sets {@code validate-on-migrate: false}, so an older image redeployed against a
     * database that has since gained a newer type would boot — and a {@code NOT IN (known)} delete
     * would then silently and permanently remove that type's rows. With an explicit list, an unknown
     * type fails loudly on read instead, which loses nothing.
     *
     * <p>Missing rows are filled per (run type, strategy), not only when the table is empty: the old
     * seed never wrote {@code TIDE_ALIGNMENT}, so an existing local database keeps three sentinel rows
     * and would otherwise never gain it. Defaults match production's (V52, V54): both enabled,
     * sentinel threshold 2. On a migrated database both halves do nothing.
     */
    @PostConstruct
    void seedDefaults() {
        int pruned = repository.deleteByStrategyTypeIn(RETIRED_STRATEGY_TYPES);
        if (pruned > 0) {
            LOG.info("Pruned {} optimisation strategy row(s) naming a retired type", pruned);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (RunType rt : FORECAST_RUN_TYPES) {
            for (OptimisationStrategyType st : OptimisationStrategyType.values()) {
                if (repository.findByRunTypeAndStrategyType(rt, st).isEmpty()) {
                    LOG.info("Seeding default optimisation strategy {} for {}", st, rt);
                    repository.save(OptimisationStrategyEntity.builder()
                            .runType(rt).strategyType(st)
                            .enabled(true).paramValue(defaultParamValue(st))
                            .updatedAt(now).build());
                }
            }
        }
    }

    private static Integer defaultParamValue(OptimisationStrategyType st) {
        return st == OptimisationStrategyType.SENTINEL_SAMPLING ? DEFAULT_SENTINEL_THRESHOLD : null;
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
     * Updates a strategy toggle.
     *
     * @param runType      the run type
     * @param strategyType the strategy to toggle
     * @param enabled      whether to enable or disable
     * @param paramValue   optional integer parameter (the sentinel threshold)
     * @return the updated entity
     * @throws IllegalArgumentException if no row exists for that run type and strategy
     */
    public OptimisationStrategyEntity updateStrategy(RunType runType,
            OptimisationStrategyType strategyType, boolean enabled, Integer paramValue) {
        OptimisationStrategyEntity entity = repository
                .findByRunTypeAndStrategyType(runType, strategyType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Strategy not found: " + runType + "/" + strategyType));

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
     * <p>Format example: {@code "SENTINEL_SAMPLING(2),TIDE_ALIGNMENT"}
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
}
