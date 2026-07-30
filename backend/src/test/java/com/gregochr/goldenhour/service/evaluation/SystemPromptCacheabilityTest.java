package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one number that decides whether batch prompt caching works at all.
 *
 * <p>{@link BatchRequestFactory} attaches {@code cache_control} to the system block of every
 * forecast request, and the far-term bucket runs on Haiku ({@code BATCH_FAR_TERM}, V92). Haiku
 * 4.5's minimum cacheable prefix is <b>4,096 tokens</b> — and below that threshold the API does not
 * error, warn, or degrade visibly. It simply returns {@code cache_creation_input_tokens: 0} and
 * charges the full input rate forever.
 *
 * <p>The forecast prompt clears the floor by roughly 5% (16,318 characters against a ~15,470
 * character equivalent). Nothing else in the codebase records that margin, so a routine edit that
 * trims a few paragraphs would silently switch caching off across every T+2/T+3 evaluation with no
 * failing test and no operational signal. That is what these assertions exist to prevent.
 *
 * <p>The character count is a <b>proxy</b>, not a token oracle — see
 * {@link PromptBuilder#MIN_CACHEABLE_SYSTEM_PROMPT_CHARS} for the derivation and its limits.
 */
class SystemPromptCacheabilityTest {

    @Test
    @DisplayName("the inland forecast prompt stays above Haiku's minimum cacheable prefix")
    void inlandPromptClearsTheHaikuCacheFloor() {
        String prompt = new PromptBuilder().getSystemPrompt();

        assertThat(prompt.length())
                .as("Shortening this prompt below ~15,470 characters drops it under Haiku 4.5's "
                        + "4,096-token cacheable minimum. Caching then fails SILENTLY — no error, "
                        + "no warning, just full input rate on every far-term evaluation. "
                        + "Re-measure with messages.count_tokens against claude-haiku-4-5 before "
                        + "lowering this bound.")
                .isGreaterThanOrEqualTo(PromptBuilder.MIN_CACHEABLE_SYSTEM_PROMPT_CHARS);
    }

    @Test
    @DisplayName("the coastal forecast prompt stays above the floor too")
    void coastalPromptClearsTheHaikuCacheFloor() {
        // Coastal is the inland prompt plus a tide suffix, so it can only fail by inheriting an
        // inland regression — asserted separately because it is a distinct batch bucket with its
        // own cache entry, and a future refactor could give it its own base.
        String prompt = new CoastalPromptBuilder().getSystemPrompt();

        assertThat(prompt.length())
                .isGreaterThanOrEqualTo(PromptBuilder.MIN_CACHEABLE_SYSTEM_PROMPT_CHARS);
    }

    @Test
    @DisplayName("bluebell and woodland prompts are knowingly below the floor — their caching is inert")
    void nichePromptsAreKnownToBeUncacheableOnHaiku() {
        // Not a defect to fix, and deliberately not an aspiration. Both prompts are roughly a
        // quarter of the forecast prompt's length, so the cache_control blocks BatchRequestFactory
        // attaches to them (buildBluebellRequest, buildWoodlandRequest) do nothing on Haiku.
        //
        // Padding them to 4,096 tokens would cost more input than caching could ever recover, and
        // routing those buckets to Sonnet to get its lower 1,024-token floor would raise the rate
        // to save on the prefix. Both directions lose.
        //
        // This test exists so the state is visible rather than surprising: if either prompt grows
        // past the floor, caching quietly starts working and this assertion fails to tell you.
        int bluebell = new BluebellPromptBuilder().getSystemPrompt().length();
        int woodland = new WoodlandPromptBuilder(new WoodlandVerdictEvaluator())
                .getSystemPrompt().length();

        assertThat(bluebell)
                .as("Bluebell prompt has grown past the Haiku cache floor — caching now works for "
                        + "that bucket. Update this test and the note in BatchRequestFactory.")
                .isLessThan(PromptBuilder.MIN_CACHEABLE_SYSTEM_PROMPT_CHARS);
        assertThat(woodland)
                .as("Woodland prompt has grown past the Haiku cache floor — caching now works for "
                        + "that bucket. Update this test and the note in BatchRequestFactory.")
                .isLessThan(PromptBuilder.MIN_CACHEABLE_SYSTEM_PROMPT_CHARS);
    }
}
