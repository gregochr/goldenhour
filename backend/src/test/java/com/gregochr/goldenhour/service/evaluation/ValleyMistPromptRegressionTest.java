package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.MistTrend.MistSlot;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions pending the owner's approval (2026-10-21); written by the autonomous build from the
 * valley-mist plan's M2 expectations, never run (no API key where it was written) &mdash; the owner may
 * tighten or loosen them, and until approved a failure here is a signal, not a gate.
 *
 * <p>Valley-mist regression for the sky prompt ({@code docs/engineering/valley-mist-plan.md} M2): three
 * cases through the REAL {@link ClaudeEvaluationStrategy} (real system prompt, real user message,
 * real output schema), all on the River Brathay, 17 October 2026 SUNRISE signature measured in M0
 * (clear night, calm, a 2-3 C temperature-dew gap, 37 km model visibility; the 2 km model did not
 * saturate the corridor, the photograph shows thick radiation mist):
 * <ol>
 *   <li><b>Flagged site, mist LIKELY</b> &mdash; the site sentence says the thin mist under a clear sky
 *       is the shot: expect a rating of at least 4, the mist named in the summary, and the echo
 *       {@code mist_potential} LIKELY.</li>
 *   <li><b>The same site, FOGBOUND</b> (visibility 150 m with no clearing in the trend) &mdash; the white
 *       frame: expect a rating of at most 2 and the echo FOGBOUND.</li>
 *   <li><b>An UNFLAGGED site with the identical weather</b> &mdash; no block, no site sentence: expect the
 *       existing behaviour (a clear sky with no canvas is capped at 3) and NO {@code mist_potential}.</li>
 * </ol>
 *
 * <p>Excluded from {@code mvn verify} (tagged "prompt-regression"); run on demand with
 * {@code ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression}. The assertions are structural bounds, never
 * wording. No assertion in any other regression class was touched.
 */
@Tag("prompt-regression")
class ValleyMistPromptRegressionTest {

    private static ClaudeEvaluationStrategy strategy;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY",
                System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY must be set as a system property or environment variable");
        }
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        String modelName = System.getProperty("regression.model",
                System.getenv().getOrDefault("REGRESSION_MODEL", "HAIKU"));
        strategy = new ClaudeEvaluationStrategy(
                new AnthropicApiClient(client), new PromptBuilder(), new CoastalPromptBuilder(),
                new ObjectMapper(), EvaluationModel.valueOf(modelName.toUpperCase()),
                new SunsetEvaluationParser());
    }

    private static MistSlot slot(int offset, int vis, double temp, double gap, Integer cloud, Double wind) {
        return new MistSlot(offset, vis, temp - gap, temp, cloud, wind);
    }

    /** The Brathay's 17 Oct trend: clear and calm through the night, the gap narrowing toward first light. */
    private static MistTrend brathayTrend() {
        return new MistTrend(List.of(
                slot(-3, 41000, 5.0, 2.9, 0, 2.6),
                slot(-2, 40000, 4.7, 2.9, 0, 1.8),
                slot(-1, 39000, 4.4, 2.8, 0, 2.2),
                slot(0, 37000, 4.1, 2.1, 0, 2.4),
                slot(1, 37000, 4.0, 2.0, 0, 2.3),
                slot(2, 36000, 5.0, 2.4, 0, 2.5)));
    }

    /** A white frame: under 300 m at the event and no later slot clearing back above it. */
    private static MistTrend fogboundTrend() {
        return new MistTrend(List.of(
                slot(-3, 200, 3.0, 0.1, 0, 0.5),
                slot(-2, 180, 2.9, 0.1, 0, 0.4),
                slot(-1, 150, 2.8, 0.0, 0, 0.4),
                slot(0, 150, 2.8, 0.0, 0, 0.5),
                slot(1, 200, 3.2, 0.1, 0, 0.6),
                slot(2, 250, 3.8, 0.2, 0, 0.8)));
    }

    private static TestAtmosphericData brathay(int visibility, MistTrend trend) {
        return TestAtmosphericData.builder()
                .locationName("River Brathay")
                .solarEventTime(LocalDateTime.of(2026, 10, 17, 6, 0))
                .targetType(TargetType.SUNRISE)
                .lowCloud(0).midCloud(0).highCloud(0)
                .visibility(visibility)
                .windSpeed(new BigDecimal("2.40"))
                .windDirection(240)
                .humidity(82)
                .weatherCode(0)
                .boundaryLayerHeight(150)
                .shortwaveRadiation(new BigDecimal("0.00"))
                .pm25(new BigDecimal("4.00"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.080"))
                .temperature(4.1)
                .dewPoint(2.0)
                .precipProbability(0)
                .mistTrend(trend);
    }

    private static void assertCore(SunsetEvaluation result) {
        assertNotNull(result.rating(), "Rating should not be null");
        assertNotNull(result.summary(), "Summary should not be null");
    }

    /**
     * Case 1: a flagged site, mist LIKELY under a clear sky &mdash; the shot the pipeline used to stand down
     * before Claude saw it.
     */
    @Test
    void brathay_17Oct2026_sunrise_flaggedSite_mistLikely_isTheShot() {
        AtmosphericData data = brathay(37000, brathayTrend())
                .mistScore(7.6).mistPotential(MistPotential.LIKELY).build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[brathay_likely] " + result.rating() + " " + result.summary());

        assertCore(result);
        assertTrue(result.rating() >= 4,
                "Rating should be >= 4 (thin mist under a clear sky is the shot here) but was "
                        + result.rating());
        assertTrue(result.summary().toLowerCase().contains("mist"),
                "Summary should name the mist but was: " + result.summary());
        assertEquals("LIKELY", result.mistPotential(),
                "Claude's echoed mist_potential should be LIKELY but was " + result.mistPotential());
    }

    /** Case 2: the same site in a white frame &mdash; fog under 300 m with no clearing is rate 2. */
    @Test
    void brathay_17Oct2026_sunrise_flaggedSite_fogbound_isAWhiteFrame() {
        AtmosphericData data = brathay(150, fogboundTrend())
                .mistScore(9.0).mistPotential(MistPotential.FOGBOUND).build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[brathay_fogbound] " + result.rating() + " " + result.summary());

        assertCore(result);
        assertTrue(result.rating() <= 2,
                "Rating should be <= 2 (a white frame) but was " + result.rating());
        assertEquals("FOGBOUND", result.mistPotential(),
                "Claude's echoed mist_potential should be FOGBOUND but was " + result.mistPotential());
    }

    /**
     * Case 3: an UNFLAGGED site with the identical weather as case 1 &mdash; nothing in its message says
     * mist is the shot, so the existing behaviour stands: a clear sky has no canvas, and no echo is asked
     * for.
     */
    @Test
    void sameWeather_unflaggedSite_keepsTheExistingBehaviour() {
        AtmosphericData data = brathay(37000, brathayTrend())
                .locationName("Roadside Layby").build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[unflagged] " + result.rating() + " " + result.summary());

        assertCore(result);
        assertTrue(result.rating() <= 3,
                "Rating should be <= 3 (clear sky, no canvas, no mist site fact) but was "
                        + result.rating());
        assertNull(result.mistPotential(),
                "An unflagged site's message never asks for mist_potential but got " + result.mistPotential());
    }
}
