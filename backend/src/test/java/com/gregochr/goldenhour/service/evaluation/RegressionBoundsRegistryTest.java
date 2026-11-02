package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.eval.RatingBand;
import com.gregochr.goldenhour.eval.RegressionBounds;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No-API guard for the shared sky-rating fixture registry.
 *
 * <p>Pins every prompt-regression bound to the owner-observed numbers, written out literally, so
 * that the move of those bounds from {@code PromptRegressionTest} into the registry is provable and
 * any later edit to a bound has to change this test visibly. Changing a bound is the owner's call
 * only (CLAUDE.md).
 */
class RegressionBoundsRegistryTest {

    private static final Map<String, RegressionBounds> EXPECTED = Map.of(
            "copt-hill-5mar-washout", new RegressionBounds(new RatingBand(1, 2), null, 25, null, 35),
            "st-marys-10mar-moderate", new RegressionBounds(new RatingBand(4, 4), 30, 85, 30, 85),
            "copt-hill-11mar-false-positive", new RegressionBounds(new RatingBand(1, 3), null, 70, null, 65),
            "copt-hill-15mar-overcast", new RegressionBounds(new RatingBand(1, 3), null, 40, null, 55),
            "copt-hill-16mar-horizon-strip", new RegressionBounds(new RatingBand(3, 4), 25, null, null, null),
            "st-marys-7apr-clear-sky-cap", new RegressionBounds(new RatingBand(1, 3), null, 40, null, null),
            "low-hauxley-4oct-worth-going", new RegressionBounds(new RatingBand(4, 4), 55, 85, 55, null),
            "steel-rigg-24oct-worth-going", new RegressionBounds(new RatingBand(4, 4), 60, 90, null, null));

    private static SkyRatingEvalFixture fixture(String name) {
        return SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing fixture " + name));
    }

    @Test
    void regressionBackedFixtureNames_areExactlyTheEightObservedDays() {
        List<String> names = SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.regression() != null)
                .map(SkyRatingEvalFixture::name)
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "copt-hill-5mar-washout",
                "st-marys-10mar-moderate",
                "copt-hill-11mar-false-positive",
                "copt-hill-15mar-overcast",
                "copt-hill-16mar-horizon-strip",
                "st-marys-7apr-clear-sky-cap",
                "low-hauxley-4oct-worth-going",
                "steel-rigg-24oct-worth-going");
    }

    @Test
    void handAuthoredFixtures_stayEvalOnly() {
        assertThat(fixture("middling-hazy-mixed").regression()).isNull();
        assertThat(fixture("far-corridor-blanket-canvas").regression()).isNull();
    }

    @Test
    void everyRegressionBound_isPinnedToTheOwnerObservedNumbers() {
        assertThat(EXPECTED).hasSize(8);
        EXPECTED.forEach((name, expected) ->
                assertThat(fixture(name).regression()).as(name).isEqualTo(expected));
    }

    @Test
    void copt5Mar_isMonitoredInTheEvalButStillRegressionBacked() {
        SkyRatingEvalFixture washout = fixture("copt-hill-5mar-washout");

        assertThat(washout.gated()).isFalse();
        assertThat(washout.regression()).isNotNull();
    }

    @Test
    void everyEvalBand_isContainedInItsRegressionRatingBand() {
        SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.regression() != null)
                .forEach(f -> {
                    RatingBand eval = f.band();
                    RatingBand regression = f.regression().rating();
                    assertThat(eval.min()).as(f.name() + " min").isGreaterThanOrEqualTo(regression.min());
                    assertThat(eval.max()).as(f.name() + " max").isLessThanOrEqualTo(regression.max());
                });
    }

    @Test
    void promptRegressionMethodSource_discoversTheEightRegistryCasesByName() {
        List<String> displayed = PromptRegressionTest.regressionFixtures()
                .map(Named::getName)
                .toList();

        assertThat(displayed).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        assertThat(displayed).hasSize(8);
    }
}
