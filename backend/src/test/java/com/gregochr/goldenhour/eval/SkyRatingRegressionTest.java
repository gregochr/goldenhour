package com.gregochr.goldenhour.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import com.gregochr.goldenhour.service.evaluation.SunsetEvaluationParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one sky-rating regression class: runs the real scorer against every frozen fixture in
 * {@link SkyRatingEvalFixtures#ALL} (the owner-observed days) and judges each run against the same
 * checks in both modes. It replaces the old single-shot {@code PromptRegressionTest} and the pass^k
 * {@code SkyRatingEvalTest}, per the owner's decision of 2026-11-08.
 *
 * <p><b>What one run checks.</b> The returned scores are not null (a null is a MALFORMED run, reported
 * rather than thrown); the star rating lies in the fixture's {@link SkyRatingEvalFixture#band()}; and,
 * where the fixture has {@link RegressionBounds}, the rating lies in the bounds' rating band (always
 * equal to the band, as {@code RegressionBoundsRegistryTest} guards) and Fiery Sky and Golden Hour lie
 * within their min/max. A run is a pass only when every check holds.
 *
 * <p><b>Quick mode</b> ({@code k=1}, the default): all fixtures, every assertion hard, monitored
 * fixtures included. <b>Eval mode</b> ({@code -Dsky.runs=8}): strict pass^k on gated fixtures, so every
 * one of the {@code k} runs must pass; monitored fixtures ({@code gated = false}) are run, reported
 * with {@code [MONITORED]} and never fail the build. The report always prints before the assertion.
 *
 * <p><b>Settings</b> (system property first, then environment variable):
 * <ul>
 *   <li>{@code sky.runs} / {@code SKY_RUNS}: runs per fixture, default 1.</li>
 *   <li>{@code sky.fixture} / {@code eval.fixture}: run only fixtures whose name contains the text.</li>
 *   <li>{@code sky.model} / {@code SKY_MODEL}: the model, default {@code SONNET_55}, the production
 *       near-term model. Precedence, highest first: {@code -Dsky.model}, {@code SKY_MODEL},
 *       {@code -Dregression.model}, {@code -Deval.model}, {@code REGRESSION_MODEL}, {@code EVAL_MODEL},
 *       then the default. The four legacy names still work so nothing run today breaks, but if two of
 *       them disagree (and no {@code sky.*} setting overrides them) the run fails fast. A legacy
 *       {@code EVAL_MODEL=SONNET} still means Sonnet 4.6. {@code setUp} prints the resolved model and
 *       where it came from.</li>
 * </ul>
 *
 * <p>Tagged {@code prompt-regression}, so excluded from {@code mvn verify}. Run on demand with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression -Dtest=SkyRatingRegressionTest
 *   # full eval: add -Dsky.runs=8 ; one fixture: add -Dsky.fixture=st-marys-10mar
 * </pre>
 *
 * <p>The judgement ({@link #judge}, {@link #verdict}, {@link #fixtures}, {@link #resolveModel}) is pure
 * and unit tested offline by {@code SkyRatingRegressionJudgementTest}. To add a new observed day, add a
 * registry line in {@link SkyRatingEvalFixtures} with its band and {@link RegressionBounds}, and add the
 * bounds literally to {@code RegressionBoundsRegistryTest}. The bounds are the owner's ground truth:
 * changing one is the owner's call only.
 */
@Tag("prompt-regression")
class SkyRatingRegressionTest {

    /** The model used when nothing is configured: the production near-term model. */
    static final EvaluationModel DEFAULT_MODEL = EvaluationModel.SONNET_55;

    /** Runs per fixture when {@code sky.runs} / {@code SKY_RUNS} is unset: quick mode. */
    static final int DEFAULT_RUNS = 1;

    private static EvaluationStrategy strategy;
    private static int runs;

    /**
     * The outcome of judging one fixture's report: whether it passes, whether it was only monitored,
     * and the message to print or fail with.
     *
     * @param pass      true if the fixture does not fail the build
     * @param monitored true if it was a monitored fixture in eval mode (reported, never failed)
     * @param message   the human-readable verdict
     */
    record Verdict(boolean pass, boolean monitored, String message) {
    }

    /**
     * A resolved model and the setting it came from.
     *
     * @param model  the model to run
     * @param source where it came from, e.g. {@code -Dsky.model} or {@code default}
     */
    record ModelChoice(EvaluationModel model, String source) {
    }

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY must be set as a system property or environment variable");
        }

        UnaryOperator<String> prop = System::getProperty;
        UnaryOperator<String> env = System::getenv;
        ModelChoice choice = resolveModel(prop, env);
        runs = resolveRuns(prop, env);

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        strategy = new ClaudeEvaluationStrategy(
                new AnthropicApiClient(client), new PromptBuilder(), new CoastalPromptBuilder(),
                new ObjectMapper(), choice.model(), new SunsetEvaluationParser());
        System.out.printf("[sky-regression] model=%s (from %s)  runs per fixture=%d (%s mode)%n",
                choice.model(), choice.source(), runs, runs == 1 ? "quick" : "eval");
    }

    /**
     * The named fixtures for the parameterised test, honouring the fixture filter settings.
     *
     * @return the named fixtures to run
     */
    static Stream<Named<SkyRatingEvalFixture>> fixtureCases() {
        return fixtures(firstNonBlank(System.getProperty("sky.fixture"), System.getProperty("eval.fixture")))
                .stream().map(f -> Named.of(f.name(), f));
    }

    /**
     * Runs one registered day {@code k} times through the real model, prints the report, then asserts
     * the verdict.
     *
     * @param fixture the registered day
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    void registeredDay_staysWithinObservedBoundsAcrossRuns(SkyRatingEvalFixture fixture) {
        AtmosphericData data = SkyRatingEvalFixtures.load(fixture);
        PassRateReport report = new PassRateReport(fixture.name(), fixture.band());

        for (int run = 1; run <= runs; run++) {
            SunsetEvaluation result = strategy.evaluate(data);
            RunOutcome outcome = judge(fixture, result);
            report.record(outcome);
            System.out.printf("[%s] run %d/%d  rating=%s fiery=%s golden=%s  %s  summary=%s%n",
                    fixture.name(), run, runs, result.rating(), result.fierySkyPotential(),
                    result.goldenHourPotential(), outcome.describe(), result.summary());
        }

        // The report is the deliverable: print it before asserting so a failure shows direction.
        System.out.println(report.render());
        Verdict verdict = verdict(fixture, report, runs);
        if (verdict.monitored()) {
            System.out.println(verdict.message());
        }
        assertTrue(verdict.pass(), verdict.message());
    }

    /**
     * Judges one scorer response against one fixture: a null score is MALFORMED; otherwise the rating
     * is classified against the band (and the bounds' rating band), and Fiery Sky and Golden Hour
     * against the bounds' min/max where the fixture has any.
     *
     * @param fixture the fixture the response is for
     * @param result  the scorer's response
     * @return the judged run
     */
    static RunOutcome judge(SkyRatingEvalFixture fixture, SunsetEvaluation result) {
        if (result.rating() == null || result.fierySkyPotential() == null
                || result.goldenHourPotential() == null || result.summary() == null) {
            return RunOutcome.malformedRun(result.rating());
        }
        MissDirection rating = fixture.band().classify(result.rating());
        RegressionBounds bounds = fixture.regression();
        if (bounds == null) {
            return new RunOutcome(result.rating(), rating, MissDirection.IN_BAND, MissDirection.IN_BAND, false);
        }
        if (rating == MissDirection.IN_BAND) {
            rating = bounds.rating().classify(result.rating());
        }
        return new RunOutcome(result.rating(), rating,
                classify(result.fierySkyPotential(), bounds.fieryMin(), bounds.fieryMax()),
                classify(result.goldenHourPotential(), bounds.goldenMin(), bounds.goldenMax()), false);
    }

    private static MissDirection classify(int actual, Integer min, Integer max) {
        if (min != null && actual < min) {
            return MissDirection.BELOW;
        }
        if (max != null && actual > max) {
            return MissDirection.ABOVE;
        }
        return MissDirection.IN_BAND;
    }

    /**
     * The passes a fixture needs out of {@code k} runs. Strict pass^k: all of them.
     *
     * @param k the runs per fixture
     * @return the minimum number of passing runs
     */
    static int minPasses(int k) {
        return k;
    }

    /**
     * Decides one fixture's verdict. At {@code k=1} every fixture is hard, monitored included. At
     * {@code k>1} a gated fixture needs {@link #minPasses} passing runs, and a monitored fixture never
     * fails: it is flagged {@code [MONITORED]} and reported only.
     *
     * @param fixture the fixture
     * @param report  its report, already filled with {@code k} runs
     * @param k       the runs per fixture
     * @return the verdict
     */
    static Verdict verdict(SkyRatingEvalFixture fixture, PassRateReport report, int k) {
        String summary = String.format(Locale.ROOT,
                "%d/%d runs passed every check (%d DOWN/too-cautious, %d UP/too-generous, "
                        + "%d bound misses, %d malformed)",
                report.passes(), k, report.belowMisses(), report.aboveMisses(), report.boundMisses(),
                report.malformed());
        if (k > 1 && !fixture.gated()) {
            return new Verdict(true, true, "[MONITORED] " + fixture.name() + ": recorded, not gated (band "
                    + fixture.band().label() + ", " + summary + "). A single session is not a verdict.");
        }
        int needed = minPasses(k);
        boolean pass = report.passes() >= needed;
        return new Verdict(pass, false, "Fixture '" + fixture.name() + "' expected band "
                + fixture.band().label() + " and its bounds in >= " + needed + "/" + k + " runs but "
                + summary + ". " + report.render());
    }

    /**
     * The fixtures to run: all of {@link SkyRatingEvalFixtures#ALL}, or those whose name contains the
     * filter.
     *
     * @param filter a substring of the fixture name, or {@code null}/blank for all
     * @return the matching fixtures
     * @throws IllegalArgumentException if a filter is given and matches nothing
     */
    static List<SkyRatingEvalFixture> fixtures(String filter) {
        if (filter == null || filter.isBlank()) {
            return SkyRatingEvalFixtures.ALL;
        }
        List<SkyRatingEvalFixture> filtered = SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.name().contains(filter))
                .toList();
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("sky.fixture='" + filter + "' matched no fixture. Available: "
                    + SkyRatingEvalFixtures.ALL.stream().map(SkyRatingEvalFixture::name).toList());
        }
        return filtered;
    }

    /**
     * Resolves the runs per fixture: {@code -Dsky.runs}, then {@code SKY_RUNS}, then 1.
     *
     * @param prop system property lookup
     * @param env  environment variable lookup
     * @return the runs per fixture, at least 1
     * @throws IllegalArgumentException if the value is not a positive integer
     */
    static int resolveRuns(UnaryOperator<String> prop, UnaryOperator<String> env) {
        String raw = firstNonBlank(prop.apply("sky.runs"), env.apply("SKY_RUNS"));
        if (raw == null) {
            return DEFAULT_RUNS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed >= 1) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the single error below
        }
        throw new IllegalArgumentException("sky.runs / SKY_RUNS must be a positive integer but was '" + raw + "'");
    }

    /**
     * Resolves the model. Precedence, highest first: {@code -Dsky.model}, {@code SKY_MODEL},
     * {@code -Dregression.model}, {@code -Deval.model}, {@code REGRESSION_MODEL}, {@code EVAL_MODEL},
     * then {@link #DEFAULT_MODEL}. If the legacy settings in play name different models and no
     * {@code sky.*} setting overrides them, it fails fast rather than guess.
     *
     * @param prop system property lookup
     * @param env  environment variable lookup
     * @return the model and where it came from
     * @throws IllegalStateException    if two legacy settings disagree
     * @throws IllegalArgumentException if the winning value is not an {@link EvaluationModel}
     */
    static ModelChoice resolveModel(UnaryOperator<String> prop, UnaryOperator<String> env) {
        String skyProp = blankToNull(prop.apply("sky.model"));
        if (skyProp != null) {
            return choice(skyProp, "-Dsky.model");
        }
        String skyEnv = blankToNull(env.apply("SKY_MODEL"));
        if (skyEnv != null) {
            return choice(skyEnv, "SKY_MODEL");
        }
        Map<String, String> legacy = new LinkedHashMap<>();
        legacy.put("-Dregression.model", blankToNull(prop.apply("regression.model")));
        legacy.put("-Deval.model", blankToNull(prop.apply("eval.model")));
        legacy.put("REGRESSION_MODEL", blankToNull(env.apply("REGRESSION_MODEL")));
        legacy.put("EVAL_MODEL", blankToNull(env.apply("EVAL_MODEL")));
        legacy.values().removeIf(v -> v == null);
        if (legacy.isEmpty()) {
            return new ModelChoice(DEFAULT_MODEL, "default");
        }
        List<String> distinct = new ArrayList<>(legacy.values().stream()
                .map(v -> v.trim().toUpperCase(Locale.ROOT)).distinct().toList());
        if (distinct.size() > 1) {
            throw new IllegalStateException("Conflicting legacy model settings " + legacy
                    + ". Set -Dsky.model (or SKY_MODEL), which overrides them, or remove all but one.");
        }
        Map.Entry<String, String> first = legacy.entrySet().iterator().next();
        return choice(first.getValue(), first.getKey());
    }

    private static ModelChoice choice(String value, String source) {
        try {
            return new ModelChoice(EvaluationModel.valueOf(value.trim().toUpperCase(Locale.ROOT)), source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + "='" + value + "' is not an EvaluationModel", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        String a = blankToNull(first);
        return a != null ? a : blankToNull(second);
    }
}
