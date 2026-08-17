package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import java.time.LocalDate;

/**
 * One solar event the Plan tab renders, in the order it renders them.
 *
 * <p><b>The wire form of a window's identity.</b> {@code PlanWindowProjector.WindowKey} is where
 * that identity is defined and it stays there — this record exists only because the key lives in
 * the service layer and a response component may not import it. The projector builds these directly
 * from the same {@code LinkedHashSet<WindowKey>} it scopes the picks to, in one place, so the two
 * are one derivation in two shapes rather than two derivations.
 *
 * <p><b>Why the list is published at all.</b> The client used to decide which events it drew — its
 * own copy of the six-event cap, its own pastness rule, its own ordering — while the backend picked
 * BEST/ALSO over a set derived separately. Two answers to "which events exist" is how a pick came to
 * name a window with no card. See {@code docs/engineering/plan-verdict-consolidation-plan.md} §1 D3
 * and §4 Phase 3.
 *
 * @param date       the event's local date
 * @param targetType the solar event
 */
public record PlanRenderedEvent(LocalDate date, TargetType targetType) {
}
