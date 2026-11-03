package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.TravelDayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins WHERE the intraday evening-refresh deferral happens: after weather fetch and triage, never
 * before. A rated T+1 sunrise or sunset that triage stands down must still record {@code SKIPPED_TRIAGED}
 * (the disposition that retracts a stale rating); one that passes triage is left to the evening
 * ({@code SKIPPED_NO_REFRESH_NEEDED}) with no Claude request built; everything else is unchanged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForecastTaskCollector — intraday evening-refresh deferral")
class ForecastTaskCollectorEveningDeferralTest {

    /** 2026-05-29 14:00 UTC: the afternoon of the 29th in the UK. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-29T14:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 29);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final String REGION = "North East";
    private static final String DURHAM = "Durham UK";
    /** The exact event set the deferral loads live ratings for: never {@code any()}. */
    private static final Set<TargetType> BOTH_EVENTS = Set.of(TargetType.SUNRISE, TargetType.SUNSET);

    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private EvaluationViewService evaluationViewService;
    @Mock private ForecastService forecastService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private SolarService solarService;
    @Mock private FreshnessResolver freshnessResolver;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private TravelDayService travelDayService;

    private ForecastTaskCollector collector;
    private ForecastPreEvalResult preEval;
    private boolean withBluebellScore;

    @BeforeEach
    void setUp() {
        collector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService, evaluationViewService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, travelDayService,
                0.5, 0, CLOCK,
                new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL"));
    }

    @Test
    @DisplayName("live rating in the resolved view and the slot passes triage: skipped as NO_REFRESH_NEEDED, "
            + "weather and triage ran, no pending row")
    void liveRatingPassingTriage_isDeferred() {
        LocationEntity loc = stub(TOMORROW, TargetType.SUNRISE, false, 1);
        stubLiveView(Map.of(DURHAM, rated()));

        ScheduledBatchTasks result = collect(true);

        assertThat(result.isEmpty()).isTrue();
        CandidateDisposition row = onlyRow(result);
        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
        assertThat(row.detail()).isEqualTo("tonight's evening refresh re-rates it");
        assertThat(row.locationId()).isEqualTo(42L);
        assertThat(row.daysAhead()).isEqualTo(1);
        verify(openMeteoService).prefetchWeatherBatchResilient(any(), any());
        CollectorTriageStubs.triageOf(verify(forecastService), loc, TOMORROW, TargetType.SUNRISE,
                EvaluationModel.SONNET);
        verify(forecastService, never()).persistPendingEvaluation(preEval);
    }

    @Test
    @DisplayName("raw cache holds a rating but the resolved view has none (retracted): NOT deferred, "
            + "a request is built")
    void retractedInResolvedView_isNotDeferred() {
        stub(TOMORROW, TargetType.SUNRISE, false, 1);
        stubPersist();
        stubLiveView(Map.of());

        ScheduledBatchTasks result = collect(true);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(onlyRow(result).category()).isEqualTo(DispositionCategory.EVALUATED);
        verify(forecastService).persistPendingEvaluation(preEval);
    }

    @Test
    @DisplayName("resolved view has a triage-only row (null rating): NOT deferred")
    void triageOnlyRow_isNotDeferred() {
        stub(TOMORROW, TargetType.SUNRISE, false, 1);
        stubPersist();
        stubLiveView(Map.of(DURHAM, new BriefingEvaluationResult(
                DURHAM, null, null, null, null, TriageReason.HIGH_CLOUD, "cloud", null)));

        assertThat(collect(true).nearInland()).hasSize(1);
    }

