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
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins WHERE the intraday evening-refresh deferral happens: after weather fetch and triage, never
 * before. A rated T+1 sunrise that triage stands down must still record {@code SKIPPED_TRIAGED}
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

    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private ForecastService forecastService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private SolarService solarService;
    @Mock private FreshnessResolver freshnessResolver;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private TravelDayService travelDayService;

    private ForecastTaskCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, travelDayService,
                0.5, 0, CLOCK,
                new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL"));
        lenient().when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));
        lenient().when(freshnessResolver.maxAgeFor(anyInt(), any())).thenReturn(Duration.ofHours(6));
        lenient().when(briefingEvaluationService.hasFreshEvaluation(any(), any())).thenReturn(false);
        lenient().when(briefingEvaluationService.getCachedScores(any(), any(), any()))
                .thenReturn(Map.of());
    }

    @Test
    @DisplayName("rated T+1 sunrise that passes triage: skipped as NO_REFRESH_NEEDED, weather was fetched, "
            + "no pending row or request")
    void ratedSunrisePassingTriage_isDeferred() {
        LocationEntity loc = stub(TOMORROW, TargetType.SUNRISE, false);
        stubRating(TOMORROW, TargetType.SUNRISE, rated());

        ScheduledBatchTasks result = collect(true);

        assertThat(result.isEmpty()).isTrue();
        CandidateDisposition row = onlyRow(result);
        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
        assertThat(row.detail()).isEqualTo("tonight's evening refresh re-rates tomorrow's sunrise");
        assertThat(row.locationId()).isEqualTo(42L);
        assertThat(row.daysAhead()).isEqualTo(1);
        verify(openMeteoService).prefetchWeatherBatchResilient(any(), any());
        CollectorTriageStubs.triageOf(verifyForecast(), loc, TOMORROW, TargetType.SUNRISE,
                EvaluationModel.SONNET);
        verify(forecastService, never()).persistPendingEvaluation(any());
    }

    @Test
    @DisplayName("rated T+1 sunrise that triage stands down: still SKIPPED_TRIAGED, which retracts the rating")
    void ratedSunriseTriagedOut_staysTriaged() {
        stub(TOMORROW, TargetType.SUNRISE, true);
        stubRating(TOMORROW, TargetType.SUNRISE, rated());

        ScheduledBatchTasks result = collect(true);

        CandidateDisposition row = onlyRow(result);
        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_TRIAGED);
        assertThat(row.detail()).isEqualTo("heavy cloud");
    }

    @Test
    @DisplayName("evening due but no stored rating: T+1 sunrise is evaluated")
    void unratedSunrise_isEvaluated() {
        stub(TOMORROW, TargetType.SUNRISE, false);

        ScheduledBatchTasks result = collect(true);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(onlyRow(result).category()).isEqualTo(DispositionCategory.EVALUATED);
    }

    @Test
    @DisplayName("a stored stand-down or retracted result is not a rating: evaluated")
    void standDownAndRetracted_areNotRatings() {
        stub(TOMORROW, TargetType.SUNRISE, false);
        stubRating(TOMORROW, TargetType.SUNRISE,
                new BriefingEvaluationResult(DURHAM, null, null, null, null, null, null, null));

        assertThat(collect(true).nearInland()).hasSize(1);
    }

    @Test
    @DisplayName("evening not due: a rated T+1 sunrise is evaluated as before")
    void eveningNotDue_ratedSunriseEvaluated() {
        stub(TOMORROW, TargetType.SUNRISE, false);
        stubRating(TOMORROW, TargetType.SUNRISE, rated());

        assertThat(collect(false).nearInland()).hasSize(1);
    }

    @Test
    @DisplayName("evening due: today's sunset is evaluated even when rated")
    void todaysSunset_stillEvaluated() {
        stub(TODAY, TargetType.SUNSET, false);
        stubRating(TODAY, TargetType.SUNSET, rated());

        assertThat(collect(true).nearInland()).hasSize(1);
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private ScheduledBatchTasks collect(boolean eveningDue) {
        return collector.collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(CLOCK, eveningDue),
                IntradayEligibilityPolicy.INSTANCE);
    }

    private ForecastService verifyForecast() {
        return verify(forecastService);
    }

    private static CandidateDisposition onlyRow(ScheduledBatchTasks result) {
        assertThat(result.dispositions()).hasSize(1);
        return result.dispositions().getFirst();
    }

    private void stubRating(LocalDate date, TargetType type, BriefingEvaluationResult r) {
        lenient().when(briefingEvaluationService.getCachedScores(REGION, date, type))
                .thenReturn(Map.of(DURHAM, r));
    }

    private static BriefingEvaluationResult rated() {
        return new BriefingEvaluationResult(DURHAM, 4, 70, 60, "Good", null, null, null);
    }

    private LocationEntity stub(LocalDate date, TargetType type, boolean triaged) {
        LocationEntity loc = location();
        when(briefingService.getCachedBriefing()).thenReturn(briefing(date, type, loc.getName()));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(openMeteoService.prefetchWeatherBatchResilient(any(), any()))
                .thenReturn(Map.of(OpenMeteoService.coordKey(loc.getLat(), loc.getLon()),
                        new WeatherExtractionResult(null, new OpenMeteoForecastResponse(),
                                new OpenMeteoAirQualityResponse())));
        when(CollectorTriageStubs.triageOf(forecastService, loc, date, type, EvaluationModel.SONNET))
                .thenReturn(preEval(loc, date, type, triaged));
        lenient().when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));
        lenient().when(forecastService.persistPendingEvaluation(any())).thenReturn(1L);
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
            boolean triaged) {
        LocalDateTime eventTime = date.atTime(6, 0);
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(eventTime)
                .targetType(type)
                .build();
        int daysAhead = (int) java.time.temporal.ChronoUnit.DAYS.between(TODAY, date);
        return new ForecastPreEvalResult(triaged, triaged ? "heavy cloud" : null,
                triaged ? TriageReason.HIGH_CLOUD : null, data, loc, date, type, eventTime, 90,
                daysAhead, EvaluationModel.SONNET, Set.of(), "k", new OpenMeteoForecastResponse());
    }
}
