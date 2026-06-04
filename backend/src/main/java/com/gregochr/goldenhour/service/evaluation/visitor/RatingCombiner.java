package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.LocationEntity;
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
 * <p><b>v2.13.2 reality.</b> Two visitors exist: {@link SkyVisitor} (always applies) and
 * {@code TideVisitor} (applies to tidal locations). Inland: the average is over the single sky
 * score and equals it exactly. Coastal: the sky and tide scores are averaged, half-up — a
 * 3★ sky + 1★ misaligned tide combines to 2★, a 3★ sky + 4★ aligned tide to 4★.
 *
 * <p><b>No gate, no cap, no weakest-link, no message/state.</b> The combiner is plain
 * arithmetic. The result handler — not this class — owns the sky-not-forecast substitution and
 * the triage state. Crucially, the handler branches out on a sky-empty location <em>before</em>
 * calling the combiner, so the combiner never runs with an absent sky score; it therefore needs
 * no "tide-alone forbidden" rule (a coastal sky-empty location can never reach here and average
 * tide alone).
 *
 * <p><b>Empty case.</b> If no visitor applies, or every applied visitor returned
 * {@link OptionalInt#empty()}, the combined rating is {@code null}. Because {@link SkyVisitor}
 * always applies and the handler only calls combine with a present sky rating, in practice the
 * null path is unreachable from the forecast handler — it remains as a defensive contract.
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
     * @param location the location under evaluation
     * @param context  the inputs the visitors read (sky evaluation + re-derived tide context)
     * @return the averaged 1–5 rating, or {@code null} when no applicable visitor produced a
     *         score (preserving today's null-rating behaviour)
     */
    public Integer combine(LocationEntity location, VisitorContext context) {
        int[] scores = visitors.stream()
                .filter(v -> v.appliesTo(location))
                .map(v -> v.evaluate(location, context))
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
