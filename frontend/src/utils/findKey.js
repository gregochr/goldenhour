/**
 * Find's keyboard shortcut — ⌘K on Apple platforms, Ctrl K everywhere else (find-and-favourites-plan.md F1).
 *
 * <p>Both modifiers are accepted on every platform (the plan names "⌘K / Ctrl K"), and only the
 * <em>hint</em> a button draws follows the platform. A shifted or alt-modified K is somebody else's
 * shortcut (Firefox's Ctrl+Shift+K is its console) and is never taken.
 */

/**
 * Whether the keydown is Find's shortcut: K with Meta or Ctrl, and neither Shift nor Alt.
 *
 * @param {KeyboardEvent} event the keydown
 * @returns {boolean} true for ⌘K / Ctrl K
 */
export function isFindKey(event) {
  if (!event || typeof event.key !== 'string') return false;
  if (event.key.toLowerCase() !== 'k') return false;
  if (!(event.metaKey || event.ctrlKey)) return false;
  return !event.altKey && !event.shiftKey;
}

/**
 * The shortcut as a button's keycap reads it: {@code ⌘K} on Apple platforms, {@code Ctrl K} elsewhere.
 *
 * @returns {string} the hint text
 */
export function findKeyHint() {
  const platform = typeof navigator === 'undefined'
    ? ''
    : `${navigator.userAgentData?.platform ?? navigator.platform ?? ''}`;
  return /mac|iphone|ipad|ipod/i.test(platform) ? '⌘K' : 'Ctrl K';
}

/**
 * Whether a keydown's target is somewhere the reader is typing — a field's own keys are never taken.
 *
 * @param {?EventTarget} el the event target
 * @returns {boolean} true for an input, textarea, select or contenteditable element
 */
export function isTypingTarget(el) {
  const tag = el?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || Boolean(el?.isContentEditable);
}