    @Test
    @DisplayName("resolved view fails to load: the slot is rated rather than skipped")
    void viewFailure_ratesTheSlot() {
        stub(TOMORROW, TargetType.SUNRISE, false, 1);
        stubPersist();
        when(evaluationViewService.getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(collect(true).nearInland()).hasSize(1);
    }

    @Test
    @DisplayName("a location with no region cannot have a cached rating: rated, view never read")
    void noRegion_ratesTheSlot() {
        LocationEntity loc = stub(TOMORROW, TargetType.SUNRISE, false, 1);
        loc.setRegion(null);
        stubPersist();

        assertThat(collect(true).nearInland()).hasSize(1);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("a rated T+1 sunrise that triage stands down: still SKIPPED_TRIAGED, and the view is never read")
    void ratedSunriseTriagedOut_staysTriaged() {
        stub(TOMORROW, TargetType.SUNRISE, true, 1);

        CandidateDisposition row = onlyRow(collect(true));

        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_TRIAGED);
        assertThat(row.detail()).isEqualTo("heavy cloud");
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("the resolved view is loaded exactly once for the pass, with the candidates' date range")
    void viewLoadedOncePerPass() {
        stub(TOMORROW, TargetType.SUNRISE, false, 1);
        stubLiveView(Map.of(DURHAM, rated()));

        collect(true);

        verify(evaluationViewService, times(1)).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("evening not due: the view is never read and a rated T+1 sunrise is evaluated")
    void eveningNotDue_evaluated() {
        stub(TOMORROW, TargetType.SUNRISE, false, 1);
        stubPersist();

        assertThat(collect(false).nearInland()).hasSize(1);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("evening due: today's sunset is evaluated and the view is never read")
    void todaysSunset_stillEvaluated() {
        stub(TODAY, TargetType.SUNSET, false, 0);
        stubPersist();

        assertThat(collect(true).nearInland()).hasSize(1);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TODAY, TODAY, BOTH_EVENTS);
    }

    @Test
    @DisplayName("a rated T+1 sunset that passes triage is deferred to the evening, at any stability")
    void ratedSunsetPassingTriage_isDeferred() {
        stub(TOMORROW, TargetType.SUNSET, false, 1, ForecastStability.UNSETTLED, location());
        stubLiveView(TargetType.SUNSET, Map.of(DURHAM, rated()));

        ScheduledBatchTasks result = collect(true);

        assertThat(result.isEmpty()).isTrue();
        CandidateDisposition row = onlyRow(result);
        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
        assertThat(row.detail()).isEqualTo(IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON);
        assertThat(row.eventType()).isEqualTo(TargetType.SUNSET);
        assertThat(ReclassSummary.from(result.dispositions()).deferredToEvening()).isEqualTo(1);
        verify(forecastService, never()).persistPendingEvaluation(preEval);
    }

    @Test
    @DisplayName("a T+1 sunset with no live rating is evaluated, not left for 21:00")
    void unratedSunset_isEvaluated() {
        stub(TOMORROW, TargetType.SUNSET, false, 1, ForecastStability.UNSETTLED, location());
        stubPersist();
        stubLiveView(TargetType.SUNSET, Map.of());

        ScheduledBatchTasks result = collect(true);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(onlyRow(result).category()).isEqualTo(DispositionCategory.EVALUATED);
    }

    @Test
    @DisplayName("a rated T+1 sunset that triage stands down is still SKIPPED_TRIAGED, view never read")
    void ratedSunsetTriagedOut_staysTriaged() {
        stub(TOMORROW, TargetType.SUNSET, true, 1, ForecastStability.UNSETTLED, location());

        CandidateDisposition row = onlyRow(collect(true));

        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_TRIAGED);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("a SETTLED T+1 sunset is skipped by the eligibility policy, not counted as a deferral")
    void settledSunset_isSkippedByThePolicyNotDeferred() {
        stub(TOMORROW, TargetType.SUNSET, false, 1, ForecastStability.SETTLED, location());

        ScheduledBatchTasks result = collect(true);

        CandidateDisposition row = onlyRow(result);
        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
        assertThat(row.detail()).isNotEqualTo(IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON);
        assertThat(ReclassSummary.from(result.dispositions()).deferredToEvening()).isZero();
        assertThat(ReclassSummary.from(result.dispositions()).settledSkipped()).isEqualTo(1);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("a woodland (canopy) T+1 sunset is not deferred: it is rated even with a live rating")
    void woodlandSunset_isNotDeferred() {
        LocationEntity wood = location();
        wood.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.WOODLAND));
        stub(TOMORROW, TargetType.SUNSET, false, 1, ForecastStability.UNSETTLED, wood);

        ScheduledBatchTasks result = collect(true);

        assertThat(result.woodland()).hasSize(1);
        assertThat(onlyRow(result).category()).isEqualTo(DispositionCategory.EVALUATED);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS);
    }

    @Test
    @DisplayName("a woodland site in bluebell season (bluebell lane) is not deferred either")
    void bluebellWoodInSeasonSunset_isNotDeferred() {
        // Season is 10 April to 20 May; use the 10th/11th so the candidate date is in season.
        Clock springClock = Clock.fixed(Instant.parse("2026-05-10T14:00:00Z"), ZoneOffset.UTC);
        LocalDate day = LocalDate.of(2026, 5, 11);
        ForecastTaskCollector springCollector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService, evaluationViewService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, travelDayService,
                0.5, 0, springClock,
                new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL"));
        LocationEntity wood = location();
        wood.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.WOODLAND,
                com.gregochr.goldenhour.entity.LocationType.BLUEBELL));
        wood.setBluebellExposure(com.gregochr.goldenhour.entity.BluebellExposure.WOODLAND);
        withBluebellScore = true;
        stub(day, TargetType.SUNSET, false, 1, ForecastStability.UNSETTLED, wood);

