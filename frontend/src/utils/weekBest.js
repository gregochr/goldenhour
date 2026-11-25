/**
 * The week's best, as the This week bar names it (docs/engineering/screen-spec.md D3): `best Fri sunrise, Crimdon Beach
 * 4★ →`, from the served Best bet and nothing else.
 *
 * <p>Nothing here ranks. The window is the card whose served {@code pick.kind} is {@code best} (the server serves
 * exactly one, and withholds it from a Lite reader and from an origin that has scoped its region away), found by that
 * flag; the place is the pick's own {@code locationName}; the stars are the served rating of that place's slot
 * ({@code pickRating}, read in {@code windowFirstCards}), which can sit below the card's best because the pick is the
 * best place of the top-ranked region, not of the week. No slot rating means no stars, never a guess from the card.
 */

/** A target type's word in the line. */
const EVENT_WORD = { SUNRISE: 'sunrise', SUNSET: 'sunset' };

/**
 * The week's best window, by the served flag.
 *
 * @param {Array<object>} cards the strip's descriptors (`buildHeatStripCards`)
 * @param {?Array<string>} forecastDates the days the screen shows; null leaves every card eligible
 * @returns {?{date: string, eventType: string, locationId: ?number, place: string, rating: ?number,
 *   dow: string, event: string}} what the bar draws, or null when no served Best
 *   pick sits in the four days (Lite, scoped away, none served)
 */
export function weekBestOf(cards, forecastDates) {
  const pool = Array.isArray(forecastDates)
    ? (cards || []).filter((card) => forecastDates.includes(card.date))
    : (cards || []);
  // The server serves exactly one BEST pick. Should a stale cached briefing ever carry two, the EARLIEST wins: the cards
  // arrive chronologically (no ranking happens here), so the first flagged card is the soonest window to act on.
  const card = pool.find((c) => c?.pickKind === 'best' && !c.away);
  const eventWord = card ? EVENT_WORD[card.targetType] : null;
  if (!card || !eventWord) return null;
  const place = card.pickLocationName || card.pickRegion;
  if (!place) return null;
  const rating = typeof card.pickRating === 'number' ? card.pickRating : null;
  return {
    date: card.date,
    eventType: card.targetType,
    locationId: card.pickLocationId ?? null,
    place,
    rating,
    dow: card.dow,
    event: eventWord,
  };
}
