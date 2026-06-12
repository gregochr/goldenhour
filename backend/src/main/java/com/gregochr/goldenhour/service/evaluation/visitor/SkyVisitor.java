package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The sky photogenic evaluator — the only visitor in v2.13.1.
 *
 * <p>Sky applies to every location, so {@link #appliesTo} is always {@code true}.
 *
 * <p><b>What this is, honestly.</b> In v2.13.1 {@code SkyVisitor} is the existing
 * <em>whole-rating</em> Claude result relocated into a visitor. Its score is the rating the
 * current pipeline already produces — for an inland location via {@code PromptBuilder}, and
 * for a coastal location via {@code CoastalPromptBuilder}, whose prompt <em>already folds the
 * tide into the single rating</em>. There is no separate tide score yet (none exists in the
 * codebase to relocate — the only tide logic is a triage gate), so introducing one and
 * averaging it now would double-count tide and change coastal ratings. That is why v2.13.1
 * ships {@code SkyVisitor} alone and provably preserves today's ratings.
 *
 * <p><b>v2.13.2 intent (not built here).</b> When the system prompt is decomposed to sky-only
 * content, {@code SkyVisitor} narrows to scoring the sky alone (owning its own focused Claude
 * call and an internal triage collaborator), and a rule-based {@code TideVisitor} takes over
 * the tide contribution. At that point the combiner's average stops being trivial.
 *
 * <p><b>Triage.</b> Not handled here in v2.13.1. Triage remains the existing pre-visitor gate
 * that produces the canned {@code rating=null} + {@code triageReason} entity before scoring;
 * a triaged location never reaches this visitor. Relocating triage into this visitor is
 * deferred to v2.13.2 (it cannot move now without forking the synchronous and asynchronous
 * result paths — see the v2.13.1 foundation notes).
 */
@Component
public class SkyVisitor implements Visitor {

    @Override
    public boolean appliesTo(LocationEntity location) {
        return true;
    }

    @Override
    public OptionalInt evaluate(LocationEntity location, VisitorContext context) {
        Integer rating = context.evaluation().rating();
        return rating != null ? OptionalInt.of(rating) : OptionalInt.empty();
    }

    @Override
    public ForecastType type() {
        return ForecastType.SKY;
    }

    /**
     * Re-exposes Claude's plain-English summary as the sky component's clause — the same
     * prose served on the rating surface. The combiner only queries this when
     * {@link #evaluate} returned a score (sky rating present), so it is paired with a real
     * sky score.
     */
    @Override
    public Optional<String> summary(LocationEntity location, VisitorContext context) {
        return Optional.ofNullable(context.evaluation().summary());
    }
}
