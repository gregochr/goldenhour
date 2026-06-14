package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BluebellPromptBuilder} covering the behaviour the golden master cannot:
 * the null-score guard and exposure routing into the user message. The full assembled-prompt
 * text is pinned by {@link BluebellPromptGoldenMasterTest}.
 */
class BluebellPromptBuilderTest {

    private final BluebellPromptBuilder builder = new BluebellPromptBuilder();

    @Test
    @DisplayName("buildUserMessage throws when no bluebell condition score is present")
    void buildUserMessage_nullScore_throws() {
        // The collector only routes in-season bluebell sites here; a missing score is a bug, not
        // a soft path — fail loud rather than emit a malformed prompt.
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(null)
                .build();

        assertThatThrownBy(() -> builder.buildUserMessage(data))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bluebell condition score");
    }

    @Test
    @DisplayName("user message carries the exposure and the deterministic condition flags")
    void buildUserMessage_rendersExposureAndFlags() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(new BluebellConditionScore(
                        7, false, true, false, true, false, true,
                        BluebellExposure.OPEN_FELL, "still air, golden hour light"))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("Exposure: OPEN_FELL")
                .contains("misty=false, calm=true")
                .contains("still air, golden hour light")
                .contains("CONDITIONS ANALYSIS")
                .endsWith(builder.getPromptSuffix());
    }

    @Test
    @DisplayName("user message degrades gracefully when wind/precip/temp/dew are absent")
    void buildUserMessage_nullWeatherFields_renderFallbacks() {
        // Exercises the null-guard branches: missing wind → 0.0 km/h, missing precip → 0.00 mm,
        // missing temperature/dew point → N/A. Open-Meteo can omit any of these on a given hour.
        AtmosphericData data = TestAtmosphericData.builder()
                .windSpeed(null)
                .precipitation(null)
                .temperature(null)
                .dewPoint(null)
                .bluebellConditionScore(new BluebellConditionScore(
                        4, false, false, false, false, false, true,
                        BluebellExposure.WOODLAND, "dry but flat and breezy"))
                .build();

        String message = builder.buildUserMessage(data);

        assertThat(message)
                .contains("Wind: 0.0 km/h")
                .contains("Precipitation: 0.00 mm")
                .contains("Temperature: N/A, Dew point: N/A");
    }

    @Test
    @DisplayName("system prompt branches the rubric on exposure and keeps the bloom caveat")
    void systemPrompt_coversBothExposuresAndBloomCaveat() {
        String prompt = builder.getSystemPrompt();

        assertThat(prompt)
                .contains("WOODLAND exposure")
                .contains("OPEN_FELL exposure")
                .contains("BLOOM ASSUMPTION")
                .contains("CONDITIONS GIVEN ASSUMED BLOOM");
    }

    @Test
    @DisplayName("output config constrains to rating (enum 1-5) + summary, with no sky fields")
    void buildOutputConfig_constrainsToBluebellContract() {
        String configStr = builder.buildOutputConfig().toString();

        assertThat(configStr)
                .contains("rating")
                .contains("summary")
                .contains("headline")
                // The bluebell schema is its own minimal contract — none of the sky sub-scores.
                .doesNotContain("fiery_sky")
                .doesNotContain("golden_hour")
                .doesNotContain("bluebell_score");
        // required = [rating, summary]; headline is optional.
        String required = configStr.substring(configStr.indexOf("required="));
        required = required.substring(0, required.indexOf("]") + 1);
        assertThat(required).contains("rating").contains("summary").doesNotContain("headline");
    }
}
