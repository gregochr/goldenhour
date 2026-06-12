package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The tide photogenic evaluator — the second v2.13.2 visitor.
 *
 * <p>Scores the tide as a foreground concern, separately from the sky. Before v2.13.2 the tide
 * was folded into Claude's single rating by the coastal prompt; that prompt instruction has been
 * removed (Claude now scores the sky alone), so this visitor re-adds the tide contribution
 * deterministically and {@link RatingCombiner} averages it with the sky score.
 *
 * <h2>Applicability</h2>
 * Applies to <em>tidal</em> locations — those with a non-empty {@link LocationEntity#getTideType()}
 * preference. This is a property of the location, not of the forecast: "is the tide aligned
 * <em>tonight</em>" is a scoring question handled in {@link #evaluate}, never folded into
 * {@link #appliesTo}. Inland locations are scored on sky alone.
 *
 * <h2>Scoring (rule R1 — a misaligned tide penalises, it does not abstain)</h2>
 * <ul>
 *   <li><b>Tide un-derivable</b> (a data gap — no tide context, e.g. no stored extremes):
 *       {@link OptionalInt#empty()}. The location scores on sky alone. A data gap must not
 *       penalise — the same epistemic principle as a missing sky rating.</li>
 *   <li><b>King/spring tide aligned within the tight golden/blue-hour window:</b> 5.</li>
 *   <li><b>Aligned within the tight window</b> (regular tide): 4. This tier is the existing
 *       {@code TideService.calculateTideAligned} check, reused unchanged.</li>
 *   <li><b>Aligned only within the widened window</b> (the existing window extended 60 minutes
 *       beyond each edge, but not the tight window): 3 — an imperfect tide that a great sky
 *       could still work into an average foreground.</li>
 *   <li><b>Outside even the widened window:</b> 1 — a misaligned tide at a tidal location means
 *       no foreground; it averages in and drags the rating (the St Mary's Lighthouse case).</li>
 * </ul>
 */
@Component
public class TideVisitor implements Visitor {

    /** Tight-window aligned + king/spring lunar tide — the strongest, most dramatic foreground. */
    private static final int SCORE_KING_OR_SPRING_ALIGNED = 5;

    /** Tight-window aligned, regular tide — a well-timed tide. */
    private static final int SCORE_TIGHT_ALIGNED = 4;

    /** Aligned only within the window widened by 60 min each edge — an imperfect but workable tide. */
    private static final int SCORE_WIDENED_ALIGNED = 3;

    /** Misaligned beyond even the widened window — no foreground. */
    private static final int SCORE_MISALIGNED = 1;

    @Override
    public boolean appliesTo(LocationEntity location) {
        Set<TideType> tideTypes = location.getTideType();
        return tideTypes != null && !tideTypes.isEmpty();
    }

    @Override
    public OptionalInt evaluate(LocationEntity location, VisitorContext context) {
        Band band = classify(context.tide());
        return band == null ? OptionalInt.empty() : OptionalInt.of(band.score);
    }

    @Override
    public ForecastType type() {
        return ForecastType.TIDAL;
    }

    /**
     * The deterministic one-line clause for this tide's state, derived from the same
     * {@link Band} classification that produces the score. Abstains (empty) on a data gap,
     * exactly as {@link #evaluate} does, so the combiner never records a tide clause without
     * a tide score.
     */
    @Override
    public Optional<String> summary(LocationEntity location, VisitorContext context) {
        Band band = classify(context.tide());
        return band == null ? Optional.empty() : Optional.of(band.clause);
    }

    /**
     * Classifies the tide into its scoring band, or {@code null} when the tide could not be
     * derived (a data gap — no context or no snapshot). The single source of truth shared by
     * {@link #evaluate} (band → score) and {@link #summary} (band → clause) so the two can
     * never disagree about the tide's state.
     *
     * @param tide the re-derived tide context, or {@code null}
     * @return the scoring band, or {@code null} on a data gap (abstain)
     */
    private Band classify(TideContext tide) {
        if (tide == null || tide.snapshot() == null) {
            return null;
        }
        TideSnapshot snapshot = tide.snapshot();
        if (Boolean.TRUE.equals(snapshot.tideAligned())) {
            LunarTideType lunar = snapshot.lunarTideType();
            if (lunar == LunarTideType.KING_TIDE) {
                return Band.KING_ALIGNED;
            }
            if (lunar == LunarTideType.SPRING_TIDE) {
                return Band.SPRING_ALIGNED;
            }
            return Band.REGULAR_ALIGNED;
        }
        if (tide.widenedAligned()) {
            return Band.WIDENED_ALIGNED;
        }
        return Band.MISALIGNED;
    }

    /**
     * The five tide-alignment bands, each pairing its 1–5 score with the deterministic clause
     * recorded on the {@link ForecastType#TIDAL} component row. King and spring both score 5
     * but carry distinct wording.
     */
    private enum Band {

        /** Tight-aligned king tide — the most dramatic foreground. */
        KING_ALIGNED(SCORE_KING_OR_SPRING_ALIGNED,
                "King tide aligns with the event — dramatic water levels at golden hour"),

        /** Tight-aligned spring tide — strong water movement. */
        SPRING_ALIGNED(SCORE_KING_OR_SPRING_ALIGNED,
                "Spring tide aligns with the event — strong water movement at golden hour"),

        /** Tight-aligned regular tide — a well-timed tide. */
        REGULAR_ALIGNED(SCORE_TIGHT_ALIGNED, "Tide aligns well with the event"),

        /** Aligned only within the widened window — imperfect but workable. */
        WIDENED_ALIGNED(SCORE_WIDENED_ALIGNED, "Tide alignment is workable but not ideal"),

        /** Misaligned beyond even the widened window — no foreground. */
        MISALIGNED(SCORE_MISALIGNED,
                "Tide works against this slot — no aligned foreground through the event");

        private final int score;
        private final String clause;

        Band(int score, String clause) {
            this.score = score;
            this.clause = clause;
        }
    }
}
