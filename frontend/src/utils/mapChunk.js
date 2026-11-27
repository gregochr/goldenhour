/**
 * The map pane's code-split chunk, loaded by `App`'s `lazy()` and preloaded on idle (`docs/engineering/
 * one-screen-plan.md` D10). Both go through {@link loadMapChunk}, so the browser's module registry hands them the same
 * promise: a preload that has finished makes the first open of the map view instant, and one still in flight is
 * joined, never repeated.
 *
 * @returns {Promise<{default: Function}>} the `WindowFirstMapPane` module
 */
export function loadMapChunk() {
  return import('../components/WindowFirstMapPane.jsx');
}

/** The fallback delay where there is no `requestIdleCallback` (Safari): after first paint has settled. */
export const PRELOAD_FALLBACK_MS = 1500;

/**
 * Starts fetching the map chunk once the browser is idle, for a reader who has something to map. Fire-and-forget and
 * silent on failure: `lazy()` handles a failed load itself when the view first opens, with its own retry surface.
 *
 * @param {Window} [win] the window to schedule on (a parameter so a test need not touch the global)
 * @returns {function(): void} cancels a preload that has not started
 */
export function preloadMapChunk(win = typeof window === 'undefined' ? undefined : window) {
  const start = () => { loadMapChunk().catch(() => {}); };
  if (!win) return () => {};
  if (typeof win.requestIdleCallback === 'function') {
    const handle = win.requestIdleCallback(start);
    return () => win.cancelIdleCallback?.(handle);
  }
  const timer = win.setTimeout(start, PRELOAD_FALLBACK_MS);
  return () => win.clearTimeout(timer);
}
