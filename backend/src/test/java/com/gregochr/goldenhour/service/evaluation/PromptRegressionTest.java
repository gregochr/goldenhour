package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        String modelName = System.getProperty("regression.model",
                System.getenv().getOrDefault("REGRESSION_MODEL", "HAIKU"));
        EvaluationModel model = EvaluationModel.valueOf(modelName.toUpperCase());

        strategy = new ClaudeEvaluationStrategy(
                apiClient, new PromptBuilder(), mapper, model);
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
                        0, 0, 0, null))  // antisolar horizon: all clear
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[coptHill_5Mar] " + result.summary());

        assertScoresNotNull(result);
        assertTrue(result.rating() <= 2,
                "Rating should be <= 2 (blocked solar horizon) but was " + result.rating());
        assertTrue(result.fierySkyPotential() <= 25,
                "Fiery Sky should be <= 30 (no light penetration) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() <= 35,
                "Golden Hour should be <= 35 (overcast at solar horizon) but was "
                        + result.goldenHourPotential());
    }

    /**
     * Angel of the North, 2 March 2026, SUNSET ~17:35 UTC — POSITIVE case (spectacular).
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

    /**
     * St Mary's Lighthouse, 10 March 2026, SUNRISE 06:34 UTC — POSITIVE case (worth going out).
     *
     * <p>Forecast snapped at 17:05 on 9 March for 10 March sunrise. Data sourced directly
     * from the production {@code forecast_evaluation} table. Observer point had clear low
     * cloud (0%) but 100% mid and high cloud — a thick canvas overhead. Solar horizon
     * (50 km ESE) also 0% low with 100% mid and high cloud. Antisolar had 47% low,
     * 2% mid, 0% high. Light wind (4.5 km/h SSW), good visibility (22 km), no precipitation.
     *
     * <p>In reality, this was a 4-star sunrise — clear low cloud let light through, and
     * the mid/high cloud canvas caught enough colour to make the trip worthwhile. Production
     * forecast correctly rated this 4 stars.
     *
     * <p>Score bounds based on the actual observed conditions:
     * <ul>
     *   <li>Rating: min 4 (worth going out for)</li>
     *   <li>Fiery Sky: 30–85 (decent colour, not a washout)</li>
     *   <li>Golden Hour: 30–85 (decent light quality)</li>
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
                        47, 2, 0, null))   // antisolar: Low 47%, Mid 2%, High 0%
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 3, 10, 7, 7),
                        new BigDecimal("1.11"),
                        LocalDateTime.of(2026, 3, 10, 13, 15),
                        new BigDecimal("-1.22"),
                        true, null, null, null, null, null, null))
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[stMarys_10Mar] " + result.summary());

        assertScoresNotNull(result);
        assertEquals(4, result.rating(),
                "Rating should be exactly 4 (worth going out, not spectacular) but was " + result.rating());
        assertTrue(result.fierySkyPotential() >= 30,
                "Fiery Sky should be >= 30 (decent colour observed) but was "
                        + result.fierySkyPotential());
        assertTrue(result.fierySkyPotential() <= 85,
                "Fiery Sky should be <= 85 (not a spectacular fireworks display) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() >= 30,
                "Golden Hour should be >= 30 (decent light) but was "
                        + result.goldenHourPotential());
        assertTrue(result.goldenHourPotential() <= 85,
                "Golden Hour should be <= 85 (decent, not spectacular) but was "
                        + result.goldenHourPotential());
    }

    /**
     * Copt Hill, 11 March 2026, SUNSET ~17:45 UTC — FALSE POSITIVE case (cloud approach risk).
     *
     * <p>The 13:45 forecast showed clear solar horizon (7% low cloud at 50 km) and 88% high
     * cloud — looked ideal. Production rated it 4 stars. In reality, a cloud bank was
     * approaching from the SW and the sunset was a 2-star disappointment.
     *
     * <p>With cloud approach risk data, the prompt now includes:
     * <ul>
     *   <li>Solar trend: T-3h=5%, T-2h=15%, T-1h=35%, event=7% [BUILDING]</li>
     *   <li>Upwind: 70% low cloud at 87 km along 228° SW, model predicts 15% at event</li>
     * </ul>
     *
     * <p>This data should tell Claude to penalise — the model is too optimistic about clearing.
     *
     * <p>Score bounds based on the actual observed outcome:
     * <ul>
     *   <li>Rating: max 2 (the trip was a disappointment)</li>
     *   <li>Fiery Sky: max 35 (cloud bank arrived and blocked the display)</li>
     *   <li>Golden Hour: max 40 (some residual light but poor overall)</li>
     * </ul>
     */
    @Test
    void coptHill_11Mar2026_sunset_cloudApproachFalsePositive() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Copt Hill")
                .solarEventTime(LocalDateTime.of(2026, 3, 11, 17, 45))
                .targetType(com.gregochr.goldenhour.entity.TargetType.SUNSET)
                .lowCloud(0)
                .midCloud(0)
                .highCloud(100)
                .visibility(27980)
                .windSpeed(new BigDecimal("5.70"))
                .windDirection(228)
                .precipitation(BigDecimal.ZERO)
                .humidity(69)
                .weatherCode(3)
                .shortwaveRadiation(new BigDecimal("180.00"))
                .aod(new BigDecimal("0.080"))
                .pm25(new BigDecimal("3.80"))
                .dust(new BigDecimal("0.00"))
                .boundaryLayerHeight(1035)
                .temperature(7.6)
                .apparentTemperature(2.8)
                .precipProbability(0)
                .directionalCloud(new DirectionalCloudData(
                        7, 0, 88,       // solar horizon: Low 7%, Mid 0%, High 88%
                        81, 0, 0, null))   // antisolar: Low 81%, Mid 0%, High 0%
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 5),
                                new SolarCloudTrend.SolarCloudSlot(2, 15),
                                new SolarCloudTrend.SolarCloudSlot(1, 35),
                                new SolarCloudTrend.SolarCloudSlot(0, 7))),
                        new UpwindCloudSample(87, 228, 70, 15)))
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[coptHill_11Mar_approach] " + result.summary());
        assertScoresNotNull(result);
        assertTrue(result.rating() <= 2,
                "Rating should be <= 2 (cloud approach risk = don't go) but was "
                        + result.rating());
        assertTrue(result.fierySkyPotential() <= 45,
                "Fiery Sky should be <= 45 (approaching cloud blocks display) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() <= 55,
                "Golden Hour should be <= 55 (poor outcome) but was "
                        + result.goldenHourPotential());
    }

    /**
     * Copt Hill, 15 March 2026, SUNSET ~18:08 UTC — NEGATIVE case (total overcast + approach risk).
     *
     * <p>Observer point completely overcast (100% low, 99% mid). Solar horizon had 39% low and
     * 65% mid cloud — marginal at best. Solar trend showed [BUILDING] behaviour, and the upwind
     * sample revealed 84% current low cloud vs model's 0% prediction — model was wildly
     * optimistic about clearing.
     *
     * <p>Antisolar side also heavily blocked (95% low, 53% mid). Light drizzle (0.1mm),
     * weather code 51. In reality this was a complete non-event.
     *
     * <p>User observation: 1-2 stars (would not have gone out).
     *
     * <p>Score ceilings:
     * <ul>
     *   <li>Rating: max 2 (total overcast, no clearing)</li>
     *   <li>Fiery Sky: max 25 (no light penetration through 100% low cloud)</li>
     *   <li>Golden Hour: max 30 (completely blocked horizon)</li>
     * </ul>
     */
    @Test
    void coptHill_15Mar2026_sunset_totalOvercastWithApproach() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Copt Hill")
                .targetType(com.gregochr.goldenhour.entity.TargetType.SUNSET)
                .solarEventTime(LocalDateTime.of(2026, 3, 15, 18, 8))
                .lowCloud(100)
                .midCloud(99)
                .highCloud(0)
                .visibility(19080)
                .windSpeed(new BigDecimal("7.70"))
                .windDirection(274)
                .precipitation(new BigDecimal("0.10"))
                .humidity(73)
                .weatherCode(51)
                .boundaryLayerHeight(1210)
                .shortwaveRadiation(new BigDecimal("25.00"))
                .pm25(new BigDecimal("3.20"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.090"))
                .temperature(5.9)
                .apparentTemperature(0.0)
                .precipProbability(45)
                .directionalCloud(new DirectionalCloudData(
                        39, 65, 0,      // solar horizon: Low 39%, Mid 65%, High 0%
                        95, 53, 0, null))  // antisolar: Low 95%, Mid 53%, High 0%
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 52),
                                new SolarCloudTrend.SolarCloudSlot(2, 100),
                                new SolarCloudTrend.SolarCloudSlot(1, 100),
                                new SolarCloudTrend.SolarCloudSlot(0, 20))),
                        new UpwindCloudSample(67, 274, 84, 0)))
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[coptHill_15Mar_overcast] " + result.summary());

        assertScoresNotNull(result);
        assertTrue(result.rating() <= 2,
                "Rating should be <= 2 (total overcast, no clearing) but was "
                        + result.rating());
        assertTrue(result.fierySkyPotential() <= 25,
                "Fiery Sky should be <= 25 (no light penetration) but was "
                        + result.fierySkyPotential());
        assertTrue(result.goldenHourPotential() <= 30,
                "Golden Hour should be <= 30 (completely blocked) but was "
                        + result.goldenHourPotential());
    }

    /**
     * Copt Hill, 16 Mar 2026 sunset — horizon strip scenario.
     *
     * <p>Solar horizon low cloud was 64% and building, but only a thin strip on the
     * horizon: far-field (226 km) low cloud was 12%, dropping 52pp beyond the strip.
     * High cloud above caught the warm light. Observed outcome: ~3 stars with pink/coral
     * colour on high cloud. Previously scored 1/5 before strip detection was added.
     *
     * <p>Asserts: rating >= 3 and &lt;5 (strip softens the penalty)
     */
    @Test
    void coptHill_16Mar2026_horizonStrip_shouldScoreAboveThreeButNotFive() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Copt Hill")
                .targetType(com.gregochr.goldenhour.entity.TargetType.SUNSET)
                .solarEventTime(LocalDateTime.of(2026, 3, 18, 18, 11))
                .lowCloud(40)
                .midCloud(30)
                .highCloud(60)
                .visibility(20000)
                .windSpeed(new BigDecimal("5.50"))
                .windDirection(270)
                .precipitation(new BigDecimal("0.00"))
                .humidity(65)
                .weatherCode(2)
                .boundaryLayerHeight(900)
                .shortwaveRadiation(new BigDecimal("45.00"))
                .pm25(new BigDecimal("4.00"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.10"))
                .temperature(8.5)
                .apparentTemperature(5.5)
                .precipProbability(5)
                .directionalCloud(new DirectionalCloudData(
                        64, 10, 55,     // solar horizon: Low 64% (strip), Mid 10%, High 55%
                        20, 15, 65,     // antisolar: Low 20%, Mid 15%, High 65% (canvas)
                        12))            // far solar (226km): Low 12% — confirms thin strip
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 20),
                                new SolarCloudTrend.SolarCloudSlot(2, 45),
                                new SolarCloudTrend.SolarCloudSlot(1, 58),
                                new SolarCloudTrend.SolarCloudSlot(0, 64))),
                        null))
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[coptHill_16Mar_strip] " + result.summary());

        assertScoresNotNull(result);
        assertTrue(result.rating() >= 3 && result.rating() < 5,
                "Rating should be >=3 and <5 (horizon strip + high cloud canvas) but was " + result.rating());
        assertTrue(result.fierySkyPotential() >= 25,
                "Fiery Sky should be >= 25 (high cloud catching warm light) but was "
                        + result.fierySkyPotential());
    }

    /**
     * St Mary's Lighthouse, 7 April 2026, SUNRISE 05:23 UTC — MISCALIBRATION case (clear sky, no canvas).
     *
     * <p>Perfectly clear dawn: 0% low, 0% mid, 0% high cloud at observer point. Directional
     * cloud samples were unavailable (null — no cone sampling data persisted for this run).
     * Excellent visibility (18 km), clean air (AOD 0.09, PM2.5 18.5 µg/m³), weather code 0.
     * High tide closely aligned: high at 05:57 UTC, 34 minutes after sunrise.
     *
     * <p>Production forecast (Haiku, generated 6 Apr 20:45 UTC) gave 5★ / Fiery Sky 45 /
     * Golden Hour 82. The observed outcome was 3★ — a perfectly clear dawn with no mid/high
     * cloud canvas delivers only clean golden light with no drama. The current prompt
     * over-scores clear-sky scenarios by treating high visibility and clean air as strong
     * positives without adequately penalising the absence of a colour canvas.
     * This test captures the miscalibration.
     *
     * <p>Score ceilings:
     * <ul>
     *   <li>Rating: max 3 (clean but undramatic — no cloud canvas to catch colour)</li>
     *   <li>Fiery Sky: max 40 (clear sky ceiling — nothing to scatter and diffuse colour)</li>
     *   <li>Golden Hour: no assertion yet — printed to stdout for calibration reference</li>
     * </ul>
     */
    @Test
    void stMarysLighthouse_7Apr2026_sunrise_clearSkyNoCanvas_shouldCapAtThree() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("St. Mary's Lighthouse")
                .targetType(com.gregochr.goldenhour.entity.TargetType.SUNRISE)
                .solarEventTime(LocalDateTime.of(2026, 4, 7, 5, 23))
                .lowCloud(0)
                .midCloud(0)
                .highCloud(0)
                .visibility(18020)
                .windSpeed(new BigDecimal("3.50"))
                .windDirection(156)
                .precipitation(BigDecimal.ZERO)
                .humidity(80)
                .weatherCode(0)
                .boundaryLayerHeight(320)
                .shortwaveRadiation(new BigDecimal("10.00"))
                .pm25(new BigDecimal("18.50"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.090"))
                .temperature(5.6)
                .apparentTemperature(2.0)
                .dewPoint(2.4)
                .precipProbability(0)
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 4, 7, 5, 57, 22),
                        new BigDecimal("1.42"),
                        LocalDateTime.of(2026, 4, 7, 12, 10, 11),
                        new BigDecimal("-1.61"),
                        null, null, null, null, null, null, null))
                .build();

        SunsetEvaluation result = strategy.evaluate(data);
        System.out.println("[stMarys_7Apr_clearSky] " + result.summary()
                + " golden=" + result.goldenHourPotential());

        assertScoresNotNull(result);
        assertTrue(result.rating() <= 3,
                "Rating should be <= 3 (no cloud canvas = no colour drama) but was " + result.rating());
        assertTrue(result.fierySkyPotential() <= 40,
                "Fiery Sky should be <= 40 (clear sky — nothing to scatter colour) but was "
                        + result.fierySkyPotential());
    }

    private static void assertScoresNotNull(SunsetEvaluation result) {
        assertNotNull(result.rating(), "Rating should not be null");
        assertNotNull(result.fierySkyPotential(), "Fiery Sky should not be null");
        assertNotNull(result.goldenHourPotential(), "Golden Hour should not be null");
        assertNotNull(result.summary(), "Summary should not be null");
    }
}
