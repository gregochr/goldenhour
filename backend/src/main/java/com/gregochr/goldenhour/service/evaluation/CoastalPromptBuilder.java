package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.AtmosphericData;

/**
 * Extends {@link PromptBuilder} with coastal-specific guidance for storm surge conditions.
 *
 * <p>Used for locations where {@link AtmosphericData#tide()} is non-null. Inland locations use
 * the base {@link PromptBuilder} directly.
 *
 * <p><b>v2.13.2 decomposition.</b> Tide is no longer a rating lever in the prompt: Claude scores
 * the <em>sky alone</em>, and the tide contribution is re-added deterministically by the
 * {@code TideVisitor} at the combine seam (averaged in by {@code RatingCombiner}). The previous
 * "tide boost / king-tide" instructions and the {@code Tide:} data line have therefore been
 * removed. The storm-surge block remains — it is a foreground/safety concern, not a tide-rating
 * lever — appended via the {@code buildUserMessage} surge overload.
 *
 * <p>Prompt caching: all coastal locations share the same system prompt prefix (base + coastal
 * surge section), maximising cache hit rate.
 */
public class CoastalPromptBuilder extends PromptBuilder {

    /**
     * Coastal storm-surge guidance appended to the base system prompt.
     *
     * <p>Sky scoring rules are unchanged from inland — a good sky is scored identically. The only
     * coastal-specific addition is how to read a storm-surge forecast when one is present.
     */
    static final String COASTAL_SYSTEM_PROMPT_SUFFIX =
            "COASTAL CONDITIONS GUIDANCE:\n"
            + "Score coastal locations on sky conditions, exactly as inland. When a STORM SURGE "
            + "FORECAST block is present, the tide will run higher than predicted — a more "
            + "dramatic foreground, but note safety implications at exposed locations.\n\n";

    /**
     * Returns the base system prompt with the coastal storm-surge guidance appended.
     *
     * @return the full system prompt for coastal location evaluations
     */
    @Override
    public String getSystemPrompt() {
        return super.getSystemPrompt() + COASTAL_SYSTEM_PROMPT_SUFFIX;
    }
}
