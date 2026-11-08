import { starsWord } from './askModel.js';

/**
 * What one Ask chip says when its place won MORE than one pick — a ranking follow-up over the thread's
 * own picks may return one place at two windows (Bamburgh sunrise is pick 1, Bamburgh sunset pick 2;
 * `docs/engineering/ask-thread-plan.md`). The map has one position per place, so it is one chip, and the
 * chip carries every rank the place won. {@code askPick.ranks} is present ONLY then (a chip at a
 * single-pick place is exactly what it was before a thread existed, which is why every helper here falls
 * back to {@code [pick.rank]}).
 *
 * <p>Filter/map/select over ranks the answer already served; nothing here decides a rank.
 */

/** @param {{rank: number, ranks?: Array<number>}} pick @returns {Array<number>} every rank at the place, in order */
export function ranksOf(pick) {
  return Array.isArray(pick.ranks) && pick.ranks.length > 0 ? pick.ranks : [pick.rank];
}

/** The rank badge's text: {@code 1} at a single-pick place, {@code 1·2} at one with two. */
export function rankBadge(pick) {
  return ranksOf(pick).join('·');
}

/** Whether the badge is the multi-rank one (a wider pill, not the 17px circle). */
export function isMultiRank(pick) {
  return ranksOf(pick).length > 1;
}

/** "1", "1 and 2", "1, 2 and 3" — for an accessible name. */
export function ranksPhrase(ranks) {
  if (ranks.length <= 1) return `${ranks[0]}`;
  return `${ranks.slice(0, -1).join(', ')} and ${ranks[ranks.length - 1]}`;
}

/**
 * The accessible name of a multi-rank chip: its visible run first ("1 2 Bamburgh Sat am 5"), then each
 * pick's own name. Label in name (WCAG 2.5.3): the badge reads "1·2", so the ranks are listed bare.
 *
 * @param {{ranks: Array<number>, shortWindow: ?string, rating: ?number}} pick the chip's shown facts
 * @param {string} name the place
 * @param {Array<string>} labels each pick's own accessible name, in rank order
 * @returns {string}
 */
export function multiRankLabel(pick, name, labels) {
  const stars = Number.isFinite(pick.rating) ? `${pick.rating} ${starsWord(pick.rating)}` : null;
  const run = [`Ask picks ${pick.ranks.join(', ')}`, name, pick.shortWindow, stars].filter(Boolean).join(', ');
  return `${run}: ${labels.join('; ')}`;
}

/**
 * The rank a press on the chip chooses. A single-pick chip chooses its rank, as ever. A multi-rank chip
 * is one control for several cards, so a press moves through them: the first press chooses the lowest,
 * the next the one after the chosen, and the last wraps to the first — every card is reachable from the map.
 *
 * @param {{rank: number, ranks?: Array<number>, selectedRank?: ?number}} pick
 * @returns {number}
 */
export function rankOnPress(pick) {
  const ranks = ranksOf(pick);
  if (ranks.length === 1) return ranks[0];
  const at = ranks.indexOf(pick.selectedRank);
  return ranks[(at + 1) % ranks.length];
}
