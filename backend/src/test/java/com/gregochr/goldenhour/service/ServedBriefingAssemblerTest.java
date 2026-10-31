package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BigSeasDay;
import com.gregochr.goldenhour.model.BigSeasStructure;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.service.pipeline.BestBetFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.gregochr.goldenhour.service.TideFactFixtures.coastal;
import static com.gregochr.goldenhour.service.TideFactFixtures.day;
import static com.gregochr.goldenhour.service.TideFactFixtures.inland;
import static com.gregochr.goldenhour.service.TideFactFixtures.region;
import static com.gregochr.goldenhour.service.TideFactFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL {@link ServedBriefingAssembler#assembleForPlan} with the REAL
 * {@link BriefingHonestyFilter}, to prove that per-location tide facts survive the filter that
 * empties a zero-coverage region's slots.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServedBriefingAssemblerTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 11);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BestBetFallbackService bestBetFallbackService;
    @Mock
    private BriefingRegionSnapshotService regionSnapshotService;
    @Mock
    private WindowTideRollupBuilder windowTideRollupBuilder;
    @Mock
    private BigSeasBuilder bigSeasBuilder;
    @Mock
    private EvaluationViewService evaluationViewService;
    @Mock
    private LocationPointProvider locationPointProvider;

    /** The guard's default: off, so this class's existing assertions are about the unguarded path. */
    private final NeighbourGuardProperties neighbourGuardProperties = new NeighbourGuardProperties();

    private ServedBriefingAssembler assembler;

    @BeforeEach
    void setUp() {
        // These tests are about the other steps; the guard has its own tests, so it is stated off here.
        neighbourGuardProperties.setEnabled(false);
        when(regionSnapshotService.previousBuild(any()))
                .thenReturn(BriefingRegionSnapshotService.PreviousBuild.none());
        when(evaluationViewService.loadServeTriage(any(), any(), any()))
                .thenReturn(EvaluationViewService.ServeTriage.EMPTY);
        // The enricher is a stub that leaves the hierarchy as cached: the fixture region's
        // scoredLocationCount of zero is then exactly what the honesty filter reads.
        assembler = new ServedBriefingAssembler(bestBetFallbackService, regionSnapshotService,
                windowTideRollupBuilder, evaluationViewService, CLOCK,
                (days, resolver, triaged) -> days, locationPointProvider, neighbourGuardProperties,
                new NeighbourGuardLog(line -> { }), bigSeasBuilder);
    }

    private static DailyBriefingResponse snapshot(BriefingEventSummary... summaries) {
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "headline",
                List.of(day(D, summaries)), List.of(), null, null, false, null,
                List.of(), List.of());
    }

    @Test
    @DisplayName("a zero-coverage coastal region loses its slots to the filter but its window "
            + "keeps every tide fact, verbatim, and the cached snapshot is untouched")
    void tideFactsSurviveTheHonestyFilter() {
        BriefingSlot bamburgh = coastal(1L, "Bamburgh", "HIGH");
        BriefingSlot whitby = coastal(2L, "Whitby", "LOW");
        BriefingSlot durham = inland(3L, "Durham");
        BriefingSlot orphan = coastal(4L, "Orphan", "MID");
        DailyBriefingResponse cached = snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, bamburgh, whitby, durham)), List.of(orphan)));
        DailyBriefingResponse before = snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, bamburgh, whitby, durham)), List.of(orphan)));

        DailyBriefingResponse served = assembler.assembleForPlan(cached, 0.5);

        BriefingEventSummary es = served.days().get(0).eventSummaries().get(0);
        assertThat(es.regions().get(0).slots()).as("the filter emptied the region").isEmpty();
        assertThat(es.regions().get(0).displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(es.window()).isNotNull();
        assertThat(es.window().tideFacts()).containsExactly(
                LocationTideFact.from(bamburgh), LocationTideFact.from(whitby),
                LocationTideFact.from(orphan));
        assertThat(cached).as("the cached snapshot is not mutated").isEqualTo(before);
        assertThat(cached.days().get(0).eventSummaries().get(0).regions().get(0).slots())
                .containsExactly(bamburgh, whitby, durham);
    }

    @Test
    @DisplayName("a window with no coastal slot carries no tideFacts")
    void inlandWindowCarriesNull() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(summary(
                TargetType.SUNRISE, List.of(region("Dales", 0, inland(1L, "Hawes"))),
                List.of())), 0.5);

        assertThat(served.days().get(0).eventSummaries().get(0).window().tideFacts()).isNull();
    }

    @Test
    @DisplayName("each window gets its own facts")
    void eachWindowItsOwn() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(
                summary(TargetType.SUNRISE,
                        List.of(region("North East", 0, coastal(1L, "Bamburgh", "HIGH"))),
                        List.of()),
                summary(TargetType.SUNSET,
                        List.of(region("North East", 0, coastal(1L, "Bamburgh", "LOW"))),
                        List.of())), 0.5);

        List<BriefingEventSummary> es = served.days().get(0).eventSummaries();
        assertThat(es.get(0).window().tideFacts()).extracting(LocationTideFact::tideState)
                .containsExactly("HIGH");
        assertThat(es.get(1).window().tideFacts()).extracting(LocationTideFact::tideState)
                .containsExactly("LOW");
    }

    @Test
    @DisplayName("a null snapshot still assembles to null")
    void nullSnapshot() {
        assertThat(assembler.assembleForPlan(null, 0.5)).isNull();
    }

    private static BigSeasDay bigSeasDay() {
        LocalDateTime hw = LocalDateTime.of(2026, 10, 11, 7, 30);
        return new BigSeasDay(D, hw.minusMinutes(90), hw.plusMinutes(90), hw, BigSeasDay.AlignedEvent.SUNRISE,
                12L, BigSeasDay.GapSide.AFTER, 4.2, 80, 9.5,
                List.of(new BigSeasStructure(243L, "Roker Pier", CoastalStructure.PIER, 4.2, 80, 9.5, 90, hw, true)),
                0, false, null, null);
    }

    @Test
    @DisplayName("big seas is attached last on the served payload, built from the served days; "
            + "the cached snapshot and the unprojected assembly never carry it")
    void bigSeasIsAttachedOnServeAndNeverCached() {
        DailyBriefingResponse cached = snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, coastal(1L, "Bamburgh", "HIGH"))), List.of()));
        List<BigSeasDay> block = List.of(bigSeasDay());
        when(bigSeasBuilder.build(any())).thenReturn(block);

        DailyBriefingResponse served = assembler.assembleForPlan(cached, 0.5);

        assertThat(served.bigSeas()).isEqualTo(block);
        org.mockito.ArgumentCaptor<List<BriefingDay>> built = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(bigSeasBuilder).build(built.capture());
        assertThat(built.getValue()).as("built from the final days").isEqualTo(served.days());
        assertThat(cached.bigSeas()).as("the cached payload has no big seas").isNull();
        assertThat(assembler.assembleWithoutPlan(cached, 0.5).bigSeas()).isNull();
        assertThat(served.withDays(served.days()).bigSeas()).as("a later wither keeps it").isEqualTo(block);
    }

    @Test
    @DisplayName("a builder that has nothing to say (null) leaves the served block absent, not empty")
    void bigSeasAbsentWhenBuilderReturnsNull() {
        when(bigSeasBuilder.build(any())).thenReturn(null);

        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, coastal(1L, "Bamburgh", "HIGH"))), List.of())), 0.5);

        assertThat(served.bigSeas()).isNull();
    }

    @Test
    @DisplayName("an empty block is served as absent, never as an empty array")
    void bigSeasEmptyIsServedAsAbsent() {
        when(bigSeasBuilder.build(any())).thenReturn(List.of());

        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, coastal(1L, "Bamburgh", "HIGH"))), List.of())), 0.5);

        assertThat(served.bigSeas()).isNull();
    }

    @Test
    @DisplayName("a null snapshot never asks for big seas")
    void nullSnapshotSkipsBigSeas() {
        assertThat(assembler.assembleForPlan(null, 0.5)).isNull();

        verify(bigSeasBuilder, never()).build(any());
    }

    @Test
    @DisplayName("the slotless-days case: a day with no summaries yields no facts and no failure")
    void dayWithoutSummaries() {
        DailyBriefingResponse snap = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0),
                "h", List.of(new BriefingDay(D, List.of())), List.of(), null, null, false, null, List.of(), List.of());

        assertThat(assembler.assembleForPlan(snap, 0.5).days()).hasSize(1);
    }

    private static DailyBriefingResponse scoredSnapshot() {
        return snapshot(
                summary(TargetType.SUNRISE, List.of(
                        region("North East", 3, coastal(1L, "Bamburgh", "HIGH"),
                                coastal(2L, "Whitby", "LOW"), inland(3L, "Durham"))),
                        List.of(coastal(4L, "Orphan", "MID"), inland(5L, "Wanderer"))),
                summary(TargetType.SUNSET, List.of(
                        region("North East", 3, coastal(1L, "Bamburgh", "LOW"))), List.of()));
    }

    private static List<BriefingSlot> allSlots(DailyBriefingResponse response) {
        return response.days().stream().flatMap(d -> d.eventSummaries().stream())
                .flatMap(es -> Stream.concat(
                        es.regions().stream().flatMap(r -> r.slots().stream()),
                        es.unregioned().stream()))
                .toList();
    }

    @Test
    @DisplayName("P5: after assembleForPlan no served slot carries tide, regioned or unregioned, "
            + "while window.tideFacts stays verbatim")
    void servedSlotsCarryNoTide() {
        DailyBriefingResponse cached = scoredSnapshot();

        DailyBriefingResponse served = assembler.assembleForPlan(cached, 0.5);

        List<BriefingSlot> slots = allSlots(served);
        assertThat(slots).as("scored region keeps its slots, so the strip is not vacuous")
                .hasSize(6);
        assertThat(slots).extracting(BriefingSlot::tide).containsOnlyNulls();
        BriefingEventSummary sunrise = served.days().get(0).eventSummaries().get(0);
        assertThat(sunrise.window().tideFacts()).containsExactly(
                LocationTideFact.from(coastal(1L, "Bamburgh", "HIGH")),
                LocationTideFact.from(coastal(2L, "Whitby", "LOW")),
                LocationTideFact.from(coastal(4L, "Orphan", "MID")));
        assertThat(served.days().get(0).eventSummaries().get(1).window().tideFacts())
                .containsExactly(LocationTideFact.from(coastal(1L, "Bamburgh", "LOW")));
    }

    @Test
    @DisplayName("P5: the strip changes slot tide and nothing else (peak, windows, renderedEvents, "
            + "every other slot and region component)")
    void stripPreservesEverythingButSlotTide() {
        DailyBriefingResponse cached = scoredSnapshot();
        DailyBriefingResponse unstripped = PlanWindowProjector.apply(
                assembler.assembleWithoutPlan(cached, 0.5),
                LocalDateTime.now(CLOCK), Map.of(),
                WindowTideFactProjector.project(cached.days()));

        DailyBriefingResponse served = assembler.assembleForPlan(cached, 0.5);

        assertThat(unstripped.renderedEvents()).as("fixture renders events").isNotEmpty();
        assertThat(served.renderedEvents()).isEqualTo(unstripped.renderedEvents());
        assertThat(served.days().get(0).peak()).isNotNull()
                .isEqualTo(unstripped.days().get(0).peak());
        for (int d = 0; d < served.days().size(); d++) {
            for (int e = 0; e < served.days().get(d).eventSummaries().size(); e++) {
                BriefingEventSummary got = served.days().get(d).eventSummaries().get(e);
                BriefingEventSummary want = unstripped.days().get(d).eventSummaries().get(e);
                assertThat(got.window()).isEqualTo(want.window());
                assertThat(got.solarEventTime()).isEqualTo(want.solarEventTime());
                assertThat(got.unregioned()).isEqualTo(
                        want.unregioned().stream().map(s -> s.withTide(null)).toList());
                for (int r = 0; r < got.regions().size(); r++) {
                    BriefingRegion expected = want.regions().get(r).withSlots(
                            want.regions().get(r).slots().stream().map(s -> s.withTide(null))
                                    .toList());
                    assertThat(got.regions().get(r)).isEqualTo(expected);
                }
            }
        }
        assertThat(served).isEqualTo(unstripped.withDays(served.days()));
    }

    @Test
    @DisplayName("P5: serving strips slot tide from the response only; the cached snapshot keeps it")
    void cachedSnapshotKeepsSlotTide() {
        DailyBriefingResponse cached = scoredSnapshot();
        DailyBriefingResponse before = scoredSnapshot();

        DailyBriefingResponse served = assembler.assembleForPlan(cached, 0.5);

        assertThat(cached).isEqualTo(before);
        assertThat(allSlots(cached)).hasSize(6);
        assertThat(allSlots(cached)).extracting(BriefingSlot::tide).doesNotContainNull();
        assertThat(allSlots(cached).get(0).tide().tideState()).isEqualTo("HIGH");
        assertThat(allSlots(served)).extracting(BriefingSlot::tide).containsOnlyNulls();
    }

    // ── Coverage reasons, end to end through the real filter and projector ────────────────────

    private static final LocalDate T_PLUS_4 = D.plusDays(1);
    private static final LocalDate T_PLUS_2 = D.minusDays(1);

    private void serveDispositions(LocalDate date, TargetType type, DispositionCategory category,
            String... locations) {
        Map<String, EvaluationViewService.LatestDisposition> latest = new java.util.HashMap<>();
        for (String name : locations) {
            latest.put(EvaluationViewService.stabilitySkipKey(name, date, type),
                    new EvaluationViewService.LatestDisposition(category, false));
        }
        when(evaluationViewService.loadServeTriage(any(), any(), any()))
                .thenReturn(new EvaluationViewService.ServeTriage(Map.of(), latest));
    }

    private DailyBriefingResponse serveOneRegion(LocalDate date) {
        return assembler.assembleForPlan(new DailyBriefingResponse(
                LocalDateTime.of(2026, 10, 8, 6, 0), "headline",
                List.of(day(date, summary(TargetType.SUNRISE, List.of(region("North East", 0,
                        inland(1L, "Durham"), inland(2L, "Hawes"))), List.of()))),
                List.of(), null, null, false, null, List.of(), List.of()), 0.5);
    }

    @Test
    @DisplayName("T+4 with every slot stability-skipped serves AWAITING 'Not scored at this "
            + "range', and the window follows")
    void tPlusFourStabilitySkipIsNotScored() {
        serveDispositions(T_PLUS_4, TargetType.SUNRISE, DispositionCategory.SKIPPED_STABILITY,
                "Durham", "Hawes");

        DailyBriefingResponse served = serveOneRegion(T_PLUS_4);

        BriefingEventSummary es = served.days().get(0).eventSummaries().get(0);
        assertThat(es.regions().get(0).displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(es.regions().get(0).verdictLabel()).isEqualTo("Not scored at this range");
        assertThat(es.regions().get(0).slots()).isEmpty();
        assertThat(es.window().verdict()).isEqualTo(DisplayVerdict.AWAITING);
    }

    @Test
    @DisplayName("a travel day serves AWAITING 'Not forecast'")
    void travelDayIsNotForecast() {
        serveDispositions(D, TargetType.SUNRISE, DispositionCategory.SKIPPED_TRAVEL_DAY,
                "Durham", "Hawes");

        DailyBriefingResponse served = serveOneRegion(D);

        BriefingEventSummary es = served.days().get(0).eventSummaries().get(0);
        assertThat(es.regions().get(0).verdictLabel()).isEqualTo("Not forecast");
        assertThat(es.window().verdict()).isEqualTo(DisplayVerdict.AWAITING);
    }

    @Test
    @DisplayName("a majority of failed submissions at T+2 serves AWAITING 'Forecast unavailable'")
    void failedSubmissionsAtTPlusTwo() {
        serveDispositions(T_PLUS_2, TargetType.SUNRISE, DispositionCategory.SUBMISSION_FAILED,
                "Durham", "Hawes");

        DailyBriefingResponse served = serveOneRegion(T_PLUS_2);

        BriefingEventSummary es = served.days().get(0).eventSummaries().get(0);
        assertThat(es.regions().get(0).verdictLabel()).isEqualTo("Forecast unavailable");
        assertThat(es.window().verdict()).isEqualTo(DisplayVerdict.AWAITING);
    }

    @Test
    @DisplayName("no record at T+2 reads AWAITING 'Not scored', never a weather stand-down")
    void noRecordReadsNotScored() {
        DailyBriefingResponse served = serveOneRegion(T_PLUS_2);

        BriefingEventSummary es = served.days().get(0).eventSummaries().get(0);
        assertThat(es.regions().get(0).displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(es.regions().get(0).verdictLabel()).isEqualTo("Not scored");
        assertThat(es.window().verdict()).isEqualTo(DisplayVerdict.AWAITING);
    }

    /**
     * Two blanked regions in one window. The UNKNOWN one sorts first by name, so a window verdict
     * that follows the stand-down is the ranking at work, not the alphabet.
     */
    private BriefingEventSummary serveTwoBlankedRegions(LocalDate date) {
        return assembler.assembleForPlan(new DailyBriefingResponse(
                LocalDateTime.of(2026, 10, 8, 6, 0), "headline",
                List.of(day(date, summary(TargetType.SUNRISE, List.of(
                        region("Aaa Dales", 0, inland(3L, "Richmond"), inland(4L, "Askrigg")),
                        region("Zzz North East", 0, inland(1L, "Durham"), inland(2L, "Hawes"))),
                        List.of()))),
                List.of(), null, null, false, null, List.of(), List.of()), 0.5)
                .days().get(0).eventSummaries().get(0);
    }

    @Test
    @DisplayName("a window whose blanked regions are all UNKNOWN reads AWAITING")
    void windowOfOnlyUnknownRegionsReadsAwaiting() {
        BriefingEventSummary es = serveTwoBlankedRegions(T_PLUS_2);

        assertThat(es.regions()).extracting(BriefingRegion::displayVerdict)
                .containsOnly(DisplayVerdict.AWAITING);
        assertThat(es.window().verdict()).isEqualTo(DisplayVerdict.AWAITING);
    }

    @Test
    @DisplayName("a window with one STOOD_DOWN region among UNKNOWN ones still reads STAND_DOWN")
    void oneStoodDownRegionSpeaksForTheWindow() {
        serveDispositions(T_PLUS_2, TargetType.SUNRISE, DispositionCategory.SKIPPED_TRIAGED,
                "Durham", "Hawes");

        BriefingEventSummary es = serveTwoBlankedRegions(T_PLUS_2);

        assertThat(es.regions()).extracting(BriefingRegion::coverageReason).containsExactly(
                com.gregochr.goldenhour.model.CoverageReason.UNKNOWN,
                com.gregochr.goldenhour.model.CoverageReason.STOOD_DOWN);
        assertThat(es.window().verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
    }

    // ── The coverage reason survives every step after the filter (ask-window-context plan §2.1) ────

    @Test
    @DisplayName("the filter's coverage reason is on the region of the ASSEMBLED payload, after movement and "
            + "the slot-tide strip rebuilt it: a stand-down, away, not attempted, failed and unknown")
    void coverageReasonSurvivesTheAssembledPayload() {
        // A previous build with a basis, so the movement step's wither runs on every region too.
        when(regionSnapshotService.previousBuild(any())).thenReturn(
                new BriefingRegionSnapshotService.PreviousBuild(LocalDateTime.of(2026, 10, 8, 0, 0),
                        Map.of("some other key", 3.0)));

        serveDispositions(T_PLUS_2, TargetType.SUNRISE, DispositionCategory.SKIPPED_TRIAGED, "Durham", "Hawes");
        assertThat(servedReason(T_PLUS_2)).isEqualTo(com.gregochr.goldenhour.model.CoverageReason.STOOD_DOWN);

        serveDispositions(D, TargetType.SUNRISE, DispositionCategory.SKIPPED_TRAVEL_DAY, "Durham", "Hawes");
        assertThat(servedReason(D)).isEqualTo(com.gregochr.goldenhour.model.CoverageReason.AWAY);

        serveDispositions(T_PLUS_4, TargetType.SUNRISE, DispositionCategory.SKIPPED_STABILITY, "Durham", "Hawes");
        assertThat(servedReason(T_PLUS_4)).isEqualTo(com.gregochr.goldenhour.model.CoverageReason.NOT_ATTEMPTED);

        serveDispositions(T_PLUS_2, TargetType.SUNRISE, DispositionCategory.SUBMISSION_FAILED, "Durham", "Hawes");
        assertThat(servedReason(T_PLUS_2)).isEqualTo(com.gregochr.goldenhour.model.CoverageReason.FAILED);

        when(evaluationViewService.loadServeTriage(any(), any(), any()))
                .thenReturn(EvaluationViewService.ServeTriage.EMPTY);
        assertThat(servedReason(T_PLUS_2)).isEqualTo(com.gregochr.goldenhour.model.CoverageReason.UNKNOWN);
    }

    private com.gregochr.goldenhour.model.CoverageReason servedReason(LocalDate date) {
        return serveOneRegion(date).days().get(0).eventSummaries().get(0).regions().get(0).coverageReason();
    }

    @Test
    @DisplayName("a region the filter did not blank serves no coverage reason")
    void aScoredRegionServesNoCoverageReason() {
        DailyBriefingResponse served = assembler.assembleForPlan(scoredSnapshot(), 0.5);

        assertThat(served.days().get(0).eventSummaries().get(0).regions())
                .extracting(BriefingRegion::coverageReason).containsOnlyNulls();
    }

    @Test
    @DisplayName("a serve reads the dispositions once, through loadServeTriage, never the "
            + "separate triaged-names method")
    void oneDispositionReadPerServe() {
        serveOneRegion(T_PLUS_2);

        verify(evaluationViewService, times(1)).loadServeTriage(any(), any(), any());
        verify(evaluationViewService, never()).getTriagedByBatchLocationNamesBulk(
                any(), any(), any());
    }
}
