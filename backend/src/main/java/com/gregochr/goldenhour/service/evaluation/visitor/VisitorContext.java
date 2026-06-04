package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;

/**
 * The inputs a {@link Visitor} reads to produce its score, threaded through
 * {@link RatingCombiner#combine}.
 *
 * <p>Introduced in v2.13.2 when a second visitor ({@code TideVisitor}) joined {@code SkyVisitor}.
 * Each visitor reads only the slice it needs: {@code SkyVisitor} reads {@link #evaluation()};
 * {@code TideVisitor} reads {@link #tide()}.
 *
 * @param evaluation the already-produced Claude evaluation (the sky result)
 * @param tide       the re-derived tide context for a coastal location, or {@code null} when the
 *                   location is inland or its tide could not be derived (a data gap). A
 *                   {@code null} here means a tide visitor abstains — it does not penalise.
 */
public record VisitorContext(SunsetEvaluation evaluation, TideContext tide) {
}
