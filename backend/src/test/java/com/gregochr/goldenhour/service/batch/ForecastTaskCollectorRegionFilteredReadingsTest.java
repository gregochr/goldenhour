package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.EvaluationService;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TideAlignmentEvaluator;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.WeatherTriageEvaluator;
import com.gregochr.goldenhour.service.evaluation.SurvivorAtmosphereWriter;
import com.gregochr.goldenhour.service.notification.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test proving {@link ForecastTaskCollector#collectRegionFilteredBatches} reaches the
 * "record conditions for every place" seam inside {@link ForecastService#fetchWeatherAndTriage}
 * (P1 fix, 2026-09-30).
 *
 * <p>Unlike {@link ForecastTaskCollectorTest}, which mocks {@link ForecastService} entirely (so
 * its real body — where the write now lives — never runs), this class wires a REAL
 * {@code ForecastService} into a real {@code ForecastTaskCollector}, with only the leaf
 * collaborators mocked. It exists because a Codex review of the first cut of this phase (commit
 * 9c01491f) found this admin region-filtered path fetches weather and triages/Gate-4-skips
 * candidates exactly like the scheduled path, but the write lived only at the SCHEDULED path's own
 * call site — so a triaged or stability-skipped slot on this path got no reading recorded at all.
 * These tests would FAIL against commit b8fe0fa6, before the write moved inside
 * {@code fetchWeatherAndTriage} itself.
 */
@ExtendWith(MockitoExtension.class)
class ForecastTaskCollectorRegionFilteredReadingsTest {

    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;

    /** Production's clock, so "today" stays real and daysAhead arithmetic is unsurprising. */
    private static final Clock CLOCK = Clock.systemUTC();

    // ── ForecastTaskCollector's own collaborators ───────────────────────────────────────────
    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private SolarService solarService;
    @Mock private FreshnessResolver freshnessResolver;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private TravelDayService travelDayService;

    // ── The REAL ForecastService's own leaf collaborators ───────────────────────────────────
    @Mock private ForecastDataAugmentor augmentor;
    @Mock private EvaluationService legacyEvaluationService;
    @Mock private com.gregochr.goldenhour.service.evaluation.EvaluationService engineEvaluationService;
    @Mock private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WeatherTriageEvaluator weatherTriageEvaluator;
    @Mock private TideAlignmentEvaluator tideAlignmentEvaluator;
    @Mock private SurvivorAtmosphereWriter survivorAtmosphereWriter;

    private static final SeasonalWindow BLUEBELL_SEASON =
            new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL");

    private ForecastTaskCollector collector;
    private AtmosphericData sharedWeatherData;

    private static LocationEntity buildLocation(String name) {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName(name);
        location.setLat(DURHAM_LAT);
        location.setLon(DURHAM_LON);
        RegionEntity region = new RegionEntity();
        region.setId(1L);
        region.setName("North East");
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }

    private DailyBriefingResponse briefingWithSlot(LocalDate date, String locationName) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot(locationName, date.atTime(5, 30),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(date, List.of(eventSummary));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        ForecastService realForecastService = new ForecastService(
                solarService, openMeteoService, augmentor, legacyEvaluationService,
                engineEvaluationService, forecastEvaluationRepository, notificationDispatcher,
                eventPublisher, weatherTriageEvaluator, tideAlignmentEvaluator,
                survivorAtmosphereWriter, CLOCK);

        collector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService,
                realForecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver, stabilitySnapshotProvider,
                travelDayService, 0.5, 0, CLOCK, BLUEBELL_SEASON);

        lenient().when(freshnessResolver.maxAgeFor(any())).thenReturn(java.time.Duration.ofHours(6));
        lenient().when(freshnessResolver.maxAgeFor(anyInt(), any()))
                .thenReturn(java.time.Duration.ofHours(6));
        lenient().when(briefingEvaluationService.hasFreshEvaluation(any(), any())).thenReturn(false);
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

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
        lenient().when(tideAlignmentEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        // Weather pre-fetch: the collector's own bulk prefetch feeds a non-empty map into
        // fetchWeatherAndTriage, which then reads it back via getAtmosphericDataFromCache.
        sharedWeatherData = TestAtmosphericData.builder()
                .locationName("Durham UK")
                .targetType(TargetType.SUNRISE)
                .build();
        String coordKey = OpenMeteoService.coordKey(DURHAM_LAT, DURHAM_LON);
        Map<String, WeatherExtractionResult> prefetched = Map.of(coordKey,
                new WeatherExtractionResult(null, null));
        lenient().when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(prefetched);
        lenient().when(openMeteoService.getAtmosphericDataFromCache(any(), any(), any()))
                .thenReturn(new WeatherExtractionResult(sharedWeatherData, null));
        lenient().when(solarService.sunriseAzimuthDeg(anyDouble(), anyDouble(), any()))
                .thenReturn(65);
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(LocalDateTime.of(2026, 1, 1, 5, 30));
    }

    @Test
    @DisplayName("a slot triaged out in collectRegionFilteredBatches still gets its atmospheric "
            + "readings written")
    void triagedSlot_stillRecordsReadings() {
        LocationEntity location = buildLocation("Durham UK");
        LocalDate today = com.gregochr.goldenhour.util.ForecastHorizon.today(CLOCK);
        when(briefingService.getCachedBriefing()).thenReturn(briefingWithSlot(today, "Durham UK"));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));
        when(forecastEvaluationRepository.save(any()))
                .thenReturn(ForecastEvaluationEntity.builder().id(10L).build());

        RegionFilteredBatchTasks result =
                collector.collectRegionFilteredBatches(null);

        assertThat(result.inland()).isEmpty();
        assertThat(result.coastal()).isEmpty();
        verify(survivorAtmosphereWriter, times(1))
                .write(location, today, TargetType.SUNRISE, sharedWeatherData);
    }

    @Test
    @DisplayName("a slot skipped by Gate 4 stability in collectRegionFilteredBatches still gets "
            + "its atmospheric readings written")
    void stabilityGatedSlot_stillRecordsReadings() {
        // T+4 is "never eligible" in NightlyEligibilityPolicy regardless of stability, and this
        // location carries no grid cell, so GridCellStabilityService.stabilityFor short-circuits
        // to TRANSITIONAL without needing the stability classifier stubbed at all.
        LocationEntity location = buildLocation("Durham UK");
        LocalDate farOut = com.gregochr.goldenhour.util.ForecastHorizon.today(CLOCK).plusDays(4);
        when(briefingService.getCachedBriefing()).thenReturn(briefingWithSlot(farOut, "Durham UK"));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        RegionFilteredBatchTasks result =
                collector.collectRegionFilteredBatches(null);

        assertThat(result.inland()).isEmpty();
        assertThat(result.coastal()).isEmpty();
        verify(survivorAtmosphereWriter, times(1))
                .write(location, farOut, TargetType.SUNRISE, sharedWeatherData);
    }
}
