package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideAlmanacSource} — the two-source path.
 *
 * <p>The two halves are tested separately on purpose. Detection is pure lunar arithmetic with no
 * horizon and is checked against a stubbed classifier so the grouping logic is visible; enrichment
 * has a horizon and is checked by withholding builder output, which is exactly what happens beyond
 * the stored-extremes window.
 */
@ExtendWith(MockitoExtension.class)
class TideAlmanacSourceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private TideRunBuilder tideRunBuilder;

    @Mock
    private LocationRepository locationRepository;

    private TideAlmanacSource source() {
        return new TideAlmanacSource(lunarPhaseService, tideRunBuilder, locationRepository);
    }

    /**
     * Marks the given offsets from MONDAY as the given type, everything else regular.
     *
     * <p>Stubs one day either side of the span too, because the source walks outwards past the
     * requested range to find a run's true first and last day. Without those the walk would hit an
     * unstubbed call; with them defaulting to REGULAR_TIDE the walk stops at the range edge, which
     * is what every test here except the straddle tests wants.
     */
    private void classify(Map<Integer, LunarTideType> byOffset, int span) {
        for (int i = -1; i <= span; i++) {
            LocalDate date = MONDAY.plusDays(i);
            lenient().when(lunarPhaseService.classifyTide(date))
                    .thenReturn(byOffset.getOrDefault(i, LunarTideType.REGULAR_TIDE));
        }
    }

    @Nested
    @DisplayName("detection — unbounded, from lunar arithmetic alone")
    class Detection {

        @Test
        @DisplayName("consecutive spring days become one run, not one entry per day")
        void groupsConsecutiveDaysIntoOneRun() {
            classify(Map.of(
                    1, LunarTideType.SPRING_TIDE,
                    2, LunarTideType.SPRING_TIDE,
                    3, LunarTideType.SPRING_TIDE), 6);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY.plusDays(5));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().start()).isEqualTo(MONDAY.plusDays(1));
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY.plusDays(3));
            assertThat(runs.getFirst().king()).isFalse();
        }

        @Test
        @DisplayName("a regular day between two spring days splits them into two runs")
        void splitsOnANonQualifyingDay() {
            classify(Map.of(
                    0, LunarTideType.SPRING_TIDE,
                    2, LunarTideType.SPRING_TIDE), 4);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY.plusDays(3));

            assertThat(runs).hasSize(2);
            assertThat(runs.get(0).start()).isEqualTo(MONDAY);
            assertThat(runs.get(1).start()).isEqualTo(MONDAY.plusDays(2));
        }

        @Test
        @DisplayName("one perigean day makes the whole run a king run — a run is one event")
        void oneKingDayPromotesTheRun() {
            classify(Map.of(
                    0, LunarTideType.SPRING_TIDE,
                    1, LunarTideType.KING_TIDE,
                    2, LunarTideType.SPRING_TIDE), 3);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY.plusDays(2));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().king()).isTrue();
        }

        @Test
        @DisplayName("a run that began before the range is reported with its true start, not "
                + "clipped to look like it starts today")
        void extendsBackwardsPastTheRangeStart() {
            // Two days of the run are before the window; only the third is inside it.
            when(lunarPhaseService.classifyTide(MONDAY.minusDays(2)))
                    .thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.minusDays(1)))
                    .thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY)).thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(1)))
                    .thenReturn(LunarTideType.REGULAR_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.minusDays(3)))
                    .thenReturn(LunarTideType.REGULAR_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY.plusDays(1));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().start()).isEqualTo(MONDAY.minusDays(2));
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY);
        }

        @Test
        @DisplayName("a run continuing past the range is reported with its true end")
        void extendsForwardsPastTheRangeEnd() {
            when(lunarPhaseService.classifyTide(MONDAY.minusDays(1)))
                    .thenReturn(LunarTideType.REGULAR_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY)).thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(1)))
                    .thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(2)))
                    .thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(3)))
                    .thenReturn(LunarTideType.REGULAR_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY.plusDays(1));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY.plusDays(2));
        }

        @Test
        @DisplayName("a run whose only perigean day falls outside the range is still a king run — "
                + "the perigean day defines the event, and it is the one most likely to be clipped")
        void kingIsDecidedOverTheTrueSpanNotTheVisibleOne() {
            // Perigee is a half-day window, so exactly one day of a run can be KING. Put it one
            // day past the window: before the outward walk this entry came back as a spring run,
            // with different copy and a different useful water, correcting itself the next day.
            when(lunarPhaseService.classifyTide(MONDAY.minusDays(1)))
                    .thenReturn(LunarTideType.REGULAR_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY)).thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(1)))
                    .thenReturn(LunarTideType.KING_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(2)))
                    .thenReturn(LunarTideType.REGULAR_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY);

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().king()).isTrue();
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY.plusDays(1));
        }

        @Test
        @DisplayName("a run still open on the last day of the range is closed and kept")
        void closesARunAtTheEndOfTheRange() {
            classify(Map.of(
                    2, LunarTideType.SPRING_TIDE,
                    3, LunarTideType.SPRING_TIDE), 4);

            List<TideAlmanacSource.Run> runs = source().detectRuns(MONDAY, MONDAY.plusDays(3));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY.plusDays(3));
        }

        @Test
        @DisplayName("no qualifying day yields no runs and never touches the database")
        void noRunsMeansNoRepositoryCall() {
            classify(Map.of(), 3);

            assertThat(source().events(MONDAY, MONDAY.plusDays(2))).isEmpty();

            verify(locationRepository, never()).findCoastalLocations();
            verify(tideRunBuilder, never()).build(anyList(), anyList(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("enrichment — bounded by the stored-extremes window")
    class Enrichment {

        @Test
        @DisplayName("a run the builder cannot derive keeps its dates and carries no figures")
        void beyondTheStoredWindowTheDatesStandAlone() {
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            // Exactly what TideRunBuilder returns past the fetch horizon: nothing derivable.
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of());

            List<AlmanacEvent> events = source().events(MONDAY, MONDAY.plusDays(1));

            assertThat(events).hasSize(1);
            AlmanacEvent event = events.getFirst();
            assertThat(event.startDate()).isEqualTo(MONDAY);
            assertThat(event.endDate()).isEqualTo(MONDAY.plusDays(1));
            assertThat(event.kind()).isEqualTo(AlmanacKind.ALMANAC);
            // The degrade rule: dates survive, numbers are absent, nothing is invented.
            assertThat(event.isDatesOnly()).isTrue();
            assertThat(event.meta()).isEmpty();
        }

        @Test
        @DisplayName("within the window the run carries the peak day's range and verdict")
        void insideTheWindowTheFiguresAreCarried() {
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of(
                    MONDAY, runDay("3.9 m", false),
                    MONDAY.plusDays(1), runDay("4.6 m", true)));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            assertThat(event.isDatesOnly()).isFalse();
            assertThat(event.meta())
                    .containsEntry("range", "4.6 m")
                    .containsEntry("verdict", "alignment falls on sunrise")
                    .containsEntry("location", "Bamburgh Beach")
                    .containsEntry("peakDate", MONDAY.plusDays(1).toString());
        }

        @Test
        @DisplayName("a partly derivable run falls back to its first real day rather than to "
                + "nothing, and does NOT call that day the peak")
        void fallsBackToTheFirstDerivableDay() {
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            // The run straddles the edge of the stored window: day one derivable, day two not,
            // and neither is flagged as the run's peak.
            when(tideRunBuilder.build(anyList(), anyList(), eq(false)))
                    .thenReturn(Map.of(MONDAY, runDay("3.9 m", false)));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            assertThat(event.meta()).containsEntry("range", "3.9 m");
            // The figure is real and correctly attributed, but no maximum was computed — calling
            // it peakDate would make it indistinguishable from a genuine run peak to a renderer.
            assertThat(event.meta()).doesNotContainKey("peakDate");
            assertThat(event.meta()).containsEntry("figuresFrom", MONDAY.toString());
            assertThat(event.meta()).containsEntry("partialCoverage", "true");
        }

        @Test
        @DisplayName("a genuine run peak is published as peakDate, with no partial-coverage flag")
        void aTruePeakIsNamedAsSuch() {
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of(
                    MONDAY, runDay("3.9 m", false),
                    MONDAY.plusDays(1), runDay("4.6 m", true)));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            assertThat(event.meta()).containsEntry("peakDate", MONDAY.plusDays(1).toString());
            assertThat(event.meta()).doesNotContainKey("figuresFrom");
            assertThat(event.meta()).doesNotContainKey("partialCoverage");
        }

        @Test
        @DisplayName("a spring run carries the spring type, title and detail — not the king ones")
        void springRunsCarrySpringCopy() {
            classify(Map.of(0, LunarTideType.SPRING_TIDE), 1);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of());

            AlmanacEvent event = source().events(MONDAY, MONDAY).getFirst();

            // Every other assertion in this file feeds KING_TIDE, so without this a bare
            // TYPE_KING in place of the ternary would ship green.
            assertThat(event.type()).isEqualTo("spring-tide");
            assertThat(event.title()).isEqualTo("Spring tide run");
            assertThat(event.detail()).contains("foreshore is at its widest");
        }

        @Test
        @DisplayName("an inland deployment with no coastal roster still reports the run's dates")
        void noCoastalLocationsStillEmitsTheRun() {
            classify(Map.of(0, LunarTideType.KING_TIDE), 1);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of());

            List<AlmanacEvent> events = source().events(MONDAY, MONDAY);

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().type()).isEqualTo(TideAlmanacSource.TYPE_KING);
            assertThat(events.getFirst().isDatesOnly()).isTrue();
            // No roster means nothing to draw a run for — the builder must not be asked.
            verify(tideRunBuilder, never()).build(anyList(), anyList(), anyBoolean());
        }

        @Test
        @DisplayName("a king run is built as a king run, not a spring one")
        void kingRunsAreBuiltAsKing() {
            classify(Map.of(0, LunarTideType.KING_TIDE), 1);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(true))).thenReturn(Map.of());

            AlmanacEvent event = source().events(MONDAY, MONDAY).getFirst();

            assertThat(event.type()).isEqualTo("king-tide");
            assertThat(event.title()).isEqualTo("King tide run");
            assertThat(event.detail()).contains("closest approach");
            verify(tideRunBuilder).build(anyList(), anyList(), eq(true));
        }

        @Test
        @DisplayName("a source failure inside the builder is not swallowed here")
        void builderFailuresPropagateToTheService() {
            classify(Map.of(0, LunarTideType.SPRING_TIDE), 1);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), anyBoolean()))
                    .thenThrow(new IllegalStateException("db down"));

            // AlmanacService owns the isolation policy; a source that caught its own errors would
            // hide a broken database behind a permanently empty tide feed.
            TideAlmanacSource source = source();
            LocalDate day = MONDAY;
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> source.events(day, day))).hasMessage("db down");
        }
    }

    private static LocationEntity coastal() {
        return LocationEntity.builder().id(1L).name("Bamburgh Beach").build();
    }

    private static TideRunDay runDay(String range, boolean peak) {
        return new TideRunDay("SPRING RUN", 1, 2, "Mon", "Bamburgh Beach", range,
                "+0.8 m", "4.9 m", null, null, "06:20", "19:44", null, List.of(),
                "alignment falls on sunrise", true, "sunrise", peak, null);
    }

    @Test
    @DisplayName("events() never returns null for an empty range")
    void emptyRangeIsEmptyNotNull() {
        classify(Map.of(), 1);
        assertThat(source().events(MONDAY, MONDAY)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("Run.dates() enumerates every day inclusive of both ends")
    void runDatesAreInclusive() {
        TideAlmanacSource.Run run =
                new TideAlmanacSource.Run(MONDAY, MONDAY.plusDays(2), false);
        assertThat(run.dates()).containsExactly(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2));
    }

    @Test
    @DisplayName("the coastal roster is fetched once for the whole feed, not once per run")
    void rosterIsFetchedOncePerCall() {
        classify(Map.of(
                0, LunarTideType.SPRING_TIDE,
                2, LunarTideType.SPRING_TIDE,
                4, LunarTideType.SPRING_TIDE), 5);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
        when(tideRunBuilder.build(anyList(), anyList(), any(Boolean.class))).thenReturn(Map.of());

        List<AlmanacEvent> events = source().events(MONDAY, MONDAY.plusDays(4));

        assertThat(events).hasSize(3);
        verify(locationRepository, org.mockito.Mockito.times(1)).findCoastalLocations();
    }
}
