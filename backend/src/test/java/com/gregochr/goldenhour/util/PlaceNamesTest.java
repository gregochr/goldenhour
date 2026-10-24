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
}
