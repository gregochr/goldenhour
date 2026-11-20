/**
 * Why the Map's List has nothing to open for the window on screen (glance-to-map-plan.md G1), in the words the greyed
 * button's screen-reader line says. The list is the Plan card's spot list, so a window with no card has none: a past
 * window, a travel day, a D-13 filler (a window the briefing served nothing for), a night and High water.
 */

/** @type {Readonly<Record<string, string>>} */
export const LIST_REASON = Object.freeze({
  NO_WINDOW: 'No window is selected',
  PAST: 'A past window has no spot list',
  NIGHT: 'A night has no spot list',
  FILLER: 'There is no forecast for this window, so no spot list',
  NO_CARD: 'There is no spot list for this window',
});

/**
 * @param {?{kind: string, past: ?boolean, served: ?boolean}} event the Map's active window row
 * @param {?object} card the Plan card for it, or null
 * @returns {?string} the reason, or null when there is a card (the list can open)
 */
export function listUnavailableReason(event, card) {
  if (card) return null;
  if (!event) return LIST_REASON.NO_WINDOW;
  if (event.past) return LIST_REASON.PAST;
  if (event.kind !== 'solar') return LIST_REASON.NIGHT;
  if (event.served === false) return LIST_REASON.FILLER;
  return LIST_REASON.NO_CARD;
}
