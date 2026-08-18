package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single owner of "which slots vote on a region's verdict".
 *
 * <p>It has four callers — the hierarchy builder's triage verdict, the confidence roster, the
 * enrichment pass's rating rollup and the Plan projector's region ranking — and until it existed
 * the rule had four independent copies, one of which was simply missing. That is what let a rated
 * wood set the band for the sky locations it shares a region with. Tested here rather than only
 * through those callers so the rule has one place that says what it is.
 */
class BriefingSlotVotingSlotsTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 5, 23, 21, 11);

    private static BriefingSlot sky(String name) {
        return new BriefingSlot(name, AT, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null);
    }

    private static BriefingSlot wood(String name) {
        return BriefingSlot.canopySlot(name, AT, Verdict.GO, null, List.of(), null);
    }

    @Test
    @DisplayName("a mixed region votes with its sky slots alone")
    void aMixedRegionDropsItsWoods() {
        List<BriefingSlot> voting = BriefingSlot.votingSlots(
                List.of(wood("Bluebell Wood"), sky("Bamburgh"), sky("Alnmouth")));

        assertThat(voting).extracting(BriefingSlot::locationName)
                .containsExactly("Bamburgh", "Alnmouth");
    }

    @Test
    @DisplayName("an all-canopy region votes with its woods, rather than with nothing")
    void anAllCanopyRegionFallsBackToItsWoods() {
        // The fallback is why this is not a plain filter. Such a region genuinely has a forecast;
        // returning empty would leave it with no verdict at all, which is a worse answer than one
        // computed on inverted polarity.
        List<BriefingSlot> voting = BriefingSlot.votingSlots(
                List.of(wood("Bluebell Wood"), wood("Hollow Copse")));

        assertThat(voting).extracting(BriefingSlot::locationName)
                .containsExactly("Bluebell Wood", "Hollow Copse");
    }

    @Test
    @DisplayName("one sky slot among many woods is enough to take the vote on its own")
    void asingleSkySlotOutvotesEveryWood() {
        // The boundary of the fallback, and the direction that matters: the fallback fires on the
        // ABSENCE of a sky slot, not on woods being a majority. Keyed on a majority instead, this
        // region's four woods would carry it.
        List<BriefingSlot> voting = BriefingSlot.votingSlots(List.of(
                wood("W1"), wood("W2"), sky("Bamburgh"), wood("W3"), wood("W4")));

        assertThat(voting).extracting(BriefingSlot::locationName).containsExactly("Bamburgh");
    }

    @Test
    @DisplayName("no slots at all yields an empty list, never null")
    void emptyInputYieldsAnEmptyList() {
        assertThat(BriefingSlot.votingSlots(List.of())).isEmpty();
        assertThat(BriefingSlot.votingSlots(null)).isEmpty();
    }

    @Test
    @DisplayName("a region with no woods is returned unchanged")
    void anAllSkyRegionIsUntouched() {
        List<BriefingSlot> slots = List.of(sky("Bamburgh"), sky("Alnmouth"));

        assertThat(BriefingSlot.votingSlots(slots)).containsExactlyElementsOf(slots);
    }
}
