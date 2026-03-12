package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulation test reconstructing the Copt Hill 2026-03-11 sunset failure case.
 *
 * <p>The 13:45 forecast showed only 7% solar low cloud at the 50 km solar horizon,
 * leading to an optimistic 4★ prediction. In reality, a cloud bank was approaching
 * from the SW and the sunset was ~2★. This test verifies that the cloud approach risk
 * block would have been present in the prompt, giving Claude the information needed
 * to score more cautiously.
 */
class PromptBuilderCoptHillSimulationTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("Copt Hill simulation: cloud approach block present with BUILDING label and upwind data")
    void coptHillSimulation_cloudApproachBlockPresent() {
        // Reconstruct the real Copt Hill 13:45 atmospheric data
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Copt Hill")
                .solarEventTime(LocalDateTime.of(2026, 3, 11, 17, 45))
                .targetType(TargetType.SUNSET)
                // Observer point: mostly clear with high cloud
                .lowCloud(0)
                .midCloud(0)
                .highCloud(100)
                // Weather
                .visibility(27980)
                .windSpeed(new BigDecimal("5.70"))
                .windDirection(228)
                .precipitation(BigDecimal.ZERO)
                .humidity(69)
                .weatherCode(3)
                .shortwaveRadiation(new BigDecimal("180.00"))
                // Aerosol
                .aod(new BigDecimal("0.080"))
                .pm25(new BigDecimal("3.80"))
                .dust(new BigDecimal("0.00"))
                .boundaryLayerHeight(1035)
                // Temperature
                .temperature(7.6)
                .apparentTemperature(2.8)
                .precipProbability(0)
                // Directional cloud — what the 13:45 run actually saw
                .directionalCloud(new DirectionalCloudData(7, 0, 88, 81, 0, 0))
                // Cloud approach risk — what we believe was actually happening
                .cloudApproach(new CloudApproachData(
                        // Solar trend: cloud was building earlier, model predicted clearing
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 5),
                                new SolarCloudTrend.SolarCloudSlot(2, 15),
                                new SolarCloudTrend.SolarCloudSlot(1, 35),
                                new SolarCloudTrend.SolarCloudSlot(0, 7))),
                        // Upwind: 70% low cloud at 87km SW right now, model says 15% at event
                        new UpwindCloudSample(87, 228, 70, 15)))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        // CLOUD APPROACH RISK block is present
        assertThat(message).contains("CLOUD APPROACH RISK:");

        // Trend data shows the building pattern
        assertThat(message).contains("Solar horizon low cloud trend (113km):");
        assertThat(message).contains("T-3h=5%");
        assertThat(message).contains("T-2h=15%");
        assertThat(message).contains("T-1h=35%");
        assertThat(message).contains("event=7%");

        // Building label is present (35% at T-1h vs 5% at T-3h = 30pp increase)
        assertThat(message).contains("[BUILDING]");

        // Upwind data shows the approaching cloud bank
        assertThat(message).contains("Upwind sample (87km along 228\u00b0 SW): current=70%, at-event=15%");

        // Directional cloud block still present with updated distance
        assertThat(message).contains("DIRECTIONAL CLOUD (113km sample):");
        assertThat(message).contains("Solar horizon (toward sun): Low 7%");
    }

    @Test
    @DisplayName("Copt Hill: prompt without approach data lacks CLOUD APPROACH RISK block")
    void coptHillSimulation_withoutApproachData_noBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Copt Hill")
                .solarEventTime(LocalDateTime.of(2026, 3, 11, 17, 45))
                .targetType(TargetType.SUNSET)
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
                .directionalCloud(new DirectionalCloudData(7, 0, 88, 81, 0, 0))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD APPROACH RISK");
    }
}
