import { departureWithDay, lightWindows, lookupForWindow } from './locationSheet.js';

/**
 * The four figures of Ask PhotoCast's "Plan this" (design README, State 4) — pure, no React, no clock.
 *
 * <p><b>Nothing here is derived; every figure is a function the location sheet already runs.</b>
 * CLAUDE.md's Backend-heavy rule applies and this is inside its already-licensed filter/map/select
 * class: "Leave home" is {@link departureWithDay} (the sheet's own departure, which is
 * {@code leaveByParts} plus the day word) over the card's served event instant and the reader's HOME
 * drive; "Best light" is {@link lightWindows} over the same score row the sheet reads; "Drive" and
 * "Tide" are the card's own joined fields (`utils/askModel.js` built them from the briefing's slot and
 * the tide-alignment index). {@code askPlan.test.js} compares the first two against
 * {@code buildLocationSheet}'s own row for the same fixture, which is the strongest pin there is that
 * the two surfaces cannot disagree about one window.
 *
 * <p>The home drive, never the Plan origin's (plan §1 #24): the card's {@code driveMinutes} was joined
 * from {@code reachById}, so under an away origin this still says how far the spot is from the reader's
 * house, and says so (⌂) — the one journey that does not change with the page.
 */

/**
 * @typedef {object} PlanFigures
 * @property {?{time: string, date: string, sameDay: boolean, dayWord: ?string}} leave the departure for
 *           the pick, or null when the drive or the event time is unknown
 * @property {?string} drive the home drive in words ("1h 25min"), or null
 * @property {?Array<{label: string, range: string}>} light golden and blue hour in the order they
 *           happen for the pick's event, or null when this window's score row carries neither
 * @property {?{tier: 'match'|'miss', state: ?string, shortfall: ?string, clause: ?string}} tide the
 *           card's served tide fact, or null for an inland spot or a slot with no served tide state
 */

/**
 * The four figures for a pick card.
 *
 * @param {object} card one element of {@code buildPickCards}' result
 * @param {?{byId: Map, byName: Map}} scoreIndex {@code buildScoreIndex(scoreRows)} — the same index
 *        {@code LocationFourDaySheet} reads its light line from
 * @returns {PlanFigures}
 */
export function planFigures(card, scoreIndex) {
  const score = lookupForWindow(scoreIndex, card.locationId, card.name, card.date, card.targetType);
  return {
    leave: departureWithDay(card.eventInstant, card.driveMinutes),
    drive: card.driveLabel ?? null,
    light: lightWindows(score, card.targetType),
    tide: card.tide ?? null,
  };
}
