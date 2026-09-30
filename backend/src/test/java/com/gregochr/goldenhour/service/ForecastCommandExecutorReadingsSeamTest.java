package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SurvivorAtmosphereWriter;
import com.gregochr.goldenhour.service.notification.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test proving {@link ForecastCommandExecutor}'s synchronous-engine triage phase
 * reaches the "record conditions for every place" seam inside
 * {@link ForecastService#fetchWeatherAndTriage} (P1 fix, 2026-09-30).
 *
 * <p>Unlike {@link ForecastCommandExecutorTest}, which mocks {@link ForecastService} entirely
 * (so its real body — where the write now lives — never runs), this class wires a REAL
 * {@code ForecastService} into a real {@code ForecastCommandExecutor}, with only the leaf
 * collaborators mocked. It exists because a Codex review of the first cut of this phase (commit
 * 9c01491f) found that {@code runTriagePhase} triages a candidate away, the caller discards it
 * without ever reaching {@code evaluateAndPersist}, and the write lived only in
 * {@code evaluateAndPersist} plus three other call sites — so a slot triaged out by the
 * synchronous engine got no reading recorded at all. This test would FAIL against commit
 * b8fe0fa6, before the write moved inside {@code fetchWeatherAndTriage} itself.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandExecutorReadingsSeamTest {

    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;
    private static final String DURHAM = "Durham UK";

    /** Production's clock, so "today" stays a real, always-current date. */
    private static final Clock CLOCK = Clock.systemUTC();

    // ── ForecastCommandExecutor's own collaborators ─────────────────────────────────────────
    @Mock private LocationService locationService;
    @Mock private JobRunService jobRunService;
    @Mock private SolarService solarService;
    @Mock private ForecastCommandFactory commandFactory;
    @Mock private OptimisationStrategyService optimisationStrategyService;
    @Mock private RunProgressTracker progressTracker;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SentinelSelector sentinelSelector;
    @Mock private AstroConditionsService astroConditionsService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private EvaluationStrategy haikuStrategy;

    // ── The REAL ForecastService's own leaf collaborators ───────────────────────────────────
    @Mock private ForecastDataAugmentor augmentor;
    @Mock private EvaluationService legacyEvaluationService;
    @Mock private com.gregochr.goldenhour.service.evaluation.EvaluationService engineEvaluationService;
    @Mock private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private WeatherTriageEvaluator weatherTriageEvaluator;
    @Mock private TideAlignmentEvaluator tideAlignmentEvaluator;
    @Mock private SurvivorAtmosphereWriter survivorAtmosphereWriter;

    private ForecastCommandExecutor executor;
    private JobRunEntity stubJobRun;

    /**
     * The one fixed {@link AtmosphericData} instance {@code getAtmosphericDataFromCache} returns
     * for every candidate this test submits — both SUNRISE and SUNSET calls receive this exact
     * object reference, so {@code verify(...)} can match it by identity ({@code eq(...)}) rather
     * than {@code any()}.
     */
    private AtmosphericData sharedWeatherData;

    private static LocationEntity durham() {
        return LocationEntity.builder()
                .id(1L)
                .name(DURHAM)
                .lat(DURHAM_LAT)
                .lon(DURHAM_LON)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .build();
    }

    @BeforeEach
    void setUp() {
        stubJobRun = new JobRunEntity();
        stubJobRun.setId(1L);

        ForecastService realForecastService = new ForecastService(
                solarService, openMeteoService, augmentor, legacyEvaluationService,
                engineEvaluationService, forecastEvaluationRepository, notificationDispatcher,
                eventPublisher, weatherTriageEvaluator, tideAlignmentEvaluator,
                survivorAtmosphereWriter, CLOCK);

        executor = new ForecastCommandExecutor(
                realForecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run,
                optimisationStrategyService, progressTracker, eventPublisher,
                sentinelSelector, astroConditionsService, stabilityClassifier,
                openMeteoService, stabilitySnapshotProvider, CLOCK);

        // Infrastructure stubs — mirrors ForecastCommandExecutorTest.stubExecuteDefaults(),
        // adapted for a real ForecastService underneath.
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());
        when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");
        when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenReturn(stubJobRun);
        when(openMeteoService.prefetchWeatherBatch(anyList(), any()))
                .thenReturn(new java.util.LinkedHashMap<>());
        when(openMeteoService.prefetchCloudBatch(anyList(), any()))
                .thenReturn(new CloudPointCache(java.util.Map.of()));
        // Neither solar time is past "now", so shouldSkipEvent never drops the slot.
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.MAX);
        when(solarService.sunriseAzimuthDeg(anyDouble(), anyDouble(), any())).thenReturn(65);
        when(solarService.sunsetAzimuthDeg(anyDouble(), anyDouble(), any())).thenReturn(310);

        // ForecastService's own augmentor pass-through chain (identical shape to
        // ForecastServiceTest's setUp — every augment* call returns its input unchanged).
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithLocationOrientation(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithStormSurge(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithInversionScore(any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithBluebellConditions(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // The candidate's weather, served from the (empty) prefetch cache — ForecastService
        // calls getAtmosphericDataFromCache whenever the executor hands it a non-null map,
        // which it always does here (an empty map is still non-null). Both SUNRISE and SUNSET
        // calls receive this identical instance, since the stub is a single fixed thenReturn.
        sharedWeatherData = TestAtmosphericData.builder()
                .locationName(DURHAM)
                .targetType(TargetType.SUNRISE)
                .build();
        when(openMeteoService.getAtmosphericDataFromCache(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(sharedWeatherData, null));
    }

    @Test
    @DisplayName("a slot triaged out in runTriagePhase still gets its atmospheric readings "
            + "written — the synchronous engine's triaged candidates are discarded by execute() "
            + "without ever reaching evaluateAndPersist, so the write cannot live there")
    void triagedSlot_stillRecordsReadings() {
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new com.gregochr.goldenhour.model.TriageResult(
                        "Low cloud 85%", com.gregochr.goldenhour.model.TriageRule.HIGH_CLOUD)));
        when(forecastEvaluationRepository.save(any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(10L).build());

        LocalDate today = com.gregochr.goldenhour.util.ForecastHorizon.today(CLOCK);
        // LocationEntity has no equals()/hashCode() override (Lombok @Getter/@Setter/@Builder
        // only), so verify() below must match the SAME instance the command carries — a fresh
        // durham() would be reference-unequal and the verification would spuriously report
        // "never invoked".
        LocationEntity location = durham();
        com.gregochr.goldenhour.service.ForecastCommand cmd =
                new com.gregochr.goldenhour.service.ForecastCommand(
                        RunType.SHORT_TERM, List.of(today), List.of(location),
                        haikuStrategy, true);

        List<ForecastEvaluationEntity> results = executor.execute(cmd);

        // Both target types (SUNRISE + SUNSET) triaged away — an early stop, zero evaluations.
        org.assertj.core.api.Assertions.assertThat(results).isEmpty();
        verify(survivorAtmosphereWriter, times(1))
                .write(location, today, TargetType.SUNRISE, sharedWeatherData);
        verify(survivorAtmosphereWriter, times(1))
                .write(location, today, TargetType.SUNSET, sharedWeatherData);
    }
}
