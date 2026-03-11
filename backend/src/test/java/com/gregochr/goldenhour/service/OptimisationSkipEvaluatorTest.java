package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OptimisationSkipEvaluator}.
 */
@ExtendWith(MockitoExtension.class)
class OptimisationSkipEvaluatorTest {

    @Mock
    private ForecastEvaluationRepository forecastRepository;

    @Mock
    private SolarService solarService;

    @InjectMocks
    private OptimisationSkipEvaluator evaluator;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final TargetType TARGET = TargetType.SUNRISE;

    private static final LocationEntity LOCATION = LocationEntity.builder()
            .id(1L).name("Test Location").lat(50.0).lon(-5.0).build();

    private static OptimisationStrategyEntity strategy(OptimisationStrategyType type,
            boolean enabled, Integer param) {
        return OptimisationStrategyEntity.builder()
                .runType(RunType.VERY_SHORT_TERM)
                .strategyType(type)
                .enabled(enabled)
                .paramValue(param)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static ForecastEvaluationEntity priorEval(Integer rating, LocalDateTime runAt) {
        return ForecastEvaluationEntity.builder()
                .id(1L)
                .targetDate(TODAY)
                .targetType(TARGET)
                .rating(rating)
                .forecastRunAt(runAt)
                .build();
    }

    // -------------------------------------------------------------------------
    // Empty strategies — no skip
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("shouldSkip returns false when no strategies are enabled")
    void noStrategies_neverSkips() {
        boolean result = evaluator.shouldSkip(List.of(), LOCATION, TODAY, TARGET);

        assertThat(result).isFalse();
        verify(forecastRepository, never())
                .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // EVALUATE_ALL — overrides everything
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("EVALUATE_ALL overrides all skip strategies")
    void evaluateAll_neverSkips() {
        var strategies = List.of(
                strategy(OptimisationStrategyType.EVALUATE_ALL, true, null));

        boolean result = evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET);

        assertThat(result).isFalse();
    }

    // -------------------------------------------------------------------------
    // SKIP_EXISTING
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SKIP_EXISTING skips when forecast exists")
    void skipExisting_skipsWhenExists() {
        when(forecastRepository.findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(List.of(priorEval(4, LocalDateTime.now())));

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_EXISTING, true, null));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isTrue();
    }

