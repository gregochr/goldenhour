package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PromptBuilder}.
 */
public class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("getSystemPrompt contains rating scale")
    void getSystemPrompt_containsRatingScale() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt).contains("1\u20135").contains("rating");
    }

    @Test
    @DisplayName("getSystemPrompt contains dual score fields")
    void getSystemPrompt_containsDualScoreFields() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt).contains("fiery_sky").contains("golden_hour");
    }

    @Test
    @DisplayName("getPromptSuffix contains rating instruction")
    void getPromptSuffix_containsRatingInstruction() {
        String suffix = promptBuilder.getPromptSuffix();

        assertThat(suffix)
                .contains("Rate 1-5")
                .contains("Fiery Sky Potential")
                .contains("Golden Hour Potential");
    }

    @Test
    @DisplayName("buildUserMessage contains location data")
    void buildUserMessage_containsLocationData() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Durham UK")
                .contains("SUNSET")
                .contains("Low 10%")
                .contains("Mid 50%")
                .contains("High 30%");
    }

    @Test
    @DisplayName("buildUserMessage contains prompt suffix")
    void buildUserMessage_containsPromptSuffix() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).endsWith(PromptBuilder.PROMPT_SUFFIX);
    }

    @Test
    @DisplayName("buildUserMessage with high AOD includes dust context")
    void buildUserMessage_highAod_includesDustContext() {
        AtmosphericData data = TestAtmosphericData.builder()
                .aod(new BigDecimal("0.50"))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("SAHARAN DUST CONTEXT:")
                .contains("AOD: 0.50", "(elevated)")
                .contains("SW")
                .contains("maximises warm scattering potential");
    }

    @Test
    @DisplayName("buildUserMessage with high dust includes dust context")
    void buildUserMessage_highDust_includesDustContext() {
        AtmosphericData data = TestAtmosphericData.builder()
                .dust(new BigDecimal("65.00"))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("SAHARAN DUST CONTEXT:")
                .contains("Surface dust: 65.00");
    }

    @Test
    @DisplayName("buildUserMessage with low aerosols omits dust context")
    void buildUserMessage_lowAerosols_noDustContext() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("SAHARAN DUST CONTEXT:");
    }

    @Test
    @DisplayName("buildUserMessage with directional cloud includes directional block")
    void buildUserMessage_withDirectionalCloud_includesDirectionalBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(65, 20, 10, 5, 45, 30, null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("DIRECTIONAL CLOUD (113km sample):")
                .contains("Solar horizon (toward sun): Low 65%, Mid 20%, High 10%")
                .contains("Antisolar horizon (away from sun): Low 5%, Mid 45%, High 30%");
    }

    @Test
    @DisplayName("buildUserMessage without directional cloud omits directional block")
    void buildUserMessage_withoutDirectionalCloud_omitsDirectionalBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("DIRECTIONAL CLOUD");
    }

    @Test
    @DisplayName("buildUserMessage with tide data includes tide block")
    void buildUserMessage_withTideData_includesTideBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage without tide omits tide block")
    void buildUserMessage_withoutTide_omitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("toCardinal converts degrees to 16-point compass correctly")
    void toCardinal_convertsCorrectly() {
        assertThat(PromptBuilder.toCardinal(0)).isEqualTo("N");
        assertThat(PromptBuilder.toCardinal(45)).isEqualTo("NE");
        assertThat(PromptBuilder.toCardinal(90)).isEqualTo("E");
        assertThat(PromptBuilder.toCardinal(135)).isEqualTo("SE");
        assertThat(PromptBuilder.toCardinal(180)).isEqualTo("S");
        assertThat(PromptBuilder.toCardinal(225)).isEqualTo("SW");
        assertThat(PromptBuilder.toCardinal(270)).isEqualTo("W");
        assertThat(PromptBuilder.toCardinal(315)).isEqualTo("NW");
        assertThat(PromptBuilder.toCardinal(360)).isEqualTo("N");
        assertThat(PromptBuilder.toCardinal(22)).isEqualTo("NNE");
        assertThat(PromptBuilder.toCardinal(202)).isEqualTo("SSW");
    }

    @Test
    @DisplayName("isDustElevated returns true for high AOD")
    void isDustElevated_highAod_returnsTrue() {
        AerosolData aerosol = new AerosolData(
                new BigDecimal("8.50"), new BigDecimal("2.10"),
                new BigDecimal("0.50"), 1200);

        assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
    }

    @Test
    @DisplayName("isDustElevated returns true for high dust")
    void isDustElevated_highDust_returnsTrue() {
        AerosolData aerosol = new AerosolData(
                new BigDecimal("8.50"), new BigDecimal("65.00"),
                new BigDecimal("0.10"), 1200);

        assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
    }

    @Test
    @DisplayName("isDustElevated returns false for low aerosols")
    void isDustElevated_lowBoth_returnsFalse() {
        AerosolData aerosol = new AerosolData(
                new BigDecimal("8.50"), new BigDecimal("2.10"),
                new BigDecimal("0.12"), 1200);

        assertThat(PromptBuilder.isDustElevated(aerosol)).isFalse();
    }

    @Test
    @DisplayName("buildOutputConfig returns non-null config")
    void buildOutputConfig_returnsNonNull() {
        assertThat(promptBuilder.buildOutputConfig()).isNotNull();
    }

    @Test
    @DisplayName("buildUserMessage with cloud approach data includes CLOUD APPROACH RISK block")
    void buildUserMessage_withCloudApproach_includesCloudApproachBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 5),
                                new SolarCloudTrend.SolarCloudSlot(2, 15),
                                new SolarCloudTrend.SolarCloudSlot(1, 35),
                                new SolarCloudTrend.SolarCloudSlot(0, 7))),
                        new UpwindCloudSample(87, 228, 70, 15)))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .contains("Solar horizon low cloud trend (113km):")
                .contains("T-3h=5%")
                .contains("event=7%")
                .contains("[BUILDING]")
                .contains("Upwind sample (87km along 228\u00b0 SW): current=70%, at-event=15%");
    }

    @Test
    @DisplayName("buildUserMessage without cloud approach data omits CLOUD APPROACH RISK block")
    void buildUserMessage_withoutCloudApproach_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD APPROACH RISK");
    }

    @Test
    @DisplayName("buildUserMessage with non-building trend omits BUILDING label")
    void buildUserMessage_nonBuildingTrend_omitsBuildingLabel() {
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 50),
                                new SolarCloudTrend.SolarCloudSlot(2, 40),
                                new SolarCloudTrend.SolarCloudSlot(1, 20),
                                new SolarCloudTrend.SolarCloudSlot(0, 10))),
                        null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .contains("Solar horizon low cloud trend (113km):")
                .doesNotContain("[BUILDING]");
    }

    @Test
    @DisplayName("buildUserMessage with upwind only (no trend) shows upwind block")
    void buildUserMessage_upwindOnly_showsUpwindBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(
                        null,
                        new UpwindCloudSample(120, 180, 65, 20)))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .doesNotContain("Solar horizon low cloud trend")
                .contains("Upwind sample (120km along 180\u00b0 S): current=65%, at-event=20%");
    }

    @Test
    @DisplayName("getSystemPrompt contains cloud approach risk guidance")
    void getSystemPrompt_containsCloudApproachGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("CLOUD APPROACH RISK:")
                .contains("Solar trend")
                .contains("Upwind sample");
    }
}
