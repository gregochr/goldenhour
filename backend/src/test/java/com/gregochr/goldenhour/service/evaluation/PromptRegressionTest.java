package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.TargetType;
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

    private static HaikuEvaluationStrategy strategy;

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
        AnthropicProperties properties = new AnthropicProperties();
        ObjectMapper mapper = new ObjectMapper();

        strategy = new HaikuEvaluationStrategy(apiClient, properties, mapper, null);
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
        AtmosphericData data = new AtmosphericData(
                "Copt Hill",
                LocalDateTime.of(2026, 3, 5, 17, 45),
                TargetType.SUNSET,
                1,      // lowCloudPercent (observer)
                0,      // midCloudPercent (observer)
                0,      // highCloudPercent (observer)
                5560,   // visibilityMetres
                new BigDecimal("3.60"),  // windSpeedMs
                182,    // windDirectionDegrees
                BigDecimal.ZERO,         // precipitationMm
                74,     // humidityPercent
                0,      // weatherCode (clear)
                630,    // boundaryLayerHeightMetres
                new BigDecimal("231"),   // shortwaveRadiationWm2
                new BigDecimal("21.0"),  // pm25
                new BigDecimal("7.0"),   // dustUgm3
                new BigDecimal("0.35"),  // aerosolOpticalDepth
                12.1,   // temperatureCelsius
                9.5,    // apparentTemperatureCelsius
                0,      // precipitationProbability
                new DirectionalCloudData(
                        67, 0, 100,   // solar horizon: Low 67%, Mid 0%, High 100%
                        0, 0, 0),     // antisolar horizon: all clear
                null, null, null, null, null, null);  // no tide data

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
        AtmosphericData data = new AtmosphericData(
                "Angel of the North",
                LocalDateTime.of(2026, 3, 2, 17, 35),
                TargetType.SUNSET,
                1,      // lowCloudPercent (observer, 17:00 slot)
                0,      // midCloudPercent (observer)
                0,      // highCloudPercent (observer)
                40160,  // visibilityMetres
                new BigDecimal("3.30"),  // windSpeedMs
                236,    // windDirectionDegrees
                BigDecimal.ZERO,         // precipitationMm
                68,     // humidityPercent
                0,      // weatherCode (clear)
                785,    // boundaryLayerHeightMetres
                new BigDecimal("80"),    // shortwaveRadiationWm2
                new BigDecimal("3.3"),   // pm25
                new BigDecimal("0.0"),   // dustUgm3
                new BigDecimal("0.06"),  // aerosolOpticalDepth
                10.0,   // temperatureCelsius
                6.9,    // apparentTemperatureCelsius
                0,      // precipitationProbability
                new DirectionalCloudData(
                        7, 0, 100,      // solar horizon: Low 7%, Mid 0%, High 100%
                        64, 71, 100),   // antisolar: Low 64%, Mid 71%, High 100%
                null, null, null, null, null, null);  // no tide data

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

    private static void assertScoresNotNull(SunsetEvaluation result) {
        assertNotNull(result.rating(), "Rating should not be null");
        assertNotNull(result.fierySkyPotential(), "Fiery Sky should not be null");
        assertNotNull(result.goldenHourPotential(), "Golden Hour should not be null");
        assertNotNull(result.summary(), "Summary should not be null");
    }
}
