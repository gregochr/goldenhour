package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Steel Rigg &amp; Winshield Crags, 24 October 2026, SUNRISE 06:56:17 UTC — the inputs production sent
 * for {@code forecast_evaluation} id 119939, shared by the tagged regression case in
 * {@link PromptRegressionTest} and the untagged {@link SteelRiggPromptFidelityTest}, so the numbers
 * live in exactly one place.
 */
final class SteelRiggFixture {

    private static final String STORED_PROMPT_RESOURCE =
            "/prompt-regression/steel-rigg-2026-10-24-sunrise.txt";

    private SteelRiggFixture() {
    }

    /**
     * Builds the atmospheric data of the production request. Steel Rigg is inland, so there is no
     * tide and no surge: the non-coastal prompt builder is selected exactly as the batch pipeline
     * selects it.
     *
     * @return the atmospheric data for Steel Rigg's 24 Oct 2026 sunrise
     */
    static AtmosphericData atmosphericData() {
        return TestAtmosphericData.builder()
                .locationName("Steel Rigg & Winshield Crags")
                .targetType(TargetType.SUNRISE)
                .solarEventTime(LocalDateTime.of(2026, 10, 24, 6, 56, 17))
                .lowCloud(34)
                .midCloud(86)
                .highCloud(100)
                .visibility(18960)
                .windSpeed(new BigDecimal("2.90"))
                .windDirection(244)
                .precipitation(new BigDecimal("0.00"))
                .humidity(96)
                .weatherCode(3)
                .boundaryLayerHeight(410)
                .shortwaveRadiation(new BigDecimal("0"))
                .pm25(new BigDecimal("3.70"))
                .dust(new BigDecimal("0.00"))
                .aod(new BigDecimal("0.080"))
                .temperature(3.2)
                .apparentTemperature(1.4)
                .dewPoint(2.6)
                .precipProbability(0)
                .locationOrientation("sunrise-optimised")
                .directionalCloud(new DirectionalCloudData(
                        0, 0, 87,        // solar horizon: Low 0%, Mid 0%, High 87%
                        60, 100, 100,    // antisolar horizon: Low 60%, Mid 100%, High 100%
                        80))             // far solar (226km): Low 80%
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 18, 0, 100),
                                new SolarCloudTrend.SolarCloudSlot(2, 0, 0, 96),
                                new SolarCloudTrend.SolarCloudSlot(1, 0, 0, 98),
                                new SolarCloudTrend.SolarCloudSlot(0, 0, 0, 87))),
                        new UpwindCloudSample(43, 244, 0, 78)))
                .mistTrend(new MistTrend(List.of(
                        new MistTrend.MistSlot(-3, 23560, 2.2, 2.8),
                        new MistTrend.MistSlot(-2, 22720, 2.3, 2.9),
                        new MistTrend.MistSlot(-1, 21580, 2.4, 2.9),
                        new MistTrend.MistSlot(0, 18960, 2.6, 3.2),
                        new MistTrend.MistSlot(1, 29840, 3.5, 5.0),
                        new MistTrend.MistSlot(2, 33980, 4.3, 6.1))))
                .build();
    }

    /**
     * Builds the user message exactly as the batch pipeline does for an inland location: the
     * plain builder, since there is no tide (and so no surge).
     *
     * @param data the atmospheric data
     * @return the user message
     */
    static String userMessage(AtmosphericData data) {
        PromptBuilder builder = data.tide() != null ? new CoastalPromptBuilder() : new PromptBuilder();
        return builder.buildUserMessage(data);
    }

    /**
     * Loads the user message production sent for record 119939, verbatim.
     *
     * @return the stored prompt text
     */
    static String storedProductionPrompt() {
        try (InputStream in = SteelRiggFixture.class.getResourceAsStream(STORED_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + STORED_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + STORED_PROMPT_RESOURCE, e);
        }
    }
}
