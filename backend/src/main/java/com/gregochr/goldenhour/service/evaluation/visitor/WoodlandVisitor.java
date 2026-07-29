package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.WoodlandEvaluation;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Contributes the WOODLAND component for a location under canopy.
 *
 * <p>Applies on {@link LocationEntity#isWoodlandOnly()} rather than on merely carrying
 * {@code WOODLAND}: a site with an open aspect as well as trees still has a sky worth scoring, and
 * its rating should not be decided by the canopy alone.
 *
 * <p>Abstains whenever the context carries no woodland evaluation — which is every slot in
 * bluebell season, where the same location is scored by the bluebell prompt instead. The two are
 * mutually exclusive by construction in {@code ForecastTaskCollector}, so at most one of this
 * visitor and {@code BluebellVisitor} ever produces a component for a given slot.
 */
@Component
public class WoodlandVisitor implements Visitor {

    @Override
    public boolean appliesTo(LocationEntity location) {
        return location.isWoodlandOnly();
    }

    @Override
    public OptionalInt evaluate(LocationEntity location, VisitorContext context) {
        WoodlandEvaluation woodland = context.woodland();
        if (woodland == null || woodland.rating() == null) {
            // In bluebell season, or the slot was not woodland-scored: abstain.
            return OptionalInt.empty();
        }
        return OptionalInt.of(woodland.rating());
    }

    @Override
    public ForecastType type() {
        return ForecastType.WOODLAND;
    }

    /**
     * Re-exposes the woodland prompt's one-sentence summary as the WOODLAND component's clause, so
     * the persisted component row carries its own narrative. Only queried when {@link #evaluate}
     * returned a score.
     *
     * @param location the location under evaluation
     * @param context  the visitor inputs
     * @return the woodland summary, or empty when absent
     */
    @Override
    public Optional<String> summary(LocationEntity location, VisitorContext context) {
        return context.woodland() == null
                ? Optional.empty()
                : Optional.ofNullable(context.woodland().summary());
    }
}
