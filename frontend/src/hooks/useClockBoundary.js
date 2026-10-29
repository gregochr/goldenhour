import { useEffect, useState } from 'react';
import { appNow, getRewindTo } from '../utils/rewind.js';

/** The longest one timer waits before re-reading the clock (hidden tabs and sleep make long timers unreliable). */
const SLICE_MS = 30000;
/** The re-render lands this long after the boundary, so the strict elapsed tests are true by then. */
const EPSILON_MS = 50;

/**
 * Re-renders the caller once the app clock passes {@code atMs} (epoch ms), so a render-time elapsed test
 * (`isEventTimePast`) is re-asked at the moment it flips rather than whenever something else renders.
 *
 * <p>A WALL-CLOCK deadline: it wakes in {@value #SLICE_MS}-ms slices, re-reads {@code appNow()} each time
 * and re-checks at once when the tab becomes visible, so a throttled tab or a slept machine still
 * lands on time. It does nothing while a Rewind is set (the clock is frozen, and a Rewind change remounts
 * the tree), for a null or already-passed boundary, and clears its timer and listener on unmount.
 *
 * @param {?number} atMs the boundary, epoch ms, or null for none
 * @returns {void}
 */
export function useClockBoundary(atMs) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (atMs == null || !Number.isFinite(atMs) || getRewindTo() != null) return undefined;
    const due = atMs + EPSILON_MS;
    if (due - appNow().getTime() <= 0) return undefined;
    let timer = null;
    const check = () => {
      timer = null;
      const remaining = due - appNow().getTime();
      if (remaining <= 0) {
        setTick((n) => n + 1);
        return;
      }
      timer = setTimeout(check, Math.min(remaining, SLICE_MS));
    };
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      if (timer != null) clearTimeout(timer);
      check();
    };
    timer = setTimeout(check, Math.min(due - appNow().getTime(), SLICE_MS));
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      if (timer != null) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [atMs]);
}
