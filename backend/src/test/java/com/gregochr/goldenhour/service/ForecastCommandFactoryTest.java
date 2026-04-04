package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastCommandFactory}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandFactoryTest {

    @Mock
    private ModelSelectionService modelSelectionService;

    @Mock
    private EvaluationStrategy haikuStrategy;

    @Mock
    private EvaluationStrategy sonnetStrategy;

    @Mock
    private EvaluationStrategy opusStrategy;

    private NoOpEvaluationStrategy noOpStrategy;

    private ForecastCommandFactory factory;

    @BeforeEach
    void setUp() {
        noOpStrategy = new NoOpEvaluationStrategy();
        Map<EvaluationModel, EvaluationStrategy> strategies = Map.of(
                EvaluationModel.HAIKU, haikuStrategy,
                EvaluationModel.SONNET, sonnetStrategy,
                EvaluationModel.OPUS, opusStrategy,
                EvaluationModel.WILDLIFE, noOpStrategy);
        factory = new ForecastCommandFactory(modelSelectionService, strategies);
    }

    @Test
    @DisplayName("create(SHORT_TERM) resolves Sonnet strategy when active model is SONNET")
    void create_shortTerm_resolvesSonnet() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);

        ForecastCommand cmd = factory.create(RunType.SHORT_TERM, false);

        assertThat(cmd.runType()).isEqualTo(RunType.SHORT_TERM);
        assertThat(cmd.strategy()).isSameAs(sonnetStrategy);
        assertThat(cmd.triggeredManually()).isFalse();
        assertThat(cmd.dates()).hasSize(3); // today, T+1, T+2
    }

    @Test
    @DisplayName("create(VERY_SHORT_TERM) resolves Haiku strategy and 2 dates")
    void create_veryShortTerm_resolvesHaikuAndTwoDates() {
        when(modelSelectionService.getActiveModel(RunType.VERY_SHORT_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        ForecastCommand cmd = factory.create(RunType.VERY_SHORT_TERM, true);

        assertThat(cmd.runType()).isEqualTo(RunType.VERY_SHORT_TERM);
        assertThat(cmd.strategy()).isSameAs(haikuStrategy);
        assertThat(cmd.triggeredManually()).isTrue();
        assertThat(cmd.dates()).hasSize(2); // today, T+1
    }

    @Test
    @DisplayName("create(LONG_TERM) resolves Opus strategy and dates T+3 through T+5")
    void create_longTerm_resolvesOpusAndLongDates() {
        when(modelSelectionService.getActiveModel(RunType.LONG_TERM))
                .thenReturn(EvaluationModel.OPUS);

        ForecastCommand cmd = factory.create(RunType.LONG_TERM, false);

        assertThat(cmd.runType()).isEqualTo(RunType.LONG_TERM);
        assertThat(cmd.strategy()).isSameAs(opusStrategy);
        assertThat(cmd.dates()).hasSize(3); // T+3, T+4, T+5
    }

    @Test
    @DisplayName("create(WEATHER) uses NoOp strategy and full horizon dates")
    void create_weather_usesNoOpStrategy() {
        ForecastCommand cmd = factory.create(RunType.WEATHER, false);

        assertThat(cmd.runType()).isEqualTo(RunType.WEATHER);
        assertThat(cmd.strategy()).isSameAs(noOpStrategy);
        assertThat(cmd.dates()).hasSize(6); // T through T+5
    }

    @Test
    @DisplayName("create(TIDE) uses null strategy and full horizon dates")
    void create_tide_usesNullStrategy() {
        ForecastCommand cmd = factory.create(RunType.TIDE, false);

        assertThat(cmd.runType()).isEqualTo(RunType.TIDE);
        assertThat(cmd.strategy()).isNull();
        assertThat(cmd.dates()).hasSize(6);
    }

    @Test
    @DisplayName("create with explicit locations and dates uses those instead of defaults")
    void create_withExplicitLocationsAndDates() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity loc = new LocationEntity();
        loc.setName("TestLoc");
        List<LocationEntity> locations = List.of(loc);
        List<LocalDate> dates = List.of(LocalDate.of(2026, 3, 1));

        ForecastCommand cmd = factory.create(RunType.SHORT_TERM, true, locations, dates);

        assertThat(cmd.locations()).isSameAs(locations);
        assertThat(cmd.dates()).isEqualTo(dates);
        assertThat(cmd.triggeredManually()).isTrue();
    }

    @Test
    @DisplayName("resolveEvaluationModel returns WILDLIFE for NoOp strategy")
    void resolveEvaluationModel_noOp_returnsWildlife() {
        ForecastCommand cmd = new ForecastCommand(
                RunType.WEATHER, List.of(LocalDate.now(ZoneOffset.UTC)),
                null, noOpStrategy, false);

        EvaluationModel model = factory.resolveEvaluationModel(cmd);

        assertThat(model).isEqualTo(EvaluationModel.WILDLIFE);
    }

    @Test
    @DisplayName("resolveEvaluationModel returns null for TIDE (null strategy)")
    void resolveEvaluationModel_tide_returnsNull() {
        ForecastCommand cmd = new ForecastCommand(
                RunType.TIDE, List.of(LocalDate.now(ZoneOffset.UTC)),
                null, null, false);

        EvaluationModel model = factory.resolveEvaluationModel(cmd);

        assertThat(model).isNull();
    }

    @Test
    @DisplayName("resolveEvaluationModel delegates to modelSelectionService for Claude strategies")
    void resolveEvaluationModel_claude_delegatesToModelSelection() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);

        ForecastCommand cmd = new ForecastCommand(
                RunType.SHORT_TERM, List.of(LocalDate.now(ZoneOffset.UTC)),
                null, sonnetStrategy, false);

        EvaluationModel model = factory.resolveEvaluationModel(cmd);

        assertThat(model).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("create with WILDLIFE active model maps to NoOp strategy")
    void create_wildlifeActiveModel_mapsToNoOp() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.WILDLIFE);

        ForecastCommand cmd = factory.create(RunType.SHORT_TERM, false);

        assertThat(cmd.strategy()).isSameAs(noOpStrategy);
    }

    @Test
    @DisplayName("create with excludedSlots propagates slot exclusions to command")
    void create_withExcludedSlots_propagates() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);

        Set<String> excluded = Set.of("2026-03-15|SUNSET");
        ForecastCommand cmd = factory.create(RunType.SHORT_TERM, true, null, null, excluded);

        assertThat(cmd.excludedSlots()).isEqualTo(excluded);
        assertThat(cmd.excludedLocations()).isEmpty();
    }

    @Test
    @DisplayName("create with excludedLocations propagates location exclusions to command")
    void create_withExcludedLocations_propagates() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);

        Set<String> excludedSlots = Set.of();
        Set<String> excludedLocs = Set.of("Durham UK", "Bamburgh");
        ForecastCommand cmd = factory.create(RunType.SHORT_TERM, true, null, null,
                excludedSlots, excludedLocs);

        assertThat(cmd.excludedLocations()).isEqualTo(excludedLocs);
    }

    @Test
    @DisplayName("create with null dates resolves to default dates for run type")
    void create_nullDates_resolvesDefaults() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);

        ForecastCommand cmd = factory.create(RunType.SHORT_TERM, false, null, null);

        assertThat(cmd.dates()).hasSize(3); // today, T+1, T+2
        assertThat(cmd.dates().getFirst()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("create(BRIEFING) uses null strategy and full horizon dates")
    void create_briefing_usesNullStrategyAndFullHorizon() {
        ForecastCommand cmd = factory.create(RunType.BRIEFING, false);

        assertThat(cmd.runType()).isEqualTo(RunType.BRIEFING);
        assertThat(cmd.strategy()).isNull();
        assertThat(cmd.dates()).hasSize(6);
    }
}
