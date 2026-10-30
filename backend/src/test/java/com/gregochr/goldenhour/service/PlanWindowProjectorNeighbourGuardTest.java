package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.D;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.day;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.region;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.sky;
import static com.gregochr.goldenhour.service.NeighbourGuardFixtures.wood;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PlanWindowProjector.topSlot} and the neighbour guard (docs/engineering/neighbour-guard-plan.md
 * §4): a flagged slot is never the destination, and after one is skipped the next must be rated at
 * least the fixed named floor of 3.
 */
class PlanWindowProjectorNeighbourGuardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 4, 0);

    private static BriefingSlot flagged(BriefingSlot slot) {
        return slot.withNeighbourOutlier(Boolean.TRUE);
    }

    private static BriefingWindow.Pick pickOf(BriefingSlot... slots) {
        DailyBriefingResponse in = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 17, 3, 0),
                "headline", List.of(day(D, TargetType.SUNRISE, region("North", slots))), List.of(), null,
                null, false, null, List.of(), List.of());
        return PlanWindowProjector.apply(in, NOW, Map.of(), Map.of()).days().get(0).eventSummaries().get(0)
                .window().pick();
    }

    @Test
    @DisplayName("the named floor is the fixed 3 Ask's pick eligibility uses")
    void namedFloorIsThree() {
        assertThat(NeighbourOutlierGuard.NAMED_FLOOR).isEqualTo(3);
    }

    @Test
    @DisplayName("with no outlier the highest-rated slot is named, however low")
    void noOutlierNamesTheTopSlotEvenAtTwo() {
        BriefingWindow.Pick pick = pickOf(sky(1L, "Low", 2), sky(2L, "Lower", 1));

        assertThat(pick.locationName()).isEqualTo("Low");
        assertThat(pick.locationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a flagged top slot is skipped and the next, rated 3, is named with its own id")
    void skipsAFlaggedTopSlotToANextAtTheFloor() {
        BriefingWindow.Pick pick = pickOf(flagged(sky(1L, "Caw Gap", 4)), sky(2L, "Housesteads", 3),
                sky(3L, "Walltown", 2));

        assertThat(pick.locationName()).isEqualTo("Housesteads");
        assertThat(pick.locationId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("after a skip the next slot rated 2 is not named: the pick names the region only")
    void nextBelowTheFloorGivesNull() {
        BriefingWindow.Pick pick = pickOf(flagged(sky(1L, "Caw Gap", 4)), sky(2L, "Walltown", 2));

        assertThat(pick).isNotNull();
        assertThat(pick.locationName()).isNull();
        assertThat(pick.locationId()).isNull();
    }

    @Test
    @DisplayName("a region of only flagged slots names no place")
    void everySlotFlaggedGivesNull() {
        BriefingWindow.Pick pick = pickOf(flagged(sky(1L, "A", 4)), flagged(sky(2L, "B", 3)));

        assertThat(pick.locationName()).isNull();
    }

    @Test
    @DisplayName("two flagged slots are both skipped before the first unflagged one is judged")
    void skipsSeveralFlaggedSlots() {
        BriefingWindow.Pick pick = pickOf(flagged(sky(1L, "A", 5)), flagged(sky(2L, "B", 4)),
                sky(3L, "C", 3));

        assertThat(pick.locationName()).isEqualTo("C");
    }

    @Test
    @DisplayName("a skipped outlier does not lower the region's average or the window's best star")
    void starsAreUntouched() {
        DailyBriefingResponse in = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 17, 3, 0),
                "headline", List.of(day(D, TargetType.SUNRISE, region("North",
                        flagged(sky(1L, "A", 4)), sky(2L, "B", 3), sky(3L, "C", 3)))), List.of(), null,
                null, false, null, List.of(), List.of());

        BriefingWindow window = PlanWindowProjector.apply(in, NOW, Map.of(), Map.of()).days().get(0)
                .eventSummaries().get(0).window();

        assertThat(window.bestRating()).isEqualTo(4);
        assertThat(window.pick().averageRating()).isEqualTo(3.3);
    }

    @Test
    @DisplayName("a canopy slot is still never named, flagged or not")
    void canopyStillNeverNamed() {
        BriefingWindow.Pick pick = pickOf(flagged(sky(1L, "A", 4)), wood(2L, "Wood", 5),
                sky(3L, "C", 3));

        assertThat(pick.locationName()).isEqualTo("C");
    }
}
