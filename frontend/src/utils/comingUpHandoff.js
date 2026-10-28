import { ukDateStrOffset } from './mapDates.js';
import { topicCoveredDates, topicWindowKeys } from './windowFirstTopics.js';

/**
 * The "Next four days" boundary helpers — plan D14, superseded in presentation by the lanes
 * (hot-topics-by-day-plan §3 C1), then by the Coming up day tiles (coming-up-glance-plan T1), which
 * glance-topic-strip-plan.md moved onto At a Glance's day headers.
 *
 * <p>This module used to build the one-line handoff row ({@code buildHandoff}); that row and its
 * builder are gone, and what remains is the part the day tiles still share: {@link lastPlanDateStr}
 * (the last date Plan renders) and {@link laneDates} (the dates and windows a served topic lands on).
 * The D14 rule survives unchanged: everything is derived client-side from the live
 * {@code briefing.hotTopics}, never from {@code /api/almanac}.
 */

/** Plan owns today plus the next three days — matches the backend's {@code PlanHorizon}. */
export const PLAN_OWNED_DAYS = 4;

/** Midday UTC, so no timezone can push a bare `YYYY-MM-DD` onto the day either side. */
function atMidday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`);
}

/**
 * The dates one served topic lands on, each with the window it names — the one helper
 * {@code fourDayTiles.js}' chips are built from, so a topic is counted by one rule
 * (hot-topics-by-day-plan §3 C1).
 *
 * <p>Anchored topics: one item per {@code topicWindowKeys} key, in the order that function
 * produced them (a NIGHT topic is its date's sunset, then the next day's sunrise). Unanchored
 * topics (storm surge, a clearance, a null {@code eventType}): one item per
 * {@code topicCoveredDates} date with {@code window: null} and {@code key: null} — a claim about a
 * day, bucketed on no window.
 *
 * @param {?{date: string, type?: string, eventType?: ?string}} topic a served {@code HotTopic}
 * @returns {Array<{date: string, window: ?string, key: ?string}>} empty for an undated topic
 */
export function laneDates(topic) {
  const keys = topicWindowKeys(topic);
  if (keys.length > 0) {
    return keys.map((key) => {
      const at = key.indexOf(':');
      return { date: key.slice(0, at), window: key.slice(at + 1), key };
    });
  }
  return topicCoveredDates(topic).map((date) => ({ date, window: null, key: null }));
}

/**
 * The last date Plan renders, given the reader's UK today.
 *
 * @param {string} todayStr the reader's UK today, `YYYY-MM-DD`
 * @returns {string} `todayStr` plus {@link PLAN_OWNED_DAYS} − 1 days
 */
export function lastPlanDateStr(todayStr) {
  return ukDateStrOffset(PLAN_OWNED_DAYS - 1, atMidday(todayStr));
}
