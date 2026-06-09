package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.model.TideStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        when(tideService.deriveTideData(eq(LOC_ID), eq(EVENT_TIME), anyLong()))
                .thenReturn(Optional.of(tideData));
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
    @DisplayName("no stored extremes (deriveTideData empty) → empty")
    void noExtremes_returnsEmpty() {
        stubSolarWindow();
        when(tideService.deriveTideData(eq(LOC_ID), eq(EVENT_TIME), anyLong()))
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
}
