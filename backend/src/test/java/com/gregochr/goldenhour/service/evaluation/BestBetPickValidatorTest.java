package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.Relationship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link BestBetPickValidator}: which picks survive validation against the known
 * events, regions and day names, and how the survivors are re-ranked.
 */
class BestBetPickValidatorTest {

    private static final Set<String> EVENTS = Set.of("2026-06-21T05:00:sunrise", "2026-06-21T21:00:sunset");
    private static final Set<String> REGIONS = Set.of("Northumberland", "Lake District");
    private static final Set<String> DAYS = Set.of("Sunday", "Monday");

    private static BestBet pick(int rank, String headline, String detail, String event, String region) {
        return new BestBet(rank, headline, detail, event, region, Confidence.HIGH, 30,
                "Sunday", "SUNSET", "21:00", null, null);
    }

    private static List<BestBet> validate(BestBet... picks) {
        return BestBetPickValidator.validateAndFilterPicks(List.of(picks), EVENTS, REGIONS, DAYS);
    }

    @Test
    @DisplayName("a stay-home pick (no event, no region) is always valid, even with unknown-day narrative")
    void stayHomePick_alwaysValid() {
        List<BestBet> result = validate(pick(1, "Stay in on Friday", "Nothing worth it", null, null));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).headline()).isEqualTo("Stay in on Friday");
    }

    @Test
    @DisplayName("a pick whose event is not among the valid events is discarded")
    void unknownEvent_discarded() {
        assertThat(validate(pick(1, "h", "d", "2026-06-22T05:00:sunrise", "Northumberland"))).isEmpty();
    }

    @Test
    @DisplayName("a pick with a known event and a known region survives")
    void knownEventAndRegion_survives() {
        assertThat(validate(pick(1, "h", "d", "2026-06-21T21:00:sunset", "Northumberland"))).hasSize(1);
    }

    @Test
    @DisplayName("a pick with no event but an unknown region is discarded")
    void noEventUnknownRegion_discarded() {
        assertThat(validate(pick(1, "h", "d", null, "Cornwall"))).isEmpty();
    }

    @Test
    @DisplayName("a pick with no event but a known region survives")
    void noEventKnownRegion_survives() {
        assertThat(validate(pick(1, "h", "d", null, "Lake District"))).hasSize(1);
    }

    @Test
    @DisplayName("a pick with a known event and an unknown region is discarded")
    void knownEventUnknownRegion_discarded() {
        assertThat(validate(pick(1, "h", "d", "2026-06-21T21:00:sunset", "Cornwall"))).isEmpty();
    }

    @Test
    @DisplayName("a pick with a known event and no region (region-agnostic aurora) survives")
    void knownEventNoRegion_survives() {
        assertThat(validate(pick(1, "h", "d", "2026-06-21T21:00:sunset", null))).hasSize(1);
    }

    @Test
    @DisplayName("a narrative naming a day outside the forecast window discards the pick")
    void headlineNamesDayOutsideWindow_discarded() {
        assertThat(validate(pick(1, "Best on Friday", "d", "2026-06-21T21:00:sunset", "Northumberland")))
                .isEmpty();
    }

    @Test
    @DisplayName("a detail naming a day outside the forecast window discards the pick")
    void detailNamesDayOutsideWindow_discarded() {
        assertThat(validate(pick(1, "h", "Clearing by Tuesday", "2026-06-21T21:00:sunset", "Northumberland")))
                .isEmpty();
    }

    @Test
    @DisplayName("a narrative naming only days inside the window survives")
    void narrativeNamesDaysInsideWindow_survives() {
        assertThat(validate(pick(1, "Sunday beats Monday", "d", "2026-06-21T21:00:sunset", "Northumberland")))
                .hasSize(1);
    }

    @Test
    @DisplayName("a pick with a null headline and null detail is validated on event and region alone")
    void nullNarrative_survives() {
        assertThat(validate(pick(1, null, null, "2026-06-21T21:00:sunset", "Northumberland"))).hasSize(1);
    }

    @Test
    @DisplayName("with no valid day names the narrative day check is skipped")
    void emptyValidDayNames_skipsDayCheck() {
        List<BestBet> result = BestBetPickValidator.validateAndFilterPicks(
                List.of(pick(1, "Friday looks best", "d", "2026-06-21T21:00:sunset", "Northumberland")),
                EVENTS, REGIONS, Set.of());

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("survivors are re-ranked 1..n in order and keep every other field")
    void survivors_reRankedAndFieldsPreserved() {
        BestBet kept = new BestBet(3, "Keep", "Detail", "2026-06-21T21:00:sunset", "Northumberland",
                Confidence.MEDIUM, 45, "Sunday", "SUNSET", "21:00", Relationship.SAME_SLOT, null);
        List<BestBet> result = validate(
                pick(1, "Bad", "d", "unknown-event", "Northumberland"),
                kept,
                pick(5, "Also kept", "d", "2026-06-21T05:00:sunrise", "Lake District"));

        assertThat(result).extracting(BestBet::rank).containsExactly(1, 2);
        assertThat(result).extracting(BestBet::headline).containsExactly("Keep", "Also kept");
        assertThat(result.get(0).confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.get(0).nearestDriveMinutes()).isEqualTo(45);
        assertThat(result.get(0).relationship()).isEqualTo(Relationship.SAME_SLOT);
        assertThat(result.get(0).eventTime()).isEqualTo("21:00");
    }

    @Test
    @DisplayName("when every pick fails validation the result is empty")
    void allInvalid_empty() {
        assertThat(validate(
                pick(1, "h", "d", "nope", "Northumberland"),
                pick(2, "h", "d", "2026-06-21T21:00:sunset", "Cornwall"))).isEmpty();
    }
}
