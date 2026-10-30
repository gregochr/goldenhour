package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Combines the scores of the {@link Visitor}s that apply to a location into a single star
 * rating by plain averaging.
 *
 * <p><b>Plain average, no weakest-link, no veto.</b> The combined rating is the arithmetic
 * mean of the scores of the visitors whose {@link Visitor#appliesTo} is {@code true} and which
 * returned a value, rounded to the nearest integer. A {@code 5}-tide + {@code 1}-sky pair
 * averages to {@code 3} before the lift cap below applies (and is then capped to {@code 2}).
 *
 * <p><b>v2.13.2 reality.</b> Two visitors exist: {@link SkyVisitor} (always applies) and
 * {@code TideVisitor} (applies to tidal locations). Inland: the average is over the single sky
 * score and equals it exactly. Coastal: the sky and tide scores are averaged, half-up — a
 * 3★ sky + 1★ misaligned tide combines to 2★, a 3★ sky + 4★ aligned tide to 4★.
 *
 * <p><b>One-star tide lift cap.</b> When the rating peers are exactly a sky score and a tide
 * score, the combined rating may be at most one star above the sky score
 * ({@code min(rounded, sky + 1)}): the tide makes the foreground, not the colour, so a perfectly
 * timed tide must not rescue a weak sky by two stars (a 2★ sky + 5 tide is 3★, not 4★; a 1★ sky
 * + 5 tide is 2★, not 3★). The cap is one-sided: a tide dragging the rating <em>down</em> is
 * unchanged. It does not apply to any other peer set (woodland or open-fell bluebell, tide with
 * bluebell and no sky, inland sky-only), and it does not apply to
 * {@code BriefingEvaluationService.recombineBluebell}, where bluebell is a peer by design.
 *
 * <p><b>No gate, no weakest-link, no message/state.</b> Apart from that cap the combiner is plain
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

    /**
     * The most stars the tide may lift the combined rating above the sky score at a coastal
     * location (sky + tide peers only). The penalty side is not limited.
     */
    static final int MAX_TIDE_LIFT = 1;

    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

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
     * The combiner's full result: the averaged star {@code rating} (the serving-path product,
     * unchanged) plus the per-visitor {@link ComponentScore}s it was averaged from. The
     * components are exposed for the Pass 2 dual-write to {@code forecast_score}; callers that
     * want only the headline rating read {@link #rating()} and get the identical value the
     * combiner has always produced.
     *
     * @param rating     the averaged 1–5 rating, or {@code null} when no applicable visitor
     *                   produced a score (preserving today's null-rating behaviour)
     * @param components the applied visitors' component scores, in registration order; empty
     *                   when {@code rating} is {@code null}
     */
    public record CombinedRating(Integer rating, List<ComponentScore> components) {
    }

    /**
     * Combines the applicable visitors' scores for a location into a star rating, exposing the
     * component scores it averaged.
     *
     * <p>The {@code rating} is the half-up rounded mean of the selected peer scores, except that
     * a sky + tide pair is capped at {@link #MAX_TIDE_LIFT} stars above the sky score. The
     * {@link CombinedRating#components()} list records every applied component regardless.
     *
     * @param location the location under evaluation
     * @param context  the inputs the visitors read (sky evaluation + re-derived tide context)
     * @return the combined rating and its component scores
     */
    public CombinedRating combine(LocationEntity location, VisitorContext context) {
        List<ComponentScore> components = visitors.stream()
                .filter(v -> v.appliesTo(location))
                .map(v -> toComponent(location, context, v))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (components.isEmpty()) {
            return new CombinedRating(null, List.of());
        }
        // All applied components are always RECORDED (the dual-write persists every one). Only the
        // RATING is computed from a selected peer subset — for the bluebell exposure rule below.
        List<ComponentScore> peers = selectRatingPeers(location, components);
        double average = peers.stream()
                .mapToInt(ComponentScore::score)
                .average()
                .orElseThrow();
        int rating = (int) Math.round(average);
        return new CombinedRating(capTideLift(peers, rating), components);
    }

    /**
     * Caps the rounded rating at {@link #MAX_TIDE_LIFT} above the sky score, only when the peers
     * are exactly one SKY and one TIDAL component and the sky score is a valid 1-5 rating.
     */
    private static int capTideLift(List<ComponentScore> peers, int rating) {
        if (peers.size() != 2) {
            return rating;
        }
        OptionalInt sky = scoreOf(peers, ForecastType.SKY);
        if (sky.isEmpty() || scoreOf(peers, ForecastType.TIDAL).isEmpty()) {
            return rating;
        }
        int skyScore = sky.getAsInt();
        if (skyScore < MIN_SCORE || skyScore > MAX_SCORE) {
            return rating;
        }
        return Math.min(rating, skyScore + MAX_TIDE_LIFT);
    }

    private static OptionalInt scoreOf(List<ComponentScore> peers, ForecastType type) {
        return peers.stream()
                .filter(c -> c.type() == type)
                .mapToInt(ComponentScore::score)
                .findFirst();
    }

    /**
     * Selects which component scores feed the headline rating (the OQ3 exposure-differentiated
     * bluebell rule). The {@code components} list itself is unaffected — every applied component
     * is still recorded; this only governs the average.
     *
     * <ul>
     *   <li><b>In-season WOODLAND bluebell site</b> (a BLUEBELL component is present and the
     *       location's exposure is {@link BluebellExposure#WOODLAND}): the bluebell score IS the
     *       rating — the sky is NOT a peer. Perfect woodland bluebell light is calm bright
     *       overcast, which scores ~2-3 as a sky; averaging would cap woodland sites at ~4 on
     *       their best mornings.</li>
     *   <li><b>Everything else</b> — inland sky-only, coastal sky+tide, and OPEN_FELL bluebell
     *       (where bluebell is a peer averaged with the sky, because golden light flatters fell
     *       and flowers alike): every applied component is a peer. This is the unchanged
     *       pre-Pass-3 behaviour, so no existing rating moves.</li>
     * </ul>
     *
     * @param location   the location under evaluation
     * @param components the applied component scores (non-empty)
     * @return the subset of components that determine the rating
     */
    private List<ComponentScore> selectRatingPeers(LocationEntity location,
            List<ComponentScore> components) {
        boolean hasBluebell = components.stream()
                .anyMatch(c -> c.type() == ForecastType.BLUEBELL);
        if (hasBluebell && location.getBluebellExposure() == BluebellExposure.WOODLAND) {
            return components.stream()
                    .filter(c -> c.type() == ForecastType.BLUEBELL)
                    .toList();
        }
        return components;
    }

    /**
     * Resolves one applied visitor's component score, pairing its 1–5 value with its type and
     * authored clause. Empty when the visitor abstained ({@link OptionalInt#empty()}).
     */
    private Optional<ComponentScore> toComponent(LocationEntity location, VisitorContext context,
            Visitor visitor) {
        OptionalInt score = visitor.evaluate(location, context);
        if (score.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ComponentScore(
                visitor.type(), score.getAsInt(),
                visitor.summary(location, context).orElse(null)));
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
