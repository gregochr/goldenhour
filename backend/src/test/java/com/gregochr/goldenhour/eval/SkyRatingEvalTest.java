package com.gregochr.goldenhour.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky-rating <b>pass^k eval</b>: runs the base {@link PromptBuilder} scorer N times against
 * each frozen, fully-augmented fixture and asserts the returned star {@code rating} lands in the
 * fixture's expected band <em>every time</em> (a band, never a point — the scorer is
 * non-deterministic).
 *
 * <p>This supersedes the mechanics of {@code PromptRegressionTest} (single-shot, partial input)
 * while inheriting its philosophy (absolute bands from real-world observation). It adds the three
 * things that test lacked: the {@code pass^k} loop, direction-bucketed miss reporting (so a failure
 * tells you <em>which way</em> the prompt is miscalibrated), and full-augmentation fixtures.
 *
 * <p><b>Why both halves.</b> The pass rate is only meaningful with both a strong fixture (proves
 * the scorer does not under-rate good conditions) and flat/middling fixtures (prove it is not
 * always-high). A strong-only or flat-only harness proves nothing.
 *
 * <p><b>Model.</b> The eval runs the model PhotoCast actually scores near-term with — Sonnet — not
 * the {@code PromptRegressionTest} Haiku default, since pass rates differ by model. Override with
 * {@code -Deval.model=HAIKU} (or {@code EVAL_MODEL}) to profile another tier; per-tier matrices can
 * be added later.
 *
 * <p><b>Gating &amp; cost.</b> Tagged {@code prompt-regression}: excluded from {@code mvn verify},
 * run deliberately with real Claude via:
 * <pre>
 *   cd backend &amp;&amp; ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression -Dtest=SkyRatingEvalTest
 * </pre>
 * A full run is {@code fixtures × N} real calls — currently {@value #RUNS_PER_FIXTURE} runs over the
 * registered fixtures (~48 Sonnet calls).
 *
 * <p><b>Pass threshold.</b> {@link #MIN_PASSES} is the gate: the default requires all
 * {@value #RUNS_PER_FIXTURE} runs in band (strict pass^k, matching "assert each run is in band").
 * The bands are 2–3 ratings wide, so normal variance should not breach them; a fixture that cannot
 * hold its band across N runs is genuinely miscalibrated. Lower {@link #MIN_PASSES} by one to
 * tolerate rare single-run variance. The per-fixture report prints regardless, so even a passing
 * run shows the realised pass rate and any near-misses.
 */
@Tag("prompt-regression")
class SkyRatingEvalTest {

    /** Runs per fixture — the {@code k} in pass^k. Modest but enough for a meaningful rate. */
    static final int RUNS_PER_FIXTURE = 8;

    /**
     * Minimum in-band runs (out of {@link #RUNS_PER_FIXTURE}) for a fixture to pass. Default is
     * strict (all runs in band). Relax by one to tolerate rare legitimate variance.
     */
    static final int MIN_PASSES = RUNS_PER_FIXTURE;

    private static ClaudeEvaluationStrategy strategy;
    private static EvaluationModel model;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY must be set as a system property or environment variable");
        }

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        AnthropicApiClient apiClient = new AnthropicApiClient(client);

        String modelName = System.getProperty("eval.model",
                System.getenv().getOrDefault("EVAL_MODEL", "SONNET"));
        model = EvaluationModel.valueOf(modelName.toUpperCase());

        strategy = new ClaudeEvaluationStrategy(
                apiClient, new PromptBuilder(), new CoastalPromptBuilder(), new ObjectMapper(), model);
    }

    private static List<SkyRatingEvalFixture> fixtures() {
        return SkyRatingEvalFixtures.ALL;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void ratingStaysInBandAcrossRuns(SkyRatingEvalFixture fixture) {
        AtmosphericData data = SkyRatingEvalFixtures.load(fixture);
        PassRateReport report = new PassRateReport(fixture.name(), fixture.band());

        for (int run = 1; run <= RUNS_PER_FIXTURE; run++) {
            SunsetEvaluation result = strategy.evaluate(data);
            assertNotNull(result.rating(),
                    "Claude returned a null rating for fixture " + fixture.name());
            report.record(result.rating());
            System.out.printf("[%s] run %d/%d  model=%s  rating=%d  summary=%s%n",
                    fixture.name(), run, RUNS_PER_FIXTURE, model, result.rating(), result.summary());
        }

        // The report is the deliverable — print it before asserting so a failure shows direction.
        System.out.println(report.render());

        assertTrue(report.passes() >= MIN_PASSES,
                "Fixture '" + fixture.name() + "' expected " + fixture.band().label()
                        + " in >= " + MIN_PASSES + "/" + RUNS_PER_FIXTURE + " runs but passed "
                        + report.passes() + " (" + report.belowMisses() + " DOWN/too-cautious, "
                        + report.aboveMisses() + " UP/too-generous). " + report.render());
    }
}
