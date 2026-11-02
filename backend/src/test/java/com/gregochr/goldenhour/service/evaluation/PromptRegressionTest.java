package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.eval.RegressionBounds;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt regression tests that call the real Claude API with known atmospheric data
 * and assert scores stay within bounds established from real-world observations.
 *
 * <p>These tests are excluded from {@code mvn verify} (tagged "prompt-regression").
 * Run on demand with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression
 * </pre>
 *
 * <p>The cases come from one registry shared with the weekly Sky-Rating Eval,
 * {@link SkyRatingEvalFixtures#ALL}. To add a new observed day:
 * <ol>
 *   <li>Go to a location for sunrise/sunset and observe the actual conditions</li>
 *   <li>Export the stored {@code atmospheric_data_json} for that run (or fetch the Open-Meteo data
 *       and use {@code scripts/generate-regression-fixture.sh}) and drop it under
 *       {@code src/main/resources/eval/fixtures/}</li>
 *   <li>Add one registry line in {@code SkyRatingEvalFixtures} with the eval band and a
 *       {@link RegressionBounds} holding your observed bounds. It then runs in both this suite and
 *       the Sky Eval; the parameterized test below picks it up with no further code</li>
 *   <li>Add the new bounds literally to {@code RegressionBoundsRegistryTest}</li>
 * </ol>
 * The bounds are the owner's ground truth: changing one is the owner's call only.
 */
@Tag("prompt-regression")
class PromptRegressionTest {

    private static ClaudeEvaluationStrategy strategy;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY",
                System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY must be set as a system property or environment variable");
        }

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        AnthropicApiClient apiClient = new AnthropicApiClient(client);
        ObjectMapper mapper = new ObjectMapper();

        String modelName = System.getProperty("regression.model",
                System.getenv().getOrDefault("REGRESSION_MODEL", "HAIKU"));
        EvaluationModel model = EvaluationModel.valueOf(modelName.toUpperCase());

        strategy = new ClaudeEvaluationStrategy(
                apiClient, new PromptBuilder(), new CoastalPromptBuilder(), mapper, model,
                new SunsetEvaluationParser());
    }

    /**
     * The registry-backed regression cases: every {@link SkyRatingEvalFixtures#ALL} entry whose
     * {@link SkyRatingEvalFixture#regression()} is non-null, named by fixture name. Package-private so
     * {@code RegressionBoundsRegistryTest} can prove discovery without calling the API.
     *
     * @return the named regression fixtures
     */
    static Stream<Named<SkyRatingEvalFixture>> regressionFixtures() {
        return SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.regression() != null)
                .map(f -> Named.of(f.name(), f));
    }

    /**
     * Runs one registered day through the real model and asserts the owner-observed bounds held in
     * the fixture's {@link RegressionBounds}. Every case is hard-asserted regardless of the eval's
     * {@code gated} flag (5 Mar is monitored in the eval but hard-asserted here).
     *
     * @param fixture the registered day
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("regressionFixtures")
    void registeredDay_staysWithinObservedBounds(SkyRatingEvalFixture fixture) {
        RegressionBounds bounds = fixture.regression();
        String name = fixture.name();
        AtmosphericData data = SkyRatingEvalFixtures.load(fixture);

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[" + name + "] rating=" + result.rating()
                + " fiery=" + result.fierySkyPotential()
                + " golden=" + result.goldenHourPotential() + " " + result.summary());

        assertScoresNotNull(result);
        assertTrue(bounds.rating().contains(result.rating()),
                name + ": rating should be within " + bounds.rating().label() + " but was " + result.rating());
        assertWithin(name, "Fiery Sky", result.fierySkyPotential(), bounds.fieryMin(), bounds.fieryMax());
        assertWithin(name, "Golden Hour", result.goldenHourPotential(), bounds.goldenMin(),
                bounds.goldenMax());
    }

    private static void assertWithin(String name, String score, int actual, Integer min, Integer max) {
        if (min != null) {
            assertTrue(actual >= min, name + ": " + score + " should be >= " + min + " but was " + actual);
        }
        if (max != null) {
            assertTrue(actual <= max, name + ": " + score + " should be <= " + max + " but was " + actual);
        }
    }

    /**
     * Angel of the North, 2 March 2026, SUNSET ~17:35 UTC — POSITIVE case (spectacular).
     *
     * <p>Regression-only, deliberately not in the shared registry: its observer-only input shape
     * (no directional cloud) is retired from the Sky Eval because production no longer produces it.
     *
     * <p>Data sourced from production record id 3284. Observer point: 2% low, 0% mid,
     * 100% high cloud — high-cloud canvas overhead. No directional cone-sampling data
     * was persisted for this run. The actual sunset was spectacular.
     *
     * <p>Score floors based on the actual observed conditions:
     * <ul>
     *   <li>Rating: min 4 (spectacular sunset)</li>
     *   <li>Fiery Sky: min 60 (dramatic colour was observed)</li>
     *   <li>Golden Hour: min 60 (excellent light quality)</li>
     * </ul>
     */
    @Test
    void angelOfTheNorth_2Mar2026_sunset_spectacular() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Angel of the North")
                .solarEventTime(LocalDateTime.of(2026, 3, 2, 17, 35))
                .lowCloud(2)
                .midCloud(0)
                .highCloud(100)
                .visibility(40160)
                .windSpeed(new BigDecimal("2.30"))
                .windDirection(202)
                .humidity(69)
                .weatherCode(3)
                .boundaryLayerHeight(690)
                .shortwaveRadiation(new BigDecimal("0.00"))
                .pm25(new BigDecimal("2.90"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.050"))
                .temperature(9.1)
                .apparentTemperature(6.4)
                .precipProbability(0)
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[angelOfNorth_2Mar] " + result.summary());

        assertScoresNotNull(result);
        assertTrue(result.rating() >= 4,
                "Rating should be >= 4 (spectacular sunset) but was " + result.rating());
        assertTrue(result.fierySkyPotential() >= 60,
                "Fiery Sky should be >= 60 (dramatic colour observed) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() >= 60,
                "Golden Hour should be >= 60 (excellent light) but was "
                        + result.goldenHourPotential());
    }

    private static void assertScoresNotNull(SunsetEvaluation result) {
        assertNotNull(result.rating(), "Rating should not be null");
        assertNotNull(result.fierySkyPotential(), "Fiery Sky should not be null");
        assertNotNull(result.goldenHourPotential(), "Golden Hour should not be null");
        assertNotNull(result.summary(), "Summary should not be null");
    }
}
