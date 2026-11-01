package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.CoastalMistResult;
import com.gregochr.goldenhour.model.MistPotential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one-line coastal mist user-message addition (coastal-mist-plan Phase 1): present only for a LIGHT
 * band, the visibility floored to 0.1 km, " under a grey lid" at 80 % low cloud or more, nothing for NONE,
 * THICK or no carrier (those messages byte-identical), and none of the valley mist wording.
 */
class PromptBuilderCoastalMistTest {

    private static final String LINE_START = "Coastal mist: the model shows about ";

    private static final String LINE_END = " km visibility with near-saturated air, so a light sea mist is likely";

    private static final String LINE_TAIL = ", softening the light and adding depth. It can be the shot here; "
            + "the model sometimes misses thicker fret.";

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private static AtmosphericData base() {
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE).build();
    }

    private static AtmosphericData with(CoastalMistBand band, int visibilityMetres, int lowCloud) {
        return base().withCoastalMist(new CoastalMistResult(band, visibilityMetres, 0.8, lowCloud));
    }

    @Test
    @DisplayName("a LIGHT band adds the hedged line in the user message")
    void lightAddsTheLine() {
        String message = promptBuilder.buildUserMessage(with(CoastalMistBand.LIGHT, 7_000, 30));

        assertThat(message).contains(LINE_START + "7.0" + LINE_END + LINE_TAIL);
    }

    @Test
    @DisplayName("low cloud of 80 % or more adds 'under a grey lid' after 'is likely'")
    void greyLidVariant() {
        String message = promptBuilder.buildUserMessage(with(CoastalMistBand.LIGHT, 7_000, 80));

        assertThat(message).contains(LINE_START + "7.0" + LINE_END + " under a grey lid" + LINE_TAIL);
    }

    @Test
    @DisplayName("79 % low cloud is not a grey lid")
    void justUnderTheLid() {
        String message = promptBuilder.buildUserMessage(with(CoastalMistBand.LIGHT, 7_000, 79));

        assertThat(message).contains(LINE_START + "7.0" + LINE_END + LINE_TAIL)
                .doesNotContain("grey lid");
    }

    @ParameterizedTest(name = "{0} m -> about {1} km")
    @CsvSource({
        "5000, 5.0",
        "5960, 5.9",
        "5999, 5.9",
        "6000, 6.0",
        "7230, 7.2",
        "9990, 9.9",
        "10000, 10.0"
    })
    @DisplayName("visibility is floored to 0.1 km, never rounded up")
    void visibilityIsFloored(int metres, String expectedKm) {
        String message = promptBuilder.buildUserMessage(with(CoastalMistBand.LIGHT, metres, 10));

        assertThat(message).contains(LINE_START + expectedKm + LINE_END);
    }

    @Test
    @DisplayName("NONE and THICK say nothing: the message is byte-identical to one with no carrier")
    void noneAndThickAreAbsent() {
        String plain = promptBuilder.buildUserMessage(base());

        assertThat(promptBuilder.buildUserMessage(with(CoastalMistBand.NONE, 25_000, 10))).isEqualTo(plain);
        assertThat(promptBuilder.buildUserMessage(with(CoastalMistBand.THICK, 2_000, 10))).isEqualTo(plain);
        assertThat(plain).doesNotContain("Coastal mist");
    }

    @Test
    @DisplayName("a LIGHT carrier with no visibility says nothing rather than failing")
    void lightWithoutVisibilityIsAbsent() {
        AtmosphericData data = base().withCoastalMist(new CoastalMistResult(CoastalMistBand.LIGHT, null, 0.8, 10));

        assertThat(promptBuilder.buildUserMessage(data)).isEqualTo(promptBuilder.buildUserMessage(base()));
    }

    @Test
    @DisplayName("the only difference a LIGHT band makes is the one line, added after the rest of the message")
    void lightIsPurelyAdditive() {
        String plain = promptBuilder.buildUserMessage(base());

        String message = promptBuilder.buildUserMessage(with(CoastalMistBand.LIGHT, 7_000, 30));

        assertThat(message.replace(System.lineSeparator() + LINE_START + "7.0" + LINE_END + LINE_TAIL, ""))
                .isEqualTo(plain);
    }

    @Test
    @DisplayName("the line never uses the valley mist wording and never asks for mist_potential")
    void neverValleyMistWording() {
        String message = promptBuilder.buildUserMessage(with(CoastalMistBand.LIGHT, 7_000, 90));
        String line = message.substring(message.indexOf(LINE_START));
        line = line.substring(0, line.indexOf("fret.") + "fret.".length());

        assertThat(line).doesNotContainIgnoringCase("valley mist")
                .doesNotContainIgnoringCase("mist_potential")
                .doesNotContain("VALLEY MIST CONDITIONS");
        assertThat(message).doesNotContain("VALLEY MIST CONDITIONS:")
                .doesNotContain(PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION);
    }

    @Test
    @DisplayName("the system prompt is untouched: it never mentions coastal mist")
    void systemPromptUntouched() {
        assertThat(promptBuilder.getSystemPrompt()).doesNotContain("Coastal mist").doesNotContain("sea mist");
        assertThat(new CoastalPromptBuilder().getSystemPrompt()).doesNotContain("Coastal mist")
                .doesNotContain("sea mist");
    }

    @Test
    @DisplayName("the line sits directly after the valley mist block when a site somehow carries both")
    void sitsDirectlyAfterTheValleyMistBlock() {
        AtmosphericData both = TestAtmosphericData.builder().targetType(TargetType.SUNRISE)
                .mistScore(7.6).mistPotential(MistPotential.LIKELY).build()
                .withCoastalMist(new CoastalMistResult(CoastalMistBand.LIGHT, 7_000, 0.8, 30));

        String message = promptBuilder.buildUserMessage(both);

        int echo = message.indexOf(PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION);
        int line = message.indexOf(LINE_START);
        assertThat(echo).isNotNegative();
        assertThat(line).isGreaterThan(echo);
        assertThat(message.substring(echo + PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION.length(), line))
                .isEqualTo(System.lineSeparator());
    }
}
