package com.gregochr.goldenhour.eval;

import java.io.UncheckedIOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How {@link SkyRatingEvalFixtures#load} reports a fixture it cannot read, so a broken fixture is never silent. */
class SkyRatingEvalFixturesLoadFailureTest {

    private static SkyRatingEvalFixture fixtureAt(String resource) {
        return new SkyRatingEvalFixture("broken", "A fixture that cannot be loaded", new RatingBand(1, 2), resource);
    }

    @Test
    @DisplayName("a fixture whose resource is not on the classpath fails loudly, naming the path")
    void load_missingResource_throwsIllegalState() {
        assertThatThrownBy(() -> SkyRatingEvalFixtures.load(fixtureAt("does-not-exist.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Eval fixture resource not found on classpath: /eval/fixtures/does-not-exist.json");
    }

    @Test
    @DisplayName("a fixture whose JSON is malformed fails with an unchecked IO error naming the path")
    void load_malformedJson_throwsUncheckedIo() {
        assertThatThrownBy(() -> SkyRatingEvalFixtures.load(fixtureAt("b4-malformed-fixture.json")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to parse eval fixture: /eval/fixtures/b4-malformed-fixture.json");
    }
}
