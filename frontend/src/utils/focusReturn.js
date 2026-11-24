/**
 * Where focus goes back to after an Undo (screen-spec-plan.md section 1, "Focus never drops to body").
 *
 * <p>A control the reader pressed can move while the request is in flight: a watched date's card leaves a month list
 * for Watching, so the toggle that had focus is a detached node by the time the toast's Undo is pressed. The toast
 * therefore keeps a LAZY target, a function resolved at Undo time, never the element itself.
 *
 * <p>The pressed control is found again by what the screen keeps stable across the move: the {@code data-entry-id} of
 * the Further ahead entry (or the {@code data-watch-key} of an unlisted Watching row) that held it, plus its own
 * {@code data-testid}. When it is still in the page it is its own answer.
 */

/** The attributes that name the row a control sits in, which survive that row being re-parented. */
const ROW_ATTRIBUTES = ['data-entry-id', 'data-watch-key'];

/**
 * The element with a given row attribute, found by comparison (an id may carry characters a selector would need escaped).
 *
 * @param {string} attribute one of {@link ROW_ATTRIBUTES}
 * @param {string} value the row's id
 * @returns {?Element}
 */
function findRow(attribute, value) {
  return [...document.querySelectorAll(`[${attribute}]`)].find((node) => node.getAttribute(attribute) === value) ?? null;
}

/**
 * A function that answers where focus should land for a control the reader pressed.
 *
 * @param {?Element} element what had focus at press time
 * @returns {?function(): ?Element} resolves to the control (or its successor with the same row and test id), or to
 *          null when there is nothing to return to; null itself when nothing had focus
 */
export function lazyReturnTo(element) {
  if (!element || element === document.body) return null;
  const anchors = ROW_ATTRIBUTES.map((attribute) => {
    const value = element.closest?.(`[${attribute}]`)?.getAttribute(attribute) ?? null;
    return value === null ? null : { attribute, value };
  }).filter(Boolean);
  const testId = element.getAttribute?.('data-testid') ?? null;
  return () => {
    if (element.isConnected) return element;
    if (testId === null) return null;
    for (const { attribute, value } of anchors) {
      const hit = findRow(attribute, value)?.querySelector(`[data-testid="${testId}"]`);
      if (hit) return hit;
    }
    return null;
  };
}

/**
 * Resolves a toast's {@code returnTo}, which is an element or a lazy function from {@link lazyReturnTo}.
 *
 * @param {?(Element|function(): ?Element)} returnTo
 * @returns {?Element} the element to focus, or null
 */
export function resolveReturnTo(returnTo) {
  const target = typeof returnTo === 'function' ? returnTo() : returnTo;
  return target?.isConnected ? target : null;
}
