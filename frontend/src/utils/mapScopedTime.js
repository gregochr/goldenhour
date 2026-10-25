import { parseUtcInstant } from './conversions.js';
import { EVENT_KIND } from './mapEvents.js';

/** The forecast-row slot each solar event type reads. */
const SLOT_BY_EVENT = { SUNRISE: 'sunrise', SUNSET: 'sunset' };

/**
 * The Map tab's window time, taken over the places in the reader's SCOPE rather than over the
 * whole catalogue.
 *
 * <p><b>Why the served time is not enough on this tab.</b> A solar window's served
 * {@code BriefingWindow.eventTime} is the EARLIEST slot time across every region
 * ({@code PlanWindowProjector.earliestEventTime}) — the right instant for deciding when a window is
 * over, because an event belongs to all of its locations. As a label it names a place the reader
 * may not be looking at: in October the northernmost coast sets about five minutes before Durham,
 * so the pill read "Tonight 18:09" over a "My area" map whose every place, and the masthead's own
 * light rule, said 18:14. The pill's verdict already follows the scope segment
 * ({@code utils/mapVerdict.js}); this makes its time describe the same places.
 *
 * <p><b>Display only.</b> The row's {@code time} is a label, read by every surface that prints a
 * window's clock: the pill and its menu, the peek sheet, the drilldown, the
 * callout's header and every-window strip, the map tooltips, {@code TideFitBlock}'s next-fit line
 * and the tide strip's sunrise/sunset labels and "height at" line. ⚠️ That last one pairs the tide
 * station's height (computed at the station's own event time) with this scoped time, which may be
 * a minute or two away; it was already paired with the catalogue-earliest time before, so this is
 * no regression, but it is not the station's own minute. Nothing here touches what decides that a window has
 * passed — that stays the served instant (the filler lend's {@code eventTime}, and the briefing's
 * own withdrawal of an elapsed window), so the map and the Plan tab still retire a window at the
 * same minute.
 *
 * <p><b>Where the times come from.</b> Each in-scope place's own {@code solarEventTime} on its
 * forecast row for that date and event ({@code location.forecastsByDate}, the same field the
 * overlay's context chip already reads). A min over served instants, narrowed by the same scope
 * pool as the verdict tally — CLAUDE.md's sixth (scope) class, entry (e).
 * A row whose scope holds no timed place keeps the served time — a true statement about the
 * window, never a blank.
 *
 * @param {object} args
 * @param {Array<object>} args.events the EV list from {@code buildMapEvents}
 * @param {Array<{id: *, name: string}>} args.spots the scope pool ("My area" or "Everywhere")
 * @param {Array<{id: *, name: string, forecastsByDate: ?Map}>} args.locations the map's locations
 * @param {(utc: ?string) => ?string} args.formatTimeUk formats a served instant as UK HH:MM
 * @returns {Array<object>} the same rows, a solar row's {@code time} replaced where the scope has one;
 *          rows needing no change are returned as the same objects
 */
export function withScopedSolarTimes({ events, spots, locations, formatTimeUk }) {
  const rows = Array.isArray(events) ? events : [];
  const pool = Array.isArray(spots) ? spots : [];
  if (rows.length === 0 || pool.length === 0) return rows;

  const byId = new Map();
  const byName = new Map();
  for (const loc of Array.isArray(locations) ? locations : []) {
    if (!loc) continue;
    if (loc.id != null) byId.set(loc.id, loc);
    if (loc.name) byName.set(loc.name, loc);
  }
  const scoped = new Set();
  for (const spot of pool) {
    const loc = (spot?.id != null && byId.get(spot.id)) || (spot?.name && byName.get(spot.name));
    if (loc) scoped.add(loc);
  }
  if (scoped.size === 0) return rows;

  return rows.map((row) => {
    // A D-13 filler (`served: false`) keeps whatever time the briefing lent it, or none: it stands
    // for a window the briefing withdrew or never rendered, and printing a time on it would be new.
    // ⚠️ A PAST row is `served: false` too and is the exception (outcome-rating-plan.md §2 #2): its
    // time is a fact of the forecast rows themselves, not something lent, so it narrows to the
    // scope pool's earliest like a served row (its base time is the roster's earliest, set by
    // `buildMapEvents`, and an empty scope leaves it untouched at the early return above).
    if (row?.kind !== EVENT_KIND.SOLAR || (row.served === false && !row.past) || !row.date) return row;
    const slot = SLOT_BY_EVENT[row.eventType] ?? null;
    if (!slot) return row;
    let earliest = null;
    let earliestRaw = null;
    for (const loc of scoped) {
      const raw = loc.forecastsByDate?.get?.(row.date)?.[slot]?.solarEventTime ?? null;
      const at = parseUtcInstant(raw);
      if (at && (earliest == null || at < earliest)) {
        earliest = at;
        earliestRaw = raw;
      }
    }
    const time = earliestRaw ? formatTimeUk(earliestRaw) : null;
    return time && time !== row.time ? { ...row, time } : row;
  });
}
