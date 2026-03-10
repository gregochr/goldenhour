package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
 * <p>To add a new regression case:
 * <ol>
 *   <li>Go to a location for sunrise/sunset and observe the actual conditions</li>
 *   <li>Fetch the Open-Meteo data for that date/time (use {@code past_days} parameter),
 *       or use {@code scripts/generate-regression-fixture.sh}</li>
 *   <li>Add a new test method with the atmospheric data and your observed score bounds</li>
 * </ol>
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

        strategy = new ClaudeEvaluationStrategy(
                apiClient, new PromptBuilder(), mapper, EvaluationModel.HAIKU);
    }

    /**
     * Copt Hill, 5 March 2026, SUNSET ~17:45 UTC — NEGATIVE case (blocked solar horizon).
     *
     * <p>Observer point showed near-clear sky (1% low, 0% mid, 0% high) but the solar
     * horizon 50 km WSW had 67% low cloud and 100% high cloud, completely blocking
     * direct light. In reality, the sunset was a total washout — heavy cloud to the west.
     *
     * <p>Score ceilings based on the actual observed conditions:
     * <ul>
     *   <li>Rating: max 2 (poor — blocked solar horizon)</li>
     *   <li>Fiery Sky: max 25 (no light penetration = no colour)</li>
     *   <li>Golden Hour: max 35 (overcast-equivalent at solar horizon)</li>
     * </ul>
     */
    @Test
    void coptHill_5Mar2026_sunset_blockedSolarHorizon() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Copt Hill")
                .solarEventTime(LocalDateTime.of(2026, 3, 5, 17, 45))
                .lowCloud(1)
                .midCloud(0)
                .highCloud(0)
                .visibility(5560)
                .windSpeed(new BigDecimal("3.60"))
                .windDirection(182)
                .humidity(74)
                .weatherCode(0)
                .boundaryLayerHeight(630)
                .shortwaveRadiation(new BigDecimal("231"))
                .pm25(new BigDecimal("21.0"))
                .dust(new BigDecimal("7.0"))
                .aod(new BigDecimal("0.35"))
                .temperature(12.1)
                .apparentTemperature(9.5)
                .precipProbability(0)
                .directionalCloud(new DirectionalCloudData(
                        67, 0, 100,   // solar horizon: Low 67%, Mid 0%, High 100%
                        0, 0, 0))     // antisolar horizon: all clear
                .build();

        SunsetEvaluation result = strategy.evaluate(data);

        assertScoresNotNull(result);
        assertTrue(result.rating() <= 2,
                "Rating should be <= 2 (blocked solar horizon) but was " + result.rating());
        assertTrue(result.fierySkyPotential() <= 25,
                "Fiery Sky should be <= 25 (no light penetration) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() <= 35,
                "Golden Hour should be <= 35 (overcast at solar horizon) but was "
                        + result.goldenHourPotential());
    }

    /**
     * Angel of the North, 2 March 2026, SUNSET ~17:35 UTC — POSITIVE case (spectacular).
     *
     * <p>Haiku rated this 5 stars with Fiery Sky 82 and Golden Hour 84.
     * The actual sunset was spectacular. Using 17:00 UTC slot (nearest to 17:35 sunset):
     * observer point had clear sky (1% low, 0% mid, 0% high), solar horizon had cleared
     * to 7% low with 100% high cloud canvas, antisolar had 64% low, 71% mid, 100% high
     * — ideal conditions for catching reflected colour.
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
                .lowCloud(1)
                .midCloud(0)
                .highCloud(0)
                .visibility(40160)
                .windSpeed(new BigDecimal("3.30"))
                .windDirection(236)
                .humidity(68)
                .weatherCode(0)
                .boundaryLayerHeight(785)
                .shortwaveRadiation(new BigDecimal("80"))
                .pm25(new BigDecimal("3.3"))
                .dust(new BigDecimal("0.0"))
                .aod(new BigDecimal("0.06"))
                .temperature(10.0)
                .apparentTemperature(6.9)
                .precipProbability(0)
                .directionalCloud(new DirectionalCloudData(
                        7, 0, 100,      // solar horizon: Low 7%, Mid 0%, High 100%
                        64, 71, 100))   // antisolar: Low 64%, Mid 71%, High 100%
                .build();

        SunsetEvaluation result = strategy.evaluate(data);

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

    /**
     * St Mary's Lighthouse, 10 March 2026, SUNRISE 06:34 UTC — MODERATE case (decent sunrise).
     *
     * <p>Forecast snapped at 17:05 on 9 March for 10 March sunrise. Data sourced directly
     * from the production {@code forecast_evaluation} table. Observer point had clear low
     * cloud (0%) but 100% mid and high cloud — a thick overcast canvas overhead. Solar
     * horizon (50 km ESE) also 0% low with 100% mid and high cloud. Antisolar had 47% low,
     * 2% mid, 0% high. Light wind (4.5 km/h SSW), good visibility (22 km), no precipitation.
     *
     * <p>In reality, this was a decent 3-star sunrise — some colour breaking through the
     * mid/high cloud layer, but not spectacular. The clear low cloud at solar horizon
     * allowed some light through, but the thick mid-cloud cap limited the display.
     *
     * <p>Score bounds based on the actual observed conditions:
     * <ul>
     *   <li>Rating: 3–4 (decent but not spectacular)</li>
     *   <li>Fiery Sky: 25–70 (some colour, limited by thick mid cloud)</li>
     *   <li>Golden Hour: 25–70 (moderate light quality)</li>
     * </ul>
     */
    @Test
    void stMarysLighthouse_10Mar2026_sunrise_moderate() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("St. Mary's Lighthouse")
                .targetType(com.gregochr.goldenhour.entity.TargetType.SUNRISE)
                .solarEventTime(LocalDateTime.of(2026, 3, 10, 6, 34))
                .lowCloud(0)
                .midCloud(100)
                .highCloud(100)
                .visibility(22020)
                .windSpeed(new BigDecimal("4.50"))
                .windDirection(204)
                .humidity(82)
                .weatherCode(3)
                .boundaryLayerHeight(625)
                .shortwaveRadiation(new BigDecimal("4.00"))
                .pm25(new BigDecimal("13.00"))
                .dust(new BigDecimal("1.00"))
                .aod(new BigDecimal("0.110"))
                .temperature(7.3)
                .apparentTemperature(3.6)
                .precipProbability(0)
                .directionalCloud(new DirectionalCloudData(
                        0, 100, 100,    // solar horizon: Low 0%, Mid 100%, High 100%
                        47, 2, 0))      // antisolar: Low 47%, Mid 2%, High 0%
                .build();

        SunsetEvaluation result = strategy.evaluate(data);

        assertScoresNotNull(result);
        assertTrue(result.rating() >= 3,
                "Rating should be >= 3 (decent sunrise) but was " + result.rating());
        assertTrue(result.rating() <= 4,
                "Rating should be <= 4 (not spectacular) but was " + result.rating());
        assertTrue(result.fierySkyPotential() >= 25,
                "Fiery Sky should be >= 25 (some colour observed) but was "
                        + result.fierySkyPotential());
        assertTrue(result.fierySkyPotential() <= 70,
                "Fiery Sky should be <= 70 (limited by thick mid cloud) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() >= 25,
                "Golden Hour should be >= 25 (moderate light) but was "
                        + result.goldenHourPotential());
        assertTrue(result.goldenHourPotential() <= 70,
                "Golden Hour should be <= 70 (limited by thick mid cloud) but was "
                        + result.goldenHourPotential());
    }

    private static void assertScoresNotNull(SunsetEvaluation result) {
        assertNotNull(result.rating(), "Rating should not be null");
        assertNotNull(result.fierySkyPotential(), "Fiery Sky should not be null");
        assertNotNull(result.goldenHourPotential(), "Golden Hour should not be null");
        assertNotNull(result.summary(), "Summary should not be null");
    }
}
