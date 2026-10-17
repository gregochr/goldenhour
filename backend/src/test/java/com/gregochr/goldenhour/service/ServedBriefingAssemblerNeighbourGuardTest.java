package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.service.pipeline.BestBetFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.D;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.at;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.day;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.points;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.region;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.sky;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.wood;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.zeroCoverageRegion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL {@link ServedBriefingAssembler#assembleForPlan} with the REAL honesty filter and the
 * REAL {@link PlanWindowProjector} to prove where the neighbour guard sits and what it changes
 * (docs/engineering/neighbour-guard-plan.md §4, G1b): after the filter, before the projector, never
 * on the build path, and off by default.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServedBriefingAssemblerNeighbourGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 17, 6, 0);

    @Mock
    private BestBetFallbackService bestBetFallbackService;
    @Mock
    private BriefingRegionSnapshotService regionSnapshotService;
    @Mock
    private WindowTideRollupBuilder windowTideRollupBuilder;
    @Mock
    private EvaluationViewService evaluationViewService;
    @Mock
    private LocationPointProvider locationPointProvider;

    private final NeighbourGuardProperties properties = new NeighbourGuardProperties();
    private final List<String> logLines = new ArrayList<>();
    private ServedBriefingAssembler assembler;

    @BeforeEach
    void setUp() {
        when(regionSnapshotService.previousBuild(any()))
                .thenReturn(BriefingRegionSnapshotService.PreviousBuild.none());
        when(evaluationViewService.loadServeTriage(any(), any(), any()))
                .thenReturn(EvaluationViewService.ServeTriage.EMPTY);
        when(locationPointProvider.points()).thenReturn(points(
                at(1, 0, 0, null), at(2, 1, 0, null), at(3, 0, 2, null), at(4, 8, 0, null)));
        properties.setEnabled(true);
        assembler = new ServedBriefingAssembler(bestBetFallbackService, regionSnapshotService,
                windowTideRollupBuilder, evaluationViewService, CLOCK,
                (days, resolver, triaged) -> days, locationPointProvider, properties,
                new NeighbourGuardLog(logLines::add));
    }

    private static DailyBriefingResponse snapshot(BriefingDay... days) {
        return new DailyBriefingResponse(BUILT, "headline", List.of(days), List.of(), null, null,
                false, false, 0, null, List.of(), List.of());
    }

    /** Sunrise on the fixture day: a 4-star slot (id 1), two 2-star neighbours, a 3-star place 8 km away. */
    private static DailyBriefingResponse caw() {
        return snapshot(day(D, TargetType.SUNRISE, region("North", sky(1L, "Caw Gap", 4),
                sky(2L, "Cawfields", 2), sky(3L, "Steel Rigg", 2), sky(4L, "Housesteads", 3))));
    }

    private static List<BriefingSlot> slotsOf(DailyBriefingResponse response) {
        return response.days().stream().flatMap(d -> d.eventSummaries().stream())
                .flatMap(es -> es.regions().stream()).flatMap(r -> r.slots().stream()).toList();
    }

    private static BriefingWindow windowOf(DailyBriefingResponse response) {
        return response.days().get(0).eventSummaries().get(0).window();
    }

    @Test
    @DisplayName("an outlier top slot is flagged and the window's pick names the next slot, id and name together")
    void outlierMovesThePickDestination() {
        DailyBriefingResponse served = assembler.assembleForPlan(caw(), 0.5);

        assertThat(slotsOf(served)).filteredOn(s -> Boolean.TRUE.equals(s.neighbourOutlier()))
                .extracting(BriefingSlot::locationName).containsExactly("Caw Gap");
        assertThat(slotsOf(served)).as("the star stays").filteredOn(s -> s.locationId() == 1L)
                .extracting(BriefingSlot::claudeRating).containsExactly(4);
        BriefingWindow.Pick pick = windowOf(served).pick();
        assertThat(pick.locationName()).isEqualTo("Housesteads");
        assertThat(pick.locationId()).isEqualTo(4L);
        assertThat(windowOf(served).bestRating()).as("the window's served best is unchanged").isEqualTo(4);
    }

    @Test
    @DisplayName("an unflagged slot carries no mark, not FALSE")
    void unflaggedSlotsCarryNull() {
        DailyBriefingResponse served = assembler.assembleForPlan(caw(), 0.5);

        assertThat(slotsOf(served)).filteredOn(s -> s.locationId() != 1L)
                .extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
    }

    @Test
    @DisplayName("disabled: nothing is flagged, the location provider is never called, the pick is the top slot")
    void disabledFlagsNothingAndReadsNothing() {
        properties.setEnabled(false);

        DailyBriefingResponse served = assembler.assembleForPlan(caw(), 0.5);

        verify(locationPointProvider, never()).points();
        assertThat(slotsOf(served)).extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
        assertThat(windowOf(served).pick().locationName()).isEqualTo("Caw Gap");
        assertThat(logLines).isEmpty();
    }

    @Test
    @DisplayName("when the next slot is rated 2 the pick names the region only")
    void nextBelowTheNamedFloorGivesNoDestination() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(day(D, TargetType.SUNRISE,
                region("North", sky(1L, "Caw Gap", 4), sky(2L, "Cawfields", 2), sky(3L, "Steel Rigg", 2)))),
                0.5);

        BriefingWindow.Pick pick = windowOf(served).pick();
        assertThat(pick).isNotNull();
        assertThat(pick.regionName()).isEqualTo("North");
        assertThat(pick.locationName()).isNull();
        assertThat(pick.locationId()).isNull();
    }

    @Test
    @DisplayName("a region whose only rated slot is an outlier names no place")
    void allOutlierRegionNamesNoPlace() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(day(D, TargetType.SUNRISE,
                region("North", sky(1L, "Caw Gap", 4)),
                region("South", sky(2L, "Cawfields", 2), sky(3L, "Steel Rigg", 2)))), 0.5);

        BriefingWindow.Pick pick = windowOf(served).pick();
        assertThat(pick.regionName()).isEqualTo("North");
        assertThat(pick.locationName()).isNull();
    }

    @Test
    @DisplayName("the guard runs after the honesty filter: a zero-coverage region's raw slots are not neighbours")
    void zeroCoverageRegionSlotsAreNotNeighbours() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(day(D, TargetType.SUNRISE,
                region("North", sky(1L, "Caw Gap", 4), sky(4L, "Housesteads", 3)),
                zeroCoverageRegion("Blanked", sky(2L, "Cawfields", 2), sky(3L, "Steel Rigg", 2)))), 0.5);

        assertThat(slotsOf(served)).extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
        assertThat(windowOf(served).pick().locationName()).isEqualTo("Caw Gap");
    }

    @Test
    @DisplayName("the mark survives the slot-tide strip and the window projection on the served payload")
    void markSurvivesTheStripAndTheProjection() {
        DailyBriefingResponse served = assembler.assembleForPlan(caw(), 0.5);

        BriefingSlot flagged = slotsOf(served).stream().filter(s -> s.locationId() == 1L).findFirst()
                .orElseThrow();
        assertThat(flagged.neighbourOutlier()).isTrue();
        assertThat(flagged.tide()).as("the tide strip ran").isNull();
    }

    @Test
    @DisplayName("a canopy slot is never marked, even one that shares a flagged sky slot's location id")
    void canopySlotIsNeverMarked() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(day(D, TargetType.SUNRISE,
                region("North", sky(1L, "Caw Gap", 4), sky(2L, "Cawfields", 2), sky(3L, "Steel Rigg", 2)),
                region("Woods", wood(1L, "Same id, a wood", 4)))), 0.5);

        assertThat(slotsOf(served)).filteredOn(BriefingSlot::canopy)
                .extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
        assertThat(slotsOf(served)).filteredOn(s -> !s.canopy() && s.locationId() == 1L)
                .extracting(BriefingSlot::neighbourOutlier).containsExactly(Boolean.TRUE);
    }

    @Test
    @DisplayName("served JSON (Jackson 3) carries the key once, only on the flagged slot; none when nothing is flagged")
    void servedJsonCarriesTheKeyOnlyWhenFlagged() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        String flagged = mapper.writeValueAsString(assembler.assembleForPlan(caw(), 0.5));
        assertThat(flagged.split("\"neighbourOutlier\":true", -1)).hasSize(2);
        assertThat(flagged).doesNotContain("\"neighbourOutlier\":false");

        String quiet = mapper.writeValueAsString(assembler.assembleForPlan(snapshot(day(D, TargetType.SUNRISE,
                region("North", sky(1L, "A", 4), sky(2L, "B", 3), sky(3L, "C", 3)))), 0.5));
        assertThat(quiet).doesNotContain("neighbourOutlier");
    }

    @Test
    @DisplayName("the build-side stage never marks a slot or reads coordinates, and the cached snapshot stays "
            + "free of the key (Jackson 2)")
    void buildSideNeverCarriesTheMark() throws Exception {
        DailyBriefingResponse cached = caw();

        DailyBriefingResponse withoutPlan = assembler.assembleWithoutPlan(cached, 0.5);
        assembler.assembleForPlan(cached, 0.5);

        assertThat(slotsOf(withoutPlan)).extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
        assertThat(slotsOf(cached)).extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
        ObjectMapper jackson2 = new ObjectMapper().registerModule(new JavaTimeModule());
        assertThat(jackson2.writeValueAsString(cached)).doesNotContain("neighbourOutlier");
    }

    @Test
    @DisplayName("a failed coordinates read serves the response unflagged instead of failing the serve")
    void failedPointReadFailsOpen() {
        when(locationPointProvider.points()).thenThrow(new IllegalStateException("db down"));

        DailyBriefingResponse served = assembler.assembleForPlan(caw(), 0.5);

        assertThat(slotsOf(served)).extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
        assertThat(windowOf(served).pick().locationName()).isEqualTo("Caw Gap");
    }

    @Test
    @DisplayName("a null snapshot and a day-less one pass through without a coordinates read")
    void nothingToJudge() {
        assertThat(assembler.assembleForPlan(null, 0.5)).isNull();
        assertThat(assembler.assembleForPlan(snapshot(), 0.5).days()).isEmpty();

        verify(locationPointProvider, never()).points();
    }

    @Test
    @DisplayName("each serve logs the summary once per briefing and the flagged slot once, ids only")
    void logsOncePerBriefing() {
        assembler.assembleForPlan(caw(), 0.5);
        assembler.assembleForPlan(caw(), 0.5);

        assertThat(logLines).hasSize(2);
        assertThat(logLines).anyMatch(l -> l.contains("Location 1 flagged") && !l.contains("Caw Gap"));
        assertThat(logLines).anyMatch(l -> l.contains("Flagged 1 of 4 rated sky slots over 1 windows"));
    }

    @Test
    @DisplayName("the other windows of the response are rebuilt with the same regions, days and events")
    void otherEventsAndDaysAreCarried() {
        BriefingDay sunrise = day(D, TargetType.SUNRISE, region("North", sky(1L, "Caw Gap", 4),
                sky(2L, "Cawfields", 2), sky(3L, "Steel Rigg", 2)));
        BriefingDay nextDay = day(D.plusDays(1), TargetType.SUNSET, region("North", sky(1L, "Caw Gap", 3)));

        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(sunrise, nextDay), 0.5);

        assertThat(served.days()).hasSize(2);
        List<BriefingEventSummary> events = Stream.of(served.days().get(0), served.days().get(1))
                .flatMap(d -> d.eventSummaries().stream()).toList();
        assertThat(events).extracting(BriefingEventSummary::targetType)
                .containsExactly(TargetType.SUNRISE, TargetType.SUNSET);
        assertThat(served.days().get(1).eventSummaries().get(0).regions().get(0).slots())
                .extracting(BriefingSlot::neighbourOutlier).containsOnlyNulls();
    }
}
