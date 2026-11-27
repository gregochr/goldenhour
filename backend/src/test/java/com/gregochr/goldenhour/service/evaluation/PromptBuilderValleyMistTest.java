package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.MistTrend.MistSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code VALLEY MIST CONDITIONS:} user-message block (valley-mist-plan M2): present only
 * for a mist-prone site, the unflagged message byte-for-byte unchanged, the site sentence verbatim,
 * the echo instruction in the USER message with the system prompt untouched, and the shared output
 * schema naming {@code mist_potential} (constrained decoding forbids undeclared fields).
 */
class PromptBuilderValleyMistTest {

    private static final String BLOCK_HEADER = "VALLEY MIST CONDITIONS:";

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private static MistSlot slot(int offset, int vis, double temp, double gap, Integer cloud, Double wind) {
        return new MistSlot(offset, vis, temp - gap, temp, cloud, wind);
    }

    /** A sunrise trend whose tightest gap (0.5 C) falls at T-1h, calmest wind 0.8, mean cloud 1 percent. */
    private static TestAtmosphericData unflagged() {
        MistTrend trend = new MistTrend(List.of(
                slot(-3, 18000, 5.2, 1.2, 0, 1.5),
                slot(-2, 17000, 5.0, 0.9, 0, 1.1),
                slot(-1, 16000, 4.8, 0.5, 0, 0.8),
                slot(0, 15000, 4.7, 0.7, 5, 1.0),
                slot(1, 15000, 4.8, 0.9, 20, 1.2)));
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE).visibility(15000)
                .mistTrend(trend);
    }

    private static AtmosphericData flagged() {
        return unflagged().mistScore(7.6).mistPotential(MistPotential.LIKELY).build();
    }

    @Test
    @DisplayName("a mist-prone site's message carries the block with score, band and the night-side facts")
    void flaggedSite_carriesBlock() {
        String message = promptBuilder.buildUserMessage(flagged());

        assertThat(message).contains(BLOCK_HEADER)
                .contains("Score: 7.6/10 (LIKELY)")
                .contains("Tightest temp-dew gap overnight: 0.5°C at T-1h")
                .contains("Calmest wind overnight: 0.8 m/s")
                .contains("Sky overnight: mean total cloud 1%")
                .contains("read the MIST/VISIBILITY TREND above");
    }

    @Test
    @DisplayName("the block follows the existing trend block and does not print the trend a second time")
    void block_referencesTheTrendInsteadOfDuplicatingIt() {
        String message = promptBuilder.buildUserMessage(flagged());

        assertThat(message.indexOf("MIST/VISIBILITY TREND (T-3h to T+2h):"))
                .isNotNegative()
                .isLessThan(message.indexOf(BLOCK_HEADER));
        assertThat(message.split("MIST/VISIBILITY TREND \\(T-3h to T\\+2h\\):", -1)).hasSize(2);
    }

    @Test
    @DisplayName("the site sentence is the plan's, verbatim, once")
    void siteSentence_isVerbatim() {
        String expected = "This location is a valley-floor / riverside site where mist forms around the camera; "
                + "thin mist with a clear sky above at sunrise is the shot here, and may rate 4–5 with no "
                + "horizon colour at all. Fog under 300 m with no clearing is a white frame: rate 2.";

        String message = promptBuilder.buildUserMessage(flagged());

        assertThat(PromptBuilder.VALLEY_MIST_SITE_SENTENCE).isEqualTo(expected);
        assertThat(message.split(java.util.regex.Pattern.quote(expected), -1)).hasSize(2);
    }

    @Test
    @DisplayName("the echo instruction rides the user message")
    void echoInstruction_isInTheUserMessage() {
        String message = promptBuilder.buildUserMessage(flagged());

        assertThat(message).contains("Include \"mist_potential\": one of NONE, POSSIBLE, LIKELY, FOGBOUND "
                + "in your JSON — your own reading of the mist at this site.");
    }

    @Test
    @DisplayName("an unflagged message has no block and equals the flagged one with the block removed")
    void unflaggedSite_isByteIdentical() {
        String plain = promptBuilder.buildUserMessage(unflagged().build());
        String withBlock = promptBuilder.buildUserMessage(flagged());

        assertThat(plain).doesNotContain(BLOCK_HEADER).doesNotContain("mist_potential");
        int start = withBlock.indexOf("\n" + BLOCK_HEADER);
        int end = withBlock.indexOf(PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION)
                + PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION.length();
        assertThat(withBlock.substring(0, start) + withBlock.substring(end)).isEqualTo(plain);
    }

    @Test
    @DisplayName("the score is truncated to one decimal so it never contradicts its band's cut")
    void score_isTruncatedNotRounded() {
        String possible = promptBuilder.buildUserMessage(
                unflagged().mistScore(5.96).mistPotential(MistPotential.POSSIBLE).build());
        String vetoed = promptBuilder.buildUserMessage(
                unflagged().mistScore(3.9).mistPotential(MistPotential.NONE).build());

        assertThat(possible).contains("Score: 5.9/10 (POSSIBLE)");
        assertThat(vetoed).contains("Score: 3.9/10 (NONE)");
    }

    @Test
    @DisplayName("a band with no score prints the band alone, never 0/10")
    void bandWithoutScore_printsBandOnly() {
        String message = promptBuilder.buildUserMessage(
                unflagged().mistPotential(MistPotential.LIKELY).build());

        assertThat(message).contains("Band: LIKELY").doesNotContain("0.0/10");
    }

    @Test
    @DisplayName("a flagged site with too few night-side slots prints the block without the overnight facts")
    void tooFewNightSlots_omitsOvernightFacts() {
        MistTrend thin = new MistTrend(List.of(slot(0, 15000, 4.7, 0.7, 5, 1.0)));
        String message = promptBuilder.buildUserMessage(TestAtmosphericData.builder()
                .targetType(TargetType.SUNRISE).mistTrend(thin)
                .mistScore(6.5).mistPotential(MistPotential.LIKELY).build());

        assertThat(message).contains(BLOCK_HEADER).contains("Score: 6.5/10 (LIKELY)")
                .doesNotContain("Tightest temp-dew gap");
    }

    @Test
    @DisplayName("a SUNSET block reads the post-event slots, and the earliest slot wins a gap tie")
    void sunset_usesPostEventSlots_earliestTieWins() {
        MistTrend trend = new MistTrend(List.of(
                slot(-1, 16000, 4.8, 0.1, 0, 3.0),
                slot(0, 15000, 4.7, 0.6, 5, 1.0),
                slot(1, 15000, 4.8, 0.4, 5, 0.9),
                slot(2, 15000, 4.9, 0.4, 5, 1.2)));
        String message = promptBuilder.buildUserMessage(TestAtmosphericData.builder()
                .targetType(TargetType.SUNSET).mistTrend(trend)
                .mistScore(6.5).mistPotential(MistPotential.LIKELY).build());

        assertThat(message).contains("Tightest temp-dew gap overnight: 0.4\u00b0C at T+1h")
                .contains("Calmest wind overnight: 0.9 m/s");
    }

    @Test
    @DisplayName("a flagged site the calculator could not score (no band) gets no block")
    void flaggedSiteWithNoBand_getsNoBlock() {
        String message = promptBuilder.buildUserMessage(unflagged().build());

        assertThat(message).doesNotContain(BLOCK_HEADER);
    }

    @Test
    @DisplayName("the SYSTEM prompt is untouched: nothing about the block or the echo lives there")
    void systemPrompt_isUntouched() {
        String system = promptBuilder.getSystemPrompt();

        assertThat(system).doesNotContain("VALLEY MIST").doesNotContain("mist_potential");
        assertThat(system).contains("MIST AND VISIBILITY GUIDANCE");
    }

    @Test
    @DisplayName("the shared output schema declares mist_potential last, as an optional enum")
    void schema_declaresMistPotentialOptionally() throws Exception {
        var mapper = ObjectMappers.jsonMapper();
        JsonNode schema = mapper.readTree(mapper.writeValueAsString(promptBuilder.buildOutputConfig()))
                .path("format").path("schema");

        JsonNode mist = schema.path("properties").path("mist_potential");
        assertThat(mist.path("enum")).extracting(JsonNode::asText)
                .containsExactly("NONE", "POSSIBLE", "LIKELY", "FOGBOUND");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .doesNotContain("mist_potential");
    }

    private static final String GENERIC_CLEARING = "Clearing: read the MIST/VISIBILITY TREND";

    private static AtmosphericData fogbound() {
        return unflagged().mistScore(9.0).mistPotential(MistPotential.FOGBOUND).build();
    }

    @Test
    @DisplayName("a FOGBOUND message carries its own clearing line and site sentence, not the generic ones")
    void fogbound_carriesOwnSentences() {
        String message = promptBuilder.buildUserMessage(fogbound());

        assertThat(message).contains(BLOCK_HEADER).contains("Score: 9.0/10 (FOGBOUND)")
                .contains(PromptBuilder.VALLEY_MIST_FOGBOUND_CLEARING)
                .contains(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE)
                .doesNotContain(PromptBuilder.VALLEY_MIST_SITE_SENTENCE)
                .doesNotContain(GENERIC_CLEARING);
    }

    @Test
    @DisplayName("the echo instruction is still the last line of a FOGBOUND block")
    void fogbound_echoInstructionIsLastLine() {
        String message = promptBuilder.buildUserMessage(fogbound());

        assertThat(message.indexOf(PromptBuilder.VALLEY_MIST_FOGBOUND_CLEARING))
                .isLessThan(message.indexOf(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE));
        assertThat(message.indexOf(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE))
                .isLessThan(message.indexOf(PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION));
    }

    @Test
    @DisplayName("LIKELY, POSSIBLE and NONE messages keep the generic sentence and carry no FOGBOUND text")
    void otherBands_keepGenericSentences() {
        for (MistPotential band : List.of(MistPotential.LIKELY, MistPotential.POSSIBLE, MistPotential.NONE)) {
            String message = promptBuilder.buildUserMessage(
                    unflagged().mistScore(5.0).mistPotential(band).build());

            assertThat(message).as(band.name())
                    .contains(PromptBuilder.VALLEY_MIST_SITE_SENTENCE)
                    .contains(GENERIC_CLEARING)
                    .doesNotContain(PromptBuilder.VALLEY_MIST_FOGBOUND_CLEARING)
                    .doesNotContain(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE);
        }
    }

    @Test
    @DisplayName("a FOGBOUND block with no score (Band: form) also carries the FOGBOUND sentences")
    void fogboundWithoutScore_carriesFogboundSentences() {
        String message = promptBuilder.buildUserMessage(
                unflagged().mistPotential(MistPotential.FOGBOUND).build());

        assertThat(message).contains("Band: FOGBOUND")
                .contains(PromptBuilder.VALLEY_MIST_FOGBOUND_CLEARING)
                .contains(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE)
                .doesNotContain(PromptBuilder.VALLEY_MIST_SITE_SENTENCE);
    }

    @Test
    @DisplayName("the FOGBOUND clearing line and site sentence are the plan's, verbatim")
    void fogboundSentences_areVerbatim() {
        assertThat(PromptBuilder.VALLEY_MIST_FOGBOUND_CLEARING).isEqualTo(
                "Clearing: none in the window. Every later slot in the MIST/VISIBILITY TREND stays under "
                + "300 m, so a small rise inside the fog is not clearing; do not describe the fog as "
                + "lifting or burning off.");
        assertThat(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE).isEqualTo(
                "This location is a valley-floor / riverside site, and it is fogbound: fog under 300 m with "
                + "no clearing is a white frame, not the thin-mist shot. MAXIMUM rating 2; the score above "
                + "measures how sure the fog is, not how good the shot is.");
    }

    @Test
    @DisplayName("the schema carries no FOGBOUND cap; the cap lives in the fogbound user message only")
    void schema_carriesNoFogboundCap() throws Exception {
        var mapper = ObjectMappers.jsonMapper();
        JsonNode props = mapper.readTree(mapper.writeValueAsString(promptBuilder.buildOutputConfig()))
                .path("format").path("schema").path("properties");

        assertThat(props.path("rating").path("description").asText())
                .doesNotContain("FOGBOUND").doesNotContain("MAXIMUM 2")
                .contains("CLOUD APPROACH RISK");
        assertThat(props.path("mist_potential").path("description").asText())
                .doesNotContain("FOGBOUND").doesNotContain("MAXIMUM 2");
        assertThat(PromptBuilder.VALLEY_MIST_FOGBOUND_SITE_SENTENCE).contains("MAXIMUM rating 2");
    }
}
