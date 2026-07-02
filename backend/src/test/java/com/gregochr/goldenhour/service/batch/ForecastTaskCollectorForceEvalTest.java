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
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for the targeted, capped force-evaluation rule in
 * {@link ForecastTaskCollector} — the second half of the Option C best-bet fix.
 *
 * <p>Force-evaluation rescues a small, capped number of far-out GO + tide-aligned
 * headline contenders that the Gate 4 stability gate would otherwise drop, so a
 * clear far-out day can be crowned with real Claude evidence behind it. It is a
 * rule WITHIN the single eligibility loop — forced evals flow through the same
 * far-term bucket and the same {@code ScheduledBatchTasks} as everything else, not
 * a parallel pathway.
 */
@ExtendWith(MockitoExtension.class)
class ForecastTaskCollectorForceEvalTest {

    // Fixed date + clock so "today" is deterministic (no near-midnight UTC/London flake).
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            TODAY.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);
    private static final LocalDateTime EVENT_TIME = TODAY.atTime(5, 30);

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
    @Mock private com.gregochr.goldenhour.service.evaluation.SurvivorAtmosphereWriter
            survivorAtmosphereWriter;
    @Mock private com.gregochr.goldenhour.service.TravelDayService travelDayService;

    private ForecastTaskCollector collectorWithCap(int cap) {
        ForecastTaskCollector c = new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, survivorAtmosphereWriter, travelDayService, 0.5, cap,
                CLOCK);
        lenient().when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));
        return c;
    }

    @BeforeEach
    void stubModels() {
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        lenient().when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("Force-eval rescues a stability-gated far-out headline candidate into the far bucket")
    void forceEvalRescuesStabilityGatedFarCandidate() {
        // T+3 GO, UNSETTLED → Gate 4 would skip. With cap 1 it is force-evaluated.
        LocationEntity loc = gridLocation("Bamburgh");
        stubBriefing(buildBriefing(TODAY.plusDays(3), TargetType.SUNSET,
                region("Northumberland", goSlot(loc.getName(), true))));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubPrefetch(loc);
        stubTriagePass(loc, TODAY.plusDays(3), 3, false);
        stubStability(loc, ForecastStability.UNSETTLED);

        ForecastTaskCollector collector = collectorWithCap(1);
        ScheduledBatchTasks result = collector.collectScheduledBatches();

        // Single path: the forced eval lands in the ordinary far-inland bucket on
        // the far-term model — no separate structure.
        assertThat(result.farInland()).hasSize(1);
        assertThat(result.farInland().get(0).location().getName()).isEqualTo("Bamburgh");
        assertThat(result.farInland().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(result.nearInland()).isEmpty();

        List<CandidateDisposition> forced = result.dispositions().stream()
                .filter(d -> d.category() == DispositionCategory.FORCE_EVALUATED).toList();
        assertThat(forced).hasSize(1);
        assertThat(forced.get(0).locationName()).isEqualTo("Bamburgh");
        assertThat(forced.get(0).daysAhead()).isEqualTo(3);
    }

    @Test
    @DisplayName("Cap 0 disables force-eval — the gated candidate stays skipped")
    void capZeroDisablesForceEval() {
        LocationEntity loc = gridLocation("Bamburgh");
        stubBriefing(buildBriefing(TODAY.plusDays(3), TargetType.SUNSET,
                region("Northumberland", goSlot(loc.getName(), true))));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubPrefetch(loc);
        stubTriagePass(loc, TODAY.plusDays(3), 3, false);
        stubStability(loc, ForecastStability.UNSETTLED);

        ScheduledBatchTasks result = collectorWithCap(0).collectScheduledBatches();

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.dispositions())
                .extracting(CandidateDisposition::category)
                .containsExactly(DispositionCategory.SKIPPED_STABILITY);
    }

    @Test
    @DisplayName("Force-eval cap is respected under many GO candidates")
    void capRespectedUnderManyCandidates() {
        // Five far-out GO locations all UNSETTLED; cap 2 → exactly 2 forced, 3 skipped.
        List<LocationEntity> locs = new ArrayList<>();
        List<BriefingSlot> slots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocationEntity loc = gridLocation("Loc" + i, 54.0 + i * 0.1, -1.0 - i * 0.1);
            locs.add(loc);
            slots.add(goSlot(loc.getName(), false));
        }
        stubBriefing(buildBriefing(TODAY.plusDays(3), TargetType.SUNSET,
                region("Northumberland", slots.toArray(new BriefingSlot[0]))));
        when(locationService.findAllEnabled()).thenReturn(locs);
        for (LocationEntity loc : locs) {
            stubTriagePass(loc, TODAY.plusDays(3), 3, false);
        }
        // Prefetch all unique coords.
        Map<String, WeatherExtractionResult> prefetch = new java.util.HashMap<>();
        for (LocationEntity loc : locs) {
            prefetch.put(OpenMeteoService.coordKey(loc.getLat(), loc.getLon()), dummyExtraction());
        }
        when(openMeteoService.prefetchWeatherBatchResilient(any())).thenReturn(prefetch);
        lenient().when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> new GridCellStabilityResult(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        ForecastStability.UNSETTLED, "active", 0));

        ScheduledBatchTasks result = collectorWithCap(2).collectScheduledBatches();

        long forced = result.dispositions().stream()
                .filter(d -> d.category() == DispositionCategory.FORCE_EVALUATED).count();
        long skipped = result.dispositions().stream()
                .filter(d -> d.category() == DispositionCategory.SKIPPED_STABILITY).count();
        assertThat(forced).isEqualTo(2);
        assertThat(skipped).isEqualTo(3);
        assertThat(result.farInland()).hasSize(2);
    }

    @Test
    @DisplayName("Tide-aligned cell wins the force-eval budget over an equal-GO inland cell")
    void tideAlignedCellRankedFirstUnderCap() {
        // Two single-slot far-out GO cells, equal GO count; the tide-aligned one
        // must take the single force-eval slot (merit tiebreak).
        LocationEntity inland = gridLocation("Inland", 54.0, -1.0);
        LocationEntity coastal = gridLocation("Coastal", 55.0, -1.5);
        DailyBriefingResponse briefing = new DailyBriefingResponse(null, null, List.of(
                new BriefingDay(TODAY.plusDays(3), List.of(new BriefingEventSummary(
                        TargetType.SUNSET, List.of(
                                region("Inland Region", goSlot(inland.getName(), false)),
                                region("Coastal Region", goSlot(coastal.getName(), true))),
                        List.of())))), null, null, null, false, false, 0, null,
                List.of(), List.of());
        stubBriefing(briefing);
        when(locationService.findAllEnabled()).thenReturn(List.of(inland, coastal));
        stubTriagePass(inland, TODAY.plusDays(3), 3, false);
        stubTriagePass(coastal, TODAY.plusDays(3), 3, false);
        Map<String, WeatherExtractionResult> prefetch = Map.of(
                OpenMeteoService.coordKey(inland.getLat(), inland.getLon()), dummyExtraction(),
                OpenMeteoService.coordKey(coastal.getLat(), coastal.getLon()), dummyExtraction());
        when(openMeteoService.prefetchWeatherBatchResilient(any())).thenReturn(prefetch);
        lenient().when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> new GridCellStabilityResult(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        ForecastStability.UNSETTLED, "active", 0));

        ScheduledBatchTasks result = collectorWithCap(1).collectScheduledBatches();

        List<CandidateDisposition> forced = result.dispositions().stream()
                .filter(d -> d.category() == DispositionCategory.FORCE_EVALUATED).toList();
        assertThat(forced).hasSize(1);
        assertThat(forced.get(0).locationName()).isEqualTo("Coastal");
    }

    @Test
    @DisplayName("Near-term candidates are evaluated normally, never force-evaluated")
    void nearTermNotForceEvaluated() {
        // T+1 is always eligible under Gate 4 — it must be EVALUATED, not FORCE_EVALUATED.
        LocationEntity loc = gridLocation("Bamburgh");
        stubBriefing(buildBriefing(TODAY.plusDays(1), TargetType.SUNSET,
                region("Northumberland", goSlot(loc.getName(), true))));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubPrefetch(loc);
        stubTriagePass(loc, TODAY.plusDays(1), 1, false);

        ScheduledBatchTasks result = collectorWithCap(6).collectScheduledBatches();

        assertThat(result.nearInland()).hasSize(1);
        assertThat(result.dispositions())
                .extracting(CandidateDisposition::category)
                .containsExactly(DispositionCategory.EVALUATED);
    }

    // ── helpers ──

    private void stubBriefing(DailyBriefingResponse briefing) {
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
    }

    private void stubPrefetch(LocationEntity loc) {
        when(openMeteoService.prefetchWeatherBatchResilient(any())).thenReturn(
                Map.of(OpenMeteoService.coordKey(loc.getLat(), loc.getLon()), dummyExtraction()));
    }

    private void stubStability(LocationEntity loc, ForecastStability stability) {
        when(stabilityClassifier.classify(any(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GridCellStabilityResult(loc.gridCellKey(),
                        loc.getGridLat(), loc.getGridLng(), stability, "reason", 0));
    }

    private void stubTriagePass(LocationEntity loc, LocalDate date, int daysAhead,
            boolean coastal) {
        when(forecastService.fetchWeatherAndTriage(
                org.mockito.ArgumentMatchers.eq(loc), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any()))
                .thenReturn(preEval(loc, date, daysAhead, coastal));
    }

    private ForecastPreEvalResult preEval(LocationEntity loc, LocalDate date, int daysAhead,
            boolean coastal) {
        TestAtmosphericData b = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .solarEventTime(EVENT_TIME)
                .targetType(TargetType.SUNSET);
        if (coastal) {
            b.tide(new com.gregochr.goldenhour.model.TideSnapshot(
                    com.gregochr.goldenhour.entity.TideState.HIGH, EVENT_TIME.plusHours(2),
                    BigDecimal.valueOf(5.5), EVENT_TIME.plusHours(8), BigDecimal.valueOf(0.5),
                    false, EVENT_TIME.plusHours(2), EVENT_TIME.plusHours(8),
                    null, null, null, null));
        }
        AtmosphericData data = b.build();
        return new ForecastPreEvalResult(false, null, null, data, loc, date,
                TargetType.SUNSET, EVENT_TIME, 60, daysAhead,
                EvaluationModel.HAIKU, Set.of(), "k", new OpenMeteoForecastResponse());
    }

    private WeatherExtractionResult dummyExtraction() {
        return new WeatherExtractionResult(
                null, new OpenMeteoForecastResponse(), new OpenMeteoAirQualityResponse());
    }

    private DailyBriefingResponse buildBriefing(LocalDate date, TargetType targetType,
            BriefingRegion... regions) {
        BriefingDay day = new BriefingDay(date, List.of(
                new BriefingEventSummary(targetType, List.of(regions), List.of())));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());
    }

    private BriefingRegion region(String name, BriefingSlot... slots) {
        return new BriefingRegion(name, Verdict.GO, "Summary", List.of(), List.of(slots),
                null, null, null, null, null, null);
    }

    private BriefingSlot goSlot(String name, boolean tideAligned) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE, 0, 0);
        BriefingSlot.TideInfo tide = tideAligned
                ? new BriefingSlot.TideInfo("HIGH", true, null, new BigDecimal("5.0"),
                        false, false, null, null, null)
                : BriefingSlot.TideInfo.NONE;
        return new BriefingSlot(name, EVENT_TIME, Verdict.GO, weather, tide, List.of(), null);
    }

    private LocationEntity gridLocation(String name) {
        return gridLocation(name, 54.7753, -1.5849);
    }

    private LocationEntity gridLocation(String name, double lat, double lon) {
        LocationEntity loc = new LocationEntity();
        loc.setId((long) Math.abs(name.hashCode()));
        loc.setName(name);
        loc.setLat(lat);
        loc.setLon(lon);
        loc.setGridLat(Math.round(lat * 4) / 4.0);
        loc.setGridLng(Math.round(lon * 4) / 4.0);
        RegionEntity region = new RegionEntity();
        region.setId(1L);
        region.setName("North East");
        loc.setRegion(region);
        loc.setTideType(Set.of());
        return loc;
    }
}
