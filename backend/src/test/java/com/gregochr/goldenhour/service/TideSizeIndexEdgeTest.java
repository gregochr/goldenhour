package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideHighWater;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of {@link TideSizeIndex#measure}: unusable questions, rosters without ids or thresholds, malformed
 * or out-of-window extremes, and a king-only threshold.
 */
class TideSizeIndexEdgeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);
    /** August is BST: the London day 14th runs from 23:00 UTC on the 13th to 23:00 UTC on the 14th. */
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 8, 13, 23, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 8, 14, 23, 0);
    private static final long KNOWN = 7L;
    private static final long UNKNOWN = 9L;

    private TideExtremeRepository extremes;
    private TideService tideService;
    private TideSizeIndex index;

    @BeforeEach
    void setUp() {
        extremes = mock(TideExtremeRepository.class);
        tideService = mock(TideService.class);
        index = new TideSizeIndex(extremes, tideService);
    }

    private static LocationEntity location(Long id) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName("Coast " + id);
        return location;
    }

    private void stats(long id, String spring, String p95) {
        when(tideService.getTideStats(id)).thenReturn(Optional.of(new TideStats(
                null, null, null, null, 900L, null, null, null,
                p95 == null ? null : new BigDecimal(p95), 0L, null,
                spring == null ? null : new BigDecimal(spring), null, 0L)));
    }

    private void highWaters(List<Long> ids, TideHighWater... rows) {
        when(extremes.findHighWatersInWindow(ids, WINDOW_START, WINDOW_END)).thenReturn(Arrays.asList(rows));
    }

    @Test
    @DisplayName("no roster, an empty roster, a missing bound or an inverted window cannot be measured")
    void measure_unusableQuestions_unmeasured() {
        List<LocationEntity> roster = List.of(location(KNOWN));

        assertThat(index.measure(null, DAY, DAY)).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
        assertThat(index.measure(List.of(), DAY, DAY)).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
        assertThat(index.measure(roster, null, DAY)).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
        assertThat(index.measure(roster, DAY, null)).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
        assertThat(index.measure(roster, DAY, DAY.minusDays(1))).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
        verifyNoInteractions(extremes);
    }

    @Test
    @DisplayName("a roster of locations with no ids cannot be measured")
    void measure_rosterWithoutIds_unmeasured() {
        assertThat(index.measure(List.of(location(null)), DAY, DAY)).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
        verifyNoInteractions(extremes);
    }

    @Test
    @DisplayName("heights for a location with no stats are ignored; the location with stats is measured")
    void measure_locationWithoutThresholds_ignored() {
        stats(KNOWN, "4.00", "4.60");
        when(tideService.getTideStats(UNKNOWN)).thenReturn(Optional.empty());
        highWaters(List.of(KNOWN, UNKNOWN),
                new TideHighWater(KNOWN, DAY.atTime(6, 0), new BigDecimal("4.20")),
                new TideHighWater(UNKNOWN, DAY.atTime(6, 0), new BigDecimal("9.90")));

        TideSizeIndex.Sizes sizes = index.measure(List.of(location(KNOWN), location(UNKNOWN)), DAY, DAY);

        assertThat(sizes.usable()).isTrue();
        assertThat(sizes.springOn(DAY)).isTrue();
        assertThat(sizes.kingOn(DAY)).isFalse();
    }

    @Test
    @DisplayName("extremes with no height or no time, or outside the London day, are not counted")
    void measure_malformedAndOutOfWindowExtremes_ignored() {
        stats(KNOWN, "4.00", "4.60");
        highWaters(List.of(KNOWN),
                new TideHighWater(KNOWN, DAY.atTime(6, 0), null),
                new TideHighWater(KNOWN, null, new BigDecimal("9.90")),
                // 23:00 UTC on the 14th is London midnight, the first minute of the 15th: not asked about.
                new TideHighWater(KNOWN, WINDOW_END, new BigDecimal("9.90")),
                // 22:00 UTC on the 13th is London 23:00 on the 13th: before the window.
                new TideHighWater(KNOWN, LocalDateTime.of(2026, 8, 13, 22, 0), new BigDecimal("9.90")),
                new TideHighWater(KNOWN, DAY.atTime(6, 0), new BigDecimal("3.50")));

        TideSizeIndex.Sizes sizes = index.measure(List.of(location(KNOWN)), DAY, DAY);

        assertThat(sizes.usable()).isTrue();
        assertThat(sizes.springOn(DAY)).isFalse();
        assertThat(sizes.kingOn(DAY)).isFalse();
        assertThat(sizes.springOn(DAY.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("with only a king threshold the day can be king-sized but never spring-sized")
    void measure_kingOnlyThreshold() {
        stats(KNOWN, null, "4.60");
        highWaters(List.of(KNOWN), new TideHighWater(KNOWN, DAY.atTime(6, 0), new BigDecimal("4.70")));

        TideSizeIndex.Sizes sizes = index.measure(List.of(location(KNOWN)), DAY, DAY);

        assertThat(sizes.kingOn(DAY)).isTrue();
        assertThat(sizes.springOn(DAY)).isFalse();
        assertThat(sizes.bestRatio()).isEmpty();
    }

    @Test
    @DisplayName("a usable roster with no stored heights at all is unmeasured, not 'nothing qualified'")
    void measure_noHeights_unmeasured() {
        stats(KNOWN, "4.00", "4.60");
        highWaters(List.of(KNOWN));

        assertThat(index.measure(List.of(location(KNOWN)), DAY, DAY)).isSameAs(TideSizeIndex.Sizes.UNMEASURED);
    }
}
