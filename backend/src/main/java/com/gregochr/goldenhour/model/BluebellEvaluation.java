package com.gregochr.goldenhour.model;

/**
 * Claude's evaluation of bluebell-photography conditions for a single in-season slot.
 *
 * <p>The parsed response of the dedicated bluebell prompt ({@code BluebellPromptBuilder}),
 * introduced in Pass 3 of the forecast-score re-architecture. Deliberately tiny: the bluebell
 * prompt asks one focused question and returns a 1–5 rating, a one-sentence summary, and an
 * optional headline — none of the sky sub-scores apply, so this is a distinct type from
 * {@link SunsetEvaluation} rather than a reuse of it.
 *
 * <p><b>Phenology caveat.</b> The {@code rating} scores conditions GIVEN ASSUMED BLOOM — it is
 * not a statement that the flowers are out. The {@code summary} hedges bloom accordingly. See
 * {@code BluebellPromptBuilder} for the full caveat.
 *
 * @param rating   1–5 bluebell-conditions rating (assuming the display is in flower)
 * @param summary  one-sentence plain-English explanation, bloom-hedged
 * @param headline optional 4–9 word card header in Claude's voice, or null when omitted
 */
public record BluebellEvaluation(Integer rating, String summary, String headline) {
}
