import { useEffect } from 'react';

/** How old the last fetch must be before a return to the tab or a window focus refetches. */
export const DEFAULT_MIN_GAP_MS = 60 * 1000;

function isHidden() {
  return typeof document !== 'undefined' && document.visibilityState === 'hidden';
}

/**
 * Calls {@code fetchFn} now and then every {@code intervalMs}, but only while the page is visible, and
 * refetches on the page becoming visible or the window gaining focus only when the last fetch is
 * older than {@code minGapMs}.
 *
 * <p>Replaces the "fetch, setInterval, refetch on every focus" shape (docs/engineering/
 * performance-review-2026-10-plan.md, P3a): a hidden tab no longer polls, and the visibility and focus
 * events that arrive together on a tab switch, or a flurry of focuses, cost one request rather than one
 * each.
 *
 * <p><b>Rules the callers rely on</b>
 * <ul>
 *   <li>A change of {@code fetchFn} identity, or of {@code enabled}, fetches AT ONCE and ignores the gap:
 *       sign-in, a role change, an upgrade and a re-key all depend on an immediate fetch through the
 *       effect's dependencies, exactly as they did before. Callers therefore pass a stable
 *       ({@code useCallback}) function and let its dependencies carry those changes.</li>
 *   <li>The gap is measured with {@code Date.now()}, never {@code appNow()}: under Rewind
 *       {@code appNow()} is frozen, so a gap read from it would never elapse. Rewind needs only the
 *       request header the shared client already adds.</li>
 *   <li>The scheduled tick fetches regardless of the gap; the gap guards the event-driven refetches
 *       only.</li>
 *   <li>A page that is hidden when the effect runs still makes its first fetch (as before) but starts
 *       no interval until it becomes visible.</li>
 * </ul>
 *
 * @param {() => *} fetchFn what to call; its return value is ignored
 * @param {number} intervalMs the poll period while visible
 * @param {{minGapMs?: number, enabled?: boolean}} [options] {@code enabled} false makes the hook inert
 */
export function useVisiblePoll(fetchFn, intervalMs, { minGapMs = DEFAULT_MIN_GAP_MS, enabled = true } = {}) {
  useEffect(() => {
    if (!enabled) return undefined;
    let lastFetchAt = 0;
    let timer = null;

    const run = () => {
      lastFetchAt = Date.now();
      fetchFn();
    };
    const stop = () => {
      if (timer !== null) clearInterval(timer);
      timer = null;
    };
    const start = () => {
      stop();
      timer = setInterval(run, intervalMs);
    };
    const refetchIfStale = () => {
      if (Date.now() - lastFetchAt >= minGapMs) run();
    };
    const onVisibility = () => {
      if (isHidden()) {
        stop();
        return;
      }
      refetchIfStale();
      start();
    };
    const onFocus = () => {
      if (isHidden()) return;
      refetchIfStale();
    };

    run();
    if (!isHidden()) start();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onFocus);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onFocus);
    };
  }, [fetchFn, intervalMs, minGapMs, enabled]);
}
