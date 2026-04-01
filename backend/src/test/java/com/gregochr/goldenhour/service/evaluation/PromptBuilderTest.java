package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.gregochr.goldenhour.service.evaluation.PromptBuilder.InversionPotential;

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
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
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

    @Test
    @DisplayName("buildUserMessage with dew point includes dew point and gap in weather block")
    void buildUserMessage_withDewPoint_includesDewPointAndGap() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(5.8)
                .dewPoint(2.2)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Dew point:")
                .contains("2.2")
                .contains("gap");
    }

    @Test
    @DisplayName("buildUserMessage without dew point shows N/A")
    void buildUserMessage_noDewPoint_showsNa() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("Dew point: N/A");
    }

    @Test
    @DisplayName("buildUserMessage with mist trend includes mist trend block")
    void buildUserMessage_withMistTrend_includesMistBlock() {
        MistTrend mistTrend = new MistTrend(java.util.List.of(
                new MistTrend.MistSlot(-2, 15000, 1.0, 6.0),
                new MistTrend.MistSlot(-1, 8000, 2.0, 4.0),
                new MistTrend.MistSlot(0, 4200, 2.2, 3.8)
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("MIST/VISIBILITY TREND")
                .contains("T-2h")
                .contains("T-1h")
                .contains("event")
                .contains("4,200m")
                .contains("gap=");
    }

    @Test
    @DisplayName("buildUserMessage near-dew-point slot gets [NEAR DEW POINT] label")
    void buildUserMessage_nearDewPointSlot_getsLabel() {
        MistTrend mistTrend = new MistTrend(java.util.List.of(
                new MistTrend.MistSlot(0, 3000, 5.8, 6.5) // gap = 0.7°C → AT/NEAR DEW POINT
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("[AT/NEAR DEW POINT]");
    }

    @Test
    @DisplayName("buildUserMessage without mist trend omits mist block")
    void buildUserMessage_noMistTrend_omitsMistBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("MIST/VISIBILITY TREND");
    }

    @Test
    @DisplayName("buildUserMessage with location orientation includes orientation line")
    void buildUserMessage_withOrientation_includesOrientationLine() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationOrientation("sunrise-optimised")
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Location orientation: sunrise-optimised")
                .contains("best suited for sunrise photography");
    }

    @Test
    @DisplayName("buildUserMessage with sunset orientation includes sunset orientation line")
    void buildUserMessage_withSunsetOrientation_includesSunsetOrientationLine() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationOrientation("sunset-optimised")
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Location orientation: sunset-optimised")
                .contains("best suited for sunset photography");
    }

    @Test
    @DisplayName("buildUserMessage without location orientation omits orientation line")
    void buildUserMessage_noOrientation_omitsOrientationLine() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("Location orientation:");
    }

    @Test
    @DisplayName("getSystemPrompt contains location orientation guidance")
    void getSystemPrompt_containsOrientationGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("LOCATION ORIENTATION")
                .contains("sunrise-optimised")
                .contains("sunset-optimised")
                .contains("Reduce fiery_sky by 10-20");
    }

    @Test
    @DisplayName("getSystemPrompt contains mist and visibility guidance")
    void getSystemPrompt_containsMistGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("MIST AND VISIBILITY GUIDANCE")
                .contains("temp-dew gap")
                .contains("SUNRISE SPECIFIC")
                .contains("SUNSET SPECIFIC");
    }

    @Test
    @DisplayName("buildUserMessage with king tide shows lunar + statistical labels")
    void buildUserMessage_kingTideWithExtraExtraHigh_showsBothLabels() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("6.20"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.KING_TIDE, "New Moon", true,
                        TideStatisticalSize.EXTRA_EXTRA_HIGH))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: KING TIDE, Extra Extra High")
                .contains("range: 6.20m")
                .contains("moon: New Moon")
                .contains("perigee: yes")
                .contains("aligned: yes");
    }

    @Test
    @DisplayName("buildUserMessage with spring tide and extra high shows both labels")
    void buildUserMessage_springTideWithExtraHigh_showsBothLabels() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("5.10"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.30"),
                        false,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false,
                        TideStatisticalSize.EXTRA_HIGH))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: SPRING TIDE, Extra High")
                .contains("moon: Full Moon")
                .contains("perigee: no")
                .contains("aligned: no");
    }

    @Test
    @DisplayName("buildUserMessage with regular tide and no statistical size omits size label")
    void buildUserMessage_regularTide_omitsSizeLabel() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.MID,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("3.80"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.50"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.REGULAR_TIDE, "Waxing Crescent", false,
                        null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: REGULAR TIDE (range:")
                .doesNotContain("Extra Extra High")
                .doesNotContain("Extra High")
                .contains("moon: Waxing Crescent")
                .contains("perigee: no");
    }

    @Test
    @DisplayName("buildUserMessage with thick mid cloud (>80%) includes annotation")
    void buildUserMessage_thickMidCloud_includesAnnotation() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(30, 85, 10, 5, 45, 30, null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("[THICK MID CLOUD — rate 4, not 5]");
    }

    @Test
    @DisplayName("buildUserMessage with thin strip detection includes THIN STRIP annotation")
    void buildUserMessage_thinStrip_includesAnnotation() {
        // Solar low cloud 60%, far solar 25% → drop of 35pp ≥ 30 → thin strip
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(60, 20, 10, 5, 45, 30, 25))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Beyond horizon (226km, solar azimuth): Low 25%")
                .contains("[THIN STRIP — soften low-cloud penalty]");
    }

    @Test
    @DisplayName("buildUserMessage with extensive blanket includes BLANKET annotation")
    void buildUserMessage_extensiveBlanket_includesAnnotation() {
        // Solar low cloud 70%, far solar 65% → both ≥ 50 → extensive blanket
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(70, 20, 10, 5, 45, 30, 65))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Beyond horizon (226km, solar azimuth): Low 65%")
                .contains("[EXTENSIVE BLANKET — full penalty applies]");
    }

    @Test
    @DisplayName("buildUserMessage far solar with no thin strip or blanket omits annotation")
    void buildUserMessage_farSolarNeitherThinStripNorBlanket_noAnnotation() {
        // Solar low cloud 40% (< 50) → neither condition triggers
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(40, 20, 10, 5, 45, 30, 10))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Beyond horizon (226km, solar azimuth): Low 10%")
                .doesNotContain("[THIN STRIP")
                .doesNotContain("[EXTENSIVE BLANKET");
    }

    @Test
    @DisplayName("buildUserMessage BUILDING + thin strip confirmed uses priority annotation")
    void buildUserMessage_buildingWithThinStrip_usesPriorityAnnotation() {
        // Thin strip: solar 60%, far 25% (drop 35pp ≥ 30) + building trend
        DirectionalCloudData dc = new DirectionalCloudData(60, 20, 10, 5, 45, 30, 25);
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(dc)
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 5),
                                new SolarCloudTrend.SolarCloudSlot(2, 15),
                                new SolarCloudTrend.SolarCloudSlot(1, 35),
                                new SolarCloudTrend.SolarCloudSlot(0, 60))),
                        null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("[BUILDING — but THIN STRIP CONFIRMED at event time")
                .doesNotContain("[BUILDING]");
    }

    @Test
    @DisplayName("buildUserMessage with dew point but null temperature shows NaN gap")
    void buildUserMessage_dewPointWithNullTemperature_showsNanGap() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(null)
                .dewPoint(3.5)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("3.5").contains("NaN");
    }

    @Test
    @DisplayName("buildUserMessage mist trend with positive hour shows T+ prefix")
    void buildUserMessage_mistTrendPositiveHour_showsTPlus() {
        MistTrend mistTrend = new MistTrend(List.of(
                new MistTrend.MistSlot(1, 12000, 8.0, 5.0),
                new MistTrend.MistSlot(2, 15000, 9.0, 4.0)
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("T+1h")
                .contains("T+2h");
    }

    @Test
    @DisplayName("buildUserMessage mist trend with gap 1.0-2.0 shows NEAR DEW POINT label")
    void buildUserMessage_mistTrendNearDewPoint_showsLabel() {
        MistTrend mistTrend = new MistTrend(List.of(
                new MistTrend.MistSlot(0, 3000, 6.0, 7.5) // dew=6.0, temp=7.5, gap=1.5°C → NEAR DEW POINT
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("[NEAR DEW POINT]")
                .doesNotContain("[AT/NEAR DEW POINT]");
    }

    @Test
    @DisplayName("buildUserMessage surge section included when significant surge present")
    void buildUserMessage_withSurge_includesSurgeBlock() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test surge");

        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data, surge, 4.15, 3.80);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .contains("Pressure effect: +0.23m")
                .contains("low pressure 990 hPa")
                .contains("Wind effect: +0.12m")
                .contains("Total estimated surge: +0.35m (upper bound)")
                .contains("Adjusted tidal range: up to 4.2m (predicted 3.8m + 0.35m surge)")
                .contains("Risk level: MODERATE");
    }

    @Test
    @DisplayName("buildUserMessage surge with high pressure shows 'high' label")
    void buildUserMessage_surgeHighPressure_showsHighLabel() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                -0.17, 0.0, 0.0, 1030.0, 5.0, 270.0, 0.0,
                TideRiskLevel.NONE, "No significant surge expected");

        // Non-significant surge should not be included
        AtmosphericData data = TestAtmosphericData.defaults();
        String message = promptBuilder.buildUserMessage(data, surge, null, null);
        assertThat(message).doesNotContain("STORM SURGE FORECAST:");
    }

    @Test
    @DisplayName("buildUserMessage surge without wind component omits wind line")
    void buildUserMessage_surgeNoWindComponent_omitsWindLine() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.15, 0.01, 0.15, 998.0, 3.0, 180.0, 0.1,
                TideRiskLevel.LOW, "Pressure only");

        AtmosphericData data = TestAtmosphericData.defaults();
        String message = promptBuilder.buildUserMessage(data, surge, null, null);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .contains("Pressure effect:")
                .doesNotContain("Wind effect:");
    }

    @Test
    @DisplayName("buildUserMessage surge without adjusted range omits tidal range line")
    void buildUserMessage_surgeNoAdjustedRange_omitsRangeLine() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.15, 0.05, 0.20, 998.0, 10.0, 80.0, 0.8,
                TideRiskLevel.LOW, "Test");

        AtmosphericData data = TestAtmosphericData.defaults();
        String message = promptBuilder.buildUserMessage(data, surge, null, null);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .doesNotContain("Adjusted tidal range:");
    }

    @Test
    @DisplayName("buildUserMessage with null lunar fields falls back to Regular Tide")
    void buildUserMessage_nullLunarFields_fallsBackToRegularTide() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Tide: Regular Tide (range:")
                .doesNotContain("moon:")
                .doesNotContain("perigee:");
    }

    // ── Cloud Inversion Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("getSystemPrompt contains cloud inversion guidance")
    void getSystemPrompt_containsInversionGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("CLOUD INVERSION GUIDANCE:")
                .contains("Inversion score 7-8")
                .contains("Inversion score 9-10")
                .contains("sea of clouds");
    }

    @Test
    @DisplayName("buildUserMessage with moderate inversion score includes inversion block")
    void buildUserMessage_moderateInversion_includesInversionBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(8.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Score: 8/10")
                .contains("Moderate Cloud Inversion Potential")
                .contains("Visible cloud layer below; light touching cloud tops")
                .contains("Peak at event time");
    }

    @Test
    @DisplayName("buildUserMessage with strong inversion score includes strong block")
    void buildUserMessage_strongInversion_includesStrongBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(9.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Score: 9/10")
                .contains("Strong Cloud Inversion Potential")
                .contains("Dramatic blanket below viewpoint; clear sky above");
    }

    @Test
    @DisplayName("buildUserMessage with score 10 uses strong potential")
    void buildUserMessage_score10_usesStrongPotential() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(10.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Score: 10/10")
                .contains("Strong Cloud Inversion Potential");
    }

    @Test
    @DisplayName("buildUserMessage with inversion score below threshold omits inversion block")
    void buildUserMessage_lowInversion_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(5.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD INVERSION FORECAST:");
    }

    @Test
    @DisplayName("buildUserMessage with null inversion score omits inversion block")
    void buildUserMessage_nullInversion_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD INVERSION FORECAST:");
    }

    @Test
    @DisplayName("buildUserMessage with inversion score exactly at threshold includes block")
    void buildUserMessage_inversionAtThreshold_includesBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(7.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Score: 7/10")
                .contains("Moderate Cloud Inversion Potential");
    }

    @Test
    @DisplayName("buildUserMessage with inversion score just below threshold omits block")
    void buildUserMessage_inversionJustBelowThreshold_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(6.9)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD INVERSION FORECAST:");
    }

    @Test
    @DisplayName("isInversionLikely returns true for score at threshold")
    void isInversionLikely_atThreshold_returnsTrue() {
        assertThat(PromptBuilder.isInversionLikely(7.0)).isTrue();
    }

    @Test
    @DisplayName("isInversionLikely returns true for score above threshold")
    void isInversionLikely_aboveThreshold_returnsTrue() {
        assertThat(PromptBuilder.isInversionLikely(9.5)).isTrue();
    }

    @Test
    @DisplayName("isInversionLikely returns false for score below threshold")
    void isInversionLikely_belowThreshold_returnsFalse() {
        assertThat(PromptBuilder.isInversionLikely(6.0)).isFalse();
    }

    @Test
    @DisplayName("isInversionLikely returns false for null")
    void isInversionLikely_null_returnsFalse() {
        assertThat(PromptBuilder.isInversionLikely(null)).isFalse();
    }

    @Test
    @DisplayName("InversionPotential.fromScore returns STRONG for 9+")
    void inversionPotential_fromScore_strong() {
        assertThat(InversionPotential.fromScore(9)).isEqualTo(InversionPotential.STRONG);
        assertThat(InversionPotential.fromScore(10)).isEqualTo(InversionPotential.STRONG);
    }

    @Test
    @DisplayName("InversionPotential.fromScore returns MODERATE for 7-8")
    void inversionPotential_fromScore_moderate() {
        assertThat(InversionPotential.fromScore(7)).isEqualTo(InversionPotential.MODERATE);
        assertThat(InversionPotential.fromScore(8)).isEqualTo(InversionPotential.MODERATE);
    }

    @Test
    @DisplayName("InversionPotential.fromScore returns NONE for below 7")
    void inversionPotential_fromScore_none() {
        assertThat(InversionPotential.fromScore(6)).isEqualTo(InversionPotential.NONE);
        assertThat(InversionPotential.fromScore(0)).isEqualTo(InversionPotential.NONE);
    }

    @Test
    @DisplayName("InversionPotential labels are human-readable")
    void inversionPotential_labels_readable() {
        assertThat(InversionPotential.MODERATE.label()).isEqualTo("Moderate Cloud Inversion Potential");
        assertThat(InversionPotential.STRONG.label()).isEqualTo("Strong Cloud Inversion Potential");
        assertThat(InversionPotential.NONE.label()).isEqualTo("No inversion potential");
    }
}
