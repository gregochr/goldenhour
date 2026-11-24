import { FILTER_CHIPS } from './comingUpFeed.js';

/**
 * The remembered Further ahead filter (docs/engineering/one-screen-plan.md D7): the chip id the reader last chose, kept
 * in this browser's {@code localStorage} because the owner's habit is tides (Coastal) and a filter that forgets itself
 * on every visit is a chore.
 *
 * <p>A per-reader CONVENIENCE and never state that matters, so every access is inside {@code try/catch} (private
 * windows, blocked site data and quota all throw), and a stored value that is not one of {@link FILTER_CHIPS}'s ids
 * (a chip since renamed, a hand-edited value, another app's key) reads as {@code all}. Nothing personal is stored:
 * the value is one of five fixed words.
 */
export const FURTHER_AHEAD_FILTER_KEY = 'furtherAheadFilter';

/** The filter in force when nothing valid is stored. */
export const DEFAULT_FURTHER_AHEAD_FILTER = 'all';

/**
 * The remembered filter, or {@code all}.
 *
 * @returns {string} a chip id from {@link FILTER_CHIPS}
 */
export function readFurtherAheadFilter() {
  try {
    const stored = window.localStorage.getItem(FURTHER_AHEAD_FILTER_KEY);
    return FILTER_CHIPS.some((chip) => chip.id === stored) ? stored : DEFAULT_FURTHER_AHEAD_FILTER;
  } catch {
    return DEFAULT_FURTHER_AHEAD_FILTER;
  }
}

/**
 * Remembers the filter. A write that fails is silent: the filter still applies for this visit.
 *
 * @param {string} filterId a chip id
 */
export function writeFurtherAheadFilter(filterId) {
  try {
    window.localStorage.setItem(FURTHER_AHEAD_FILTER_KEY, filterId);
  } catch {
    // Not remembered; the chip still applies for now.
  }
}
