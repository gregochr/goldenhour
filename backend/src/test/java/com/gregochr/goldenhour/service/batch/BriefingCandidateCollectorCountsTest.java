package com.gregochr.goldenhour.service.batch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.entity.DispositionCategory;
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
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the per-gate slot counts and the stability breakdown that
 * {@link BriefingCandidateCollector#collectForecastCandidates} reports in its diagnostic log, plus
 * the disposition details recorded for each skip. The counts exist only in the log (the operator's
 * view of a batch run), so the log text is the observable under test.
 */
@ExtendWith(MockitoExtension.class)
class BriefingCandidateCollectorCountsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate TRAVEL = TODAY.plusDays(1);
    private static final LocalDate LATER = TODAY.plusDays(2);
    private static final Clock CLOCK = Clock.fixed(
            TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock private TravelDayService travelDayService;
    @Mock private LocationService locationService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private BriefingCandidateCollector collector;
    private ListAppender<ILoggingEvent> logs;
    private Logger collectorLogger;

    @BeforeEach
    void setUp() {
        FreshnessProperties props = new FreshnessProperties();
        props.setSettledHours(36);
        props.setTransitionalHours(12);
        props.setUnsettledHours(4);
        props.setSafetyFloorHours(2);
        props.getHorizon().setT0Hours(2);
        props.getHorizon().setT1Hours(8);
        collector = new BriefingCandidateCollector(travelDayService, locationService,
                briefingEvaluationService, new FreshnessResolver(props),
                stabilitySnapshotProvider, CLOCK);
        collectorLogger = (Logger) LoggerFactory.getLogger(ForecastTaskCollector.class);
        logs = new ListAppender<>();
        logs.start();
        collectorLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        collectorLogger.detachAppender(logs);
    }

    private static BriefingSlot slot(String name, LocalDate date) {
        return new BriefingSlot(name, date.atTime(8, 0), Verdict.GO, null, null, List.of());
    }

    private static BriefingRegion region(String name, List<BriefingSlot> slots) {
        return new BriefingRegion(name, Verdict.GO, null, List.of(), slots,
                null, null, null, null, null, null);
    }

    private static BriefingDay day(LocalDate date, TargetType type, BriefingRegion... regions) {
        return new BriefingDay(date, List.of(
                new BriefingEventSummary(type, List.of(regions), List.of())));
    }

    private static DailyBriefingResponse briefing(BriefingDay... days) {
        return new DailyBriefingResponse(LocalDateTime.now(), null, List.of(days), List.of(),
                null, null, false, null, List.of(), List.of());
    }

    private static CandidateCollectionStrategy acceptAll() {
        return (date, targetType) -> true;
    }

    private static LocationEntity location(String name) {
        LocationEntity l = new LocationEntity();
        l.setName(name);
        l.setId((long) name.hashCode());
        return l;
    }

    private static StabilitySummaryResponse snapshot(Map<ForecastStability, List<String>> byLevel) {
        List<StabilitySummaryResponse.GridCellDetail> cells = new ArrayList<>();
        byLevel.forEach((level, names) -> cells.add(new StabilitySummaryResponse.GridCellDetail(
                "k" + level, 55.0, -1.5, level, "r", 3, names)));
        return new StabilitySummaryResponse(Instant.now(), cells.size(), Map.of(), cells);
    }

    private String messageContaining(String fragment) {
        return messages().stream().filter(m -> m.contains(fragment)).findFirst().orElseThrow();
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<CandidateDisposition> byCategory(
            BriefingCandidateCollector.Result r, DispositionCategory c) {
        return r.dispositions().stream().filter(d -> d.category() == c).toList();
    }

    /**
     * Past: 3 slots. Travel day: 2 slots. Today: 1 slot (UNSETTLED, evaluated). Later: a cached
     * SETTLED region of 3, a cached TRANSITIONAL region of 1, an uncached TRANSITIONAL region of 2,
     * and an uncached region of 2 where one location is unknown to the enabled set.
     */
    private DailyBriefingResponse mixedBriefing() {
        return briefing(
                day(YESTERDAY, TargetType.SUNSET, region("Past",
                        List.of(slot("P1", YESTERDAY), slot("P2", YESTERDAY), slot("P3", YESTERDAY)))),
                day(TRAVEL, TargetType.SUNRISE, region("Away",
                        List.of(slot("T1", TRAVEL), slot("T2", TRAVEL)))),
                day(TODAY, TargetType.SUNSET, region("TodayRegion",
                        List.of(slot("Alnwick", TODAY)))),
                day(LATER, TargetType.SUNRISE,
                        region("CachedSettled", List.of(
                                slot("S1", LATER), slot("S2", LATER), slot("S3", LATER))),
                        region("CachedTrans", List.of(slot("X1", LATER))),
                        region("FreshTrans", List.of(slot("Seaham", LATER), slot("Craster", LATER))),
                        region("Mixed", List.of(slot("Bamburgh", LATER), slot("Ghost", LATER)))));
    }

    private void stubMixedWorld() {
        when(travelDayService.isTravelDay(TRAVEL)).thenReturn(true);
        when(travelDayService.isTravelDay(TODAY)).thenReturn(false);
        when(travelDayService.isTravelDay(LATER)).thenReturn(false);
        when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(snapshot(Map.of(
                ForecastStability.SETTLED, List.of("S1", "S2", "S3"),
                ForecastStability.TRANSITIONAL,
                List.of("X1", "Seaham", "Craster", "Bamburgh"))));
        // The freshness window each region is asked about is part of the behaviour: SETTLED 36h,
        // TRANSITIONAL 12h, UNSETTLED 4h at T+2; UNSETTLED today is capped to the T+0 floor of 2h.
        when(briefingEvaluationService.hasFreshEvaluation(
                "CachedSettled|2026-01-17|SUNRISE", Duration.ofHours(36))).thenReturn(true);
        when(briefingEvaluationService.hasFreshEvaluation(
                "CachedTrans|2026-01-17|SUNRISE", Duration.ofHours(12))).thenReturn(true);
        when(briefingEvaluationService.hasFreshEvaluation(
                "FreshTrans|2026-01-17|SUNRISE", Duration.ofHours(12))).thenReturn(false);
        when(briefingEvaluationService.hasFreshEvaluation(
                "Mixed|2026-01-17|SUNRISE", Duration.ofHours(4))).thenReturn(false);
        when(briefingEvaluationService.hasFreshEvaluation(
                "TodayRegion|2026-01-15|SUNSET", Duration.ofHours(2))).thenReturn(false);
        when(locationService.findAllEnabled()).thenReturn(List.of(
                location("Alnwick"), location("Seaham"), location("Craster"), location("Bamburgh")));
    }

    @Test
    @DisplayName("the summary log reports each gate's slot count and the candidate total separately")
    void summaryLogCountsEveryGate() {
        stubMixedWorld();

        BriefingCandidateCollector.Result result =
                collector.collectForecastCandidates(mixedBriefing(), acceptAll());

        assertThat(result.candidates()).extracting(c -> c.location().getName())
                .containsExactly("Alnwick", "Seaham", "Craster", "Bamburgh");
        assertThat(messageContaining("Task collection complete")).isEqualTo(
                "[BATCH DIAG] Task collection complete — 4 tasks from 14 total slots "
                        + "(pastDate=3, travelDay=2, cached=4, unknownLoc=1)");
    }

    @Test
    @DisplayName("each skip log line carries the number of slots skipped on that date or region")
    void perSkipLogLinesCarryTheSlotCount() {
        stubMixedWorld();

        collector.collectForecastCandidates(mixedBriefing(), acceptAll());

        assertThat(messages()).contains(
                "[BATCH DIAG] SKIP date 2026-01-14 | reason=PAST_DATE (3 slots skipped)",
                "[BATCH DIAG] SKIP date 2026-01-16 | reason=TRAVEL_DAY (2 slots skipped)");
        assertThat(messageContaining("region CachedSettled|2026-01-17|SUNRISE")).isEqualTo(
                "[BATCH DIAG] SKIP region CachedSettled|2026-01-17|SUNRISE | reason=CACHED "
                        + "(T+2, stability=SETTLED, threshold=36h, 3 slots skipped)");
        assertThat(messageContaining("region CachedTrans|2026-01-17|SUNRISE")).contains(
                "stability=TRANSITIONAL, threshold=12h, 1 slots skipped");
    }

    @Test
    @DisplayName("the stability breakdown lists refreshed-of-eligible per level with the refreshed "
            + "percentage and the level's threshold")
    void stabilityBreakdownIsExact() {
        stubMixedWorld();

        collector.collectForecastCandidates(mixedBriefing(), acceptAll());

        // SETTLED: 3 eligible, all cached. TRANSITIONAL: CachedTrans(1, cached) + FreshTrans(2).
        // UNSETTLED: TodayRegion(1, Alnwick not in snapshot) + Mixed(2, Ghost not in snapshot).
        assertThat(messageContaining("Candidate breakdown by stability")).isEqualTo(
                "[BATCH DIAG] Candidate breakdown by stability:"
                        + " SETTLED: 0 of 3 (0.0% refreshed, threshold 36h) |"
                        + " TRANSITIONAL: 2 of 3 (66.7% refreshed, threshold 12h) |"
                        + " UNSETTLED: 3 of 3 (100.0% refreshed, threshold 4h) ");
    }

    @Test
    @DisplayName("past-date and travel-day slots carry the horizon and a null location id; "
            + "cached slots carry the threshold, horizon and stability in the detail")
    void dispositionsCarryHorizonAndDetail() {
        stubMixedWorld();

        BriefingCandidateCollector.Result result =
                collector.collectForecastCandidates(mixedBriefing(), acceptAll());

        assertThat(byCategory(result, DispositionCategory.SKIPPED_PAST_DATE)).hasSize(3)
                .allSatisfy(d -> {
                    assertThat(d.daysAhead()).isEqualTo(-1);
                    assertThat(d.evaluationDate()).isEqualTo(YESTERDAY);
                    assertThat(d.eventType()).isEqualTo(TargetType.SUNSET);
                    assertThat(d.locationId()).isNull();
                    assertThat(d.detail()).isEqualTo("Date in past");
                });
        assertThat(byCategory(result, DispositionCategory.SKIPPED_TRAVEL_DAY))
                .extracting(CandidateDisposition::locationName).containsExactly("T1", "T2");
        assertThat(byCategory(result, DispositionCategory.SKIPPED_TRAVEL_DAY))
                .allSatisfy(d -> {
                    assertThat(d.daysAhead()).isEqualTo(1);
                    assertThat(d.detail()).isEqualTo("Travel day — away");
                });
        List<CandidateDisposition> cached = byCategory(result, DispositionCategory.SKIPPED_CACHED);
        assertThat(cached).extracting(CandidateDisposition::locationName)
                .containsExactly("S1", "S2", "S3", "X1");
        assertThat(cached.get(0).detail())
                .isEqualTo("Fresh cached evaluation within 36h (T+2, SETTLED)");
        assertThat(cached.get(3).detail())
                .isEqualTo("Fresh cached evaluation within 12h (T+2, TRANSITIONAL)");
        assertThat(byCategory(result, DispositionCategory.SKIPPED_UNKNOWN_LOCATION))
                .singleElement().satisfies(d -> {
                    assertThat(d.locationName()).isEqualTo("Ghost");
                    assertThat(d.daysAhead()).isEqualTo(2);
                    assertThat(d.locationId()).isNull();
                });
        assertThat(result.dispositions()).hasSize(3 + 2 + 4 + 1);
    }

    @Test
    @DisplayName("today is not a past date: a slot dated today is evaluated at T+0")
    void todayIsNotPast() {
        stubMixedWorld();

        BriefingCandidateCollector.Result result =
                collector.collectForecastCandidates(mixedBriefing(), acceptAll());

        assertThat(result.dispositions()).noneMatch(d -> d.evaluationDate().equals(TODAY));
        assertThat(result.candidates()).anyMatch(c -> c.date().equals(TODAY));
        verify(travelDayService).isTravelDay(TODAY);
    }

    @Test
    @DisplayName("a past day never consults the travel-day gate, and a travel day never reaches "
            + "the cache or location lookups")
    void gatesShortCircuit() {
        when(travelDayService.isTravelDay(TRAVEL)).thenReturn(true);
        when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);

        collector.collectForecastCandidates(briefing(
                day(YESTERDAY, TargetType.SUNSET, region("Past", List.of(slot("P1", YESTERDAY)))),
                day(TRAVEL, TargetType.SUNRISE, region("Away", List.of(slot("T1", TRAVEL))))),
                acceptAll());

        verify(travelDayService, never()).isTravelDay(YESTERDAY);
        verify(briefingEvaluationService, never()).hasFreshEvaluation(anyString(), any());
        verify(locationService, never()).findAllEnabled();
    }

    @Test
    @DisplayName("a strategy that excludes a date/event silently drops it: no disposition, no count, "
            + "and the cache is not consulted")
    void strategyExclusionIsSilent() {
        when(travelDayService.isTravelDay(LATER)).thenReturn(false);
        when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);

        BriefingCandidateCollector.Result result = collector.collectForecastCandidates(
                briefing(day(LATER, TargetType.SUNRISE, region("R", List.of(slot("A", LATER))))),
                (date, type) -> false);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.dispositions()).isEmpty();
        assertThat(messageContaining("Task collection complete")).isEqualTo(
                "[BATCH DIAG] Task collection complete — 0 tasks from 0 total slots "
                        + "(pastDate=0, travelDay=0, cached=0, unknownLoc=0)");
        verify(briefingEvaluationService, never()).hasFreshEvaluation(anyString(), any());
    }

    @Test
    @DisplayName("with no stability snapshot every region is UNSETTLED and only that level is "
            + "listed in the breakdown")
    void noSnapshotTreatsEverythingAsUnsettled() {
        when(travelDayService.isTravelDay(LATER)).thenReturn(false);
        when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
        when(briefingEvaluationService.hasFreshEvaluation(
                "R|2026-01-17|SUNRISE", Duration.ofHours(4))).thenReturn(false);
        when(locationService.findAllEnabled()).thenReturn(List.of(location("A")));

        collector.collectForecastCandidates(
                briefing(day(LATER, TargetType.SUNRISE, region("R", List.of(slot("A", LATER))))),
                acceptAll());

        assertThat(messageContaining("Candidate breakdown by stability")).isEqualTo(
                "[BATCH DIAG] Candidate breakdown by stability:"
                        + " UNSETTLED: 1 of 1 (100.0% refreshed, threshold 4h) ");
        assertThat(messageContaining("Stability snapshot unavailable")).contains("(4h threshold)");
    }

    @Test
    @DisplayName("a snapshot whose cells are unavailable is treated like no snapshot")
    void snapshotWithoutCellsIsIgnored() {
        when(travelDayService.isTravelDay(LATER)).thenReturn(false);
        StabilitySummaryResponse noCells = mock(StabilitySummaryResponse.class);
        when(noCells.cells()).thenReturn(null);
        when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(noCells);
        when(briefingEvaluationService.hasFreshEvaluation(
                "R|2026-01-17|SUNRISE", Duration.ofHours(4))).thenReturn(false);
        when(locationService.findAllEnabled()).thenReturn(List.of(location("A")));

        collector.collectForecastCandidates(
                briefing(day(LATER, TargetType.SUNRISE, region("R", List.of(slot("A", LATER))))),
                acceptAll());

        assertThat(messages()).anyMatch(m -> m.contains("Stability snapshot unavailable"));
    }
}
