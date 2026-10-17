package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.model.TideStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideFactDeriver}, the single tide-fact derivation seam.
 *
 * <p>Focuses on the logic the deriver itself owns: the king/spring statistical height comparisons
 * at their exact thresholds (value, value-1, value+1), the {@link TideDerivation#statisticalSize()}
 * collapse, the inland / data-gap short-circuits, and faithful pass-through of the underlying tide
 * and lunar facts. The alignment-window thresholds inside {@code TideService.calculateTideAligned}
 * are that service's own concern and are covered by its tests; here the deriver only relays its
 * boolean result.
 */
@ExtendWith(MockitoExtension.class)
class TideFactDeriverTest {

    @Mock
    private TideService tideService;
    @Mock
    private LunarPhaseService lunarPhaseService;
    @Mock
    private SolarService solarService;

    private TideFactDeriver deriver() {
        return new TideFactDeriver(tideService, lunarPhaseService, solarService);
    }

    private static final Long LOC_ID = 7L;
    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 21, 20, 47);
    private static final double LAT = 55.0;
    private static final double LON = -1.6;
    private static final Set<TideType> COASTAL = Set.of(TideType.HIGH);

    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger deriverLogger;

    @BeforeEach
    void setUpLogCapture() {
        deriverLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TideFactDeriver.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        deriverLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        deriverLogger.detachAppender(logAppender);
    }

    private List<String> warnLogMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** p95 = 5.50, springThreshold = 5.00 — the two thresholds under test. */
    private static final BigDecimal P95 = new BigDecimal("5.50");
    private static final BigDecimal SPRING_THRESHOLD = new BigDecimal("5.00");

    private void stubSolarWindow() {
        // Sunset: goldenHourStart 20:17, blueHourEnd 20:47 → 30-minute span → 15-minute half-width.
        when(solarService.goldenBlueWindow(anyDouble(), anyDouble(), any(), anyBoolean()))
                .thenReturn(new SolarService.SolarWindow(
                        EVENT_TIME.minusMinutes(30), EVENT_TIME,
                        EVENT_TIME.minusMinutes(30), EVENT_TIME));
    }

    private TideData highTide(BigDecimal height) {
        return new TideData(TideState.HIGH, false,
                EVENT_TIME.plusMinutes(20), height,
                EVENT_TIME.plusHours(6), new BigDecimal("0.80"),
                EVENT_TIME.plusMinutes(20), EVENT_TIME.minusHours(6));
    }

    private TideStats statsWith(BigDecimal p95, BigDecimal springThreshold) {
        return new TideStats(
                new BigDecimal("4.00"), new BigDecimal("6.00"),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                200, new BigDecimal("3.00"),
                new BigDecimal("4.50"), new BigDecimal("5.00"), p95,
                10, new BigDecimal("0.05"), springThreshold,
                p95, 5);
    }

    private void stubDerivable(TideData tideData, boolean aligned) {
        stubSolarWindow();
        when(tideService.deriveDualWindowTideData(eq(LOC_ID), eq(EVENT_TIME), anyLong(), anyLong()))
                .thenReturn(Optional.of(new TideService.DualWindowTideData(tideData, tideData)));
        when(tideService.calculateTideAligned(any(), any())).thenReturn(aligned);
    }

    // ── short-circuits ──────────────────────────────────────────────────────

    @Test
    @DisplayName("inland (empty tide types) → empty, no service calls")
    void inland_returnsEmpty() {
        Optional<TideDerivation> result =
                deriver().derive(LOC_ID, EVENT_TIME, Set.of(), LAT, LON, TargetType.SUNSET);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null location id → empty")
    void nullLocationId_returnsEmpty() {
        Optional<TideDerivation> result =
                deriver().derive(null, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("no stored extremes (deriveDualWindowTideData empty) → empty")
    void noExtremes_returnsEmpty() {
        stubSolarWindow();
        when(tideService.deriveDualWindowTideData(eq(LOC_ID), eq(EVENT_TIME), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        Optional<TideDerivation> result =
                deriver().derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET);
        assertThat(result).isEmpty();
    }

    // ── statistical signals: data gaps ──────────────────────────────────────

    @Test
    @DisplayName("null high-tide height → both statistical flags false, getTideStats not called")
    void nullHeight_flagsFalse_statsNotCalled() {
        stubDerivable(highTide(null), false);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isFalse();
        assertThat(d.statisticalSize()).isNull();
        verify(tideService, never()).getTideStats(any());
    }

    @Test
    @DisplayName("no stats available → both statistical flags false")
    void noStats_flagsFalse() {
        stubDerivable(highTide(new BigDecimal("9.99")), false);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.empty());

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isFalse();
        assertThat(d.statisticalSize()).isNull();
    }

    // ── P95 (king) boundary: value-1 / value / value+1 ──────────────────────

    @Test
    @DisplayName("height just above P95 → king signal, EXTRA_EXTRA_HIGH")
    void heightJustAboveP95() {
        stubDerivable(highTide(new BigDecimal("5.51")), true);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.of(statsWith(P95, SPRING_THRESHOLD)));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isTrue();
        assertThat(d.heightAboveSpringThreshold()).isTrue();
        assertThat(d.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_EXTRA_HIGH);
    }

    @Test
    @DisplayName("height exactly equal to P95 → NOT king (strict >), still spring")
    void heightEqualToP95() {
        stubDerivable(highTide(new BigDecimal("5.50")), true);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.of(statsWith(P95, SPRING_THRESHOLD)));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isTrue();
        assertThat(d.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_HIGH);
    }

    @Test
    @DisplayName("height just below P95 (still above spring) → spring only, EXTRA_HIGH")
    void heightJustBelowP95() {
        stubDerivable(highTide(new BigDecimal("5.49")), true);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.of(statsWith(P95, SPRING_THRESHOLD)));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isTrue();
        assertThat(d.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_HIGH);
    }

    // ── spring threshold boundary: value-1 / value / value+1 ────────────────

    @Test
    @DisplayName("height just above spring threshold → spring signal, EXTRA_HIGH")
    void heightJustAboveSpringThreshold() {
        stubDerivable(highTide(new BigDecimal("5.01")), true);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.of(statsWith(P95, SPRING_THRESHOLD)));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isTrue();
        assertThat(d.statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_HIGH);
    }

    @Test
    @DisplayName("height exactly equal to spring threshold → NOT spring (strict >), null size")
    void heightEqualToSpringThreshold() {
        stubDerivable(highTide(new BigDecimal("5.00")), true);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.of(statsWith(P95, SPRING_THRESHOLD)));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isFalse();
        assertThat(d.statisticalSize()).isNull();
    }

    @Test
    @DisplayName("height just below spring threshold → neither signal, null size")
    void heightJustBelowSpringThreshold() {
        stubDerivable(highTide(new BigDecimal("4.99")), true);
        when(tideService.getTideStats(LOC_ID)).thenReturn(Optional.of(statsWith(P95, SPRING_THRESHOLD)));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.heightAboveP95()).isFalse();
        assertThat(d.heightAboveSpringThreshold()).isFalse();
        assertThat(d.statisticalSize()).isNull();
    }

    // ── pass-through of tide + lunar facts ──────────────────────────────────

    @Test
    @DisplayName("derive() passes through tide state, times, alignment, and lunar facts")
    void passesThroughTideAndLunarFacts() {
        TideData tideData = highTide(new BigDecimal("4.00"));
        stubDerivable(tideData, true);
        when(lunarPhaseService.classifyTide(EVENT_TIME.toLocalDate()))
                .thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(EVENT_TIME.toLocalDate())).thenReturn("Full Moon");
        when(lunarPhaseService.isMoonAtPerigee(EVENT_TIME.toLocalDate())).thenReturn(true);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.tideState()).isEqualTo(TideState.HIGH);
        assertThat(d.tideAligned()).isTrue();
        assertThat(d.widenedAligned()).isTrue();
        assertThat(d.nextHighTideTime()).isEqualTo(tideData.nextHighTideTime());
        assertThat(d.nextHighTideHeightMetres()).isEqualTo(tideData.nextHighTideHeightMetres());
        assertThat(d.nextLowTideTime()).isEqualTo(tideData.nextLowTideTime());
        assertThat(d.nextLowTideHeightMetres()).isEqualTo(tideData.nextLowTideHeightMetres());
        assertThat(d.nearestHighTideTime()).isEqualTo(tideData.nearestHighTideTime());
        assertThat(d.nearestLowTideTime()).isEqualTo(tideData.nearestLowTideTime());
        assertThat(d.lunarTideType()).isEqualTo(LunarTideType.KING_TIDE);
        assertThat(d.lunarPhase()).isEqualTo("Full Moon");
        assertThat(d.moonAtPerigee()).isTrue();
    }

    @Test
    @DisplayName("widenedAligned is derived independently of tideAligned from the same fetch")
    void widenedAligned_independentOfTightAligned() {
        // Same curve, two windows: tight not aligned, widened aligned (the scoring 3-star band).
        TideData tight = new TideData(TideState.HIGH, false, EVENT_TIME.plusMinutes(20), null,
                EVENT_TIME.plusHours(6), new BigDecimal("0.80"),
                EVENT_TIME.plusMinutes(20), EVENT_TIME.minusHours(6));
        TideData widened = new TideData(TideState.HIGH, false, EVENT_TIME.plusMinutes(25), null,
                EVENT_TIME.plusHours(6), new BigDecimal("0.80"),
                EVENT_TIME.plusMinutes(25), EVENT_TIME.minusHours(6));
        stubSolarWindow();
        when(tideService.deriveDualWindowTideData(eq(LOC_ID), eq(EVENT_TIME), anyLong(), anyLong()))
                .thenReturn(Optional.of(new TideService.DualWindowTideData(tight, widened)));
        when(tideService.calculateTideAligned(tight, COASTAL)).thenReturn(false);
        when(tideService.calculateTideAligned(widened, COASTAL)).thenReturn(true);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.tideAligned()).isFalse();
        assertThat(d.widenedAligned()).isTrue();
    }

    // ── alignment-window sizing ─────────────────────────────────────────────

    @Test
    @DisplayName("tightAlignmentWindowMinutes is half the sunset golden-to-blue span")
    void tightAlignmentWindowMinutes_sunset() {
        // goldenHourStart 20:17, blueHourEnd 20:47 → 30-minute span → half-width 15.
        when(solarService.goldenBlueWindow(anyDouble(), anyDouble(), any(), eq(false)))
                .thenReturn(new SolarService.SolarWindow(
                        EVENT_TIME.minusMinutes(40), EVENT_TIME,
                        EVENT_TIME.minusMinutes(30), EVENT_TIME.plusMinutes(10)));

        long minutes = deriver()
                .tightAlignmentWindowMinutes(LAT, LON, EVENT_TIME, TargetType.SUNSET);

        assertThat(minutes).isEqualTo(15);
    }

    @Test
    @DisplayName("tightAlignmentWindowMinutes is half the sunrise blue-to-golden span")
    void tightAlignmentWindowMinutes_sunrise() {
        // blueHourStart 20:07, goldenHourEnd 20:47 → 40-minute span → half-width 20.
        when(solarService.goldenBlueWindow(anyDouble(), anyDouble(), any(), eq(true)))
                .thenReturn(new SolarService.SolarWindow(
                        EVENT_TIME.minusMinutes(40), EVENT_TIME.minusMinutes(10),
                        EVENT_TIME.minusMinutes(20), EVENT_TIME));

        long minutes = deriver()
                .tightAlignmentWindowMinutes(LAT, LON, EVENT_TIME, TargetType.SUNRISE);

        assertThat(minutes).isEqualTo(20);
    }

    // ── tideAlignmentQuality (C0) ────────────────────────────────────────────
    //
    // stubDerivable() calls stubSolarWindow(), whose fixed golden/blue span
    // (EVENT_TIME-30 .. EVENT_TIME) makes a SUNSET tight window of exactly 15 minutes
    // (tightAlignmentWindowMinutes_sunset pins the same figure directly), so every quality
    // figure below is 1 - |offsetMinutes| / 15.

    private TideData tideAt(TideState state, boolean nearMidPoint, LocalDateTime nearestHigh,
            LocalDateTime nearestLow) {
        return new TideData(state, nearMidPoint, null, null, null, null, nearestHigh, nearestLow);
    }

    @Test
    @DisplayName("HIGH-aligned, the light exactly on the high water → quality 1.0")
    void highAligned_lightOnTheExtreme_qualityOne() {
        TideData tideData = tideAt(TideState.HIGH, false, EVENT_TIME, null);
        stubDerivable(tideData, true);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.tideAlignmentQuality()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("HIGH-aligned, the light at the tight window's own edge → quality ~0")
    void highAligned_lightAtWindowEdge_qualityZero() {
        // Tight window is 15 minutes; the extreme lands exactly on that edge.
        TideData tideData = tideAt(TideState.HIGH, false, EVENT_TIME.plusMinutes(15), null);
        stubDerivable(tideData, true);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.tideAlignmentQuality()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("HIGH-aligned, the extreme lands BEFORE the light — the offset is negative and "
            + "must score identically to the same magnitude after it")
    void highAligned_extremeBeforeTheLight_sameQualityAsAfter() {
        // Same 5-minute distance as highOrLowWant_reportsTheAlignedOnesOwnQuality below, but on
        // the other side of the light — proves Math.abs() is doing real work, not just present.
        TideData tideData = tideAt(TideState.HIGH, false, EVENT_TIME.minusMinutes(5), null);
        stubDerivable(tideData, true);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        // 1 - |-5|/15 = 0.667, the same figure the symmetric +5-minute case reports.
        assertThat(d.tideAlignmentQuality())
                .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("MID-aligned, the light exactly on the bracketing midpoint → quality 1.0")
    void midAligned_lightOnTheMidpoint_qualityOne() {
        TideData tideData = tideAt(TideState.MID, true, null, null);
        stubDerivable(tideData, true);
        when(tideService.nearestMidpointOffsetMinutes(LOC_ID, EVENT_TIME))
                .thenReturn(Optional.of(0L));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.MID), LAT, LON, TargetType.SUNSET)
                .orElseThrow();

        assertThat(d.tideAlignmentQuality()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⚠️ a MID match nearer the midpoint outscores one nearer an extreme — the case the "
            + "nearest-extreme offset ordering got backwards")
    void midAligned_nearerMidpointBeatsNearerExtreme() {
        // Two MID-aligned slots, distinguished only by how far the light sits from the
        // bracketing midpoint. A better-centred MID match is FARTHER from either extreme, so a
        // tiebreak built on nearestSolarOffsetMinutes (distance to the nearest extreme) would
        // rank these the wrong way round; tideAlignmentQuality, built on the midpoint distance
        // instead, must not.
        TideData atMidpoint = tideAt(TideState.MID, true, null, null);
        stubDerivable(atMidpoint, true);
        when(tideService.nearestMidpointOffsetMinutes(LOC_ID, EVENT_TIME))
                .thenReturn(Optional.of(0L));
        double atMidpointQuality = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.MID), LAT, LON, TargetType.SUNSET)
                .orElseThrow().tideAlignmentQuality();

        TideData nearExtreme = tideAt(TideState.MID, true, null, null);
        stubDerivable(nearExtreme, true);
        when(tideService.nearestMidpointOffsetMinutes(LOC_ID, EVENT_TIME))
                .thenReturn(Optional.of(12L));
        double nearExtremeQuality = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.MID), LAT, LON, TargetType.SUNSET)
                .orElseThrow().tideAlignmentQuality();

        assertThat(atMidpointQuality).isEqualTo(1.0);
        assertThat(nearExtremeQuality).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(atMidpointQuality).isGreaterThan(nearExtremeQuality);
    }

    @Test
    @DisplayName("a {HIGH, LOW} want takes the better of the two — here only HIGH is itself "
            + "the aligned one, and its own quality is what is reported")
    void highOrLowWant_reportsTheAlignedOnesOwnQuality() {
        TideData tideData = tideAt(TideState.HIGH, false, EVENT_TIME.plusMinutes(5), null);
        stubDerivable(tideData, true);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.HIGH, TideType.LOW), LAT, LON,
                        TargetType.SUNSET)
                .orElseThrow();

        // 1 - 5/15 = 0.667
        assertThat(d.tideAlignmentQuality())
                .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("when two wanted states are BOTH aligned, the higher quality of the two wins")
    void twoAlignedWants_bestOfTheTwoWins() {
        // HIGH is aligned (offset 12 → quality 0.2) and, independently, the light also sits near
        // the bracketing midpoint (offset 3 → quality 0.8): a location wanting {HIGH, MID} takes
        // the better of the two, not the first or the state-matched one.
        TideData tideData = tideAt(TideState.HIGH, true, EVENT_TIME.plusMinutes(12), null);
        stubDerivable(tideData, true);
        when(tideService.nearestMidpointOffsetMinutes(LOC_ID, EVENT_TIME))
                .thenReturn(Optional.of(3L));

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.HIGH, TideType.MID), LAT, LON,
                        TargetType.SUNSET)
                .orElseThrow();

        // MID's 0.8 beats HIGH's 0.2.
        assertThat(d.tideAlignmentQuality()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("a non-aligned slot reports no quality — null, not zero")
    void notAligned_qualityIsNull() {
        TideData tideData = tideAt(TideState.HIGH, false, EVENT_TIME, null);
        stubDerivable(tideData, false);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, COASTAL, LAT, LON, TargetType.SUNSET).orElseThrow();

        assertThat(d.tideAlignmentQuality()).isNull();
    }

    @Test
    @DisplayName("⚠️ a non-aligned MID want never pays for the midpoint lookup — the guard on "
            + "tideAligned is checked BEFORE any want is evaluated, not per-want")
    void notAligned_midWantNearTheMidpoint_neverCallsTheMidpointLookup() {
        // nearMidPoint() is TRUE here — if the tideAligned ? ... : null guard in derive() were
        // ever weakened to a per-want check instead, this fixture is exactly the one that would
        // start calling nearestMidpointOffsetMinutes despite the mocked overall alignment being
        // false. A HIGH-only want set (as in notAligned_qualityIsNull above) could never catch
        // that regression, because MID is never in scope for it either way.
        TideData tideData = tideAt(TideState.MID, true, null, null);
        stubDerivable(tideData, false);

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.MID), LAT, LON, TargetType.SUNSET)
                .orElseThrow();

        assertThat(d.tideAlignmentQuality()).isNull();
        verify(tideService, never()).nearestMidpointOffsetMinutes(any(), any());
    }

    @Test
    @DisplayName("⚠️ tideAligned=true but the MID lookup's second fetch finds nothing (a tide "
            + "refresh landed between the two queries) → quality null, and a WARN so the "
            + "degraded sample never dies silently")
    void alignedButMidpointLookupEmpty_qualityNullAndWarned() {
        TideData tideData = tideAt(TideState.MID, true, null, null);
        stubDerivable(tideData, true);
        when(tideService.nearestMidpointOffsetMinutes(LOC_ID, EVENT_TIME))
                .thenReturn(Optional.empty());

        TideDerivation d = deriver()
                .derive(LOC_ID, EVENT_TIME, Set.of(TideType.MID), LAT, LON, TargetType.SUNSET)
                .orElseThrow();

        assertThat(d.tideAlignmentQuality()).isNull();
        assertThat(warnLogMessages())
                .as("the one avenue tideAligned and tideAlignmentQuality can disagree on must "
                        + "not degrade silently")
                .anyMatch(msg -> msg.contains(LOC_ID.toString()) && msg.contains("tideAligned=true"));
    }
}
