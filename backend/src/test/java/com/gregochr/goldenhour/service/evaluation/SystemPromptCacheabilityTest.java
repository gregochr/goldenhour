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
 * <p>The forecast prompt clears the floor by roughly 5% (16,193 characters inland, 16,452 coastal,
 * against a ~15,350 character equivalent). Nothing else in the codebase records that margin, so a
 * routine edit that trims a few paragraphs would silently switch caching off across every T+2/T+3
 * evaluation with no failing test and no operational signal. That is what these assertions exist
 * to prevent.
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
                .as("Shortening this prompt below ~15,350 characters drops it under Haiku 4.5's "
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
    @DisplayName("bluebell and woodland prompts are below the HAIKU floor — inert on the far-term path")
    void nichePromptsAreKnownToBeUncacheableOnHaiku() {
        // Scoped to Haiku on purpose, and the scope is the point. These two buckets are NOT
        // Haiku-only: ForecastTaskCollector builds them with the same decision.model() as the sky
        // path, so T+0/T+1 runs them on BATCH_NEAR_TERM — Sonnet by default (V92), whose floor is
        // 1,024 tokens, a quarter of Haiku's. Woodland is comfortably over that and does cache
        // there; bluebell is close enough to the line to be unknowable without measuring. So
        // "their caching is inert" is true of the far-term path only, and an earlier version of
        // this comment said it flatly — which would have invited someone to delete a live
        // cache_control block as dead weight.
        //
        // Not a defect to fix, and deliberately not an aspiration. Padding them to 4,096 tokens to
        // win the far-term path would cost more input than caching could ever recover.
        //
        // This test exists so the state is visible rather than surprising: if either prompt grows
        // past the Haiku floor, caching quietly starts working there and this assertion fails to
        // tell you.
        int bluebell = new BluebellPromptBuilder().getSystemPrompt().length();
        int woodland = new WoodlandPromptBuilder(new WoodlandVerdictEvaluator())
                .getSystemPrompt().length();

        assertThat(bluebell)
                .as("Bluebell prompt has grown past the Haiku cache floor — caching now works on "
                        + "the far-term path too. Update this test and the note in "
                        + "BatchRequestFactory.")
                .isLessThan(PromptBuilder.MIN_CACHEABLE_SYSTEM_PROMPT_CHARS);
        assertThat(woodland)
                .as("Woodland prompt has grown past the Haiku cache floor — caching now works on "
                        + "the far-term path too. Update this test and the note in "
                        + "BatchRequestFactory.")
                .isLessThan(PromptBuilder.MIN_CACHEABLE_SYSTEM_PROMPT_CHARS);
    }
}
