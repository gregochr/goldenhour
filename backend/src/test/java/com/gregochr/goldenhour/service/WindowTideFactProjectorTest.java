package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.LocationTideFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.TideFactFixtures.canopy;
import static com.gregochr.goldenhour.service.TideFactFixtures.coastal;
import static com.gregochr.goldenhour.service.TideFactFixtures.day;
import static com.gregochr.goldenhour.service.TideFactFixtures.inland;
import static com.gregochr.goldenhour.service.TideFactFixtures.region;
import static com.gregochr.goldenhour.service.TideFactFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;

class WindowTideFactProjectorTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 11);
    private static final PlanWindowProjector.WindowKey SUNRISE =
            new PlanWindowProjector.WindowKey(D, TargetType.SUNRISE);

    private static List<LocationTideFact> factsAt(List<BriefingDay> days,
            PlanWindowProjector.WindowKey key) {
        return WindowTideFactProjector.project(days).get(key);
    }

    @Test
    @DisplayName("regioned slots come first, then unregioned, each as one fact")
    void regionedThenUnregioned() {
        List<BriefingDay> days = List.of(day(D, summary(TargetType.SUNRISE,
                List.of(region("North", 0, coastal(1L, "Bamburgh", "HIGH")),
                        region("South", 0, coastal(2L, "Whitby", "LOW"))),
                List.of(coastal(3L, "Orphan", "MID")))));

        assertThat(factsAt(days, SUNRISE)).extracting(LocationTideFact::locationName)
                .containsExactly("Bamburgh", "Whitby", "Orphan");
    }

    @Test
    @DisplayName("unregioned slots alone still yield facts")
    void unregionedOnly() {
        List<BriefingDay> days = List.of(day(D, summary(TargetType.SUNRISE, List.of(),
                List.of(coastal(3L, "Orphan", "MID")))));

        assertThat(factsAt(days, SUNRISE)).extracting(LocationTideFact::locationName)
                .containsExactly("Orphan");
    }

    @Test
    @DisplayName("inland and canopy slots yield no fact")
    void nonCoastalSlotsAreExcluded() {
        List<BriefingDay> days = List.of(day(D, summary(TargetType.SUNRISE,
                List.of(region("North", 0, inland(1L, "Durham"), canopy(2L, "Wood"),
                        coastal(3L, "Bamburgh", "HIGH"))),
                List.of())));

        assertThat(factsAt(days, SUNRISE)).extracting(LocationTideFact::locationName)
                .containsExactly("Bamburgh");
    }

    @Test
    @DisplayName("a slot whose tide state is null is excluded even when other tide fields exist")
    void tideStateNullSlotIsExcluded() {
        BriefingSlot noState = coastal(1L, "Ghost", null);

        assertThat(WindowTideFactProjector.project(List.of(day(D, summary(TargetType.SUNRISE,
                List.of(region("North", 0, noState)), List.of()))))).isEmpty();
    }

    @Test
    @DisplayName("fields are copied verbatim, never re-derived from one another")
    void fieldsAreCopiedVerbatim() {
        // coastal() is deliberately inconsistent: aligned=false beside a "suits" phrase.
        List<BriefingDay> days = List.of(day(D, summary(TargetType.SUNRISE,
                List.of(region("North", 0, coastal(1L, "Bamburgh", "HIGH"))), List.of())));

        LocationTideFact f = factsAt(days, SUNRISE).get(0);

        assertThat(f.locationId()).isEqualTo(1L);
        assertThat(f.locationName()).isEqualTo("Bamburgh");
        assertThat(f.tideState()).isEqualTo("HIGH");
        assertThat(f.tideAligned()).isFalse();
        assertThat(f.tideAlignmentQuality()).isEqualTo(0.61);
        assertThat(f.tideOnTheLight()).isTrue();
        assertThat(f.nearestSolarOffsetPhrase()).isEqualTo("HW 06:12 · 25m after sunrise");
        assertThat(f.tideLevel()).isEqualTo(0.83);
        assertThat(f.tideDirection()).isEqualTo("RISING");
        assertThat(f.tideHeight()).isEqualTo("4.9 m");
        assertThat(f.tideShortfall()).isEqualTo("wants higher");
        assertThat(f.tideFitPhrase()).isEqualTo("Tide suits this spot");
    }

    @Test
    @DisplayName("a legacy slot with no id is kept, keyed by name, with a null id")
    void legacySlotWithoutId() {
        List<BriefingDay> days = List.of(day(D, summary(TargetType.SUNRISE,
                List.of(region("North", 0, coastal(null, "Old", "HIGH"))), List.of())));

        LocationTideFact f = factsAt(days, SUNRISE).get(0);

        assertThat(f.locationId()).isNull();
        assertThat(f.locationName()).isEqualTo("Old");
    }

    @Test
    @DisplayName("a location named twice in a window: the first in region order wins")
    void duplicateLocationFirstWins() {
        List<BriefingDay> days = List.of(day(D, summary(TargetType.SUNRISE,
                List.of(region("A", 0, coastal(1L, "Bamburgh", "HIGH")),
                        region("B", 0, coastal(1L, "Bamburgh", "LOW"))),
                List.of(coastal(1L, "Bamburgh", "MID")))));

        assertThat(factsAt(days, SUNRISE)).singleElement()
                .extracting(LocationTideFact::tideState).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("identity is by id before name, and per window")
    void identityIsPerWindowAndByIdBeforeName() {
        List<BriefingDay> days = List.of(day(D,
                summary(TargetType.SUNRISE, List.of(region("A", 0,
                        coastal(1L, "Twin", "HIGH"), coastal(2L, "Twin", "LOW"))), List.of()),
                summary(TargetType.SUNSET, List.of(region("A", 0,
                        coastal(1L, "Twin", "MID"))), List.of())));

        assertThat(factsAt(days, SUNRISE)).extracting(LocationTideFact::locationId)
                .containsExactly(1L, 2L);
        assertThat(factsAt(days, new PlanWindowProjector.WindowKey(D, TargetType.SUNSET)))
                .extracting(LocationTideFact::tideState).containsExactly("MID");
    }

    @Test
    @DisplayName("a summary with no coastal slot has no entry at all, never an empty list")
    void noCoastalSlotMeansNoKey() {
        Map<PlanWindowProjector.WindowKey, List<LocationTideFact>> out =
                WindowTideFactProjector.project(List.of(day(D, summary(TargetType.SUNRISE,
                        List.of(region("North", 0, inland(1L, "Durham"))), List.of()))));

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("null and empty input project to an empty map")
    void nullAndEmptyInput() {
        assertThat(WindowTideFactProjector.project(null)).isEmpty();
        assertThat(WindowTideFactProjector.project(List.of())).isEmpty();
    }

    @Test
    @DisplayName("the input is not mutated")
    void inputIsNotMutated() {
        BriefingSlot slot = coastal(1L, "Bamburgh", "HIGH");
        BriefingEventSummary es = summary(TargetType.SUNRISE,
                List.of(region("North", 0, slot)), List.of(coastal(2L, "Orphan", "LOW")));
        List<BriefingDay> days = List.of(day(D, es));
        List<BriefingDay> before = List.copyOf(days);

        WindowTideFactProjector.project(days);

        assertThat(days).isEqualTo(before);
        assertThat(days.get(0).eventSummaries().get(0)).isEqualTo(es);
        assertThat(es.regions().get(0).slots()).containsExactly(slot);
    }
}
