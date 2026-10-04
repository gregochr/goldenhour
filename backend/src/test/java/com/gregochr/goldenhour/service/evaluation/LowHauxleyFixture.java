package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Low Hauxley, 4 October 2026, SUNRISE 06:13:58 UTC — the inputs production sent for
 * {@code forecast_evaluation} id 86677, shared by the tagged regression case in
 * {@link PromptRegressionTest} and the untagged {@link LowHauxleyPromptFidelityTest}, so the numbers
 * live in exactly one place.
 */
final class LowHauxleyFixture {

    private static final String STORED_PROMPT_RESOURCE =
            "/prompt-regression/low-hauxley-2026-10-04-sunrise.txt";

    private LowHauxleyFixture() {
    }

    /**
     * Builds the atmospheric data of the production request, tide and surge included, so the
     * coastal prompt builder is selected exactly as the batch pipeline selects it.
     *
     * @return the atmospheric data for Low Hauxley's 4 Oct 2026 sunrise
     */
    static AtmosphericData atmosphericData() {
        return TestAtmosphericData.builder()
                .locationName("Low Hauxley")
                .targetType(TargetType.SUNRISE)
                .solarEventTime(LocalDateTime.of(2026, 10, 4, 6, 13, 58))
                .lowCloud(0)
                .midCloud(100)
                .highCloud(100)
                .visibility(26180)
                .windSpeed(new BigDecimal("3.90"))
                .windDirection(192)
                .precipitation(new BigDecimal("0.00"))
                .humidity(81)
                .weatherCode(3)
                .boundaryLayerHeight(400)
                .shortwaveRadiation(new BigDecimal("13"))
                .pm25(new BigDecimal("4.50"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.150"))
                .temperature(8.7)
                .apparentTemperature(5.5)
                .dewPoint(5.6)
                .precipProbability(0)
                .locationOrientation("sunrise-optimised")
                .directionalCloud(new DirectionalCloudData(
                        0, 10, 100,      // solar horizon: Low 0%, Mid 10%, High 100%
                        100, 100, 100,   // antisolar horizon: all 100%
                        1))              // far solar (226km): Low 1%
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 0, 0, 0),
                                new SolarCloudTrend.SolarCloudSlot(2, 0, 0, 0),
                                new SolarCloudTrend.SolarCloudSlot(1, 0, 0, 61),
                                new SolarCloudTrend.SolarCloudSlot(0, 0, 10, 100))),
                        new UpwindCloudSample(72, 192, 0, 0)))
                .mistTrend(new MistTrend(List.of(
                        new MistTrend.MistSlot(-3, 30180, 5.0, 7.5),
                        new MistTrend.MistSlot(-2, 29140, 5.1, 7.7),
                        new MistTrend.MistSlot(-1, 24520, 4.5, 7.4),
                        new MistTrend.MistSlot(0, 26180, 5.6, 8.7),
                        new MistTrend.MistSlot(1, 24880, 6.5, 10.6),
                        new MistTrend.MistSlot(2, 25420, 7.4, 11.9))))
                .tide(new TideSnapshot(
                        TideState.MID,
                        LocalDateTime.of(2026, 10, 4, 8, 52, 37),
                        new BigDecimal("1.06"),
                        LocalDateTime.of(2026, 10, 4, 15, 5, 0),
                        new BigDecimal("-0.78"),
                        true, null, null, null, null, null, null))
                .surge(new StormSurgeBreakdown(-0.122, 0, 0, 1025.2, 3.9, 192, 0,
                        TideRiskLevel.NONE, "No significant surge expected"))
                .adjustedRangeMetres(1.837)
                .astronomicalRangeMetres(1.837)
                .build();
    }

    /**
     * Builds the user message exactly as {@code ClaudeEvaluationStrategy} and the batch factory
     * do: the coastal builder when a tide is present, the surge overload when a surge is present.
     *
     * @param data the atmospheric data
     * @return the user message
     */
    static String userMessage(AtmosphericData data) {
        PromptBuilder builder = data.tide() != null ? new CoastalPromptBuilder() : new PromptBuilder();
        return data.surge() != null
                ? builder.buildUserMessage(data, data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres())
                : builder.buildUserMessage(data);
    }

    /**
     * Loads the user message production sent for record 86677, verbatim.
     *
     * @return the stored prompt text
     */
    static String storedProductionPrompt() {
        try (InputStream in = LowHauxleyFixture.class.getResourceAsStream(STORED_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + STORED_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + STORED_PROMPT_RESOURCE, e);
        }
    }
}
