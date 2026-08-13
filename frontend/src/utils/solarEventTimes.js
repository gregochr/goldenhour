/**
 * When each solar event is over <em>everywhere</em> on the roster.
 *
 * <p>Answers one question, for one caller: the admin run dialog needs to know whether a
 * sunrise/sunset slot has already happened, so it can mark it past. That is an <b>instant</b>
 * question, and it has a real answer in the briefing payload — which is the point of this module,
 * because the alternative the dialog used was a fixed wall-clock hour, and no fixed hour can be
 * right. Measured over the GB bounding box in the fortnight around the 2026 solstice: sunset
 * reaches <b>23:06</b> UK on Lewis (58.65°N, 6.30°W) and 23:51 at the box's north-west corner.
 * There is no hour above 23, so the approximation was not mistuned, it was unfixable.
 *
 * <p>⚠️ <b>The latest across the roster, never {@code eventSummary.solarEventTime}.</b> That field
 * exists and looks exactly right, and it is the <em>earliest</em> across the region set — chosen
 * deliberately for determinism, on the stated grounds that "the choice between earliest and latest
 * changes nothing a reader could see" (`BriefingEventSummary.earliestEventTime`). True for the
 * afterglow window it was built for; false here. Reading it would mark a slot past while the sun
 * is still up further north and west, which is the one error direction that hurts: a slot marked
 * past is disabled AND dropped from the run dialog's exclusion list, so an admin can neither run
 * it knowingly nor skip it.
 *
 * <p>Absent data yields <b>no entry</b>, never a guess. The caller's contract is that a missing
 * event time means "make no claim" — every slot stays selectable and the backend's own already-past
 * gate skips whatever is genuinely over. Under-claiming is free; over-claiming is the bug.
 */

import { parseUtcInstant } from './conversions.js';

/** Key for the (date, event type) pair a slot list is grouped under. */
function keyFor(date, targetType) {
  return `${date}|${targetType}`;
}

/**
 * Every slot in an event summary, across its regioned and unregioned halves.
 *
 * <p>Both halves matter: a location with no region lands in {@code unregioned}, and a roster can be
 * entirely one or the other. Reading only {@code regions} would silently narrow the maximum.
 */
function slotsOf(eventSummary) {
  const regioned = (eventSummary.regions ?? []).flatMap((r) => r.slots ?? []);
  return [...regioned, ...(eventSummary.unregioned ?? [])];
}

/**
 * The latest solar event time for each (date, event type) in a briefing payload.
 *
 * @param {object|null} briefing - the `GET /api/briefing` payload, or null
 * @returns {Map<string, Date>} keyed `YYYY-MM-DD|SUNRISE`; absent when nothing is known
 */
export function latestSolarEventTimes(briefing) {
  const times = new Map();
  for (const day of briefing?.days ?? []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      if (!summary?.targetType) continue;
      const instants = slotsOf(summary)
        .map((slot) => parseUtcInstant(slot?.solarEventTime))
        .filter(Boolean);
      if (instants.length === 0) continue;
      const latest = instants.reduce((a, b) => (b > a ? b : a));
      const key = keyFor(day.date, summary.targetType);
      // A payload could carry the same (date, type) twice; keep the later, matching the rule
      // within a single summary rather than letting document order decide.
      if (!times.has(key) || latest > times.get(key)) times.set(key, latest);
    }
  }
  return times;
}

/**
 * Whether the given slot's event is over everywhere on the roster.
 *
 * @param {Map<string, Date>|null} times - from {@link latestSolarEventTimes}
 * @param {string} date                  - ISO date, YYYY-MM-DD
 * @param {string} targetType            - SUNRISE or SUNSET
 * @param {Date} now                     - the instant to judge against
 * @returns {boolean} true only when the event time is known AND has passed
 */
export function hasEventPassed(times, date, targetType, now) {
  const eventTime = times?.get(keyFor(date, targetType));
  return eventTime ? now.getTime() > eventTime.getTime() : false;
}
