package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.WeatherData;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WoodlandPromptBuilder}.
 *
 * <p>These pin the two things that make the woodland prompt a different subject rather than a
 * re-skinned sky prompt: that its polarity is inverted (overcast and low visibility are assets),
 * and that it hedges leaf state the way the bluebell prompt hedges bloom.
 */
class WoodlandPromptBuilderTest {

    private final WoodlandPromptBuilder builder =
            new WoodlandPromptBuilder(new WoodlandVerdictEvaluator());

    private static AtmosphericData data(int lowCloud, int midCloud, int visibilityMetres,
            String precipMm, String windMs, LocalDateTime eventTime) {
        return data(lowCloud, midCloud, visibilityMetres, precipMm, windMs, eventTime, null);
    }

    private static AtmosphericData data(int lowCloud, int midCloud, int visibilityMetres,
            String precipMm, String windMs, LocalDateTime eventTime, Double precedingRainMm) {
        return new AtmosphericData(
                "Bluebell Wood",
                eventTime,
                TargetType.SUNRISE,
                new CloudData(lowCloud, midCloud, 20),
                new WeatherData(visibilityMetres, new BigDecimal(windMs), 180,
                        new BigDecimal(precipMm), 95, 3, null, 8.0, 1015.0, null, null, null,
                        null, null, precedingRainMm),
                null,
                new ComfortData(9.0, null, null),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static AtmosphericData mistyStillDawn() {
        return data(90, 60, 800, "0.00", "1.0", LocalDateTime.of(2026, 10, 20, 7, 30));
    }

    @Test
    @DisplayName("system prompt states the sky is not in the frame")
    void systemPrompt_excludesSky() {
        assertThat(builder.getSystemPrompt())
                .contains("THE SKY IS NOT IN THE FRAME")
                .contains("Never score sunset colour");
    }

    @Test
    @DisplayName("system prompt inverts light polarity — overcast is good, dapple is worst")
    void systemPrompt_invertsLightPolarity() {
        String p = builder.getSystemPrompt();
        assertThat(p).contains("Heavy overcast is GOOD");
        assertThat(p).contains("Broken cloud with the sun behind it is the WORST case");
    }

    @Test
    @DisplayName("system prompt hedges leaf state rather than asserting it")
    void systemPrompt_hedgesLeafState() {
        assertThat(builder.getSystemPrompt())
                .contains("LEAF-STATE ASSUMPTION")
                .contains("hedge anything you cannot see");
    }

    @Test
    @DisplayName("system prompt bounds the rain window it was actually given")
    void systemPrompt_boundsTheRainWindow() {
        // precedingPrecipitationMm covers HOURS before the slot, which is the right window for wet
        // bark and saturated foliage. It is not a multi-day accumulation, so the prompt must not
        // let the model infer a lush or drought-stricken wood from it.
        String p = builder.getSystemPrompt();
        assertThat(p).contains("HOURS before the slot");
        assertThat(p).contains("do not assert the wood is lush, parched, or in drought");
    }

    @Test
    @DisplayName("user message carries the date so the model can reason about season")
    void userMessage_carriesDateAndMonth() {
        String msg = builder.buildUserMessage(mistyStillDawn());
        assertThat(msg).contains("Date: 2026-10-20").contains("OCTOBER");
    }

    @Test
    @DisplayName("user message reports the deterministic verdict and flags as a hint")
    void userMessage_carriesDeterministicHint() {
        String msg = builder.buildUserMessage(mistyStillDawn());
        assertThat(msg).contains("WOODLAND CONDITIONS (deterministic hint, season-blind)");
        // 90% low + 60% mid caps at 100 diffuse cover, and 800 m is working mist: GO, both flags.
        assertThat(msg).contains("Verdict: GO");
        assertThat(msg).contains("Mist").contains("Soft even light");
    }

    @Test
    @DisplayName("user message reports combined diffuse cover, not an average")
    void userMessage_reportsDiffuseCover() {
        // The sky prompt averages low/mid/high; a wood cares about low+mid combined, because high
        // cirrus does not soften light at ground level. Averaging would report 56% for a sky that
        // is in fact fully overcast at the canopy.
        String msg = builder.buildUserMessage(mistyStillDawn());
        assertThat(msg).contains("combined diffuse cover 100%");
    }

    @Test
    @DisplayName("a gale is reported as an unsafe stand-down, not merely windy")
    void userMessage_galeReadsUnsafe() {
        String msg = builder.buildUserMessage(
                data(90, 60, 8000, "0.00", "20.0", LocalDateTime.of(2026, 1, 10, 8, 0)));
        assertThat(msg).contains("Verdict: STANDDOWN");
        assertThat(msg).contains("Unsafe — falling branches");
    }

    @Test
    @DisplayName("output config constrains rating to 1-5 and requires a summary")
    void outputConfig_constrainsRating() {
        assertThat(builder.buildOutputConfig()).isNotNull();
        assertThat(builder.buildOutputConfig().toString())
                .contains("rating")
                .contains("summary");
    }

    @Test
    @DisplayName("reports rain in the preceding hours — the wet-bark saturation signal")
    void userMessage_reportsPrecedingRain() {
        String msg = builder.buildUserMessage(
                data(90, 60, 800, "0.00", "1.0", LocalDateTime.of(2026, 10, 20, 7, 30), 3.4));
        assertThat(msg).contains("Rain in the preceding hours: 3.40 mm");
    }

    @Test
    @DisplayName("says so plainly when preceding rain is unavailable, rather than implying dry")
    void userMessage_precedingRainAbsentReadsUnavailable() {
        // Printing 0.00 for a null would tell the model it definitely did not rain, which is a
        // different claim from having no reading.
        assertThat(builder.buildUserMessage(mistyStillDawn()))
                .contains("Rain in the preceding hours: not available");
    }

    @Test
    @DisplayName("no snow line when there is no snow")
    void userMessage_omitsSnowWhenAbsent() {
        assertThat(builder.buildUserMessage(mistyStillDawn())).doesNotContain("Snow depth");
    }
}