    @Test
    @DisplayName("SKIP_EXISTING does not skip when no forecast exists")
    void skipExisting_doesNotSkipWhenEmpty() {
        when(forecastRepository.findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(List.of());

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_EXISTING, true, null));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isFalse();
    }

    // -------------------------------------------------------------------------
    // SKIP_LOW_RATED — low rating
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SKIP_LOW_RATED skips when prior rating is below threshold")
    void skipLowRated_skipsWhenBelowThreshold() {
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(2, LocalDateTime.now())));

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isTrue();
    }

    @Test
    @DisplayName("SKIP_LOW_RATED does not skip when prior rating meets threshold")
    void skipLowRated_doesNotSkipWhenMeetsThreshold() {
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(4, LocalDateTime.now())));

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isFalse();
    }

    @Test
    @DisplayName("SKIP_LOW_RATED defaults to threshold 3 when paramValue is null")
    void skipLowRated_defaultsToThree() {
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(2, LocalDateTime.now())));

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, null));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isTrue();
    }

    // -------------------------------------------------------------------------
    // SKIP_LOW_RATED — no prior evaluation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SKIP_LOW_RATED skips when no prior evaluation exists")
    void skipLowRated_skipsWhenNoPrior() {
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.empty());

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isTrue();
    }

    @Test
    @DisplayName("SKIP_LOW_RATED does not skip when prior rating is null (unrated)")
    void skipLowRated_doesNotSkipWhenUnrated() {
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(null, LocalDateTime.now())));

        var strategies = List.of(
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isFalse();
    }

    // -------------------------------------------------------------------------
    // FORCE_IMMINENT — overrides skips for today
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FORCE_IMMINENT overrides SKIP_LOW_RATED for today's date")
    void forceImminent_overridesSkipForToday() {
        var strategies = List.of(
                strategy(OptimisationStrategyType.FORCE_IMMINENT, true, null),
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        // FORCE_IMMINENT should return false (don't skip) for today
        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isFalse();
    }

    @Test
    @DisplayName("FORCE_IMMINENT does not apply to future dates")
    void forceImminent_doesNotApplyToFutureDates() {
        LocalDate future = TODAY.plusDays(3);
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(future), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(1, LocalDateTime.now())));

        var strategies = List.of(
                strategy(OptimisationStrategyType.FORCE_IMMINENT, true, null),
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, future, TARGET)).isTrue();
    }

    // -------------------------------------------------------------------------
    // FORCE_STALE — overrides skips when eval is old
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FORCE_STALE overrides SKIP_LOW_RATED when latest eval is from yesterday")
    void forceStale_overridesWhenStale() {
        LocalDateTime yesterday = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(1, yesterday)));

        var strategies = List.of(
                strategy(OptimisationStrategyType.FORCE_STALE, true, null),
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isFalse();
    }

    @Test
    @DisplayName("FORCE_STALE does not override when latest eval is from today")
    void forceStale_doesNotOverrideWhenFresh() {
        LocalDateTime todayMorning = LocalDate.now(ZoneOffset.UTC).atTime(6, 0);
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.of(priorEval(1, todayMorning)));

        var strategies = List.of(
                strategy(OptimisationStrategyType.FORCE_STALE, true, null),
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Combination: SKIP_LOW_RATED + FORCE_IMMINENT for no prior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FORCE_IMMINENT overrides SKIP_LOW_RATED no-prior skip for today")
    void forceImminent_overridesNoPriorForToday() {
        when(forecastRepository.findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                eq(LOCATION.getId()), eq(TODAY), eq(TARGET)))
                .thenReturn(Optional.empty());

        var strategies = List.of(
                strategy(OptimisationStrategyType.FORCE_IMMINENT, true, null),
                strategy(OptimisationStrategyType.SKIP_LOW_RATED, true, 3));

        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TARGET)).isFalse();
    }

    // -------------------------------------------------------------------------
    // NEXT_EVENT_ONLY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("NEXT_EVENT_ONLY does not skip when this is the nearest upcoming event")
    void nextEventOnly_doesNotSkipNearestEvent() {
        // Use times relative to now so the test works regardless of when it runs
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime sunriseToday = now.plusHours(1);
        LocalDateTime sunsetToday = now.plusHours(6);
        LocalDateTime sunriseTomorrow = now.plusHours(25);
        LocalDateTime sunsetTomorrow = now.plusHours(30);

        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(sunriseToday);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(sunsetToday);
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY.plusDays(1))))
                .thenReturn(sunriseTomorrow);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY.plusDays(1))))
                .thenReturn(sunsetTomorrow);

        var strategies = List.of(
                strategy(OptimisationStrategyType.NEXT_EVENT_ONLY, true, null));

        // Sunrise today (now+1h) is the nearest upcoming event — don't skip
        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TargetType.SUNRISE)).isFalse();
    }

    @Test
    @DisplayName("NEXT_EVENT_ONLY skips sunset when sunrise is the nearest event")
    void nextEventOnly_skipsSunsetWhenSunriseIsNext() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime sunriseToday = now.plusHours(1);
        LocalDateTime sunsetToday = now.plusHours(6);
        LocalDateTime sunriseTomorrow = now.plusHours(25);
        LocalDateTime sunsetTomorrow = now.plusHours(30);

        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(sunriseToday);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(sunsetToday);
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY.plusDays(1))))
                .thenReturn(sunriseTomorrow);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY.plusDays(1))))
                .thenReturn(sunsetTomorrow);

        var strategies = List.of(
                strategy(OptimisationStrategyType.NEXT_EVENT_ONLY, true, null));

        // Sunset today (now+6h) is not the nearest — sunrise (now+1h) is
        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY, TargetType.SUNSET)).isTrue();
    }

    @Test
    @DisplayName("NEXT_EVENT_ONLY skips tomorrow's events when today's sunset is nearest")
    void nextEventOnly_skipsTomorrowWhenTodaySunsetIsNext() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Sunrise already passed, sunset still upcoming
        LocalDateTime sunriseToday = now.minusHours(6);
        LocalDateTime sunsetToday = now.plusHours(1);
        LocalDateTime sunriseTomorrow = now.plusHours(19);
        LocalDateTime sunsetTomorrow = now.plusHours(25);

        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(sunriseToday);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY)))
                .thenReturn(sunsetToday);
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(TODAY.plusDays(1))))
                .thenReturn(sunriseTomorrow);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(TODAY.plusDays(1))))
                .thenReturn(sunsetTomorrow);

        var strategies = List.of(
                strategy(OptimisationStrategyType.NEXT_EVENT_ONLY, true, null));

        // Tomorrow's sunrise should be skipped — today's sunset (now+1h) is nearer
        assertThat(evaluator.shouldSkip(strategies, LOCATION, TODAY.plusDays(1),
                TargetType.SUNRISE)).isTrue();
    }
}
