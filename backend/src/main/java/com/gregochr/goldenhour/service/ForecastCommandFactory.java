package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Builds {@link ForecastCommand} instances from a {@link RunType}, resolving
 * the evaluation model config and strategy.
 */
@Service
public class ForecastCommandFactory {

    /** Maximum number of days ahead to forecast. */
    public static final int FORECAST_HORIZON_DAYS = 5;

    private final ModelSelectionService modelSelectionService;
    private final Map<EvaluationModel, EvaluationStrategy> strategies;

    /**
     * Constructs a {@code ForecastCommandFactory}.
     *
     * @param modelSelectionService resolves the active model for a run type
     * @param strategies            map from evaluation model to its strategy
     */
    public ForecastCommandFactory(ModelSelectionService modelSelectionService,
            Map<EvaluationModel, EvaluationStrategy> strategies) {
        this.modelSelectionService = modelSelectionService;
        this.strategies = strategies;
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
        List<LocalDate> resolvedDates = dates != null ? dates : defaultDates(runType);
        EvaluationStrategy strategy = resolveStrategy(runType);
        return new ForecastCommand(runType, resolvedDates, locations, strategy, manual);
    }

    /**
     * Returns the default dates for the given run type.
     *
     * @param runType the run type
     * @return list of dates
     */
    private List<LocalDate> defaultDates(RunType runType) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return switch (runType) {
            case VERY_SHORT_TERM -> List.of(today, today.plusDays(1));
            case SHORT_TERM -> List.of(today, today.plusDays(1), today.plusDays(2));
            case LONG_TERM -> today.plusDays(3)
                    .datesUntil(today.plusDays(FORECAST_HORIZON_DAYS + 1))
                    .toList();
            case WEATHER, TIDE -> IntStream.rangeClosed(0, FORECAST_HORIZON_DAYS)
                    .mapToObj(today::plusDays)
                    .toList();
        };
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
            case TIDE -> null;
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
        if (command.strategy() instanceof NoOpEvaluationStrategy) {
            return EvaluationModel.WILDLIFE;
        }
        return modelSelectionService.getActiveModel(command.runType());
    }
}
