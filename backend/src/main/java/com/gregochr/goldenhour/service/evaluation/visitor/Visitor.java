package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.LocationEntity;

import java.util.OptionalInt;

/**
 * A photogenic evaluator that contributes a star score to a location's overall rating.
 *
 * <p>This is the foundation interface for the v2.13 visitor architecture. Each visitor
 * answers two questions independently: <em>does it apply to this location at all</em>
 * ({@link #appliesTo}), and if so, <em>what is its 1–5 contribution</em>
 * ({@link #evaluate}). A {@link RatingCombiner} averages the contributions of the visitors
 * that apply. No visitor depends on another's output.
 *
 * <h2>Design corrections carried from the investigation (read before extending)</h2>
 * <ul>
 *   <li><b>No SCORE/BLOCK result and no weakest-link combiner.</b> The combiner is a plain
 *       average of the applied visitors' scores. A bad condition is just a low score that
 *       averages in — there is no veto.</li>
 *   <li><b>Triage is not on this interface.</b> The interface is {@link #appliesTo} +
 *       {@link #evaluate} only. In v2.13.1 triage remains the existing pre-visitor gate
 *       (it produces the canned {@code rating=null} + {@code triageReason} entity before any
 *       visitor runs); a triaged location never reaches a visitor. v2.13.2 may relocate
 *       triage into the Claude-backed visitor as a private collaborator.</li>
 * </ul>
 *
 * <h2>Two deviations from the original v2.13.1 prompt sketch (code-grounded)</h2>
 * <ol>
 *   <li><b>{@link #evaluate} returns {@link OptionalInt}, not a bare {@code int}.</b> The
 *       A.0 ground-fact check found the Claude colour call returns {@code rating} as a
 *       <em>nullable</em> field (Claude may omit it). {@link OptionalInt#empty()} represents
 *       "this visitor produced no number for this evaluation", which the combiner skips —
 *       preserving today's behaviour where a missing rating persists as {@code null} rather
 *       than a fabricated star value. This is absence, not a veto.</li>
 *   <li><b>The second argument is the already-produced {@link SunsetEvaluation}, not raw
 *       forecast data.</b> v2.13.1 is a relocate-only refactor: the rating is already
 *       computed by the existing asynchronous (batch) and synchronous Claude machinery, so a
 *       visitor here <em>re-expresses</em> that result rather than recomputing it. Recomputing
 *       from forecast inputs would require re-calling Claude, which is impossible on the
 *       asynchronous batch path. When v2.13.2 gives {@code SkyVisitor} its own sky-only Claude
 *       call and adds a rule-based {@code TideVisitor}, this signature is expected to change to
 *       consume forecast inputs directly.</li>
 * </ol>
 */
public interface Visitor {

    /**
     * Whether this visitor is relevant to the given location at all.
     *
     * <p>If {@code false}, the visitor does not participate and contributes nothing to the
     * location's averaged score — as if it did not exist for that location. (A future tide
     * visitor will return {@code false} for inland locations so they are scored on sky alone,
     * never dragged down by a phantom tide contribution.)
     *
     * @param location the location under evaluation
     * @return {@code true} if this visitor should contribute a score for {@code location}
     */
    boolean appliesTo(LocationEntity location);

    /**
     * This visitor's score for the location on a 1–5 scale, or
     * {@link OptionalInt#empty()} if it has no number to contribute for this evaluation.
     *
     * @param location the location under evaluation
     * @param context  the inputs to score from — the produced Claude evaluation and, for coastal
     *                 locations, the re-derived tide context (see {@link VisitorContext})
     * @return the 1–5 contribution, or empty when there is no score
     */
    OptionalInt evaluate(LocationEntity location, VisitorContext context);
}
