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
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.SeasonalWindow;
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
import com.gregochr.goldenhour.service.evaluation.SurvivorAtmosphereWriter;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins the calendar the {@code daysAhead} horizon is derived on, at the one hour of the
 * day where the answer depends on it.
 *
 * <p>Under BST, between 23:00 and 00:00 UTC, the UTC date is a day behind the UK civil
 * date, so a UTC-derived horizon overstates every slot by one. The intraday cycle is the
 * first consumer that <em>branches</em> on the horizon — {@link IntradayEligibilityPolicy}
 * skips a SETTLED sunset at {@code daysAhead > 0} on the grounds that two further looks
 * are guaranteed before it — so in that hour tonight's sunset, which has no later look at
 * all, was skipped on a justification that only holds for tomorrow's.
 *
 * <p>Every case here therefore runs {@link IntradayCandidateCollectionStrategy} against
 * {@link IntradayEligibilityPolicy}. The collector cases added with the intraday cycle all
 * run the nightly strategy, which cannot see this: nightly admits T+0 and T+1 alike, so
 * the two bases give the same answer and a disagreement is invisible.
 *
 * <p>The fixture reproduces the disagreement rather than describing it: the stubbed
 * {@link ForecastPreEvalResult} carries the horizon <em>UTC would have produced</em> at
 * this instant, which is what {@code ForecastService} really did return before the fix.
 * A collector that trusts that number skips the slot; one deriving its own on the UK
 * calendar evaluates it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForecastTaskCollector — daysAhead timezone basis (BST pre-midnight band)")
class ForecastTaskCollectorHorizonBasisTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * 2026-08-11 23:30 UTC — inside BST, so the UK civil date is already 2026-08-12.
     * This is the whole point of the fixture: {@code LocalDate.now(UTC)} says the 11th,
     * {@code LocalDate.now(Europe/London)} says the 12th.
     */
    private static final Clock BST_PRE_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-08-11T23:30:00Z"), ZoneOffset.UTC);

    /** 2026-08-12 — "today" on the calendar the domain rule names. */
    private static final LocalDate LONDON_TODAY = LocalDate.now(BST_PRE_MIDNIGHT.withZone(LONDON));

    /** 2026-08-11 — "today" on the calendar the horizon used to be derived on. */
    private static final LocalDate UTC_TODAY = LocalDate.now(BST_PRE_MIDNIGHT.withZone(ZoneOffset.UTC));

    /**
     * The horizon a UTC basis produces for tonight's sunset at this instant: one day too
     * many. Not a magic number — it is asserted against both calendars below.
     */
    private static final int UTC_BASIS_HORIZON = 1;

    private static final SeasonalWindow BLUEBELL_SEASON =
            new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL");
    private static final double MIN_PREFETCH_RATIO = 0.5;
    private static final String DURHAM = "Durham UK";
    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;

    @Mock
    private LocationService locationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ForecastStabilityClassifier stabilityClassifier;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private SolarService solarService;
    @Mock
    private FreshnessResolver freshnessResolver;
    @Mock
    private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock
    private SurvivorAtmosphereWriter survivorAtmosphereWriter;
    @Mock
    private TravelDayService travelDayService;

    private ForecastTaskCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, survivorAtmosphereWriter, travelDayService,
                MIN_PREFETCH_RATIO, 0, BST_PRE_MIDNIGHT,
                BLUEBELL_SEASON);
        lenient().when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));
        lenient().when(freshnessResolver.maxAgeFor(anyInt(), any())).thenReturn(Duration.ofHours(6));
    }

    @Test
    @DisplayName("the fixture's two calendars really do disagree, and by exactly one day")
    void fixtureStraddlesMidnightUtc() {
        // Guards the rest of the class: if BST ever stops applying on this date, or the
        // instant drifts out of the 23:00-00:00 band, every case below would still pass
        // while testing nothing. Assert the premise, not just the conclusion.
        assertThat(LONDON_TODAY).isEqualTo(UTC_TODAY.plusDays(1));
        assertThat(LONDON.getRules().getOffset(BST_PRE_MIDNIGHT.instant()))
                .isEqualTo(java.time.ZoneOffset.ofHours(1));
        assertThat((int) java.time.temporal.ChronoUnit.DAYS.between(UTC_TODAY, LONDON_TODAY))
                .isEqualTo(UTC_BASIS_HORIZON);
    }

    @Test
    @DisplayName("tonight's SETTLED sunset is evaluated, not skipped as though it had a later look")
    void tonightsSettledSunset_isEvaluated() {
        LocationEntity loc = stubSunsetBriefing(LONDON_TODAY);
        stubSettledStability(loc);

        ScheduledBatchTasks result = collector.collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(BST_PRE_MIDNIGHT),
                IntradayEligibilityPolicy.INSTANCE);

        // Tonight's sunset has no later look at all — the 01:00 nightly lands after it.
        // On the UTC basis this read as T+1, whose skip is justified by two guaranteed
        // later looks that tonight does not have.
        assertThat(result.nearInland())
                .as("tonight's sunset must survive the intraday eligibility gate")
                .hasSize(1);
        assertThat(result.nearInland().getFirst().date()).isEqualTo(LONDON_TODAY);
        assertThat(result.nearInland().getFirst().targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("the audit row for that sunset reads T+0 — the horizon the decision was made on")
    void dispositionRecordsLondonHorizon() {
        LocationEntity loc = stubSunsetBriefing(LONDON_TODAY);
        stubSettledStability(loc);

        ScheduledBatchTasks result = collector.collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(BST_PRE_MIDNIGHT),
                IntradayEligibilityPolicy.INSTANCE);

        // Pre-fix this row read 1, because the include and stability-skip rows were written
        // from preEval.daysAhead() (UTC) while the triage and error rows a few lines above
        // used the London value — so which calendar a run's audit trail spoke depended on
        // which branch each candidate took. It is one value for all of them now.
        CandidateDisposition row = result.dispositions().stream()
                .filter(d -> d.eventType() == TargetType.SUNSET)
                .findFirst()
                .orElseThrow();
        assertThat(row.daysAhead()).isZero();
        assertThat(row.category()).isEqualTo(DispositionCategory.EVALUATED);
    }

    @Test
    @DisplayName("tomorrow's SETTLED sunset is still skipped — the retained rule is not collateral")
    void tomorrowsSettledSunset_isStillSkipped() {
        // The fix moves the boundary onto the right calendar; it does not move the boundary.
        // Tomorrow evening genuinely has two further looks before it, so it still skips —
        // otherwise the "fix" would just be a blanket relaxation wearing a timezone costume.
        LocationEntity loc = stubSunsetBriefing(LONDON_TODAY.plusDays(1));
        stubSettledStability(loc);

        ScheduledBatchTasks result = collector.collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(BST_PRE_MIDNIGHT),
                IntradayEligibilityPolicy.INSTANCE);

        assertThat(result.isEmpty()).isTrue();
        CandidateDisposition row = result.dispositions().stream()
                .filter(d -> d.eventType() == TargetType.SUNSET)
                .findFirst()
                .orElseThrow();
        assertThat(row.daysAhead()).isEqualTo(1);
        assertThat(row.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    /**
     * Stubs a one-slot GO sunset briefing on the given UK-local date, with the pre-eval
     * carrying the horizon a UTC derivation would have produced for it at this instant.
     */
    private LocationEntity stubSunsetBriefing(LocalDate date) {
        LocationEntity loc = buildInlandLocation();
        when(briefingService.getCachedBriefing()).thenReturn(buildSunsetBriefing(date, loc.getName()));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(OpenMeteoService.coordKey(loc.getLat(), loc.getLon()),
                        new WeatherExtractionResult(null, new OpenMeteoForecastResponse(),
                                new OpenMeteoAirQualityResponse())));
        int utcBasisHorizon = (int) java.time.temporal.ChronoUnit.DAYS.between(UTC_TODAY, date);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(sunsetPreEval(loc, date, utcBasisHorizon));
        return loc;
    }

    private void stubSettledStability(LocationEntity loc) {
        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(
                        loc.gridCellKey(), loc.getGridLat(), loc.getGridLng(),
                        ForecastStability.SETTLED, "stable", 3));
    }

    private LocationEntity buildInlandLocation() {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName(DURHAM);
        location.setLat(DURHAM_LAT);
        location.setLon(DURHAM_LON);
        location.setGridLat(54.7500);
        location.setGridLng(-1.6250);
        RegionEntity region = new RegionEntity();
        region.setId(1L);
        region.setName("North East");
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }

    private DailyBriefingResponse buildSunsetBriefing(LocalDate date, String locationName) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot(locationName, date.atTime(20, 30),
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNSET, List.of(region), List.of());
        return new DailyBriefingResponse(null, null, List.of(new BriefingDay(date, List.of(eventSummary))),
                null, null, null, false, false, 0, null, List.of(), List.of());
    }

    private ForecastPreEvalResult sunsetPreEval(LocationEntity loc, LocalDate date, int daysAhead) {
        LocalDateTime eventTime = date.atTime(20, 30);
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(eventTime)
                .targetType(TargetType.SUNSET)
                .build();
        return new ForecastPreEvalResult(false, null, null, data, loc, date,
                TargetType.SUNSET, eventTime, 290, daysAhead,
                EvaluationModel.HAIKU, Set.of(), "k", new OpenMeteoForecastResponse());
    }
}
