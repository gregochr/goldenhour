package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
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

    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private ForecastService forecastService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private SolarService solarService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private ForecastTaskCollector collector;

    /** Tomorrow's date — always in the future so PAST_DATE doesn't trigger. */
    private final LocalDate tomorrow = LocalDate.now().plusDays(1);

    @BeforeEach
    void setUp() {
        FreshnessProperties props = new FreshnessProperties();
        props.setSettledHours(36);
        props.setTransitionalHours(12);
        props.setUnsettledHours(4);
        props.setSafetyFloorHours(2);
        FreshnessResolver freshnessResolver = new FreshnessResolver(props);

        collector = new ForecastTaskCollector(
                locationService, briefingService,
                briefingEvaluationService, forecastService, stabilityClassifier,
                modelSelectionService, openMeteoService, solarService,
                freshnessResolver, stabilitySnapshotProvider, 0.5);
    }

    @SuppressWarnings("unchecked")
    private List<?> invokeCollectForecastCandidates(DailyBriefingResponse briefing)
            throws Exception {
        Method method = ForecastTaskCollector.class
                .getDeclaredMethod("collectForecastCandidates", DailyBriefingResponse.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(collector, briefing);
    }

    private DailyBriefingResponse briefingWithOneSlot(String regionName, String locationName) {
        BriefingSlot slot = new BriefingSlot(
                locationName, LocalDateTime.now().plusDays(1), Verdict.GO,
                null, null, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                regionName, Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(tomorrow, List.of(eventSummary));
        return new DailyBriefingResponse(
                LocalDateTime.now(), null, List.of(day), List.of(),
                null, null, false, false, 0, null, List.of(), List.of());
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
    @DisplayName("Stability-driven CACHED gate")
    class StabilityDrivenCachedGate {

        @Test
        @DisplayName("SETTLED cell with 30h-old cache entry — skipped (within 36h threshold)")
        void settledWithin36hSkipped() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(36)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("SETTLED cell with 40h-old cache entry — candidate (beyond 36h threshold)")
        void settledBeyond36hRefreshed() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(36)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("UNSETTLED cell with 6h-old cache entry — candidate (beyond 4h threshold)")
        void unsettledBeyond4hRefreshed() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.UNSETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("UNSETTLED cell with 3h-old cache entry — skipped (within 4h threshold)")
        void unsettledWithin4hSkipped() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.UNSETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("No stability snapshot — treated as UNSETTLED (4h threshold)")
        void noSnapshotFallsBackToUnsettled() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(false);
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).hasSize(1);
        }

        @Test
        @DisplayName("Location not in snapshot — treated as UNSETTLED (4h threshold)")
        void unknownLocationFallsBackToUnsettled() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            // Snapshot has a different location
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("OtherPlace", ForecastStability.SETTLED));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(4)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("TRANSITIONAL cell — uses 12h threshold")
        void transitionalUses12hThreshold() throws Exception {
            String cacheKey = "North East|" + tomorrow + "|SUNRISE";
            when(stabilitySnapshotProvider.getLatestStabilitySummary())
                    .thenReturn(snapshotWith("Bamburgh", ForecastStability.TRANSITIONAL));
            when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                    eq(Duration.ofHours(12)))).thenReturn(true);

            List<?> tasks = invokeCollectForecastCandidates(
                    briefingWithOneSlot("North East", "Bamburgh"));

            assertThat(tasks).isEmpty();
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
