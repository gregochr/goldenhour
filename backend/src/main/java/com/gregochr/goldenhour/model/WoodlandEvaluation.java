package com.gregochr.goldenhour.model;

/**
 * A woodland-prompt evaluation: the year-round conditions score for a location under canopy.
 *
 * <p>Structurally identical to {@link BluebellEvaluation} — both prompts return a 1–5 rating, a
 * one-sentence summary and an optional headline — but deliberately a separate type. The two are
 * different <em>subjects</em>, and in production they overlap completely: every one of the
 * canopy sites is also typed {@code BLUEBELL}, so a shared carrier would let a woodland response
 * be recorded as a {@code BLUEBELL} component by {@code BluebellVisitor}, whose {@code appliesTo}
 * matches those very locations. The rating would look right and the provenance would be a lie,
 * which is exactly the conflation V132 and #347 existed to undo.
 *
 * @param rating   1–5 woodland conditions rating, or null when the response omitted it
 * @param summary  one-sentence user-facing summary
 * @param headline optional short card header in Claude's voice
 */
public record WoodlandEvaluation(Integer rating, String summary, String headline) {
}
