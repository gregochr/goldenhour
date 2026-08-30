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
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>The two halves are tested separately on purpose. Detection is checked directly against a
 * {@link TideSizeIndex.Sizes} so the grouping logic is visible; enrichment has a horizon and is
 * checked by withholding builder output, which is exactly what happens beyond the stored-extremes
 * window.
 *
 * <p>Detection itself has two arms and both are exercised: the measured arm, where stored heights
 * decide which dates carry spring- or king-sized water, and the lunar fallback for a database that
 * cannot answer yet. The fallback tests feed {@link TideSizeIndex.Sizes#UNMEASURED} explicitly
 * rather than relying on a default, because "nothing was measured" and "nothing qualified" are the
 * two states this source must never confuse.
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

    @Mock
    private TideSizeIndex tideSizeIndex;

    private TideAlmanacSource source() {
        return new TideAlmanacSource(
                lunarPhaseService, tideRunBuilder, locationRepository, tideSizeIndex);
    }

    /**
     * Stubs the index as unable to measure, which is what every {@code events()} test below wants:
     * they are about enrichment and copy, and the lunar fallback keeps their date setup readable.
     * Detection from heights has its own nested class.
     */
    private void indexCannotMeasure() {
        when(tideSizeIndex.measure(anyList(), any(), any()))
                .thenReturn(TideSizeIndex.Sizes.UNMEASURED);
    }

    /** A measured index carrying the given offsets from MONDAY as spring and king days. */
    private static TideSizeIndex.Sizes sizes(Set<Integer> springOffsets, Set<Integer> kingOffsets) {
        return new TideSizeIndex.Sizes(
                springOffsets.stream().map(MONDAY::plusDays).collect(Collectors.toSet()),
                kingOffsets.stream().map(MONDAY::plusDays).collect(Collectors.toSet()),
                true);
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
    @DisplayName("detection from stored heights — the measured arm")
    class HeightDetection {

        @Test
        @DisplayName("the water decides membership and the moon does not get a vote — a run sits "
                + "where the big tides are, not where syzygy was")
        void theWaterDecidesMembershipNotTheMoon() {
            // The moon calls every day in the range a spring tide; the stored heights carry three
            // of them. This is the defect in miniature: the lunar window closes about a day after
            // syzygy and a port's biggest water arrives a day or two later still, so the two answers
            // are genuinely different sets and the feed used to publish the wrong one.
            when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.SPRING_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(5), sizes(Set.of(1, 2, 3), Set.of()));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().start()).isEqualTo(MONDAY.plusDays(1));
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY.plusDays(3));
            // Spring everywhere, king nowhere: the astronomical arm of the label is untouched by
            // the fact that the moon would have accepted every one of these days as a member.
            assertThat(runs.getFirst().king()).isFalse();
        }

        @Test
        @DisplayName("a king-sized date still qualifies as part of a run — the two height "
                + "thresholds are independent and neither implies the other")
        void kingSizedWaterQualifiesOnItsOwn() {
            // P95 and 125%-of-mean are computed from one sample but neither is defined in terms of
            // the other, so a location's P95 can sit above its spring threshold. Treating king-sized
            // as a subset of spring-sized would drop exactly the biggest day of the run. This is
            // about MEMBERSHIP — which dates the run covers — not about its label.
            when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.SPRING_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(2), sizes(Set.of(), Set.of(1)));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().start()).isEqualTo(MONDAY.plusDays(1));
        }

        @Test
        @DisplayName("king-SIZED water does not make a KING run — the label is the moon's, the "
                + "dates are the water's")
        void measuredKingDoesNotPromoteTheRun() {
            // The two axes, in one assertion. Every day of this run carries water above its port's
            // P95, and none of it is perigean: the run is real and correctly dated, and it is a
            // SPRING run. Promoting it would print a card about "the moon's closest approach" on a
            // date the moon was not close, which is what the Plan tab used to do and what the
            // height arm was removed from both surfaces to stop.
            when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.SPRING_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(2), sizes(Set.of(0, 1, 2), Set.of(0, 1, 2)));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().king()).isFalse();
        }

        @Test
        @DisplayName("a perigean day still promotes a measured run — the astronomical arm survives")
        void perigeePromotesAMeasuredRun() {
            when(lunarPhaseService.classifyTide(MONDAY)).thenReturn(LunarTideType.REGULAR_TIDE);
            when(lunarPhaseService.classifyTide(MONDAY.plusDays(1)))
                    .thenReturn(LunarTideType.KING_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(1), sizes(Set.of(0, 1), Set.of()));

            assertThat(runs.getFirst().king()).isTrue();
        }

        @Test
        @DisplayName("the outward walk uses the measured index too, so a run that began before the "
                + "range keeps its true start")
        void theWalkReadsTheMeasuredIndex() {
            when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.REGULAR_TIDE);

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY, sizes(Set.of(-2, -1, 0), Set.of()));

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().start()).isEqualTo(MONDAY.minusDays(2));
        }
    }

    @Nested
    @DisplayName("detection — the lunar fallback, for a database that cannot answer yet")
    class Detection {

        @Test
        @DisplayName("consecutive spring days become one run, not one entry per day")
        void groupsConsecutiveDaysIntoOneRun() {
            classify(Map.of(
                    1, LunarTideType.SPRING_TIDE,
                    2, LunarTideType.SPRING_TIDE,
                    3, LunarTideType.SPRING_TIDE), 6);

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(5), TideSizeIndex.Sizes.UNMEASURED);

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

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(3), TideSizeIndex.Sizes.UNMEASURED);

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

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(2), TideSizeIndex.Sizes.UNMEASURED);

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

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(1), TideSizeIndex.Sizes.UNMEASURED);

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

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(1), TideSizeIndex.Sizes.UNMEASURED);

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

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY, TideSizeIndex.Sizes.UNMEASURED);

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

            List<TideAlmanacSource.Run> runs = source().detectRuns(
                    MONDAY, MONDAY.plusDays(3), TideSizeIndex.Sizes.UNMEASURED);

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().end()).isEqualTo(MONDAY.plusDays(3));
        }

        @Test
        @DisplayName("no qualifying day yields no runs and never builds a curve")
        void noRunsMeansNoBuilderCall() {
            indexCannotMeasure();
            classify(Map.of(), 3);

            assertThat(source().events(MONDAY, MONDAY.plusDays(2))).isEmpty();

            // The roster IS read now, once, before detection — it is the thing being measured, so
            // the read moved ahead of the decision it used to sit behind. The expensive half is
            // still skipped: no run means no curve to draw and no extremes to fetch for one.
            verify(locationRepository).findCoastalLocations();
            verify(tideRunBuilder, never()).build(anyList(), anyList(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("enrichment — bounded by the stored-extremes window")
    class Enrichment {

        @Test
        @DisplayName("a run the builder cannot derive keeps its dates and carries no figures")
        void beyondTheStoredWindowTheDatesStandAlone() {
            indexCannotMeasure();
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
        @DisplayName("within the window the run carries the peak day's range and an alignment")
        void insideTheWindowTheFiguresAreCarried() {
            indexCannotMeasure();
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of(
                    MONDAY, runDay("3.9 m", false),
                    MONDAY.plusDays(1), runDay("4.6 m", true)));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            assertThat(event.isDatesOnly()).isFalse();
            assertThat(event.meta())
                    .containsEntry("range", "4.6 m")
                    .containsEntry("alignment", "LW 06:54 · 34m after sunrise")
                    .containsEntry("location", "Bamburgh Beach")
                    .containsEntry("peakDate", MONDAY.plusDays(1).toString())
                    .doesNotContainKey("noAlignment");
            // The verdict is never republished under an alignment label. It has an unaligned form
            // that always reads as a sentence — "peak range · LW 2h12 after sunset" — which is how
            // a low water two hours into the dark came to be printed as this run's alignment.
            assertThat(event.meta()).doesNotContainKey("verdict");
            assertThat(event.meta().values()).noneMatch(v -> v.contains("peak range"));
        }

        @Test
        @DisplayName("a run where no day lands the water in the light says so, rather than "
                + "promoting the biggest day's unaligned verdict")
        void anUnalignedRunSaysSo() {
            indexCannotMeasure();
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of(
                    MONDAY, runDay("3.9 m", false, null),
                    MONDAY.plusDays(1), runDay("4.6 m", true, null)));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            // A finding, not an absence: the figures are here, every day was examined, and none of
            // them works. Silence would be indistinguishable from "no curve could be derived",
            // which is the state the empty-meta degrade rule already reports.
            assertThat(event.meta())
                    .containsEntry("range", "4.6 m")
                    .containsEntry("noAlignment", "true")
                    .doesNotContainKey("alignment")
                    .doesNotContainKey("alignmentDate");
        }

        @Test
        @DisplayName("the alignment comes from the day that has one, even when that is not the "
                + "run's biggest day — and names which day it is")
        void alignmentIsTakenFromTheAlignedDayNotThePeak() {
            indexCannotMeasure();
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            // The biggest water of the run misses the light; the smaller first day catches it.
            // Reading the peak day would report the run as unaligned and hide the one morning in
            // it worth driving to — the failure this split exists to prevent.
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of(
                    MONDAY, runDay("3.9 m", false, "HW 07:02 · 42m after sunrise"),
                    MONDAY.plusDays(1), runDay("4.6 m", true, null)));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            assertThat(event.meta())
                    // Figures still describe the biggest day — the two questions are separate.
                    .containsEntry("range", "4.6 m")
                    .containsEntry("peakDate", MONDAY.plusDays(1).toString())
                    .containsEntry("alignment", "HW 07:02 · 42m after sunrise")
                    .containsEntry("alignmentDate", MONDAY.toString())
                    .doesNotContainKey("noAlignment");
        }

        @Test
        @DisplayName("the run's biggest day wins the alignment when it has one too")
        void theBiggestAlignedDayIsPreferred() {
            indexCannotMeasure();
            classify(Map.of(0, LunarTideType.SPRING_TIDE, 1, LunarTideType.SPRING_TIDE), 2);
            when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastal()));
            when(tideRunBuilder.build(anyList(), anyList(), eq(false))).thenReturn(Map.of(
                    MONDAY, runDay("3.9 m", false, "HW 07:02 · 42m after sunrise"),
                    MONDAY.plusDays(1), runDay("4.6 m", true, "HW 07:48 · 12m after sunrise")));

            AlmanacEvent event = source().events(MONDAY, MONDAY.plusDays(1)).getFirst();

            // Largest water and usable light on one morning is the day to name, and it is the day
            // the range chip and the "Biggest" note are already about.
            assertThat(event.meta())
                    .containsEntry("alignment", "HW 07:48 · 12m after sunrise")
                    .containsEntry("alignmentDate", MONDAY.plusDays(1).toString());
        }

        @Test
        @DisplayName("a partly derivable run falls back to its first real day rather than to "
                + "nothing, and does NOT call that day the peak")
        void fallsBackToTheFirstDerivableDay() {
            indexCannotMeasure();
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
            indexCannotMeasure();
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
            indexCannotMeasure();
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
            indexCannotMeasure();
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
            indexCannotMeasure();
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
            indexCannotMeasure();
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

    /** A derivable day whose water lands in the light. */
    private static TideRunDay runDay(String range, boolean peak) {
        return runDay(range, peak, "LW 06:54 · 34m after sunrise");
    }

    /**
     * A derivable day, aligned or not.
     *
     * <p>{@code alignmentPhrase} is the whole alignment signal — {@code alignedEvent} is set with it
     * because the builder guarantees the two are non-null together, and {@code verdict} is populated
     * on both so a test cannot pass by accident through the field this source deliberately stopped
     * reading.
     */
    private static TideRunDay runDay(String range, boolean peak, String alignmentPhrase) {
        return new TideRunDay("SPRING RUN", 1, 2, "Mon", "Bamburgh Beach", range,
                "+0.8 m", "4.9 m", null, null, "06:20", "19:44", null, List.of(),
                "peak range · LW 2h12 after sunset", alignmentPhrase != null,
                // bestAligned tracks aligned here — the almanac reads alignmentPhrase, not the
                // accent, so this fixture only has to stay internally consistent.
                alignmentPhrase != null,
                alignmentPhrase == null ? null : "sunrise", alignmentPhrase,
                new TideRunDay.RosterAlignment(3, 0, 4), null, null, peak, null);
    }

    @Test
    @DisplayName("events() never returns null for an empty range")
    void emptyRangeIsEmptyNotNull() {
        indexCannotMeasure();
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
        indexCannotMeasure();
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
