package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.service.ForecastService;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * The collector's triage call, as its tests stub and verify it.
 *
 * <p>The collector calls the ten-argument {@code fetchWeatherAndTriage} with values these tests' mocks fix:
 * no job run, no cloud cache (the mocked cloud prefetch returns null), no tide snapshot (the mocked snapshot
 * load returns null) and an EMPTY inversion profile map (the mocked {@code inversionV2Enabled} is false, so
 * the prefetch is skipped). Pass the mock itself to stub it, or {@code verify(mock)} to verify it.
 */
final class CollectorTriageStubs {

    private static final Map<Long, InversionProfile> NO_PROFILES = Map.of();

    private CollectorTriageStubs() {
    }

    /**
     * The triage call for one candidate, with every argument the collector fixes pinned.
     *
     * @param forecastService the mock (or {@code verify(mock)})
     * @param location        the location the collector passes
     * @param date            the candidate's date
     * @param targetType      the candidate's event
     * @param model           the evaluation model the collector resolves for the candidate
     * @return the (stubbed or verified) result
     */
    static ForecastPreEvalResult triageOf(ForecastService forecastService, LocationEntity location,
            LocalDate date, TargetType targetType, EvaluationModel model) {
        return forecastService.fetchWeatherAndTriage(
                eq(location), eq(date), eq(targetType), eq(location.getTideType()),
                eq(model), isNull(), anyMap(), isNull(), isNull(), eq(NO_PROFILES));
    }

    /**
     * The triage call when several locations share one slot: any location, with the date, event and model
     * they have in common pinned.
     *
     * @param forecastService the mock (or {@code verify(mock)})
     * @param date            the shared date
     * @param targetType      the shared event
     * @param model           the shared evaluation model
     * @return the (stubbed or verified) result
     */
    @SuppressWarnings("unchecked")
    static ForecastPreEvalResult triageOfAny(ForecastService forecastService, LocalDate date,
            TargetType targetType, EvaluationModel model) {
        return forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), eq(date), eq(targetType), any(Set.class),
                eq(model), isNull(), anyMap(), isNull(), isNull(), eq(NO_PROFILES));
    }
}
