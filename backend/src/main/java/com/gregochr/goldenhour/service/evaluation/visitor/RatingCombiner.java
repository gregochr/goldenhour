package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalInt;

/**
 * Combines the scores of the {@link Visitor}s that apply to a location into a single star
 * rating by plain averaging.
 *
 * <p><b>Plain average, no weakest-link, no veto.</b> The combined rating is the arithmetic
 * mean of the scores of the visitors whose {@link Visitor#appliesTo} is {@code true} and which
 * returned a value, rounded to the nearest integer. A future {@code 5}-tide + {@code 1}-sky
 * would average to {@code 3} — intentionally.
 *
 * <p><b>v2.13.1 reality.</b> Only {@link SkyVisitor} exists and it always applies, so the
 * "average" is over a single value and equals that value exactly — the combined rating is
 * byte-equivalent to the rating the pre-visitor pipeline produced. The averaging structure is
 * what later passes (tide and beyond) build on; the rounding only becomes load-bearing once a
 * second visitor can contribute, at which point the rounding rule must be confirmed against the
 * product decision (today there is no averaging, so there is nothing to match).
 *
 * <p><b>Empty case.</b> If no visitor applies, or every applied visitor returned
 * {@link OptionalInt#empty()}, the combined rating is {@code null} — which preserves today's
 * behaviour exactly: a Claude response that omitted {@code rating} persists as {@code null},
 * not as a fabricated star value. Because {@link SkyVisitor} always applies, the only way to
 * reach {@code null} is a genuinely absent sky rating.
 */
@Component
public class RatingCombiner {

    private final List<Visitor> visitors;

    /**
     * Constructs the combiner with all registered visitors.
     *
     * @param visitors every {@link Visitor} bean (v2.13.1: just {@link SkyVisitor})
     */
    public RatingCombiner(List<Visitor> visitors) {
        this.visitors = List.copyOf(visitors);
    }

    /**
     * Combines the applicable visitors' scores for a location into a star rating.
     *
     * @param location   the location under evaluation
     * @param evaluation the already-produced Claude evaluation the visitors read
     * @return the averaged 1–5 rating, or {@code null} when no applicable visitor produced a
     *         score (preserving today's null-rating behaviour)
     */
    public Integer combine(LocationEntity location, SunsetEvaluation evaluation) {
        int[] scores = visitors.stream()
                .filter(v -> v.appliesTo(location))
                .map(v -> v.evaluate(location, evaluation))
                .filter(OptionalInt::isPresent)
                .mapToInt(OptionalInt::getAsInt)
                .toArray();
        if (scores.length == 0) {
            return null;
        }
        double average = java.util.Arrays.stream(scores).average().orElseThrow();
        return (int) Math.round(average);
    }

    /**
     * Returns the visitors that apply to the given location. Exposed for tests that assert the
     * applied set (e.g. that exactly one visitor applies to an inland location today).
     *
     * @param location the location to test
     * @return the applicable visitors, in registration order
     */
    List<Visitor> appliedVisitors(LocationEntity location) {
        return visitors.stream().filter(v -> v.appliesTo(location)).toList();
    }
}
