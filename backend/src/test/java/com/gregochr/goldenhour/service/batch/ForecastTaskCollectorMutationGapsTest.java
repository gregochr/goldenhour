package com.gregochr.goldenhour.service.batch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
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
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.TideSnapshot;
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
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mutation-gap tests for {@link ForecastTaskCollector#collectScheduledBatches} and, where it
 * shares the decisions, {@link ForecastTaskCollector#collectRegionFilteredBatches}.
 *
 * <p>A PIT run left 51 of 116 mutants alive in this class. The survivors were the decisions the
 * existing suites reach but never pin down: which bucket a task lands in, what a forced
 * disposition records, the bluebell / woodland lane counts, the summary totals and the
 * operator-facing {@code [BATCH DIAG]} / {@code [BATCH ELIG]} lines that report them. Every test
 * here asserts on what the collector returns or records (task lists, dispositions, the
 * pre-fetch report) or on the log line an operator reads to reconcile a cycle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForecastTaskCollector — mutation gaps")
class ForecastTaskCollectorMutationGapsTest {

    private static final SeasonalWindow BLUEBELL_SEASON =
            new SeasonalWindow(MonthDay.of(4, 10), MonthDay.of(5, 20), "BLUEBELL");

    /** Out of bluebell season. */
    private static final LocalDate JAN = LocalDate.of(2026, 1, 15);
    /** In bluebell season (10 April to 20 May). */
    private static final LocalDate APR = LocalDate.of(2026, 4, 20);

    private static final String FORCED_DETAIL = "Force-evaluated best-bet headline candidate";

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

    private final Logger collectorLogger =
            (Logger) LoggerFactory.getLogger(ForecastTaskCollector.class);
    private ListAppender<ILoggingEvent> logs;
    private Level originalLevel;

    private final List<BriefingSlot> briefingSlots = new ArrayList<>();
    private final List<LocationEntity> enabledLocations = new ArrayList<>();
    private final Map<String, OpenMeteoForecastResponse.Hourly> hourlyByName = new HashMap<>();
    private final List<LocationEntity> prefetched = new ArrayList<>();
    private LocalDate today = JAN;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        originalLevel = collectorLogger.getLevel();
        collectorLogger.setLevel(Level.INFO);
        collectorLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        collectorLogger.detachAppender(logs);
        collectorLogger.setLevel(originalLevel);
    }

    // ── The weather pre-fetch gates ───────────────────────────────────────────

    @Test
    @DisplayName("empty pre-fetch: nothing is triaged, classified or published, and the report says 0/N")
    void emptyPrefetch_abortsBeforeAnyTriage() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        slot(a, JAN, Verdict.GO, false);
        slot(b, JAN, Verdict.GO, false);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.dispositions()).isEmpty();
        assertThat(result.weatherPrefetch().locationsRequested()).isEqualTo(2);
        assertThat(result.weatherPrefetch().locationsReturned()).isZero();
        assertThat(result.weatherPrefetch().abortReason()).isEqualTo("0/2 locations returned");
        // any(): a never() verify - triage must not run for any candidate at all.
        verify(forecastService, never()).fetchWeatherAndTriage(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        // any(): a never() verify - no snapshot may be published whatever it holds.
        verify(stabilitySnapshotProvider, never()).update(any());
        verifyNoInteractions(stabilityClassifier);
        assertThat(messages()).anyMatch(m -> m.contains("weather pre-fetch returned 0/2 locations"));
    }

    @Test
    @DisplayName("partial pre-fetch above the ratio proceeds with every candidate, warns 2/3, and reports no abort")
    void partialPrefetch_continuesAndWarns() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        LocationEntity c = loc(3, "Gamma", 56.0, -1.0, false);
        for (LocationEntity l : List.of(a, b, c)) {
            slot(l, JAN, Verdict.GO, false);
            triagePass(l, JAN, false, null);
        }
        prefetched.addAll(List.of(a, b));
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.nearInland()).extracting(t -> t.location().getName())
                .containsExactlyInAnyOrder("Alpha", "Beta", "Gamma");
        assertThat(result.weatherPrefetch().locationsRequested()).isEqualTo(3);
        assertThat(result.weatherPrefetch().locationsReturned()).isEqualTo(2);
        assertThat(result.weatherPrefetch().aborted()).isFalse();
        assertThat(messages()).anyMatch(m -> m.contains("weather pre-fetch partial")
                && m.contains("2/3 locations fetched"));
    }

    @Test
    @DisplayName("a complete pre-fetch does not log the partial warning")
    void completePrefetch_doesNotWarnPartial() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        for (LocationEntity l : List.of(a, b)) {
            slot(l, JAN, Verdict.GO, false);
            triagePass(l, JAN, false, null);
            prefetched.add(l);
        }
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.nearInland()).hasSize(2);
        assertThat(messages()).noneMatch(m -> m.contains("pre-fetch partial"));
    }

    @Test
    @DisplayName("a pre-fetch ratio exactly at the threshold (1/2 at 0.5) is not degraded")
    void prefetchRatioExactlyAtThreshold_proceeds() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        for (LocationEntity l : List.of(a, b)) {
            slot(l, JAN, Verdict.GO, false);
            triagePass(l, JAN, false, null);
        }
        prefetched.add(a);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.weatherPrefetch().aborted()).isFalse();
        assertThat(result.nearInland()).hasSize(2);
    }

    @Test
    @DisplayName("a pre-fetch ratio just below the threshold (1/3 at 0.5) aborts with the ratio in the reason")
    void prefetchRatioBelowThreshold_aborts() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        LocationEntity c = loc(3, "Gamma", 56.0, -1.0, false);
        for (LocationEntity l : List.of(a, b, c)) {
            slot(l, JAN, Verdict.GO, false);
        }
        prefetched.add(a);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.weatherPrefetch().locationsRequested()).isEqualTo(3);
        assertThat(result.weatherPrefetch().locationsReturned()).isEqualTo(1);
        assertThat(result.weatherPrefetch().abortReason())
                .isEqualTo("1/3 locations returned, ratio 0.33 below threshold 0.50");
        // any(): a never() verify - triage must not run for any candidate at all.
        verify(forecastService, never()).fetchWeatherAndTriage(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    // ── The "nothing included" exit ───────────────────────────────────────────

    @Test
    @DisplayName("every candidate triaged out: empty buckets, one SKIPPED_TRIAGED row each, no abort reason")
    void nothingIncluded_returnsDispositionsAndCleanReport() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        for (LocationEntity l : List.of(a, b)) {
            slot(l, JAN, Verdict.GO, false);
            triageStandDown(l, JAN, "heavy cloud");
            prefetched.add(l);
        }
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.dispositions()).hasSize(2)
                .allSatisfy(d -> {
                    assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_TRIAGED);
                    assertThat(d.detail()).isEqualTo("heavy cloud");
                    assertThat(d.daysAhead()).isZero();
                });
        assertThat(result.weatherPrefetch().locationsRequested()).isEqualTo(2);
        assertThat(result.weatherPrefetch().locationsReturned()).isEqualTo(2);
        assertThat(result.weatherPrefetch().aborted()).isFalse();
        assertThat(triageSummary()).isEqualTo("Triage complete — 0 included (near=0, far=0, bluebell=0, "
                + "woodland=0, forced=0), 2 skipped (triage=2, stability=0, error=0)");
        // any(): a never() verify - no pending row may be written for any evaluation.
        verify(forecastService, never()).persistPendingEvaluation(any());
    }

    // ── Totals: one included lane must never cancel another ───────────────────

    @Test
    @DisplayName("one near and one far task together are two included, not zero")
    void nearAndFar_areBothCounted() {
        today = JAN;
        LocationEntity near = loc(1, "Near", 54.0, -1.0, false);
        LocationEntity far = loc(2, "Far", 55.0, -1.0, false);
        LocalDate t2 = JAN.plusDays(2);
        slot(near, JAN, Verdict.GO, false);
        slot(far, t2, Verdict.GO, false);
        triagePass(near, JAN, false, null);
        triagePass(far, t2, false, null);
        prefetched.addAll(List.of(near, far));
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.nearInland()).extracting(t -> t.location().getName()).containsExactly("Near");
        assertThat(result.farInland()).extracting(t -> t.location().getName()).containsExactly("Far");
        assertThat(result.farInland().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(result.nearInland().get(0).model()).isEqualTo(EvaluationModel.SONNET);
        assertThat(triageSummary()).startsWith("Triage complete — 2 included (near=1, far=1, bluebell=0, "
                + "woodland=0, forced=0), 0 skipped");
    }

    @Test
    @DisplayName("an open-fell site in season is one near sky task plus one paired bluebell task, two included")
    void openFellPair_isCountedAsTwo() {
        today = APR;
        LocationEntity fell = bluebellLoc(1, "Fell", 54.0, -1.0, BluebellExposure.OPEN_FELL);
        slot(fell, APR, Verdict.GO, false);
        triagePass(fell, APR, false, BluebellExposure.OPEN_FELL);
        prefetched.add(fell);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(APR, 0.5, 0);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(result.nearInland().get(0).promptKind()).isEqualTo(EvaluationTask.Forecast.PromptKind.SKY);
        assertThat(result.bluebell()).hasSize(1);
        assertThat(result.bluebell().get(0).promptKind())
                .isEqualTo(EvaluationTask.Forecast.PromptKind.BLUEBELL);
        assertThat(result.bluebell().get(0).evalRowId()).isNull();
        assertThat(triageSummary()).startsWith("Triage complete — 2 included (near=1, far=0, bluebell=1, "
                + "woodland=0, forced=0)");
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE Fell")
                && m.contains("tier=near type=inland+bluebell"));
    }

    @Test
    @DisplayName("a near sky task and a woodland task together are two included, not zero")
    void nearAndWoodland_areBothCounted() {
        today = JAN;
        LocationEntity sky = loc(1, "Sky", 54.0, -1.0, false);
        LocationEntity wood = woodlandLoc(2, "Wood", 55.0, -1.0, false);
        slot(sky, JAN, Verdict.GO, false);
        slot(wood, JAN, Verdict.GO, false);
        triagePass(sky, JAN, false, null);
        triagePass(wood, JAN, false, null);
        prefetched.addAll(List.of(sky, wood));
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.nearInland()).extracting(t -> t.location().getName()).containsExactly("Sky");
        assertThat(result.woodland()).extracting(t -> t.location().getName()).containsExactly("Wood");
        assertThat(result.woodland().get(0).promptKind())
                .isEqualTo(EvaluationTask.Forecast.PromptKind.WOODLAND);
        assertThat(triageSummary()).startsWith("Triage complete — 2 included (near=1, far=0, bluebell=0, "
                + "woodland=1, forced=0)");
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE Wood")
                && m.contains("tier=woodland type=canopy") && !m.contains("(forced)"));
    }

    // ── Forced candidates ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a force-evaluated far candidate records FORCE_EVALUATED with its detail; ordinary ones record none")
    void forcedDisposition_carriesDetail_andOrdinaryOnesDoNot() {
        today = JAN;
        LocationEntity ordinary = loc(1, "Ordinary", 54.0, -1.0, false);
        LocationEntity forced = loc(2, "Forced", 55.0, -1.0, true);
        LocalDate t3 = JAN.plusDays(3);
        slot(ordinary, JAN, Verdict.GO, false);
        slot(forced, t3, Verdict.GO, true);
        triagePass(ordinary, JAN, false, null);
        triagePass(forced, t3, false, null);
        stabilityOf(forced, ForecastStability.UNSETTLED);
        prefetched.addAll(List.of(ordinary, forced));
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 1);

        CandidateDisposition ordinaryRow = disposition(result, "Ordinary");
        assertThat(ordinaryRow.category()).isEqualTo(DispositionCategory.EVALUATED);
        assertThat(ordinaryRow.detail()).isNull();
        CandidateDisposition forcedRow = disposition(result, "Forced");
        assertThat(forcedRow.category()).isEqualTo(DispositionCategory.FORCE_EVALUATED);
        assertThat(forcedRow.detail()).isEqualTo(FORCED_DETAIL);
        assertThat(forcedRow.daysAhead()).isEqualTo(3);
        assertThat(result.farInland()).hasSize(1);
        assertThat(result.farInland().get(0).forced()).isTrue();
        assertThat(result.farInland().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(result.nearInland().get(0).forced()).isFalse();
        assertThat(triageSummary()).startsWith("Triage complete — 2 included (near=1, far=1, bluebell=0, "
                + "woodland=0, forced=1)");
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE Forced")
                && m.contains("tier=far type=inland (forced)"));
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE Ordinary")
                && m.endsWith("tier=near type=inland"));
    }

    @Test
    @DisplayName("a force-evaluated bluebell-wood task keeps its forced marker, and is counted as forced")
    void forcedBluebellWood_isCountedAsForced() {
        today = APR;
        LocationEntity wood = woodlandLoc(1, "BlueWood", 54.0, -1.0, true);
        wood.setGridLat(54.0);
        wood.setGridLng(-1.0);
        LocalDate t3 = APR.plusDays(3);
        slot(wood, t3, Verdict.GO, true);
        triagePass(wood, t3, false, BluebellExposure.WOODLAND);
        stabilityOf(wood, ForecastStability.UNSETTLED);
        prefetched.add(wood);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(APR, 0.5, 1);

        assertThat(result.bluebell()).hasSize(1);
        assertThat(result.bluebell().get(0).forced()).isTrue();
        assertThat(result.bluebell().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(result.dispositions()).singleElement().satisfies(d -> {
            assertThat(d.category()).isEqualTo(DispositionCategory.FORCE_EVALUATED);
            assertThat(d.detail()).isEqualTo(FORCED_DETAIL);
        });
        assertThat(triageSummary()).startsWith("Triage complete — 1 included (near=0, far=0, bluebell=1, "
                + "woodland=0, forced=1)");
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE BlueWood")
                && m.endsWith("tier=bluebell type=woodland (forced)"));
    }

    @Test
    @DisplayName("a force-evaluated canopy task is counted as forced in the woodland lane")
    void forcedWoodland_isCountedAsForced() {
        today = JAN;
        LocationEntity wood = woodlandLoc(1, "Canopy", 54.0, -1.0, false);
        wood.setGridLat(54.0);
        wood.setGridLng(-1.0);
        LocalDate t3 = JAN.plusDays(3);
        slot(wood, t3, Verdict.GO, true);
        triagePass(wood, t3, false, null);
        stabilityOf(wood, ForecastStability.UNSETTLED);
        prefetched.add(wood);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 1);

        assertThat(result.woodland()).hasSize(1);
        assertThat(result.woodland().get(0).forced()).isTrue();
        assertThat(triageSummary()).startsWith("Triage complete — 1 included (near=0, far=0, bluebell=0, "
                + "woodland=1, forced=1)");
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE Canopy")
                && m.endsWith("tier=woodland type=canopy (forced)"));
    }

    // ── Bluebell routing and the misconfiguration warning ─────────────────────

    @Test
    @DisplayName("in season, a BLUEBELL site with no exposure takes the sky lane and the warning names it")
    void bluebellSiteWithNullExposure_inSeason_warnsAndStaysInSkyLane() {
        today = APR;
        LocationEntity site = bluebellLoc(1, "NoExposure", 54.0, -1.0, null);
        slot(site, APR, Verdict.GO, false);
        triagePass(site, APR, false, null);
        prefetched.add(site);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(APR, 0.5, 0);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(result.nearInland().get(0).promptKind()).isEqualTo(EvaluationTask.Forecast.PromptKind.SKY);
        assertThat(result.bluebell()).isEmpty();
        assertThat(result.woodland()).isEmpty();
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] NoExposure is a BLUEBELL site in season")
                && m.contains("no bluebell_exposure set"));
    }

    @Test
    @DisplayName("the null-exposure warning is silent when the exposure is set")
    void bluebellSiteWithExposure_doesNotWarn() {
        today = APR;
        LocationEntity site = bluebellLoc(1, "HasExposure", 54.0, -1.0, BluebellExposure.OPEN_FELL);
        slot(site, APR, Verdict.GO, false);
        triagePass(site, APR, false, BluebellExposure.OPEN_FELL);
        prefetched.add(site);
        stubBriefingAndPrefetch();

        collect(APR, 0.5, 0);

        assertThat(messages()).noneMatch(m -> m.contains("no bluebell_exposure set"));
    }

    @Test
    @DisplayName("the null-exposure warning is silent out of season")
    void bluebellSiteWithNullExposure_outOfSeason_doesNotWarn() {
        today = JAN;
        LocationEntity site = bluebellLoc(1, "OffSeason", 54.0, -1.0, null);
        slot(site, JAN, Verdict.GO, false);
        triagePass(site, JAN, false, null);
        prefetched.add(site);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(result.nearInland()).hasSize(1);
        assertThat(messages()).noneMatch(m -> m.contains("no bluebell_exposure set"));
    }

    @Test
    @DisplayName("the null-exposure warning is silent for a site that is not typed BLUEBELL")
    void nonBluebellSiteWithNullExposure_doesNotWarn() {
        today = APR;
        LocationEntity plain = loc(1, "Plain", 54.0, -1.0, false);
        plain.setLocationType(Set.of(LocationType.LANDSCAPE));
        LocationEntity untyped = loc(2, "Untyped", 55.0, -1.0, false);
        untyped.setLocationType(null);
        for (LocationEntity l : List.of(plain, untyped)) {
            slot(l, APR, Verdict.GO, false);
            triagePass(l, APR, false, null);
            prefetched.add(l);
        }
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(APR, 0.5, 0);

        assertThat(result.nearInland()).hasSize(2);
        assertThat(messages()).noneMatch(m -> m.contains("no bluebell_exposure set"));
    }

    @Test
    @DisplayName("a bluebell wood in season is rated by the bluebell lane alone, even when sky triage stands it down")
    void bluebellWood_inSeason_triagedSlotStillGoesToBluebellLane() {
        today = APR;
        LocationEntity wood = woodlandLoc(1, "Wood", 54.0, -1.0, true);
        slot(wood, APR, Verdict.GO, false);
        stub(CollectorTriageStubs.triageOf(forecastService, wood, APR, TargetType.SUNRISE,
                EvaluationModel.SONNET), preEval(wood, APR, false, BluebellExposure.WOODLAND, true));
        prefetched.add(wood);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(APR, 0.5, 0);

        assertThat(result.bluebell()).hasSize(1);
        assertThat(result.nearInland()).isEmpty();
        assertThat(result.dispositions()).singleElement()
                .satisfies(d -> assertThat(d.category()).isEqualTo(DispositionCategory.EVALUATED));
        // any(): a never() verify - no pending row may be written for any evaluation.
        verify(forecastService, never()).persistPendingEvaluation(any());
    }

    // ── Coastal / inland, near / far ──────────────────────────────────────────

    @Test
    @DisplayName("bucketing is by data: tide data goes coastal, none goes inland, across near and far")
    void tideDataDecidesCoastalBucket_acrossNearAndFar() {
        today = JAN;
        LocationEntity nearIn = loc(1, "NearIn", 54.0, -1.0, false);
        LocationEntity nearCo = loc(2, "NearCo", 55.0, -1.0, false);
        LocationEntity farIn = loc(3, "FarIn", 56.0, -1.0, false);
        LocationEntity farCo = loc(4, "FarCo", 57.0, -1.0, false);
        LocalDate t1 = JAN.plusDays(1);
        LocalDate t2 = JAN.plusDays(2);
        slot(nearIn, JAN, Verdict.GO, false);
        slot(nearCo, t1, Verdict.GO, false);
        slot(farIn, t2, Verdict.GO, false);
        slot(farCo, t2, Verdict.GO, false);
        triagePass(nearIn, JAN, false, null);
        triagePass(nearCo, t1, true, null);
        triagePass(farIn, t2, false, null);
        triagePass(farCo, t2, true, null);
        prefetched.addAll(List.of(nearIn, nearCo, farIn, farCo));
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(names(result.nearInland())).containsExactly("NearIn");
        assertThat(names(result.nearCoastal())).containsExactly("NearCo");
        assertThat(names(result.farInland())).containsExactly("FarIn");
        assertThat(names(result.farCoastal())).containsExactly("FarCo");
        assertThat(result.nearCoastal().get(0).model()).isEqualTo(EvaluationModel.SONNET);
        assertThat(result.farCoastal().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE NearCo")
                && m.endsWith("tier=near type=coastal"));
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE FarIn")
                && m.endsWith("tier=far type=inland"));
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] INCLUDE FarCo")
                && m.endsWith("tier=far type=coastal"));
    }

    @Test
    @DisplayName("the near-term boundary: T+1 is near, T+2 is far")
    void nearTermBoundary_isTPlusOne() {
        today = JAN;
        LocationEntity t1Loc = loc(1, "Tomorrow", 54.0, -1.0, false);
        LocationEntity t2Loc = loc(2, "DayAfter", 55.0, -1.0, false);
        slot(t1Loc, JAN.plusDays(1), Verdict.GO, false);
        slot(t2Loc, JAN.plusDays(2), Verdict.GO, false);
        triagePass(t1Loc, JAN.plusDays(1), false, null);
        triagePass(t2Loc, JAN.plusDays(2), false, null);
        prefetched.addAll(List.of(t1Loc, t2Loc));
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(names(result.nearInland())).containsExactly("Tomorrow");
        assertThat(names(result.farInland())).containsExactly("DayAfter");
    }

    @Test
    @DisplayName("a location typed coastal that carries no tide data is bucketed inland, and the collector says so")
    void tideLessCoastalLocation_isInlandAndWarns() {
        today = JAN;
        LocationEntity site = loc(1, "TideLess", 54.0, -1.0, false);
        slot(site, JAN, Verdict.GO, false);
        triagePass(site, JAN, false, null);
        prefetched.add(site);
        when(locationService.isCoastal(site)).thenReturn(true);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(names(result.nearInland())).containsExactly("TideLess");
        assertThat(result.nearCoastal()).isEmpty();
        assertThat(messages()).anyMatch(m -> m.startsWith("[BATCH DIAG] TIDE-LESS COASTAL TideLess")
                && m.contains("bucketed inland"));
    }

    @Test
    @DisplayName("an inland location that is not coastal gets no tide-less warning")
    void inlandLocation_getsNoTideLessWarning() {
        today = JAN;
        LocationEntity site = loc(1, "Inland", 54.0, -1.0, false);
        slot(site, JAN, Verdict.GO, false);
        triagePass(site, JAN, false, null);
        prefetched.add(site);
        stubBriefingAndPrefetch();

        collect(JAN, 0.5, 0);

        assertThat(messages()).noneMatch(m -> m.contains("TIDE-LESS COASTAL"));
    }

    @Test
    @DisplayName("a coastal location that does carry tide data is bucketed coastal and needs no coastal lookup")
    void coastalLocationWithTide_getsNoTideLessWarning() {
        today = JAN;
        LocationEntity site = loc(1, "Coast", 54.0, -1.0, false);
        slot(site, JAN, Verdict.GO, false);
        triagePass(site, JAN, true, null);
        prefetched.add(site);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(JAN, 0.5, 0);

        assertThat(names(result.nearCoastal())).containsExactly("Coast");
        assertThat(messages()).noneMatch(m -> m.contains("TIDE-LESS COASTAL"));
        // any(): a never() verify - the coastal lookup must not be made for any location.
        verify(locationService, never()).isCoastal(any());
    }

    // ── The whole cycle: buckets, dispositions and the summary lines ──────────

    @Test
    @DisplayName("a mixed cycle: every lane, skip reason and forced rescue is bucketed, recorded and summed correctly")
    void mixedCycle_bucketsRecordsAndSummarises() {
        today = APR;
        LocalDate t1 = APR.plusDays(1);
        LocalDate t2 = APR.plusDays(2);
        LocalDate t3 = APR.plusDays(3);

        LocationEntity inlandNear = loc(1, "InlandNear", 54.0, -1.0, false);
        LocationEntity coastalNear = loc(2, "CoastalNear", 54.1, -1.0, false);
        LocationEntity farSettled = loc(3, "FarSettled", 54.2, -1.2, true);
        LocationEntity farCoastal = loc(4, "FarCoastal", 54.3, -1.4, true);
        LocationEntity triaged = loc(5, "Triaged", 54.4, -1.0, false);
        LocationEntity unsettled = loc(6, "Unsettled", 54.5, -1.6, true);
        LocationEntity forced = loc(7, "Forced", 54.6, -1.8, true);
        LocationEntity broken = loc(8, "Broken", 54.7, -1.0, false);
        LocationEntity canopy = woodlandLoc(9, "Canopy", 54.8, -1.0, false);
        LocationEntity bluebellWood = woodlandLoc(10, "BluebellWood", 54.9, -1.0, true);
        LocationEntity openFell = bluebellLoc(11, "OpenFell", 55.0, -1.0, BluebellExposure.OPEN_FELL);

        slot(inlandNear, APR, Verdict.GO, false);
        slot(triaged, APR, Verdict.MARGINAL, false);
        slot(broken, APR, Verdict.MARGINAL, false);
        slot(canopy, APR, Verdict.MARGINAL, false);
        slot(bluebellWood, APR, Verdict.MARGINAL, false);
        slot(coastalNear, t1, Verdict.MARGINAL, false);
        slot(openFell, t1, Verdict.MARGINAL, false);
        slot(farSettled, t2, Verdict.MARGINAL, false);
        slot(farCoastal, t2, Verdict.MARGINAL, false);
        slot(unsettled, t3, Verdict.MARGINAL, false);
        slot(forced, t3, Verdict.GO, true);

        triagePass(inlandNear, APR, false, null);
        triageStandDown(triaged, APR, "visibility");
        when(CollectorTriageStubs.triageOf(forecastService, broken, APR, TargetType.SUNRISE,
                EvaluationModel.SONNET)).thenThrow(new IllegalStateException("boom"));
        triagePass(canopy, APR, false, null);
        triagePass(bluebellWood, APR, false, BluebellExposure.WOODLAND);
        triagePass(coastalNear, t1, true, null);
        triagePass(openFell, t1, false, BluebellExposure.OPEN_FELL);
        triagePass(farSettled, t2, false, null);
        triagePass(farCoastal, t2, true, null);
        triagePass(unsettled, t3, false, null);
        triagePass(forced, t3, false, null);
        stabilityOf(farSettled, ForecastStability.SETTLED);
        stabilityOf(farCoastal, ForecastStability.TRANSITIONAL);
        stabilityOf(unsettled, ForecastStability.UNSETTLED);
        stabilityOf(forced, ForecastStability.UNSETTLED);
        prefetched.addAll(enabledLocations);
        stubBriefingAndPrefetch();

        ScheduledBatchTasks result = collect(APR, 0.5, 1);

        assertThat(names(result.nearInland())).containsExactlyInAnyOrder("InlandNear", "OpenFell");
        assertThat(names(result.nearCoastal())).containsExactly("CoastalNear");
        assertThat(names(result.farInland())).containsExactlyInAnyOrder("FarSettled", "Forced");
        assertThat(names(result.farCoastal())).containsExactly("FarCoastal");
        assertThat(names(result.bluebell())).containsExactlyInAnyOrder("BluebellWood", "OpenFell");
        assertThat(names(result.woodland())).containsExactly("Canopy");
        assertThat(result.totalSize()).isEqualTo(9);
        assertThat(result.farInland()).filteredOn(t -> t.location().getName().equals("Forced"))
                .singleElement().satisfies(t -> assertThat(t.forced()).isTrue());
        assertThat(result.farInland()).filteredOn(t -> t.location().getName().equals("FarSettled"))
                .singleElement().satisfies(t -> assertThat(t.forced()).isFalse());

        assertThat(disposition(result, "Triaged").category()).isEqualTo(DispositionCategory.SKIPPED_TRIAGED);
        assertThat(disposition(result, "Broken").category()).isEqualTo(DispositionCategory.SKIPPED_ERROR);
        assertThat(disposition(result, "Broken").detail()).isEqualTo("boom");
        CandidateDisposition stabilitySkip = disposition(result, "Unsettled");
        assertThat(stabilitySkip.category()).isEqualTo(DispositionCategory.SKIPPED_STABILITY);
        assertThat(stabilitySkip.detail()).isEqualTo("T+3 UNSETTLED");
        assertThat(stabilitySkip.daysAhead()).isEqualTo(3);
        assertThat(disposition(result, "Forced").category()).isEqualTo(DispositionCategory.FORCE_EVALUATED);
        assertThat(result.dispositions()).hasSize(11);

        assertThat(triageSummary()).isEqualTo("Triage complete — 9 included (near=3, far=3, bluebell=2, "
                + "woodland=1, forced=1), 3 skipped (triage=1, stability=1, error=1)");
        assertThat(eligSummary()).isEqualTo("included T+0=3(T:3) T+1=2(T:2) T+2=2(S:1,T:1) T+3=1(U:1) "
                + "| excluded T+3=1(U:1)");
        assertThat(result.weatherPrefetch().locationsRequested()).isEqualTo(11);
        assertThat(result.weatherPrefetch().locationsReturned()).isEqualTo(11);
        assertThat(result.weatherPrefetch().aborted()).isFalse();
    }

    @Test
    @DisplayName("[BATCH ELIG] reads 'none' for a side with no entries")
    void eligSummary_noExclusions_readsNone() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        slot(a, JAN, Verdict.GO, false);
        triagePass(a, JAN, false, null);
        prefetched.add(a);
        stubBriefingAndPrefetch();

        collect(JAN, 0.5, 0);

        assertThat(eligSummary()).isEqualTo("included T+0=1(T:1) | excluded none");
    }

    @Test
    @DisplayName("evening deferral: rated T+1 sunrises are skipped as NO_REFRESH_NEEDED and counted excluded")
    void eveningDeferral_isRecordedAndSummarised() {
        today = JAN;
        LocationEntity a = loc(1, "Alpha", 54.0, -1.0, false);
        LocationEntity b = loc(2, "Beta", 55.0, -1.0, false);
        LocalDate t1 = JAN.plusDays(1);
        for (LocationEntity l : List.of(a, b)) {
            slot(l, t1, Verdict.GO, false);
            triagePass(l, t1, false, null);
            prefetched.add(l);
        }
        stubBriefingAndPrefetch();
        BriefingEvaluationResult rated = new BriefingEvaluationResult("x", 4, 70, 60, "Good", null, null, null);
        when(evaluationViewService.getLiveScoresForEnrichmentBulk(t1, t1,
                Set.of(TargetType.SUNRISE, TargetType.SUNSET)))
                .thenReturn(Map.of(CacheKeyFactory.build("North East", t1, TargetType.SUNRISE),
                        Map.of("Alpha", rated, "Beta", rated)));
        Clock clock = Clock.fixed(JAN.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        ScheduledBatchTasks result = collector(JAN, 0.5, 0).collectScheduledBatches(
                new IntradayCandidateCollectionStrategy(clock, true), IntradayEligibilityPolicy.INSTANCE);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.dispositions()).hasSize(2).allSatisfy(d -> {
            assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
            assertThat(d.daysAhead()).isEqualTo(1);
        });
        assertThat(triageSummary()).isEqualTo("Triage complete — 0 included (near=0, far=0, bluebell=0, "
                + "woodland=0, forced=0), 2 skipped (triage=0, stability=2, error=0)");
        assertThat(eligSummary()).isEqualTo("included none | excluded T+1=2(T:2)");
    }

    // ── The region-filtered admin path ────────────────────────────────────────

    @Test
    @DisplayName("region-filtered: tide splits coastal from inland; canopy, far-out and triaged are dropped")
    void regionFiltered_splitsAndExcludes() {
        today = JAN;
        LocationEntity inland = loc(1, "Inland", 54.0, -1.0, false);
        LocationEntity coastal = loc(2, "Coastal", 55.0, -1.0, false);
        LocationEntity canopy = woodlandLoc(3, "Canopy", 56.0, -1.0, false);
        LocationEntity beyond = loc(4, "Beyond", 57.0, -1.0, false);
        LocationEntity triaged = loc(5, "Triaged", 58.0, -1.0, false);
        for (LocationEntity l : List.of(inland, coastal, canopy, beyond, triaged)) {
            slot(l, JAN, Verdict.GO, false);
            prefetched.add(l);
        }
        ForecastPreEvalResult inlandPre = preEval(inland, JAN, false, null, false);
        ForecastPreEvalResult coastalPre = preEval(coastal, JAN, true, null, false);
        stub(CollectorTriageStubs.triageOf(forecastService, inland, JAN, TargetType.SUNRISE,
                EvaluationModel.SONNET), inlandPre);
        stub(CollectorTriageStubs.triageOf(forecastService, coastal, JAN, TargetType.SUNRISE,
                EvaluationModel.SONNET), coastalPre);
        triagePass(canopy, JAN, false, null);
        stub(CollectorTriageStubs.triageOf(forecastService, beyond, JAN, TargetType.SUNRISE,
                EvaluationModel.SONNET), preEvalAt(beyond, JAN, 5));
        triageStandDown(triaged, JAN, "cloud");
        stubBriefingAndPrefetch(false);

        RegionFilteredBatchTasks result = collector(JAN, 0.5, 0).collectRegionFilteredBatches(null);

        assertThat(names(result.inland())).containsExactly("Inland");
        assertThat(names(result.coastal())).containsExactly("Coastal");
        assertThat(result.inland().get(0).model()).isEqualTo(EvaluationModel.SONNET);
        assertThat(result.weatherPrefetch().locationsRequested()).isEqualTo(5);
        assertThat(result.weatherPrefetch().locationsReturned()).isEqualTo(5);
        // Exactly the two kept slots are persisted; nothing else is (canopy, far-out and triaged are dropped).
        verify(forecastService).persistPendingEvaluation(inlandPre);
        verify(forecastService).persistPendingEvaluation(coastalPre);
        verify(forecastService, never()).persistPendingEvaluation(
                argThat(pre -> pre != inlandPre && pre != coastalPre));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ForecastTaskCollector collector(LocalDate clockDay, double minRatio, int forceCap) {
        today = clockDay;
        Clock clock = Clock.fixed(clockDay.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new ForecastTaskCollector(
                locationService, briefingService, briefingEvaluationService, evaluationViewService,
                forecastService, stabilityClassifier, modelSelectionService,
                openMeteoService, solarService, freshnessResolver,
                stabilitySnapshotProvider, travelDayService,
                minRatio, forceCap, clock, BLUEBELL_SEASON);
    }

    private ScheduledBatchTasks collect(LocalDate clockDay, double minRatio, int forceCap) {
        return collector(clockDay, minRatio, forceCap).collectScheduledBatches(
                NightlyCandidateCollectionStrategy.INSTANCE, NightlyEligibilityPolicy.INSTANCE);
    }

    private LocationEntity loc(long id, String name, double lat, double lon, boolean gridded) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setName(name);
        l.setLat(lat);
        l.setLon(lon);
        if (gridded) {
            l.setGridLat(Math.round(lat * 4) / 4.0);
            l.setGridLng(Math.round(lon * 4) / 4.0);
        }
        RegionEntity region = new RegionEntity();
        region.setId(1L);
        region.setName("North East");
        l.setRegion(region);
        l.setTideType(Set.of());
        enabledLocations.add(l);
        return l;
    }

    private LocationEntity woodlandLoc(long id, String name, double lat, double lon, boolean alsoBluebell) {
        LocationEntity l = loc(id, name, lat, lon, false);
        l.setLocationType(alsoBluebell
                ? Set.of(LocationType.WOODLAND, LocationType.BLUEBELL)
                : Set.of(LocationType.WOODLAND));
        l.setBluebellExposure(BluebellExposure.WOODLAND);
        return l;
    }

    private LocationEntity bluebellLoc(long id, String name, double lat, double lon,
            BluebellExposure exposure) {
        LocationEntity l = loc(id, name, lat, lon, false);
        l.setLocationType(Set.of(LocationType.BLUEBELL));
        l.setBluebellExposure(exposure);
        return l;
    }

    private void slot(LocationEntity l, LocalDate date, Verdict verdict, boolean tideAligned) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE, 0, 0);
        BriefingSlot.TideInfo tide = tideAligned
                ? new BriefingSlot.TideInfo("HIGH", true, null, new BigDecimal("5.0"),
                        false, false, null, null, null)
                : BriefingSlot.TideInfo.NONE;
        briefingSlots.add(new BriefingSlot(l.getName(), date.atTime(5, 30), verdict, weather, tide,
                List.of()));
    }

    /** Stubs the briefing, the model lookups, the freshness lookups and the weather pre-fetch (scheduled path). */
    private void stubBriefingAndPrefetch() {
        stubBriefingAndPrefetch(true);
    }

    /**
     * Stubs the briefing (one region per day, one SUNRISE summary), the model lookups, the freshness lookups and
     * the weather pre-fetch.
     *
     * @param scheduledPath {@code false} for the region-filtered path, which never asks for the far-term model
     */
    private void stubBriefingAndPrefetch(boolean scheduledPath) {
        // Production: near term SONNET, far term HAIKU.
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM)).thenReturn(EvaluationModel.SONNET);
        if (scheduledPath) {
            when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM)).thenReturn(EvaluationModel.HAIKU);
        }
        // With no stability snapshot in memory every region reads as UNSETTLED; the candidate pass asks
        // for that threshold once for its log, then once per briefing day at that day's horizon.
        when(freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED)).thenReturn(Duration.ofHours(6));
        briefingSlots.stream().map(s -> s.solarEventTime().toLocalDate()).distinct().forEach(d ->
                when(freshnessResolver.maxAgeFor((int) ChronoUnit.DAYS.between(today, d),
                        ForecastStability.UNSETTLED)).thenReturn(Duration.ofHours(6)));
        Map<LocalDate, List<BriefingSlot>> byDate = new LinkedHashMap<>();
        for (BriefingSlot s : briefingSlots) {
            byDate.computeIfAbsent(s.solarEventTime().toLocalDate(), d -> new ArrayList<>()).add(s);
        }
        List<BriefingDay> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<BriefingSlot>> e : byDate.entrySet()) {
            BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "Summary", List.of(),
                    e.getValue(), null, null, null, null, null, null);
            days.add(new BriefingDay(e.getKey(), List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of()))));
        }
        when(briefingService.getCachedBriefing()).thenReturn(new DailyBriefingResponse(
                null, null, days, null, null, null, false, null, List.of(), List.of()));
        when(locationService.findAllEnabled()).thenReturn(enabledLocations);
        Map<String, WeatherExtractionResult> weather = new HashMap<>();
        for (LocationEntity l : prefetched) {
            weather.put(OpenMeteoService.coordKey(l.getLat(), l.getLon()),
                    new WeatherExtractionResult(null, forecastResponseFor(l),
                            new OpenMeteoAirQualityResponse()));
        }
        // The coordinates are pinned: one point per distinct grid cell (or bare coordinate) among the
        // candidates' locations, so a collector that asked for none, or for the wrong places, would not
        // receive the fixture. Only the call-report sink is left as any(): it is a pure sink, and the calls
        // it receives are asserted by the existing pre-fetch report tests.
        when(openMeteoService.prefetchWeatherBatchResilient(argThat(requestsExactly(expectedRequests())), any()))
                .thenReturn(weather);
    }

    /** The representative coordinates the collector should request: one per cell, or per bare coordinate. */
    private Set<List<Double>> expectedRequests() {
        Map<String, LocationEntity> byName = new HashMap<>();
        enabledLocations.forEach(l -> byName.put(l.getName(), l));
        Map<String, List<Double>> byGroup = new LinkedHashMap<>();
        for (BriefingSlot s : briefingSlots) {
            LocationEntity l = byName.get(s.locationName());
            String group = l.hasGridCell() ? "cell:" + l.gridCellKey()
                    : "coord:" + OpenMeteoService.coordKey(l.getLat(), l.getLon());
            byGroup.putIfAbsent(group, List.of(l.getLat(), l.getLon()));
        }
        return new java.util.HashSet<>(byGroup.values());
    }

    private static ArgumentMatcher<List<double[]>> requestsExactly(Set<List<Double>> expected) {
        return points -> points != null && points.size() == expected.size()
                && points.stream().map(p -> List.of(p[0], p[1])).collect(Collectors.toSet()).equals(expected);
    }

    private OpenMeteoForecastResponse.Hourly hourlyFor(LocationEntity l) {
        return hourlyByName.computeIfAbsent(l.getName(), n -> new OpenMeteoForecastResponse.Hourly());
    }

    private OpenMeteoForecastResponse forecastResponseFor(LocationEntity l) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourlyFor(l));
        return response;
    }

    /** Stubs the grid-cell classification of one gridded location: its cell, grid point and fetched hourly data. */
    private void stabilityOf(LocationEntity l, ForecastStability stability) {
        when(stabilityClassifier.classify(eq(l.gridCellKey()), eq(l.getGridLat().doubleValue()),
                eq(l.getGridLng().doubleValue()), same(hourlyFor(l))))
                .thenReturn(new GridCellStabilityResult(l.gridCellKey(), l.getGridLat(), l.getGridLng(),
                        stability, "stub", 0));
    }

    private void triagePass(LocationEntity l, LocalDate date, boolean tide, BluebellExposure scoreExposure) {
        stub(CollectorTriageStubs.triageOf(forecastService, l, date, TargetType.SUNRISE,
                EvaluationModel.SONNET), preEval(l, date, tide, scoreExposure, false));
    }

    private void triageStandDown(LocationEntity l, LocalDate date, String reason) {
        stub(CollectorTriageStubs.triageOf(forecastService, l, date, TargetType.SUNRISE,
                EvaluationModel.SONNET), new ForecastPreEvalResult(true, reason, null, null, l, date,
                TargetType.SUNRISE, date.atTime(5, 30), 60, 0, EvaluationModel.HAIKU, Set.of(), "k", null));
    }

    private static void stub(ForecastPreEvalResult call, ForecastPreEvalResult result) {
        when(call).thenReturn(result);
    }

    private ForecastPreEvalResult preEval(LocationEntity l, LocalDate date, boolean tide,
            BluebellExposure scoreExposure, boolean triaged) {
        LocalDateTime eventTime = date.atTime(5, 30);
        TestAtmosphericData builder = TestAtmosphericData.builder()
                .locationName(l.getName())
                .solarEventTime(eventTime)
                .targetType(TargetType.SUNRISE);
        if (tide) {
            builder.tide(new TideSnapshot(TideState.HIGH, eventTime.plusHours(2), BigDecimal.valueOf(5.5),
                    eventTime.plusHours(8), BigDecimal.valueOf(0.5), false,
                    eventTime.plusHours(2), eventTime.plusHours(8), null, null, null, null));
        }
        if (scoreExposure != null) {
            builder.bluebellConditionScore(new BluebellConditionScore(
                    7, true, true, true, false, false, true, scoreExposure, "Bright still light."));
        }
        AtmosphericData data = builder.build();
        int daysAhead = (int) java.time.temporal.ChronoUnit.DAYS.between(today, date);
        return new ForecastPreEvalResult(triaged, triaged ? "cloud" : null, null, data, l, date,
                TargetType.SUNRISE, eventTime, 60, daysAhead, EvaluationModel.HAIKU, Set.of(), "k",
                new OpenMeteoForecastResponse());
    }

    private ForecastPreEvalResult preEvalAt(LocationEntity l, LocalDate date, int daysAhead) {
        ForecastPreEvalResult base = preEval(l, date, false, null, false);
        return new ForecastPreEvalResult(false, null, null, base.atmosphericData(), l, date,
                TargetType.SUNRISE, base.eventTime(), 60, daysAhead, EvaluationModel.HAIKU, Set.of(), "k",
                new OpenMeteoForecastResponse());
    }

    private static List<String> names(List<EvaluationTask.Forecast> tasks) {
        return tasks.stream().map(t -> t.location().getName()).toList();
    }

    private static CandidateDisposition disposition(ScheduledBatchTasks result, String name) {
        return result.dispositions().stream().filter(d -> d.locationName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no disposition for " + name));
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String triageSummary() {
        return messages().stream().filter(m -> m.startsWith("[BATCH DIAG] Triage complete"))
                .findFirst().map(m -> m.substring("[BATCH DIAG] ".length()))
                .orElseThrow(() -> new AssertionError("no triage summary logged: " + messages()));
    }

    private String eligSummary() {
        return messages().stream().filter(m -> m.startsWith("[BATCH ELIG] "))
                .findFirst().map(m -> m.substring("[BATCH ELIG] ".length()))
                .orElseThrow(() -> new AssertionError("no eligibility summary logged: " + messages()));
    }
}
