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
     * The triage call for one location, with every argument the collector fixes pinned.
     *
     * @param forecastService the mock (or {@code verify(mock)})
     * @param location        the location the collector passes
     * @return the (stubbed or verified) result
     */
    static ForecastPreEvalResult triageOf(ForecastService forecastService, LocationEntity location) {
        return forecastService.fetchWeatherAndTriage(
                eq(location), any(LocalDate.class), any(TargetType.class), eq(location.getTideType()),
                any(EvaluationModel.class), isNull(), anyMap(), isNull(), isNull(), eq(NO_PROFILES));
    }

    /**
     * The triage call when several locations are in play: any location, the pinned rest.
     *
     * @param forecastService the mock (or {@code verify(mock)})
     * @return the (stubbed or verified) result
     */
    @SuppressWarnings("unchecked")
    static ForecastPreEvalResult triageOfAny(ForecastService forecastService) {
        return forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), any(LocalDate.class), any(TargetType.class), any(Set.class),
                any(EvaluationModel.class), isNull(), anyMap(), isNull(), isNull(), eq(NO_PROFILES));
    }
}
