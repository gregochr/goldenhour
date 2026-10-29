import { vi } from 'vitest';

/**
 * The shortest wait after which a window focus refetches (`useVisiblePoll`'s default `minGapMs`, plus a
 * millisecond). Suites that drive a race through a focus refetch call {@link focusPastGap} instead of
 * dispatching `focus` directly: the poll hooks now ignore a focus that follows a fetch within the gap.
 */
export const PAST_POLL_GAP_MS = 60 * 1000 + 1;

/**
 * Moves the fake clock past the poll gap, then dispatches a window `focus`. The suite must fake `Date`
 * (`vi.useFakeTimers({ toFake: [..., 'Date'] })`), because the gap is read from `Date.now()`.
 */
export function focusPastGap() {
  vi.setSystemTime(new Date(Date.now() + PAST_POLL_GAP_MS));
  window.dispatchEvent(new Event('focus'));
}
