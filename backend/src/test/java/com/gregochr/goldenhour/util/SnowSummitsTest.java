package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.util.SnowSummits.Summit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the reviewed summit list: names, OS heights, unique keys, and positions inside northern England. */
class SnowSummitsTest {

    private static final Map<String, Integer> OS_HEIGHTS = new HashMap<>();

    static {
        OS_HEIGHTS.put("Helvellyn", 950);
        OS_HEIGHTS.put("Skiddaw", 931);
        OS_HEIGHTS.put("Blencathra", 868);
        OS_HEIGHTS.put("Scafell Pike", 978);
        OS_HEIGHTS.put("Coniston Old Man", 803);
        OS_HEIGHTS.put("High Street", 828);
        OS_HEIGHTS.put("Cross Fell", 893);
        OS_HEIGHTS.put("Whernside", 736);
        OS_HEIGHTS.put("Ingleborough", 723);
        OS_HEIGHTS.put("Pen-y-ghent", 694);
        OS_HEIGHTS.put("Great Whernside", 704);
        OS_HEIGHTS.put("Buckden Pike", 702);
        OS_HEIGHTS.put("The Cheviot", 815);
        OS_HEIGHTS.put("Hedgehope Hill", 714);
        OS_HEIGHTS.put("Urra Moor", 454);
    }

    @Test
    @DisplayName("the list is the fifteen reviewed summits at their OS heights")
    void fifteenSummitsAtTheirHeights() {
        assertThat(SnowSummits.ALL).hasSize(OS_HEIGHTS.size());
        for (Summit summit : SnowSummits.ALL) {
            assertThat(OS_HEIGHTS).as(summit.name()).containsEntry(summit.name(), summit.heightM());
        }
    }

    @Test
    @DisplayName("keys are unique, lower-case and prefixed in the point key")
    void keysAreUniqueAndPrefixed() {
        List<String> keys = SnowSummits.ALL.stream().map(Summit::key).toList();

        assertThat(keys).doesNotHaveDuplicates().allMatch(k -> k.matches("[a-z][a-z-]*"));
        assertThat(SnowSummits.ALL.get(0).pointKey()).isEqualTo("summit:helvellyn");
        assertThat(SnowSummits.ALL).allSatisfy(s -> assertThat(s.pointKey()).startsWith(SnowSummits.KEY_PREFIX));
    }

    @Test
    @DisplayName("every summit sits in the Lakes, Pennines, Dales, Cheviots or North York Moors")
    void positionsAreInNorthernEngland() {
        assertThat(SnowSummits.ALL).allSatisfy(s -> {
            assertThat(s.lat()).as(s.name()).isBetween(54.0, 55.6);
            assertThat(s.lon()).as(s.name()).isBetween(-3.3, -1.0);
        });
    }

    @Test
    @DisplayName("the Lakes summits are where the Ordnance Survey puts them (Scafell Pike, Helvellyn)")
    void spotCheckAgainstPublishedPositions() {
        Summit scafell = SnowSummits.ALL.stream().filter(s -> s.key().equals("scafell-pike")).findFirst().orElseThrow();
        Summit cheviot = SnowSummits.ALL.stream().filter(s -> s.key().equals("the-cheviot")).findFirst().orElseThrow();

        assertThat(scafell.lat()).isBetween(54.450, 54.458);
        assertThat(scafell.lon()).isBetween(-3.216, -3.208);
        assertThat(cheviot.lat()).isBetween(55.475, 55.482);
        assertThat(cheviot.lon()).isBetween(-2.150, -2.141);
        assertThat(SnowSummits.VIEW_RADIUS_KM).isEqualTo(12.0);
    }
}
