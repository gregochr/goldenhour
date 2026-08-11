package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.model.SeasonalWindow;
import java.time.MonthDay;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.Verdict;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests that the CACHED gate inside {@link ForecastTaskCollector} uses
 * stability-driven freshness thresholds via {@link FreshnessResolver}.
 *
 * <p>Originally tested {@code ScheduledBatchEvaluationService.collectForecastTasks}
 * directly; following the v2.12.5 (Pass 3.2.1) collector extraction, this targets
 * the collector's package-private {@code collectForecastCandidates} method.
 */
@ExtendWith(MockitoExtension.class)
class CollectForecastTasksCachedGateTest {

    /** April 10 - May 20: matches the production bluebell window shape. */
    private static final SeasonalWindow BLUEBELL_SEASON =
            new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL");


    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private ForecastService forecastService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private SolarService solarService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private com.gregochr.goldenhour.service.evaluation.SurvivorAtmosphereWriter
            survivorAtmosphereWriter;
    @Mock private com.gregochr.goldenhour.service.TravelDayService travelDayService;

    private ForecastTaskCollector collector;

    // Fixed date + clock so "today" is deterministic (no near-midnight UTC/London flake).
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 1, 15);
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            FIXED_TODAY.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);

    /** Tomorrow's date — always in the future so PAST_DATE doesn't trigger. */
    private final LocalDate tomorrow = FIXED_TODAY.plusDays(1);

    /**
     * T+3 — beyond the horizon caps, so the stability threshold alone governs.
     *
     * <p>The stability cases below deliberately sit here rather than at T+1. Their subject is the
     * stability table (36 / 12 / 4), and at T+1 the 8h horizon cap would be the binding term for
     * SETTLED and TRANSITIONAL, so the assertions would be pinning the cap while claiming to pin
     * the table. Horizon capping has its own nest.
     */
    private final LocalDate inThreeDays = FIXED_TODAY.plusDays(3);

    @BeforeEach
    void setUp() {
        FreshnessProperties props = new FreshnessProperties();
        props.setSettledHours(36);
        props.setTransitionalHours(12);
        props.setUnsettledHours(4);
        props.setSafetyFloorHours(2);
        props.getHorizon().setT0Hours(2);
        props.getHorizon().setT1Hours(8);
        FreshnessResolver freshnessResolver = new FreshnessResolver(props);

        collector = new ForecastTaskCollector(
                locationService, briefingService,
                briefingEvaluationService, forecastService, stabilityClassifier,
                modelSelectionService, openMeteoService, solarService,
                freshnessResolver, stabilitySnapshotProvider, survivorAtmosphereWriter,
                travelDayService, 0.5, 0, CLOCK,
                BLUEBELL_SEASON);
    }

    @SuppressWarnings("unchecked")
    private List<?> invokeCollectForecastCandidates(DailyBriefingResponse briefing,
            List<CandidateDisposition> dispositions) throws Exception {
        Method method = ForecastTaskCollector.class
                .getDeclaredMethod("collectForecastCandidates",
                        DailyBriefingResponse.class, List.class,
                        CandidateCollectionStrategy.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(collector, briefing, dispositions,
                NightlyCandidateCollectionStrategy.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    private List<?> invokeCollectForecastCandidates(DailyBriefingResponse briefing)
            throws Exception {
        // V101 added a second parameter (List<CandidateDisposition>) that the
        // first pass appends to. Commit 2 (orchestrator extraction) added a
        // third parameter (CandidateCollectionStrategy) so the same iteration
        // serves nightly + intraday cycles. This test exercises the
        // stability-driven cache-gate behaviour only and passes nightly's
        // accept-all strategy so the iteration matches the pre-extraction
        // behaviour bit-for-bit.
        Method method = ForecastTaskCollector.class
                .getDeclaredMethod("collectForecastCandidates",
                        DailyBriefingResponse.class, List.class,
                        CandidateCollectionStrategy.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(collector, briefing,
                new ArrayList<CandidateDisposition>(),
                NightlyCandidateCollectionStrategy.INSTANCE);
    }

    private DailyBriefingResponse briefingWithOneSlot(String regionName, String locationName) {
        return briefingWithOneSlot(regionName, locationName, tomorrow);
    }

    /**
     * One-slot briefing on an explicit target date, so a test can choose its horizon.
     *
     * @param regionName   region the slot belongs to (first half of the cache key)
     * @param locationName the slot's location
     * @param date         the target date — the horizon under test, relative to FIXED_TODAY
     * @return a briefing carrying exactly that one GO slot
     */
    private DailyBriefingResponse briefingWithOneSlot(String regionName, String locationName,
            LocalDate date) {
        BriefingSlot slot = new BriefingSlot(
                locationName, date.atTime(8, 0), Verdict.GO,
                null, null, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                regionName, Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(date, List.of(eventSummary));
        return new DailyBriefingResponse(
                LocalDateTime.now(), null, List.of(day), List.of(),
                null, null, false, false, 0, null, List.of(), List.of());
    }

    /**
     * The cache key the gate builds for a region on a date, always SUNRISE in this fixture.
     *
     * @param regionName region name
     * @param date       target date
     * @return the {@code region|date|SUNRISE} cache key
     */
    private static String cacheKeyFor(String regionName, LocalDate date) {
        return regionName + "|" + date + "|SUNRISE";
    }

    private LocationEntity locationEntity(String name, long id) {
        LocationEntity loc = new LocationEntity();
        loc.setName(name);
        loc.setId(id);
        return loc;
    }

    private StabilitySummaryResponse snapshotWith(String locationName,
            ForecastStability stability) {
        StabilitySummaryResponse.GridCellDetail cell =
                new StabilitySummaryResponse.GridCellDetail(
                        "54.75,-1.63", 54.75, -1.63, stability,
                        "test", stability.evaluationWindowDays(),
                        List.of(locationName));
        return new StabilitySummaryResponse(
                Instant.now(), 1, Map.of(stability, 1L), List.of(cell));
    }

    @Nested
    @DisplayName("Stability-driven CACHED gate (T+3 — beyond the horizon caps)")
    class StabilityDrivenCachedGate {

        @Test
        @DisplayName("SETTLED cell with 30h-old cache entry — skipped (within 36h threshold)")
        void settledWithin36hSkipped() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(36)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("SETTLED cell with 40h-old cache entry — candidate (beyond 36h threshold)")
        void settledBeyond36hRefreshed() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(36)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("UNSETTLED cell with 6h-old cache entry — candidate (beyond 4h threshold)")
        void unsettledBeyond4hRefreshed() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.UNSETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("UNSETTLED cell with 3h-old cache entry — skipped (within 4h threshold)")
        void unsettledWithin4hSkipped() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.UNSETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("No stability snapshot — treated as UNSETTLED (4h threshold)")
        void noSnapshotFallsBackToUnsettled() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("Location not in snapshot — treated as UNSETTLED (4h threshold)")
        void unknownLocationFallsBackToUnsettled() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            // Snapshot has a different location
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("OtherPlace", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("TRANSITIONAL cell — uses 12h threshold")
        void transitionalUses12hThreshold() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.TRANSITIONAL));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(12)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).isEmpty();
        }
    }

    @Nested
    @DisplayName("Horizon-capped CACHED gate")
    class HorizonCappedCachedGate {

        /**
         * The reported bug and its boundary, as a pair.
         *
         * <p>A 5★ sunset was evaluated at T+1 and never re-evaluated on the day of the event: the
         * region was SETTLED, the entry was ~23h old, and 23h sits inside the 36h stability
         * threshold. The gate ran before {@code NightlyEligibilityPolicy}, whose T+0 arm includes
         * every stability, so the horizon never got a say.
         *
         * <p>The two tests differ only in the target date. That is the whole fix.
         */
        @Test
        @DisplayName("REGRESSION: SETTLED cell, 23h-old entry at T+0 — collected (2h cap, "
                + "not the 36h stability threshold)")
        void settledAtTodayIsRefreshedDespiteStabilityThreshold() throws Exception {
            String cacheKey = cacheKeyFor("North East", FIXED_TODAY);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            // A 23h-old entry is NOT fresh against a 2h cap. Before the fix the gate asked with
            // 36h, the answer was "fresh", and the whole region was dropped.
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(2)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", FIXED_TODAY));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("BOUNDARY: the same SETTLED cell and 23h-old entry at T+3 — still skipped")
        void settledAtThreeDaysStillHonoursStabilityThreshold() throws Exception {
            String cacheKey = cacheKeyFor("North East", inThreeDays);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(36)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", inThreeDays));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("T+1 SETTLED — asks with the 8h cap, not the 36h stability threshold")
        void settledAtTomorrowUsesEightHourCap() throws Exception {
            String cacheKey = cacheKeyFor("North East", tomorrow);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(8)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", tomorrow));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("T+1 UNSETTLED — stability's 4h is tighter than the 8h cap, so 4h wins")
        void unsettledAtTomorrowKeepsTighterStabilityThreshold() throws Exception {
            String cacheKey = cacheKeyFor("North East", tomorrow);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.UNSETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", tomorrow));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("T+0 UNSETTLED — the 2h cap is tighter than stability's 4h, so 2h wins")
        void unsettledAtTodayTakesTheCap() throws Exception {
            String cacheKey = cacheKeyFor("North East", FIXED_TODAY);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.UNSETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(2)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", FIXED_TODAY));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("The CACHED disposition names the horizon alongside the threshold")
        void cachedDispositionCarriesTheHorizon() throws Exception {
            String cacheKey = cacheKeyFor("North East", FIXED_TODAY);
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(2)))).thenReturn(true);

            List<CandidateDisposition> dispositions = new ArrayList<>();
            invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh", FIXED_TODAY), dispositions);

            // This string is the whole of what forecast_run_disposition records about a cache
            // skip, and reading a threshold without its horizon is what made the original
            // diagnosis take a day.
            assertThat(dispositions)
                    .singleElement()
                    .satisfies(d -> assertThat(d.detail())
                            .isEqualTo("Fresh cached evaluation within 2h (T+0, SETTLED)"));
        }
    }

    @Nested
    @DisplayName("Travel-day gate")
    class TravelDayGate {

        @Test
        @DisplayName("target date is a travel day — whole day skipped, no candidate, "
                + "SKIPPED_TRAVEL_DAY disposition recorded")
        void travelDaySkipsWholeDay() throws Exception {
            when(travelDayService.isTravelDay(tomorrow)).thenReturn(true);

            List<CandidateDisposition> dispositions = new ArrayList<>();
            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"), dispositions);

            assertThat(tasks).isEmpty();
            assertThat(dispositions)
                    .singleElement()
                    .satisfies(d -> {
                        assertThat(d.category())
                                .isEqualTo(com.gregochr.goldenhour.entity.DispositionCategory
                                        .SKIPPED_TRAVEL_DAY);
                        assertThat(d.locationName()).isEqualTo("Bamburgh");
                        assertThat(d.evaluationDate()).isEqualTo(tomorrow);
                    });
        }

        @Test
        @DisplayName("not a travel day — slot passes through to a candidate")
        void nonTravelDayPassesThrough() throws Exception {
            when(travelDayService.isTravelDay(tomorrow)).thenReturn(false);
            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(briefingEvaluationService.hasFreshEvaluation(any(), any(Duration.class)))
                    .thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Absent cache entry")
    class AbsentCacheEntry {

        @Test
        @DisplayName("absent cache entry falls through to other filters")
        void absentCacheEntryPassesThrough() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    any(Duration.class))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).hasSize(1);
        }
    }
}
