package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The bluebell photogenic evaluator — the third visitor (Pass 3).
 *
 * <p>Scores a bluebell display as a foreground subject, separately from the sky, from the
 * dedicated bluebell prompt's result ({@code BluebellEvaluation}) carried on
 * {@link VisitorContext#bluebell()}. Where {@code TideVisitor} re-derives its score
 * deterministically, this visitor re-expresses a Claude bluebell evaluation — the bluebell
 * judgement is genuinely nuanced (canopy shelter, long-exposure sensitivity, mist-and-low-sun),
 * so it earns its own prompt.
 *
 * <h2>Applicability</h2>
 * Applies to bluebell sites — a location whose types include {@link LocationType#BLUEBELL} and
 * which carries a {@code bluebellExposure}. That is a property of the location, not the date.
 * "Is it bluebell <em>season</em>" is handled in {@link #evaluate}: out of season the orchestration
 * runs no bluebell prompt, so {@link VisitorContext#bluebell()} is {@code null} and this visitor
 * abstains — exactly the data-gap abstain {@code TideVisitor} uses, never a penalty.
 *
 * <h2>Rating role (combiner, not here)</h2>
 * The exposure-differentiated rating role — WOODLAND: bluebell IS the rating (the sky is not a
 * peer, because perfect woodland bluebell light scores poorly as a sky); OPEN_FELL: bluebell is a
 * peer averaged with the sky — lives in {@link RatingCombiner}. This visitor only produces the
 * BLUEBELL component (1-5 score + the prompt's prose); the combiner decides how it folds in.
 */
@Component
public class BluebellVisitor implements Visitor {

    @Override
    public boolean appliesTo(LocationEntity location) {
        return location.getLocationType() != null
                && location.getLocationType().contains(LocationType.BLUEBELL)
                && location.getBluebellExposure() != null;
    }

    @Override
    public OptionalInt evaluate(LocationEntity location,
            VisitorContext context) {
        BluebellEvaluation bluebell = context.bluebell();
        if (bluebell == null || bluebell.rating() == null) {
            // No bluebell evaluation (out of season, or the slot was not bluebell-scored): abstain.
            return OptionalInt.empty();
        }
        return OptionalInt.of(bluebell.rating());
    }

    @Override
    public ForecastType type() {
        return ForecastType.BLUEBELL;
    }

    /**
     * Re-exposes the bluebell prompt's one-sentence summary as the BLUEBELL component's clause, so
     * the persisted component row carries its own narrative (consistent with Pass 2's
     * component-summary model). Only queried when {@link #evaluate} returned a score.
     *
     * @param location the location under evaluation
     * @param context  the visitor inputs
     * @return the bluebell summary, or empty when absent
     */
    @Override
    public Optional<String> summary(LocationEntity location,
            VisitorContext context) {
        return context.bluebell() == null
                ? Optional.empty()
                : Optional.ofNullable(context.bluebell().summary());
    }
}
