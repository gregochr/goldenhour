package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.WeatherData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the storm surge prompt integration in {@link PromptBuilder}.
 */
class PromptBuilderSurgeTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    private AtmosphericData testData() {
        return new AtmosphericData(
                "Craster",
                LocalDateTime.of(2026, 3, 30, 18, 45),
                TargetType.SUNSET,
                new CloudData(20, 45, 60),
                new WeatherData(15000, BigDecimal.valueOf(8.5), 80, BigDecimal.ZERO, 75,
                        3, BigDecimal.valueOf(150.0), 2.5),
                new AerosolData(BigDecimal.valueOf(5.0), BigDecimal.valueOf(10.0),
                        BigDecimal.valueOf(0.1), 800),
                new ComfortData(12.0, 10.5, 15),
                null, null, null, null
        );
    }

    @Nested
    @DisplayName("buildUserMessage with surge")
    class SurgePromptTests {

        @Test
        @DisplayName("Significant surge adds STORM SURGE FORECAST section")
        void significantSurgeAddsSection() {
            var surge = new StormSurgeBreakdown(
                    0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                    TideRiskLevel.MODERATE, "Test explanation");

            String message = promptBuilder.buildUserMessage(testData(), surge, 5.4, 5.0);

            assertThat(message).contains("STORM SURGE FORECAST:");
            assertThat(message).contains("Pressure effect:");
            assertThat(message).contains("Total estimated surge:");
            assertThat(message).contains("Risk level: MODERATE");
            assertThat(message).contains("upper bound");
        }

        @Test
        @DisplayName("Insignificant surge does not add section")
        void insignificantSurgeOmitsSection() {
            var surge = new StormSurgeBreakdown(
                    0.01, 0.01, 0.02, 1015.0, 3.0, 180.0, 0.0,
                    TideRiskLevel.NONE, "No significant surge");

            String message = promptBuilder.buildUserMessage(testData(), surge, 4.02, 4.0);

            assertThat(message).doesNotContain("STORM SURGE FORECAST:");
        }

        @Test
        @DisplayName("Null surge does not add section")
        void nullSurgeOmitsSection() {
            String message = promptBuilder.buildUserMessage(testData(), null, null, null);

            assertThat(message).doesNotContain("STORM SURGE FORECAST:");
        }

        @Test
        @DisplayName("Surge section includes adjusted tidal range")
        void includesAdjustedRange() {
            var surge = new StormSurgeBreakdown(
                    0.30, 0.10, 0.40, 980.0, 15.0, 80.0, 0.9,
                    TideRiskLevel.HIGH, "Test");

            String message = promptBuilder.buildUserMessage(testData(), surge, 5.4, 5.0);

            assertThat(message).contains("Adjusted tidal range: up to 5.4m");
            assertThat(message).contains("predicted 5.0m");
        }

        @Test
        @DisplayName("Wind effect omitted when below 0.02m threshold")
        void windOmittedBelowThreshold() {
            var surge = new StormSurgeBreakdown(
                    0.15, 0.01, 0.16, 998.0, 5.0, 180.0, 0.0,
                    TideRiskLevel.LOW, "Test");

            String message = promptBuilder.buildUserMessage(testData(), surge, 4.16, 4.0);

            assertThat(message).contains("Pressure effect:");
            assertThat(message).doesNotContain("Wind effect:");
        }

        @Test
        @DisplayName("Wind effect included when above threshold")
        void windIncludedAboveThreshold() {
            var surge = new StormSurgeBreakdown(
                    0.20, 0.15, 0.35, 990.0, 18.0, 80.0, 0.85,
                    TideRiskLevel.MODERATE, "Test");

            String message = promptBuilder.buildUserMessage(testData(), surge, 5.35, 5.0);

            assertThat(message).contains("Wind effect:");
            assertThat(message).contains("onshore wind");
        }

        @Test
        @DisplayName("Prompt suffix is preserved after surge section")
        void suffixPreserved() {
            var surge = new StormSurgeBreakdown(
                    0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                    TideRiskLevel.MODERATE, "Test");

            String message = promptBuilder.buildUserMessage(testData(), surge, 5.4, 5.0);

            assertThat(message).endsWith(promptBuilder.getPromptSuffix());
        }

        @Test
        @DisplayName("Surge section comes before prompt suffix")
        void surgeBeforeSuffix() {
            var surge = new StormSurgeBreakdown(
                    0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                    TideRiskLevel.MODERATE, "Test");

            String message = promptBuilder.buildUserMessage(testData(), surge, 5.4, 5.0);

            int surgeIdx = message.indexOf("STORM SURGE FORECAST:");
            int suffixIdx = message.lastIndexOf(promptBuilder.getPromptSuffix());
            assertThat(surgeIdx).isLessThan(suffixIdx);
        }

        @Test
        @DisplayName("Adjusted range omitted when null")
        void adjustedRangeOmittedWhenNull() {
            var surge = new StormSurgeBreakdown(
                    0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                    TideRiskLevel.MODERATE, "Test");

            String message = promptBuilder.buildUserMessage(testData(), surge, null, null);

            assertThat(message).contains("STORM SURGE FORECAST:");
            assertThat(message).doesNotContain("Adjusted tidal range");
        }

        @Test
        @DisplayName("Original buildUserMessage still works without surge")
        void originalMethodStillWorks() {
            String message = promptBuilder.buildUserMessage(testData());

            assertThat(message).contains("Location: Craster");
            assertThat(message).doesNotContain("STORM SURGE FORECAST:");
        }
    }
}
