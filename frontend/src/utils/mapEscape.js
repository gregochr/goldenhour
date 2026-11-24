/**
 * Which native Escape key events the map pane has already answered (`MapView`'s pane-level handler), so the map view
 * around it (`components/MapScreen.jsx`) does not answer the same press a second time when the event bubbles up
 * through both. A WeakSet: nothing to clean up, nothing retained.
 */
const answered = new WeakSet();

/**
 * Records that the pane answered this event.
 *
 * @param {?Event} nativeEvent the DOM event behind the React keydown
 */
export function markEscapeAnswered(nativeEvent) {
  if (nativeEvent && typeof nativeEvent === 'object') answered.add(nativeEvent);
}

/**
 * @param {?Event} nativeEvent
 * @returns {boolean} whether the pane already answered it
 */
export function escapeAnswered(nativeEvent) {
  return Boolean(nativeEvent) && typeof nativeEvent === 'object' && answered.has(nativeEvent);
}
