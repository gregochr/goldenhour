package com.gregochr.goldenhour.eval;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline proof of {@link SkyRatingRegressionTest}'s judgement: the pure helpers {@code judge},
 * {@code verdict}, {@code fixtures}, and the model and runs resolution, driven by scripted
 * {@link EvaluationStrategy} lambdas. Nothing here calls Anthropic, and the tagged class itself is never
 * run in the normal build.
 */
class SkyRatingRegressionJudgementTest {

    private static final RegressionBounds BOUNDS =
            new RegressionBounds(new RatingBand(4, 4), 30, 85, null, 70);

    private static final SkyRatingEvalFixture GATED = new SkyRatingEvalFixture(
            "gated", "d", new RatingBand(4, 4), "x.json", true, BOUNDS);
    private static final SkyRatingEvalFixture MONITORED = new SkyRatingEvalFixture(
            "monitored", "d", new RatingBand(4, 4), "x.json", false, BOUNDS);
    private static final SkyRatingEvalFixture BAND_ONLY = new SkyRatingEvalFixture(
            "band-only", "d", new RatingBand(2, 3), "x.json", true);

    private static SunsetEvaluation eval(Integer rating, Integer fiery, Integer golden) {
        return new SunsetEvaluation(rating, fiery, golden, "summary");
    }

    /** A scripted strategy: returns the given evaluations in order, one per call. */
    private static EvaluationStrategy scripted(SunsetEvaluation... results) {
        int[] next = {0};
        return new EvaluationStrategy() {
            @Override
            public SunsetEvaluation evaluate(AtmosphericData data) {
                return results[next[0]++];
            }

            @Override
            public com.gregochr.goldenhour.model.EvaluationDetail evaluateWithDetails(AtmosphericData data) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EvaluationModel getEvaluationModel() {
                return EvaluationModel.SONNET_55;
            }
        };
    }

    /** Runs {@code k} scripted runs of a fixture through the real judge and report, then the verdict. */
    private static SkyRatingRegressionTest.Verdict run(SkyRatingEvalFixture fixture, SunsetEvaluation... results) {
        EvaluationStrategy strategy = scripted(results);
        PassRateReport report = new PassRateReport(fixture.name(), fixture.band());
        for (int i = 0; i < results.length; i++) {
            report.record(SkyRatingRegressionTest.judge(fixture, strategy.evaluate(null)));
        }
        return SkyRatingRegressionTest.verdict(fixture, report, results.length);
    }

    private static SunsetEvaluation[] repeat(int passing, int total, SunsetEvaluation good, SunsetEvaluation bad) {
        SunsetEvaluation[] all = new SunsetEvaluation[total];
        for (int i = 0; i < total; i++) {
            all[i] = i < passing ? good : bad;
        }
        return all;
    }

    // ---- judge ----

