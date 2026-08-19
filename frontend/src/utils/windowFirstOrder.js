/**
 * The Plan pane's ordering axis — the third control on the lens bar.
 *
 * <h2>Two orders, and only one of them is a ranking</h2>
 *
 * <p>{@code When} is the pane's own spine: chronological, away days folded back in where they
 * fall, which is what {@code buildPaneItems} already returns. {@code Best} answers the other
 * question a reader arrives with — "which of these six is worth my evening" — by ranking the
 * window cards on the best region mean the forecast served for each.
 *
 * <h2>What must NOT be reordered, and why it is not this module's job to remember</h2>
 *
 * <p>The heat strip stays chronological under both orders. That is not enforced here by
 * convention: the strip is built from {@code upcomingEvents} and never sees this function, so
 * there is no call site at which it could be re-sorted by accident. The design's own note is
 * blunt about why — "reordering the strip would destroy its time axis, which is the only reason
 * the shape of the week is legible at a glance".
 *
 * <h2>Away rows sink, and the pane says so</h2>
 *
 * <p>Under {@code When} an away row is a gap in a sequence and reads as one. Under {@code Best}
 * there is no sequence for it to be a gap in — a day with no forecast has no rank, and slotting it
 * between two ranked cards would imply one. So they follow the ranked cards, in date order, under
 * a line naming what happened; a silent move is the thing that would read as a bug.
 *
 * <h2>The rank is attached, never rendered here</h2>
 *
 * <p>Items are returned with a {@code rank} of 1…N on the cards under {@code Best} and no rank at
 * all under {@code When} — a number beside a card in date order would be an ordinal for an order
 * nobody asked for.
 */

/** Chronological — the pane's default and the order every other surface in the arm speaks. */
export const PLAN_ORDER_WHEN = 'when';
/** Ranked by the best region mean the forecast served for each window. */
export const PLAN_ORDER_BEST = 'best';

/** The segmented control's options, in the design's order. */
export const PLAN_ORDERS = [
  { id: PLAN_ORDER_WHEN, label: 'When' },
  { id: PLAN_ORDER_BEST, label: 'Best' },
];

/** Every id this module recognises — the hook's validation list. */
export const PLAN_ORDER_IDS = PLAN_ORDERS.map((o) => o.id);

/**
 * Ranks two window cards: higher mean first, unrated last, then the pane's own date order.
 *
 * <p>The tiebreak is the date and then the event, so the comparator is <b>total</b> — two windows
 * with equal means (and six windows over four days make ties ordinary, not exotic) always sort the
 * same way, whatever the engine's sort stability happens to be. Sunrise before sunset because that
 * is the order the two fall in on the day they share.
 */
function byBestThenDate(a, b) {
  const ma = a.card.topMeanRating;
  const mb = b.card.topMeanRating;
  const ra = typeof ma === 'number' && Number.isFinite(ma) ? ma : null;
  const rb = typeof mb === 'number' && Number.isFinite(mb) ? mb : null;
  if (ra !== rb) {
    // An unrated window ranks last rather than as a zero. AWAITING is "not looked at yet", not
    // "looked at and poor", and the two must not sort into the same place (plan §2.12).
    if (ra === null) return 1;
    if (rb === null) return -1;
    return rb - ra;
  }
  if (a.card.date !== b.card.date) return a.card.date < b.card.date ? -1 : 1;
  if (a.card.targetType === b.card.targetType) return 0;
  return a.card.targetType === 'SUNRISE' ? -1 : 1;
}

/**
 * The pane's items in the requested order.
 *
 * @param {Array}  items the chronological items from {@code buildPaneItems}
 * @param {string} order {@link PLAN_ORDER_WHEN} or {@link PLAN_ORDER_BEST}
 * @returns {Array} the items to render — the same objects under {@code When}, and under
 *          {@code Best} the ranked cards (each carrying a 1-based {@code rank}) followed by the
 *          away rows in date order
 */
export function orderPaneItems(items, order) {
  const list = Array.isArray(items) ? items : [];
  // Identity on the default, so the pane's ordinary path allocates nothing and a caller memoising
  // on the result gets the same array back.
  if (order !== PLAN_ORDER_BEST) return list;
  const cards = list.filter((item) => item.kind === 'card' && item.card);
  const rest = list.filter((item) => !(item.kind === 'card' && item.card));
  const ranked = [...cards].sort(byBestThenDate)
    .map((item, index) => ({ ...item, rank: index + 1 }));
  return [...ranked, ...rest];
}
