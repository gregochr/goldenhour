package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link ForecastCommand} instances from a {@link RunType}, resolving
 * the evaluation model config and strategy.
 */
@Service
public class ForecastCommandFactory {

    /** Maximum number of days ahead to forecast. */
    public static final int FORECAST_HORIZON_DAYS = RunType.FORECAST_HORIZON_DAYS;

    private final ModelSelectionService modelSelectionService;
    private final Map<EvaluationModel, EvaluationStrategy> strategies;
    private final Clock clock;

    /**
     * Constructs a {@code ForecastCommandFactory}.
     *
     * @param modelSelectionService resolves the active model for a run type
     * @param strategies            map from evaluation model to its strategy
     * @param clock                 supplies "today" for the default date range, resolved in
     *                              {@code Europe/London} by {@link ForecastHorizon}
     */
    public ForecastCommandFactory(ModelSelectionService modelSelectionService,
            Map<EvaluationModel, EvaluationStrategy> strategies, Clock clock) {
        this.modelSelectionService = modelSelectionService;
        this.strategies = strategies;
        this.clock = clock;
    }

    /**
     * Creates a command for the given run type using default dates and all applicable locations.
     *
     * @param runType  the type of forecast run
     * @param manual   whether this was triggered manually
     * @return a fully resolved command
     */
    public ForecastCommand create(RunType runType, boolean manual) {
        return create(runType, manual, null, null);
    }

    /**
     * Creates a command for the given run type with optional location and date overrides.
     *
     * @param runType   the type of forecast run
     * @param manual    whether this was triggered manually
     * @param locations the locations to process (null = all applicable)
     * @param dates     the target dates (null = default for the run type)
     * @return a fully resolved command
     */
    public ForecastCommand create(RunType runType, boolean manual,
            List<LocationEntity> locations, List<LocalDate> dates) {
        return create(runType, manual, locations, dates, Set.of());
    }

    /**
     * Creates a command for the given run type with optional location, date, and slot exclusion overrides.
     *
     * @param runType       the type of forecast run
     * @param manual        whether this was triggered manually
     * @param locations     the locations to process (null = all applicable)
     * @param dates         the target dates (null = default for the run type)
     * @param excludedSlots (date|TARGETTYPE) keys to exclude; empty = skip none
     * @return a fully resolved command
     */
    public ForecastCommand create(RunType runType, boolean manual,
            List<LocationEntity> locations, List<LocalDate> dates, Set<String> excludedSlots) {
        return create(runType, manual, locations, dates, excludedSlots, Set.of());
    }

    /**
     * Creates a command for the given run type with optional location, date, slot, and location exclusion overrides.
     *
     * @param runType            the type of forecast run
     * @param manual             whether this was triggered manually
     * @param locations          the locations to process (null = all applicable)
     * @param dates              the target dates (null = default for the run type)
     * @param excludedSlots      (date|TARGETTYPE) keys to exclude; empty = skip none
     * @param excludedLocations  location names to exclude; empty = skip none
     * @return a fully resolved command
     */
    public ForecastCommand create(RunType runType, boolean manual,
            List<LocationEntity> locations, List<LocalDate> dates,
            Set<String> excludedSlots, Set<String> excludedLocations) {
        List<LocalDate> resolvedDates = dates != null ? dates : defaultDates(runType);
        EvaluationStrategy strategy = resolveStrategy(runType);
        return new ForecastCommand(runType, resolvedDates, locations, strategy, manual,
                excludedSlots, excludedLocations);
    }

    /**
     * Returns the default dates for the given run type.
     *
     * <p>The range is anchored on the UK civil date, not UTC: every date it produces names a solar
     * event at a UK location, so it must be counted from the day those locations are living in.
     * Between 23:00 and 00:00 UTC under BST a UTC anchor shifted every run type's range a day
     * early. What that cost differs by run type, and only the first case is about a past day:
     * VERY_SHORT_TERM and SHORT_TERM began on the UK's <em>yesterday</em>, whose events are all
     * over and whose slots {@code ForecastCommandExecutor}'s already-past gate then dropped, so the
     * run reached one fewer future day. LONG_TERM starts at T+3 and so never met that gate at all —
     * it simply ran T+2…T+4 of the UK's days instead of T+3…T+5.
     *
     * @param runType the run type
     * @return list of dates
     */
    private List<LocalDate> defaultDates(RunType runType) {
        return runType.defaultDateRange(ForecastHorizon.today(clock));
    }

    /**
     * Resolves the evaluation strategy for the given run type.
     *
     * @param runType the run type
     * @return the appropriate strategy, or null for TIDE (no evaluation)
     */
    private EvaluationStrategy resolveStrategy(RunType runType) {
        return switch (runType) {
            case VERY_SHORT_TERM, SHORT_TERM, LONG_TERM -> resolveModelStrategy(runType);
            case WEATHER -> strategies.get(EvaluationModel.WILDLIFE);
            case TIDE, LIGHT_POLLUTION, BRIEFING, BRIEFING_BEST_BET, BRIEFING_GLOSS,
                    AURORA_EVALUATION, AURORA_GLOSS, BLUEBELL_GLOSS,
                    SCHEDULED_BATCH, BATCH_NEAR_TERM, BATCH_FAR_TERM -> null;
        };
    }

    /**
     * Looks up the active model for the run type and returns the matching strategy.
     *
     * @param runType the run type (VERY_SHORT_TERM, SHORT_TERM, or LONG_TERM)
     * @return the evaluation strategy for the active model
     */
    private EvaluationStrategy resolveModelStrategy(RunType runType) {
        EvaluationModel model = modelSelectionService.getActiveModel(runType);
        return strategies.get(model);
    }

    /**
     * Returns the {@link EvaluationModel} that a command's strategy corresponds to.
     *
     * @param command the command
     * @return the evaluation model, or {@link EvaluationModel#WILDLIFE} for no-op, or null for TIDE
     */
    public EvaluationModel resolveEvaluationModel(ForecastCommand command) {
        if (command.strategy() == null) {
            return null;
        }
        if (command.strategy().getEvaluationModel() == EvaluationModel.WILDLIFE) {
            return EvaluationModel.WILDLIFE;
        }
        return modelSelectionService.getActiveModel(command.runType());
    }
}
