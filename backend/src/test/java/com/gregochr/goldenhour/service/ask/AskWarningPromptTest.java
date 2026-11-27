package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The warning rules in Ask's system prompt (weather-warnings-plan.md section 14, W6): added to the per-question
 * tail of a typed conversation only while a warning is in force, so the cached static prefix, the Ready prompt and
 * every prompt with nothing to say are byte for byte what they were.
 */
class AskWarningPromptTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private final AskPromptBuilder builder = new AskPromptBuilder();

    private AskPromptBuilder.SystemPrompt blocks(boolean hasUser, boolean hasThread, boolean warnings) {
        return builder.systemPromptBlocks(TODAY, AskScope.ALL, null, null, null, hasUser, hasThread, List.of(),
                warnings);
    }

    /**
     * Length and digest of the whole prompt as it was before W6 (origin/main's builder, which the false path must
     * reproduce exactly), for 2026-10-05, every region, no window or night, keyed by hasUser then hasThread. Pinned,
     * not derived from the code under test, so a change to the false path fails here. The two typed (hasUser) entries
     * were re-pinned for find-and-favourites F4b (two sentences in {@code WITH_USER}) and again when the
     * colour score was renamed from Fiery Sky to Red Sky. All four were re-pinned for the Ask regression triage
     * (B1 adds a clause to {@code RULES}; B3 and B5 reword {@code WITH_USER}; B4 adds a sentence for the typed prompt).
     */
    private static final Map<String, String> PRE_W6 = Map.of(
            "true,true", "13934:f698da223fd58674e448bc48723184976abf4b523d55d59640447c3c2cfd3bfd",
            "true,false", "12663:c30b4728e9b99cc6015ca01182242716961bdf34512a0e5cac4c6db63bdf507f",
            "false,true", "7401:7b322ac9d34256cc8f8e5514e115346007db7bafcce181b83b472c78c4478b5c",
            "false,false", "6130:4740fd8326744a5c73898241cad706b494f89c28b4cc84772551c4285c9a37a2");

    private static String fingerprint(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return text.length() + ":" + HexFormat.of().formatHex(digest);
    }

    @Test
    @DisplayName("golden: with no warning in force the prompt is byte for byte the pinned one from before W6")
    void noWarnings_isByteIdentical() throws Exception {
        for (boolean hasUser : new boolean[] {true, false}) {
            for (boolean hasThread : new boolean[] {true, false}) {
                String text = blocks(hasUser, hasThread, false).text();

                assertThat(fingerprint(text)).as("hasUser=%s hasThread=%s", hasUser, hasThread)
                        .isEqualTo(PRE_W6.get(hasUser + "," + hasThread));
                assertThat(text).doesNotContain("Met Office");
            }
        }
    }

    @Test
    @DisplayName("a typed conversation with a warning in force gains the rules in its per-question tail; the cached "
            + "static part is unchanged")
    void typedWithWarnings_gainsTheRulesInTheTail() {
        AskPromptBuilder.SystemPrompt plain = blocks(true, false, false);

        AskPromptBuilder.SystemPrompt with = blocks(true, false, true);

        assertThat(with.staticPart()).isEqualTo(plain.staticPart());
        assertThat(with.dynamicPart()).isEqualTo(plain.dynamicPart() + "\n" + AskPromptBuilder.WARNING_RULES);
        assertThat(with.staticPart()).doesNotContain("Met Office");
    }

    @Test
    @DisplayName("a Ready (user-less) conversation never gains the warning rules, whatever the snapshot holds")
    void ready_neverGainsTheRules() {
        assertThat(blocks(false, false, true)).isEqualTo(blocks(false, false, false));
    }

    @Test
    @DisplayName("the warning rules come before the thread rules, so the thread rules still end the prompt")
    void warningRulesPrecedeTheThreadRules() {
        String tail = blocks(true, true, true).dynamicPart();

        assertThat(tail).contains(AskPromptBuilder.WARNING_RULES).endsWith(AskPromptBuilder.THREAD_RULES);
        assertThat(tail.indexOf(AskPromptBuilder.WARNING_RULES))
                .isLessThan(tail.indexOf(AskPromptBuilder.THREAD_RULES));
    }

    @Test
    @DisplayName("the rules forbid softening, implying, an all-clear and a rating change, and ask for the Met Office "
            + "and the out-of-date line; the attribution is the server's, not the model's")
    void theRulesSayWhatTheStyleGuideRequires() {
        String rules = String.join(" ", AskPromptBuilder.WARNING_RULES.split("\\s+"));

        assertThat(rules)
                .contains("only source of anything you say about a warning")
                .contains("in the Met Office's own words")
                .contains("say it is from the Met Office")
                .contains("Never soften, upgrade, shorten or paraphrase a level")
                .contains("never say a warning changes a rating")
                .contains("never treat a missing warning as an all-clear")
                .contains("say the warnings may be out of date")
                .contains("mention one only if this conversation's tool result lists it")
                .contains("the attribution and link are added for you");
    }

    @Test
    @DisplayName("the rules add a bounded amount to the prompt (measured: about 1,090 characters, roughly 270 tokens)")
    void theRulesAreSmall() {
        assertThat(AskPromptBuilder.WARNING_RULES.length()).isLessThan(1_200);
    }
}
