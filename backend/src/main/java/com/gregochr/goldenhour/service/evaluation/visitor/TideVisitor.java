package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.springframework.stereotype.Component;

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
        TideContext tide = context.tide();
        if (tide == null || tide.snapshot() == null) {
            // Data gap: the tide could not be derived (e.g. no stored extremes). Abstain.
            return OptionalInt.empty();
        }
        TideSnapshot snapshot = tide.snapshot();

        if (Boolean.TRUE.equals(snapshot.tideAligned())) {
            return OptionalInt.of(isKingOrSpring(snapshot)
                    ? SCORE_KING_OR_SPRING_ALIGNED : SCORE_TIGHT_ALIGNED);
        }
        if (tide.widenedAligned()) {
            return OptionalInt.of(SCORE_WIDENED_ALIGNED);
        }
        return OptionalInt.of(SCORE_MISALIGNED);
    }

    private boolean isKingOrSpring(TideSnapshot snapshot) {
        LunarTideType lunar = snapshot.lunarTideType();
        return lunar == LunarTideType.KING_TIDE || lunar == LunarTideType.SPRING_TIDE;
    }
}
