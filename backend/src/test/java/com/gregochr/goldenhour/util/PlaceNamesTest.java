package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PlaceNames}, the one normalisation Ask's matching and the admin name guard share.
 */
class PlaceNamesTest {

    @Test
    @DisplayName("words() folds case, drops straight and curly apostrophes and splits on other punctuation")
    void words_folds() {
        assertThat(PlaceNames.words("Hadrian's Wall")).containsExactly("hadrians", "wall");
        assertThat(PlaceNames.words("Cuddy’s Crags")).containsExactly("cuddys", "crags");
        assertThat(PlaceNames.words("Steel Rigg & Winshield-Crags"))
                .containsExactly("steel", "rigg", "winshield", "crags");
        assertThat(PlaceNames.words("Hadrians Wall")).isEqualTo(PlaceNames.words("HADRIAN'S WALL"));
    }

    @Test
    @DisplayName("words() of nothing but punctuation is empty")
    void words_empty() {
        assertThat(PlaceNames.words("  !! ")).isEmpty();
    }

    @Test
    @DisplayName("containsRun() needs a contiguous run of whole words, and never matches an empty run")
    void containsRun_contiguousWholeWords() {
        List<String> words = List.of("steel", "rigg", "and", "crags");

        assertThat(PlaceNames.containsRun(words, List.of("rigg", "and"))).isTrue();
        assertThat(PlaceNames.containsRun(words, List.of("steel", "crags"))).isFalse();
        assertThat(PlaceNames.containsRun(words, List.of("rig"))).isFalse();
        assertThat(PlaceNames.containsRun(words, List.of())).isFalse();
    }

    private static int distance(String name, String query) {
        return PlaceNames.prefixDistance(PlaceNames.words(name), PlaceNames.words(query));
    }

    @Test
    @DisplayName("prefixDistance() counts edits to the closest leading run of the name")
    void prefixDistance_leadingRuns() {
        assertThat(distance("Bamburgh", "bamborough")).isEqualTo(3);
        assertThat(distance("Bamburgh Castle", "bamburgh")).isZero();
        assertThat(distance("Whitby Abbey", "witby")).isEqualTo(1);
        assertThat(distance("Stanley Gill Force", "stanley gil force")).isEqualTo(1);
    }

    @Test
    @DisplayName("prefixDistance() joins words, so a split or a joined name is the same name")
    void prefixDistance_splitAndJoined() {
        assertThat(distance("Dunstanburgh", "dunstan burgh")).isZero();
        assertThat(distance("Dunstan Burgh Castle", "dunstanburgh")).isZero();
    }

    @Test
    @DisplayName("prefixDistance() counts an adjacent swap as one edit")
    void prefixDistance_transposition() {
        assertThat(distance("Bamburgh", "bmaburgh")).isEqualTo(1);
    }

    @Test
    @DisplayName("prefixDistance() needs the first letters to match, so 'bamborough' is not near Flamborough Head")
    void prefixDistance_firstLetter() {
        assertThat(distance("Flamborough Head", "bamborough")).isEqualTo(Integer.MAX_VALUE);
        assertThat(distance("Gill Force", "stanley gill force")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("prefixDistance() of an empty query or name is never a match")
    void prefixDistance_empty() {
        assertThat(PlaceNames.prefixDistance(List.of("bamburgh"), List.of())).isEqualTo(Integer.MAX_VALUE);
        assertThat(PlaceNames.prefixDistance(List.of(), List.of("bamburgh"))).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("prefixMatch() reports how many leading words gave the distance, the shortest on a tie")
    void prefixMatch_words() {
        assertThat(PlaceNames.prefixMatch(PlaceNames.words("Bamburgh Castle"), PlaceNames.words("bamborough")))
                .isEqualTo(new PlaceNames.PrefixMatch(3, 1));
        assertThat(PlaceNames.prefixMatch(PlaceNames.words("Dunstan Burgh Castle"),
                PlaceNames.words("dunstanburgh"))).isEqualTo(new PlaceNames.PrefixMatch(0, 2));
    }

    @Test
    @DisplayName("allowedEdits() steps up at 4, 6 and 9 letters")
    void allowedEdits_boundaries() {
        assertThat(PlaceNames.allowedEdits(3)).isZero();
        assertThat(PlaceNames.allowedEdits(4)).isEqualTo(1);
        assertThat(PlaceNames.allowedEdits(5)).isEqualTo(1);
        assertThat(PlaceNames.allowedEdits(6)).isEqualTo(2);
        assertThat(PlaceNames.allowedEdits(8)).isEqualTo(2);
        assertThat(PlaceNames.allowedEdits(9)).isEqualTo(3);
        assertThat(PlaceNames.allowedEdits(0)).isZero();
    }
}
