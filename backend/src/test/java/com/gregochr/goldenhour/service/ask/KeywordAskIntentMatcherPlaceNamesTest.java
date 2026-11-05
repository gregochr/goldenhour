package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeywordAskIntentMatcher#namesAPlace}: a question that names a place is declined by the Ready matcher, a
 * name that is missing or blank anywhere in the snapshot never matches (and never throws), and every kind of name
 * the snapshot holds counts.
 */
class KeywordAskIntentMatcherPlaceNamesTest {

    private static final LocalDate DAY = LocalDate.of(2031, 3, 10);

    private static AskSnapshot.Slot slot(Long id, String name) {
        return new AskSnapshot.Slot(id, name, 4, null, null, false, null, false, null, false, null, null, null, null,
                null);
    }

    private static AskSnapshot.Region region(String name, AskSnapshot.Slot... slots) {
        return new AskSnapshot.Region(name, null, 4.0, true, true, List.of(slots), null);
    }

    private static AskSnapshot.NightSpot spot(long id, String name, String regionName) {
        return new AskSnapshot.NightSpot(id, name, regionName, 55.0, -1.5, false, 3, null, 4, null, null, null, null,
                null, null, null, null, false, null, null, null);
    }

    private static AskSnapshot snapshot(List<AskSnapshot.Region> regions, List<AskSnapshot.NightSpot> spots,
            List<AskSnapshot.Area> areas, Map<Long, List<String>> aliases) {
        AskSnapshot.Window window = new AskSnapshot.Window("2031-03-10_sunset", DAY, TargetType.SUNSET,
                LocalDateTime.of(2031, 3, 10, 18, 0), null, 4, null, regions);
        AskSnapshot.Night night = new AskSnapshot.Night(DAY + "_astro", DAY, AskWindowKind.ASTRO, null, null, null,
                spots);
        return new AskSnapshot(LocalDateTime.of(2031, 3, 10, 6, 0), "run", DAY, List.of(window), List.of(), List.of(),
                false, new AskSnapshot.Nights(DAY, List.of(night), null), areas, aliases, List.of());
    }

    private static AskSnapshot unnamed() {
        List<AskSnapshot.Region> regions = new ArrayList<>();
        regions.add(region(null, slot(1L, null), slot(2L, "  ")));
        regions.add(region("  "));
        List<AskSnapshot.NightSpot> spots = List.of(spot(3L, null, null), spot(4L, " ", " "));
        List<AskSnapshot.Area> areas = List.of(new AskSnapshot.Area(null, List.of(" "), Set.of()));
        return snapshot(regions, spots, areas, Map.of(1L, List.of(" ")));
    }

    @Test
    @DisplayName("names that are null or blank anywhere in the snapshot name no place")
    void blankNamesMatchNothing() {
        assertThat(KeywordAskIntentMatcher.namesAPlace("best spot this weekend", unnamed())).isFalse();
    }

    @Test
    @DisplayName("a location, a region, a night spot and its region, an area, an area alias and a location alias "
            + "each count as a place, as whole words")
    void everyKindOfNameCounts() {
        AskSnapshot snapshot = snapshot(List.of(region("Northumberland", slot(1L, "Bamburgh"))),
                List.of(spot(3L, "Dark Sky Park", "Kielder")),
                List.of(new AskSnapshot.Area("The Lakes", List.of("Lakeland"), Set.of(1L))),
                Map.of(1L, List.of("the Heugh")));

        assertThat(KeywordAskIntentMatcher.namesAPlace("best spot at bamburgh", snapshot)).isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("anywhere in northumberland", snapshot)).isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("is dark sky park good", snapshot)).isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("what about kielder", snapshot)).isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("the lakes tonight", snapshot)).isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("somewhere in lakeland", snapshot)).isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("near the heugh", snapshot)).isTrue();
        // Whole words only: "bamburghs" is not "bamburgh".
        assertThat(KeywordAskIntentMatcher.namesAPlace("bamburghs best", snapshot)).isFalse();
        assertThat(KeywordAskIntentMatcher.namesAPlace("best spot this weekend", snapshot)).isFalse();
    }
}
