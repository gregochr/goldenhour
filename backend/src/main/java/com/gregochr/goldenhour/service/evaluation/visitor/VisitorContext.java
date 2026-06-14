package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;

/**
 * The inputs a {@link Visitor} reads to produce its score, threaded through
 * {@link RatingCombiner#combine}.
 *
 * <p>Introduced in v2.13.2 when a second visitor ({@code TideVisitor}) joined {@code SkyVisitor}.
 * Each visitor reads only the slice it needs: {@code SkyVisitor} reads {@link #evaluation()};
 * {@code TideVisitor} reads {@link #tide()}; {@code BluebellVisitor} reads {@link #bluebell()}.
 *
 * <p><b>Pass 3.</b> The bluebell slice arrives via its own dedicated prompt. Crucially the sky
 * {@link #evaluation()} may now be {@code null}: an in-season WOODLAND bluebell site is evaluated
 * by the bluebell prompt ALONE (no sky call), so there is no {@link SunsetEvaluation} to carry.
 * Visitors that read the sky slice must tolerate a {@code null} evaluation and abstain.
 *
 * @param evaluation the already-produced Claude sky evaluation, or {@code null} when the slot was
 *                   not scored for sky (an in-season WOODLAND bluebell-only evaluation)
 * @param tide       the re-derived tide context for a coastal location, or {@code null} when the
 *                   location is inland or its tide could not be derived (a data gap). A
 *                   {@code null} here means a tide visitor abstains — it does not penalise.
 * @param bluebell   the bluebell evaluation for an in-season bluebell site, or {@code null} when
 *                   the location is not a bluebell site or is out of season. A {@code null} means
 *                   the bluebell visitor abstains.
 */
public record VisitorContext(SunsetEvaluation evaluation, TideContext tide,
                             BluebellEvaluation bluebell) {

    /**
     * Convenience constructor for the sky+tide case (no bluebell slice) — the shape every
     * pre-Pass-3 caller and test uses.
     *
     * @param evaluation the sky evaluation (may be null)
     * @param tide       the re-derived tide context, or null
     */
    public VisitorContext(SunsetEvaluation evaluation, TideContext tide) {
        this(evaluation, tide, null);
    }
}
