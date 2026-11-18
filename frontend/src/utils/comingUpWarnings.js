import { instantMs, warningLabel } from './warnings.js';

/**
 * Met Office warning entries in the "Coming up" feed (weather-warnings-plan.md W3b).
 *
 * <p>The server merges the entries into {@code GET /api/almanac} per request, after the day cache. The feed itself
 * is fetched once a day and then only on a change of the warning set, so on its own it could hold a withdrawn warning
 * far past the licence's ten minutes. The polled {@code GET /api/warnings} list (every two minutes, W2) is therefore
 * the authority for whether a warning entry still exists, what level it is and what it says: an entry whose id is not
 * in the polled list, or whose polled {@code validTo} has passed, is dropped, and a surviving entry is drawn from the
 * polled warning, never from the feed's copy. Nothing here decides which warnings belong in the feed (the server did)
 * and nothing reads or writes a rating.
 */

/** The wire {@code type} of a warning entry. */
export const WARNING_ENTRY_TYPE = 'weather-warning';

/** The wire {@code family} of a warning entry (it has no chip of its own and is found under All). */
export const WARNING_ENTRY_FAMILY = 'warning';

/**
 * Whether a wire entry is a warning entry.
 *
 * @param {?object} entry a served {@code ComingUpEntry}
 * @returns {boolean}
 */
export function isWarningEntry(entry) {
  return entry?.type === WARNING_ENTRY_TYPE;
}

/**
 * A key that changes exactly when the polled warning set changes (an id appears or goes, a version or a level
 * moves). {@code useComingUpFeed} refetches the almanac when it changes, so a new warning reaches the feed inside the
 * licence's bound. Empty while the feature is off, so the feed is not refetched for nothing.
 *
 * @param {?{enabled: boolean, warnings: ?object[]}} served the warnings context value
 * @returns {string}
 */
export function warningsKey(served) {
  if (!served || served.enabled !== true || !Array.isArray(served.warnings)) return '';
  return served.warnings
    .map((w) => `${String(w?.warningId)}@${String(w?.warningVersion)}@${String(w?.level)}`
      + `@${w?.upgradedFrom ? 'raised' : ''}`)
    .join('|');
}

/** The id a warning entry names, as a string, or null. */
function entryWarningId(entry) {
  const id = entry?.meta?.warningId;
  return id == null || id === '' ? null : String(id);
}

/**
 * Reconciles the served feed's warning entries with the polled warnings.
 *
 * <p>While the feature is off (or before the first poll has answered, or after a refusal) the context carries no
 * warnings and every warning entry is dropped: the client never shows a warning the poll has not confirmed. A kept
 * entry carries the polled warning as {@code warning} and takes its title from it, so the title, the chip and the
 * level can never disagree. The served counts lose one forecast and one {@code warning} for each dropped entry, so a
 * chip's number still matches the list. When nothing is dropped and nothing is a warning the same object comes back.
 *
 * @param {?{entries: ?object[], counts: ?object}} events the wrapped wire payload (null before it arrives)
 * @param {{enabled: boolean, warnings: ?object[]}} served the warnings context value
 * @param {number} nowMs the reader's now, from {@code appNow()} (Rewind-aware)
 * @returns {?object} the payload to render
 */
export function reconcileFeed(events, served, nowMs) {
  if (!events || !Array.isArray(events.entries) || !events.entries.some(isWarningEntry)) return events;
  const polled = served?.enabled === true && Array.isArray(served.warnings) ? served.warnings : [];
  let dropped = 0;
  const entries = [];
  events.entries.forEach((entry) => {
    if (!isWarningEntry(entry)) {
      entries.push(entry);
      return;
    }
    const id = entryWarningId(entry);
    const warning = id == null ? null : polled.find((w) => String(w?.warningId) === id);
    const label = warning ? warningLabel(warning) : null;
    const end = warning ? instantMs(warning.validTo) : Number.NaN;
    const live = label != null && (Number.isNaN(end) || end > nowMs);
    if (!live) {
      dropped += 1;
      return;
    }
    entries.push({ ...entry, title: label, warning });
  });
  const counts = dropped > 0 && events.counts ? discountWarnings(events.counts, dropped) : events.counts;
  return { ...events, entries, counts };
}

/** The counts with {@code dropped} warning entries taken back out (never below zero). */
function discountWarnings(counts, dropped) {
  const byFamily = { ...(counts.byFamily ?? {}) };
  if (byFamily[WARNING_ENTRY_FAMILY] != null) {
    byFamily[WARNING_ENTRY_FAMILY] = Math.max(0, byFamily[WARNING_ENTRY_FAMILY] - dropped);
  }
  return { ...counts, forecast: Math.max(0, (counts.forecast ?? 0) - dropped), byFamily };
}
