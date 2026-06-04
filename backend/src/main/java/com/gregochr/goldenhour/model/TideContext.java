package com.gregochr.goldenhour.model;

/**
 * The tide facts a {@code TideVisitor} needs to score a coastal location, re-derived at the
 * post-batch combine seam.
 *
 * <p>Carries the {@link TideSnapshot} (whose {@code tideAligned} flag is the <em>tight</em>
 * golden/blue-hour alignment, exactly as the forecast prompt pipeline computed it) plus a
 * second, <em>widened</em> alignment flag.
 *
 * <p><b>Why the widened flag lives here rather than on {@link TideSnapshot}.</b> The alignment
 * window is consumed at {@code TideData} build time and baked into the snapshot's tide state, so
 * a snapshot alone cannot answer "would this have aligned within a window widened by 60 minutes
 * on each edge?". That second question is answered by re-running the same
 * {@code TideService.calculateTideAligned} over tide data derived with the window widened by
 * {@code ForecastDataAugmentor.WIDENED_ALIGNMENT_EXTENSION_MINUTES}. Computing it during
 * derivation (where the window width is known) and carrying it here keeps the widening a single,
 * additive extension of the existing rule — and avoids adding a field to the heavily-constructed
 * {@link TideSnapshot} record.
 *
 * @param snapshot       the tide snapshot for the solar event (tight alignment baked in)
 * @param widenedAligned true if the tide aligns within the existing window widened by 60 minutes
 *                       beyond each edge — the "imperfect tide, but a great sky could still work
 *                       an average foreground" band. Implies, but is not implied by, tight
 *                       alignment (the widened window is a superset of the tight window).
 */
public record TideContext(TideSnapshot snapshot, boolean widenedAligned) {
}