    @Test
    void inBandWithBoundsHeld_passes() {
        RunOutcome outcome = SkyRatingRegressionTest.judge(GATED, eval(4, 50, 50));

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.describe()).isEqualTo("ok");
    }

    @Test
    void ratingAboveTheBand_failsWithDirectionUp() {
        RunOutcome outcome = SkyRatingRegressionTest.judge(GATED, eval(5, 50, 50));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.ratingDirection()).isEqualTo(MissDirection.ABOVE);
        assertThat(outcome.describe()).contains("rating ABOVE");
    }

    @Test
    void ratingBelowTheBand_failsWithDirectionDown() {
        RunOutcome outcome = SkyRatingRegressionTest.judge(GATED, eval(3, 50, 50));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.ratingDirection()).isEqualTo(MissDirection.BELOW);
        assertThat(outcome.describe()).contains("rating BELOW");
    }

    @Test
    void fieryBelowItsMinimum_fails() {
        RunOutcome outcome = SkyRatingRegressionTest.judge(GATED, eval(4, 29, 50));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.fieryDirection()).isEqualTo(MissDirection.BELOW);
        assertThat(outcome.boundMissed()).isTrue();
        assertThat(outcome.ratingDirection()).isEqualTo(MissDirection.IN_BAND);
    }

    @Test
    void fieryAboveItsMaximum_fails() {
        assertThat(SkyRatingRegressionTest.judge(GATED, eval(4, 86, 50)).fieryDirection())
                .isEqualTo(MissDirection.ABOVE);
    }

    @Test
    void goldenAboveItsMaximum_fails() {
        RunOutcome outcome = SkyRatingRegressionTest.judge(GATED, eval(4, 50, 71));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.goldenDirection()).isEqualTo(MissDirection.ABOVE);
    }

    @Test
    void boundsAreInclusiveAndUnboundedSidesAreIgnored() {
        // fiery exactly at min and max, golden has no min so 0 is fine, golden exactly at max.
        assertThat(SkyRatingRegressionTest.judge(GATED, eval(4, 30, 70)).passed()).isTrue();
        assertThat(SkyRatingRegressionTest.judge(GATED, eval(4, 85, 0)).passed()).isTrue();
    }

    @Test
    void aFixtureWithNoBounds_checksTheBandOnly() {
        // Fiery 0 and golden 100 would break most bounds; with no bounds only the band matters.
        assertThat(SkyRatingRegressionTest.judge(BAND_ONLY, eval(3, 0, 100)).passed()).isTrue();
        assertThat(SkyRatingRegressionTest.judge(BAND_ONLY, eval(4, 0, 100)).ratingDirection())
                .isEqualTo(MissDirection.ABOVE);
    }

    @Test
    void aBoundsRatingBandWiderThanNothing_stillAppliesWhenTheBandIsNarrower() {
        // Defensive: if a fixture's bounds rating were ever narrower than its band, the bounds still bite.
        SkyRatingEvalFixture narrowBounds = new SkyRatingEvalFixture("n", "d", new RatingBand(3, 5), "x.json", true,
                new RegressionBounds(new RatingBand(4, 4), null, null, null, null));

        assertThat(SkyRatingRegressionTest.judge(narrowBounds, eval(3, 50, 50)).ratingDirection())
                .isEqualTo(MissDirection.BELOW);
    }

    @Test
    void aNullScore_isMalformedAndReportedNotThrown() {
        for (SunsetEvaluation bad : List.of(
                eval(null, 50, 50), eval(4, null, 50), eval(4, 50, null),
                new SunsetEvaluation(4, 50, 50, null))) {
            RunOutcome outcome = SkyRatingRegressionTest.judge(GATED, bad);

            assertThat(outcome.malformed()).isTrue();
            assertThat(outcome.passed()).isFalse();
            assertThat(outcome.describe()).contains("MALFORMED");
        }

        PassRateReport report = new PassRateReport("g", GATED.band());
        report.record(SkyRatingRegressionTest.judge(GATED, eval(null, 50, 50)));
        assertThat(report.malformed()).isEqualTo(1);
        assertThat(report.belowMisses() + report.aboveMisses() + report.boundMisses()).isZero();
        assertThat(report.render()).contains("malformed: 1");
        assertThat(SkyRatingRegressionTest.verdict(GATED, report, 1).pass()).isFalse();
    }

    // ---- verdict ----

    @Test
    void k1_gatedFixtureInBand_passes() {
        SkyRatingRegressionTest.Verdict v = run(GATED, eval(4, 50, 50));

        assertThat(v.pass()).isTrue();
        assertThat(v.monitored()).isFalse();
    }

    @Test
    void k1_monitoredFixtureOutOfBand_fails() {
        SkyRatingRegressionTest.Verdict v = run(MONITORED, eval(2, 50, 50));

        assertThat(v.pass()).isFalse();
        assertThat(v.monitored()).isFalse();
        assertThat(v.message()).contains("monitored").contains("0/1");
    }

    @Test
    void k8_monitoredFixtureAtSevenOfEight_passesAndIsFlaggedMonitored() {
        SkyRatingRegressionTest.Verdict v = run(MONITORED,
                repeat(7, 8, eval(4, 50, 50), eval(2, 50, 50)));

        assertThat(v.pass()).isTrue();
        assertThat(v.monitored()).isTrue();
        assertThat(v.message()).startsWith("[MONITORED]").contains("7/8");
    }

    @Test
    void k8_gatedFixtureAtSevenOfEight_fails() {
        SkyRatingRegressionTest.Verdict v = run(GATED, repeat(7, 8, eval(4, 50, 50), eval(2, 50, 50)));

        assertThat(v.pass()).isFalse();
        assertThat(v.monitored()).isFalse();
        assertThat(v.message()).contains("7/8").contains("1 DOWN");
    }

    @Test
    void k8_gatedFixtureAtEightOfEight_passes() {
        assertThat(run(GATED, repeat(8, 8, eval(4, 50, 50), eval(2, 50, 50))).pass()).isTrue();
    }

    @Test
    void k8_gatedFixtureInBandEveryTimeButOneBoundMiss_fails() {
        // Every rating is in band; one run has fiery below its minimum. 7 of 8 pass every check.
        SunsetEvaluation[] results = repeat(7, 8, eval(4, 50, 50), eval(4, 10, 50));

        SkyRatingRegressionTest.Verdict v = run(GATED, results);

        assertThat(v.pass()).isFalse();
        assertThat(v.message()).contains("7/8").contains("1 bound misses");
    }

    @Test
    void minPasses_isStrict() {
        assertThat(SkyRatingRegressionTest.minPasses(1)).isEqualTo(1);
        assertThat(SkyRatingRegressionTest.minPasses(8)).isEqualTo(8);
    }

    // ---- fixtures ----

    @Test
    void fixtures_withNoFilterReturnsAllTen() {
        assertThat(SkyRatingRegressionTest.fixtures(null)).hasSize(10).isEqualTo(SkyRatingEvalFixtures.ALL);
        assertThat(SkyRatingRegressionTest.fixtures("  ")).hasSize(10);
    }

    @Test
    void fixtures_filtersBySubstring() {
        assertThat(SkyRatingRegressionTest.fixtures("st-marys"))
                .extracting(SkyRatingEvalFixture::name)
                .containsExactlyInAnyOrder("st-marys-10mar-moderate", "st-marys-7apr-clear-sky-cap");
    }

    @Test
    void fixtures_withAFilterThatMatchesNothingFailsListingTheNames() {
        assertThatThrownBy(() -> SkyRatingRegressionTest.fixtures("no-such-day"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-day")
                .hasMessageContaining("copt-hill-5mar-washout");
    }

    // ---- model and runs resolution ----

    private static UnaryOperator<String> of(Map<String, String> map) {
        return map::get;
    }

    private static final Map<String, String> NONE = Map.of();

    @Test
    void model_defaultsToSonnet55() {
        SkyRatingRegressionTest.ModelChoice choice = SkyRatingRegressionTest.resolveModel(of(NONE), of(NONE));

        assertThat(choice.model()).isEqualTo(EvaluationModel.SONNET_55);
        assertThat(choice.source()).isEqualTo("default");
    }

    @Test
    void model_skyPropertyBeatsEverything() {
        SkyRatingRegressionTest.ModelChoice choice = SkyRatingRegressionTest.resolveModel(
                of(Map.of("sky.model", "haiku", "regression.model", "OPUS")),
                of(Map.of("SKY_MODEL", "SONNET", "EVAL_MODEL", "SONNET_55")));

        assertThat(choice.model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(choice.source()).isEqualTo("-Dsky.model");
    }

    @Test
    void model_skyEnvBeatsLegacyEvenWhenLegacyDisagree() {
        SkyRatingRegressionTest.ModelChoice choice = SkyRatingRegressionTest.resolveModel(
                of(Map.of("regression.model", "HAIKU")),
                of(Map.of("SKY_MODEL", "SONNET_55", "EVAL_MODEL", "SONNET")));

        assertThat(choice.model()).isEqualTo(EvaluationModel.SONNET_55);
        assertThat(choice.source()).isEqualTo("SKY_MODEL");
    }

    @Test
    void model_aLoneLegacySettingStillWorks_andLegacySonnetMeansFourSix() {
        SkyRatingRegressionTest.ModelChoice choice =
                SkyRatingRegressionTest.resolveModel(of(NONE), of(Map.of("EVAL_MODEL", "SONNET")));

        assertThat(choice.model()).isEqualTo(EvaluationModel.SONNET);
        assertThat(choice.source()).isEqualTo("EVAL_MODEL");
    }

    @Test
    void model_agreeingLegacySettingsResolveByPrecedence() {
        SkyRatingRegressionTest.ModelChoice choice = SkyRatingRegressionTest.resolveModel(
                of(Map.of("eval.model", "haiku")), of(Map.of("REGRESSION_MODEL", "HAIKU")));

        assertThat(choice.model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(choice.source()).isEqualTo("-Deval.model");
    }

    @Test
    void model_conflictingLegacySettingsFailFast() {
        assertThatThrownBy(() -> SkyRatingRegressionTest.resolveModel(
                of(Map.of("regression.model", "HAIKU")), of(Map.of("EVAL_MODEL", "SONNET"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting legacy model settings")
                .hasMessageContaining("-Dregression.model")
                .hasMessageContaining("EVAL_MODEL");
    }

    @Test
    void model_blankSettingsAreIgnored_andAnUnknownNameIsRejectedWithItsSource() {
        assertThat(SkyRatingRegressionTest.resolveModel(of(Map.of("sky.model", "  ")), of(NONE)).source())
                .isEqualTo("default");
        assertThatThrownBy(() -> SkyRatingRegressionTest.resolveModel(of(NONE), of(Map.of("SKY_MODEL", "GPT"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKY_MODEL");
    }

    @Test
    void runs_defaultToOne_propertyBeatsEnv_andMustBePositive() {
        assertThat(SkyRatingRegressionTest.resolveRuns(of(NONE), of(NONE))).isEqualTo(1);
        assertThat(SkyRatingRegressionTest.resolveRuns(of(Map.of("sky.runs", "8")), of(Map.of("SKY_RUNS", "3"))))
                .isEqualTo(8);
        assertThat(SkyRatingRegressionTest.resolveRuns(of(NONE), of(Map.of("SKY_RUNS", " 3 ")))).isEqualTo(3);
        assertThatThrownBy(() -> SkyRatingRegressionTest.resolveRuns(of(Map.of("sky.runs", "0")), of(NONE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkyRatingRegressionTest.resolveRuns(of(NONE), of(Map.of("SKY_RUNS", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
