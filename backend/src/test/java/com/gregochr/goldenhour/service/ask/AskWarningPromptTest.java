package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

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

    @Test
    @DisplayName("golden: with no warning in force the prompt is byte for byte the one before W6")
    void noWarnings_isByteIdentical() {
        for (boolean hasUser : new boolean[] {true, false}) {
            for (boolean hasThread : new boolean[] {true, false}) {
                AskPromptBuilder.SystemPrompt before = builder.systemPromptBlocks(TODAY, AskScope.ALL, null, null,
                        null, hasUser, hasThread, List.of());

                AskPromptBuilder.SystemPrompt now = blocks(hasUser, hasThread, false);

                assertThat(now).isEqualTo(before);
                assertThat(now.text()).doesNotContain("Met Office");
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