        ScheduledBatchTasks result = springCollector.collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(springClock, true),
                IntradayEligibilityPolicy.INSTANCE);

        assertThat(result.bluebell()).hasSize(1);
        assertThat(onlyRow(result).category()).isEqualTo(DispositionCategory.EVALUATED);
        verify(evaluationViewService, never()).getLiveScoresForEnrichmentBulk(
                day, day, BOTH_EVENTS);
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private ScheduledBatchTasks collect(boolean eveningDue) {
        return collector.collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(CLOCK, eveningDue),
                IntradayEligibilityPolicy.INSTANCE);
    }

    private static CandidateDisposition onlyRow(ScheduledBatchTasks result) {
        assertThat(result.dispositions()).hasSize(1);
        return result.dispositions().getFirst();
    }

    private void stubLiveView(Map<String, BriefingEvaluationResult> forDurham) {
        stubLiveView(TargetType.SUNRISE, forDurham);
    }

    private void stubLiveView(TargetType type, Map<String, BriefingEvaluationResult> forDurham) {
        when(evaluationViewService.getLiveScoresForEnrichmentBulk(
                TOMORROW, TOMORROW, BOTH_EVENTS))
                .thenReturn(Map.of(CacheKeyFactory.build(REGION, TOMORROW, type), forDurham));
    }

    private static BriefingEvaluationResult rated() {
        return new BriefingEvaluationResult(DURHAM, 4, 70, 60, "Good", null, null, null);
    }

    private void stubPersist() {
        when(forecastService.persistPendingEvaluation(preEval)).thenReturn(1L);
    }

    private LocationEntity stub(LocalDate date, TargetType type, boolean triaged, int daysAhead) {
        return stub(date, type, triaged, daysAhead, ForecastStability.SETTLED, location());
    }

    private LocationEntity stub(LocalDate date, TargetType type, boolean triaged, int daysAhead,
            ForecastStability stability, LocationEntity loc) {
        when(briefingService.getCachedBriefing()).thenReturn(briefing(date, type, loc.getName()));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(freshnessResolver.maxAgeFor(daysAhead, ForecastStability.UNSETTLED))
                .thenReturn(Duration.ofHours(6));
        when(freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED)).thenReturn(Duration.ofHours(6));
        when(briefingEvaluationService.hasFreshEvaluation(
                CacheKeyFactory.build(REGION, date, type), Duration.ofHours(6))).thenReturn(false);
        when(openMeteoService.prefetchWeatherBatchResilient(any(), any()))
                .thenReturn(Map.of(OpenMeteoService.coordKey(loc.getLat(), loc.getLon()),
                        new WeatherExtractionResult(null, new OpenMeteoForecastResponse(),
                                new OpenMeteoAirQualityResponse())));
        preEval = preEval(loc, date, type, triaged, daysAhead);
        when(CollectorTriageStubs.triageOf(forecastService, loc, date, type, EvaluationModel.SONNET))
                .thenReturn(preEval);
        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        stability, "stable", 3));
        return loc;
    }

    private LocationEntity location() {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName(DURHAM);
        location.setLat(54.7753);
        location.setLon(-1.5849);
        location.setGridLat(54.75);
        location.setGridLng(-1.625);
        RegionEntity region = new RegionEntity();
        region.setId(1L);
        region.setName(REGION);
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }

    private DailyBriefingResponse briefing(LocalDate date, TargetType type, String locationName) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot(locationName, date.atTime(6, 0),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of());
        BriefingRegion region = new BriefingRegion(
                REGION, Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(type, List.of(region), List.of());
        return new DailyBriefingResponse(null, null,
                List.of(new BriefingDay(date, List.of(eventSummary))),
                null, null, null, false, null, List.of(), List.of());
    }

    private ForecastPreEvalResult preEval(LocationEntity loc, LocalDate date, TargetType type,
            boolean triaged, int daysAhead) {
        LocalDateTime eventTime = date.atTime(6, 0);
        TestAtmosphericData builder = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(eventTime)
                .targetType(type);
        if (withBluebellScore) {
            builder.bluebellConditionScore(new com.gregochr.goldenhour.model.BluebellConditionScore(
                    7, true, true, true, false, false, true,
                    com.gregochr.goldenhour.entity.BluebellExposure.WOODLAND, "Bright still light."));
        }
        AtmosphericData data = builder.build();
        return new ForecastPreEvalResult(triaged, triaged ? "heavy cloud" : null,
                triaged ? TriageReason.HIGH_CLOUD : null, data, loc, date, type, eventTime, 90,
                daysAhead, EvaluationModel.SONNET, Set.of(), "k", new OpenMeteoForecastResponse());
    }
}
